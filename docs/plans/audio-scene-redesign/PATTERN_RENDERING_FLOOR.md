# Pattern Rendering Floor — Benchmark Results and Interpretation

**Status:** foundational evidence; batching is implemented (see STATE_OF_PLAY.md). This records the measurements that justified it.

**Benchmark class:** `engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmark.java`

---

## What This Measured

The benchmark isolated the cost of the four-kernel pattern rendering chain with no
`PatternLayerManager` / `PatternFeatures` orchestration overhead. It established the
lower bound achievable without rethinking the rendering model, and it determined whether
graph-batching (one compiled `CollectionProducer` per tick, one dispatch) removed the
cost. It did: per-note JNI dispatch was the dominant cost, and batching eliminated ~99.4%
of it. That conclusion is now built — pattern audio batching is wired via
`PatternLayerManager.enableBatched` / `AR_PATTERN_BATCHED`, implemented in
`BatchedPatternLayerRenderer` (studio/music) over `BatchedPatternRenderer` (engine/audio).

### Kernel chain

1. **Pitch interpolation** — linear resample `[SOURCE_SIZE=2048]` → `[NOTE_SIZE=1024]` via
   fractional-position gather.
2. **Volume envelope** — element-wise multiply by a precomputed ADSR curve.
3. **FIR lowpass filter** — `MultiOrderFilter` convolution (order 40, cutoff 8 kHz).
4. **Accumulate** — element-wise add into an output buffer.

Each note was one sequential `.evaluate()` of the once-compiled chain. Threshold for
real-time at 32 measures/tick is 92.9 ms ("ratio-of-1").

---

## Headline Results — Linux/CPU (JNI) Baseline

**Sequential 4-kernel floor** (one `evaluate()` per note, 32 measures/tick):

| notes/measure | notes/tick | Mean (ms) | Per-note (ms) | vs 92.9 ms |
|---|---|---|---|---|
| 16 | 512 | 468.78 | 0.916 | 5.0× over |
| 32 | 1,024 | 909.35 | 0.888 | 9.8× over |
| 64 | 2,048 | **1,804.14** | 0.881 | **19.4× over** |
| 128 | 4,096 | 3,563.95 | 0.870 | 38.4× over |
| 256 | 8,192 | 7,095.85 | 0.866 | 76.4× over |

Per-note cost is constant (~0.88 ms): total ≈ notes × 0.88 ms. This is the signature of
purely sequential per-note JNI dispatch — no kernel fusion across notes.

**Per-phase breakdown (64 notes/m).** Running the 4 kernels separately summed to 3,123 ms
vs 1,804 ms for the fused chain (1.73× fusion benefit). FIR was the largest single phase
(28.5%) but only marginally over volume (26.3%), accumulate (26.2%), resample (18.9%).
The ~0.44 ms/note non-arithmetic share is the single JNI dispatch per note; arithmetic
intensity is too low for FIR-order reduction to matter.

**`Process.optimized()`** added ~15% overhead on this flat chain (no isolation targets) —
do not use it on the per-note rendering chain.

---

## Batching Removes ~99.4% of the Cost

**Batched 3-kernel chain** (resample + volume + accumulate, all N notes for one tick in a
single `CollectionProducer`, one `evaluate()` per tick):

| notes/measure | Sequential (ms) | Batched (ms) | Speedup |
|---|---|---|---|
| 16 | 276.56 | 2.25 | 123× |
| 64 | 1,100.50 | **6.39** | 172× |
| 256 | 4,378.53 | 21.83 | 201× |

At 64 notes/m: batched = 6.39 ms vs 92.9 ms threshold → **14.5× under**. Amortized
per-note 0.0031 ms vs 0.5374 ms sequential — **99.4% of sequential cost is JNI dispatch
overhead**, not arithmetic.

**Batched 2-kernel chain** (volume + accumulate). Even a minimal chain captures the win:
at 64 notes/m, **0.90 ms** (~921× speedup, ~103× under threshold). The
JNI-dispatch-elimination — not chain length — is the load-bearing mechanic, so a minimal
batched form could ship first and grow incrementally.

**Batched 4-kernel chain with padded FIR** (resample + volume + per-row pad + FIR). At 64
notes/m: **1.89 ms** (~49× under threshold), faster than the 3-kernel ceiling above (whose
tile-and-add dominated at high N). The FIR-batching constraint was resolved without
touching `MultiOrderFilter`: `MultiOrderFilter`'s boundary check treats `length()` as the
whole array, so on `[N, NOTE_SIZE]` input samples within `±filterOrder/2` (20 samples at
order 40) bleed across row boundaries. Fix: pad each row by `filterOrder/2` zeros on each
side (`pad()` → `[N, NOTE_SIZE + filterOrder]`); boundary reads land in the zero pad, not
adjacent-note audio. ~4% memory overhead, acoustic per-note independence preserved, FIR
API unchanged for non-batched callers.

**Reduction vs tile accumulate.** Production must sum N notes into a single `[NOTE_SIZE]`
buffer. Expressing the final accumulate as a reduction
(`permute(reshape, 1, 0).traverse(1).sum()` → `[NOTE_SIZE]`) was ~0–7% of the tile-and-add
(`[N × NOTE_SIZE]`) cost at ≤128 notes/m and faster at 256 notes/m — the framework folds
it into the same batched kernel. Production output is therefore `[NOTE_SIZE]` via reduction,
eliminating the O(N) intermediate and any Java-side post-sum.

---

## Summary

| Finding | Value | Implication |
|---|---|---|
| Sequential per-note floor (4-kernel, JNI) | 0.88 ms/note | 19× over threshold at 64 notes/m |
| `Process.optimized()` overhead | +15% | Don't use on flat per-note chain |
| Kernel fusion benefit | 1.73× | 43% of chain cost is JNI overhead |
| Batched 3-kernel at 64 notes/m | 6.39 ms | 14.5× under threshold |
| Batched 2-kernel at 64 notes/m | 0.90 ms | 103× under; 921× speedup |
| Batched 4-kernel padded-FIR at 64 notes/m | 1.89 ms | 49× under; FIR resolved via padding |
| Reduction-accumulate vs tile | ~0–7% (≤128 npm) | Output `[NOTE_SIZE]` via reduce |
| **Per-note dispatch share** | **99.4%** | Batching is the architecturally correct, sufficient fix |

The benchmark proved that per-note JNI dispatch — not arithmetic — was the cost, and that
graph-batching removes ~99.4% of it, clearing the threshold even on the slowest backend
(JNI/CPU). This justified the batched-rendering work that is now shipped. The current
bottleneck has moved downstream: the Metal dispatch ceiling is fixed (real-time uses hybrid
JNI+Metal routing), and the next target is migrating the a3 DSP/mixdown per-frame loop to
PDSL (see STATE_OF_PLAY.md).

**Known issue:** see KNOWN_ISSUES.md (Metal `floor()` resample ambiguity in
`BatchedPatternRenderer.buildResampleProducer`).

Run: `mvn test -pl engine/audio -Dtest=PatternRenderingFloorBenchmark`. Results append to
`engine/audio/results/pattern-rendering-floor.txt`.
