# AudioScene Rendering Pipeline Redesign

> **Status (May 2026, revised after PatternRenderingFloorBenchmark exploration):** Plan
> revised to reflect three developments since the original was written: (1) the PDSL
> audio DSP substrate has landed on master — it is the path forward for the DSP half
> of the redesign, not one option among several; (2) profiling on
> `feature/rings-realtime-generation` found that **pattern generation** accounts for
> ~278ms of 311ms total per-tick cost, making it the dominant bottleneck; (3) the
> `PatternRenderingFloorBenchmark` (May 2026, six additions across two task rounds)
> demonstrated that batching the pattern chain into a single `Producer` evaluation
> drops cost by 100–1500× and lands all chain variants well under the 92.9ms realtime
> threshold even on the slowest backend (Linux/JNI CPU). Specifically: the 2-kernel
> batched form runs in 0.90ms at 64 notes/m (921× speedup), and the 4-kernel batched
> form *with* FIR runs in 1.89ms (49× under threshold). The benchmark settles the
> Phase 2 vs Phase 3 question (Phase 3 is required) and resolves the FIR-batching
> open question (padded-row strategy works with zero `MultiOrderFilter` changes).
>
> The revised scope covers both halves: DSP cutover (substrate ready, mostly mechanical)
> and pattern generation (Phase 3 batched compilation is required, confirmed achievable,
> and now has concrete implementation guidance — see Section 5, Phase 3).

---

## 1. The Corrected Scope

The original plan framed AudioScene redesign as a DSP/mixdown performance problem. It
was written before the PDSL audio DSP work landed and before recent profiling identified
where render time actually goes. Both of those have changed the picture materially.

**Reality after profiling (32-measure render, M1 Ultra, 4096 frames):**

| Component | Time | Fraction |
|-----------|------|---------|
| Pattern generation (PatternLayerManager.sumInternal + PatternFeatures.render) | ~278ms | ~89% |
| DSP kernel (Loop, monolithic) | ~33ms | ~11% |
| **Total per tick** | **311ms** | — |

The DSP mixdown — the subject of the original plan's entire analysis and Options A–D
architecture section — accounts for roughly 11% of total cost. Pattern generation
accounts for the other 89%. A redesign that only fixes DSP leaves the dominant cost
untouched.

**The PatternRenderingFloorBenchmark (May 2026) further decomposed the 278ms.** Per-note
sequential JNI dispatch is the dominant cost, not arithmetic or allocation. At 64 notes/m
the per-note cost is 0.88ms but **99.4% of that is JNI boundary overhead, not compute**.
Batching all 2048 notes into one `evaluate()` call drops the tick to 6.39ms — well below
the 92.9ms realtime threshold. The compute itself is essentially free; the cost is the
dispatch frequency. Phase 3 (batch compilation, single Producer per tick) is therefore
required, not contingent on Phase 2 outcomes.

The revised scope covers both halves:
- **DSP cutover:** Replace the Cell-based DSP path with the PDSL substrate. The substrate
  exists and is tested. The remaining work is mechanical: production cutover, EfxManager
  migration, one deferred primitive (`delay_network`), and variable channel count support.
- **Pattern generation:** Phase 3 batched compilation is the required path. The benchmark
  data settles this question. Phase 2 targeted fixes (cache, allocation) attack the ~1% of
  cost that is not JNI dispatch — they are incremental wins, not the architectural fix.

---

## 2. What Has Already Landed

### 2A: The PDSL Audio DSP Substrate (merged to master)

The `feature/pdsl-audio-dsp` branch, now merged, implemented the full declarative
substrate for the DSP pipeline. This is the foundation the DSP cutover builds on.

**Audio primitives** — all implemented and tested:
`fir`, `scale`, `identity`, `lowpass`, `highpass`, `biquad`, `delay`, `lfo`

**Multi-channel constructs:**
- `channels: int` header parameter — declares N-channel layer
- `for each channel { }` — iterates a body over each channel independently
- `repeat(N)` — replicates a `[1, signal_size]` input to `[N, signal_size]`
- `route(matrix)` — cross-channel routing via `[in, out]` matrix (square or rectangular)
- `sum_channels()` — collapses `[N, signal_size]` to `[1, signal_size]`
- `accum_blocks(...)` — branches, applies each arm to the input, sums outputs

**Producer-valued arguments:**
Every audio primitive that the live system drives with a `Producer<PackedCollection>`
(volume, HP/LP cutoff, delay time, automation modulation) accepts `producer([shape])`.
Constant-folded literal calls compile identically to before — no regression for
fixed-parameter PDSL files. `bindProducerArg` is the single dispatch point.

**PDSL files:**
| File | What it covers |
|------|---------------|
| `pdsl/audio/efx_channel.pdsl` | EFX channel: `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix` |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown layers: `mixdown_main` (HP→scale→LP), `mixdown_channel` (full with wet/delay) |
| `pdsl/audio/delay_feedback_bank.pdsl` | Multi-channel delay bank: `delay_feedback_bank` (repeat → per-channel delay → route → sum_channels) |
| `pdsl/audio/mixdown_manager.pdsl` | Top-level mixdown: `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_master` |

**Adapter and verification:**
- `MixdownManagerPdslAdapter` — bridges genome state to PDSL args (`buildArgsMap`) and
  exposes a compiled Block's Cell as a CellList (`wrapBlockAsCellList`).
- `MixdownManagerPdslVerificationTest` — verifies the PDSL path matches the Cell path
  with reverb disabled (reverb path depends on `delay_network`, which is deferred).

**Key design note:** Per-channel kernel splitting — the central goal of the original
plan's Phase 1 — happens automatically as a property of how PDSL compiles the
`for each channel` construct. No graph-analysis pass, no CellList restructuring.
The original plan's Options A–D and their associated difficulty/tradeoff analysis are
moot: PDSL is the answer to all of them.

### 2B: Structural Changes on the Branch

Since the original plan was written, the following have landed:

- **`AudioSceneLoader` extracted** (master, April 2026): Loading, saving, and JSON
  serialization moved out of `AudioScene`. `AudioScene` now focuses on the rendering
  pipeline only.
- **Alternate sample rates** (branch, April 2026): `BufferedAudioPlayer` honors the
  `sampleRate` parameter consistently.
- **Package reorganization**: Key classes are now in semantically appropriate packages:
  - `org.almostrealism.studio.*` — `AudioScene`, `AudioSceneLoader`, `Mixer`
  - `org.almostrealism.studio.arrange.*` — `MixdownManager`, `EfxManager`, `AutomationManager`
  - `org.almostrealism.music.pattern.*` — `PatternSystemManager`, `PatternAudioBuffer`

---

## 3. The DSP Path: What Remains

The PDSL substrate is in place. The remaining DSP work is:

### 3A: Production Cutover (MixdownManager)

The Cell-based `MixdownManager.createCells()` body is still the production path.
`MixdownManagerPdslAdapter.buildArgsMap()` and `wrapBlockAsCellList()` exist and are
tested. The cutover means replacing (or A/B-flagging) the `createCells()` body with
calls to the adapter.

The adapter applies a shared producer to all channels for genome-driven parameters
(volume, HP cutoff, LP cutoff, transmission) — this is a known simplification relative
to the per-channel-distinct envelopes in the Java path, documented in the adapter's
javadoc. `MixdownManagerPdslVerificationTest` captures the current scope of equivalence.

### 3B: `delay_network` Primitive

The multi-tap feedback reverb (`org.almostrealism.audio.filter.DelayNetwork`) has no
PDSL equivalent yet. This is explicitly deferred to this workstream
(`PDSL_AUDIO_DSP.md`, Section 12.5). `MixdownManagerPdslVerificationTest` runs both
paths with reverb disabled to keep the comparison meaningful until this lands.

Implementing `delay_network` unblocks the full reverb path in the PDSL rendition and is
a prerequisite for a complete MixdownManager cutover. It is sized as weeks of work.

### 3C: EfxManager Migration

`EfxManager` is fully Cell-based. Its per-channel processing (FIR filtering, adjustable
delay, automation-modulated wet/dry mix, self-feedback) overlaps substantially with what
the existing `efx_channel.pdsl` describes. The migration is expected to be mostly
mechanical once `delay_network` is available (EfxManager also uses DelayNetwork for its
reverb tail).

### 3D: Variable Channel Count

The `channels` parameter in PDSL is fixed at block-build time. Dynamic channel count —
channel activation via gene — remains Java CellList code. Supporting this in PDSL is
deferred; the current PDSL rendition targets a fixed channel count configuration.

### 3E: CellList Retirement

`CellList` and the Cell/CachedStateCell/Receptor model are being deprecated in favor of
Block-based execution. `wrapBlockAsCellList` is an intentionally narrow bridge that lets
the PDSL Block slot into AudioScene without changing AudioScene's interface. The CellList
interface can be retired once all callers migrate to Block.

---

## 4. The Pattern Generation Bottleneck

### 4A: Where the 278ms Goes (Current Understanding)

The profiling finding is: Java orchestration in `PatternLayerManager.sumInternal` and
`PatternFeatures.render` accounts for ~278ms of 311ms total per tick at 32 measures.

Reading the code makes the source visible:

**`PatternLayerManager.sumInternal`** iterates over pattern repetitions (`IntStream.range`)
and, for each repetition, over `NoteAudioChoice` entries. For each (repetition, choice)
pair, it creates a `NoteAudioContext` and calls `PatternFeatures.render()`.

**`PatternFeatures.render`** iterates over `PatternElement` instances via streams. For
each element it calls `getNoteDestinations()`, which produces `RenderedNoteAudio` objects.
For each note not in cache, it calls `note.getProducer(-1).evaluate()` — a JNI round-trip
that evaluates one note's audio against the GPU (or CPU) and returns a `PackedCollection`.
It then sums the result into the destination buffer via `AudioProcessingUtils.getSum()`.

The per-note evaluation pattern is the structural source of cost: each note is an
independent `evaluate()` call. If a 32-measure arrangement has many pattern elements
across many repetitions and many layers, this accumulates to many separate kernel
dispatches in sequence.

**Note:** We do not yet have a breakdown of the 278ms by contributor (allocation, JNI
dispatch, sum-to-destination, Java loop overhead). The profiling finding is at the method
level. Phase 2 exists to get that breakdown.

### 4B: Candidate Directions

The PatternRenderingFloorBenchmark settled which of these is the load-bearing fix.
**Batch note evaluation** (the third candidate below) is the required path; the others
are incremental improvements at most.

**Batch note evaluation. (Phase 3 — required path.)** Rather than calling `evaluate()`
per note, compose a single `CollectionProducer` that evaluates all notes for a given
choice in one kernel dispatch. The benchmark showed this drops the kernel cost from
1804ms to 1.89ms at 64 notes/m for the full 4-kernel chain with FIR (954× speedup, 49×
under threshold), or 0.90ms for a 2-kernel batched form (921× speedup, 103× under
threshold). 99.4% of the per-note cost is JNI dispatch overhead, not arithmetic.
Production work to integrate this with `PatternFeatures.render` orchestration is the
central Phase 3 task.

**Reduce allocation churn. (Phase 2 — incremental.)** `PatternElement.getNoteDestinations()`
creates `RenderedNoteAudio` objects on every render call. These attack at most a few
percent of the cost — not ratio-of-1. Worth doing alongside Phase 3, not instead of it.

**Cache more aggressively. (Phase 2 — incremental.)** `NoteAudioCache` already caches by
frame offset. Each cache hit removes one JNI dispatch. With Phase 3 batched compilation,
all dispatches collapse to one per pattern layer per tick anyway, so cache improvements
remove a small constant fraction of an already-small total.

**Batch-render across pattern variants. (Phase 3+ extension.)** The vector-space
exploration UX uses a compact fixed-size pattern representation with similarity-driven
sampling. This suggests a possible reorganization: render a neighborhood of similar
patterns in a batched forward pass. This is a Phase 3 implementation choice, not a
separate phase: if the batched compilation graph is shaped right, batching across pattern
variants is a re-batch dimension rather than an architectural change.

**Move pattern assembly to PDSL.** Patterns as tensor operations: `PatternElement`
positions become a tensor, note audio becomes a learned embedding, assembly becomes a
matrix operation. This is the long-term direction (Phase 4 / trajectory). It requires
PDSL to support dynamic-shape operations not currently on the roadmap. Not a near-term
option.

---

## 5. Phasing

### Phase 1: DSP Cutover (Mostly Mechanical)

The PDSL substrate is in place. Phase 1 delivers the production cutover.

**1A — `delay_network` primitive.** Implement the PDSL equivalent of
`org.almostrealism.audio.filter.DelayNetwork`. This is the prerequisite for completing
the MixdownManager and EfxManager migrations. Deferred from PDSL audio DSP; sized as
weeks.

**1B — MixdownManager production cutover.** Replace (or A/B-flag with a runtime switch)
`MixdownManager.createCells()` with calls through `MixdownManagerPdslAdapter`. Enable
the reverb path in `MixdownManagerPdslVerificationTest` once `delay_network` is
available. Verify acoustic match.

**1C — EfxManager migration.** Migrate EfxManager's per-channel Cell chain to the PDSL
`efx_channel.pdsl` definition. Expected to be mostly mechanical once `delay_network` is
available.

**1D — Variable channel count.** If runtime-variable channel count via gene is needed,
design the bridge. Current position: PDSL uses fixed channel count; variable channel
activation remains in Java. Evaluate whether this is a blocker for the cutover.

**Expected outcome of Phase 1:** The DSP path runs through PDSL-compiled Block kernels
instead of the monolithic CellList kernel. Per-channel splitting happens automatically.
The expected improvement to DSP time (~33ms today) is secondary to pattern generation;
Phase 1's primary value is architectural foundation.

Phase 1 is characterised as "mostly mechanical" because the substrate exists and is
tested. The hard engineering work is `delay_network`; the rest follows from it.

### Phase 2: Targeted Cleanup (Optional Incremental Wins)

Phase 2 was originally framed as the path to ratio-of-1 performance. The benchmark data
disproves that framing: the 278ms cost is dominated by JNI dispatch frequency
(~99.4% on the slowest backend), not by allocation, cache misses, or Java loop overhead.
Phase 2 fixes attack the wrong part of the cost curve.

Phase 2 work is reframed as **incremental wins worth doing alongside the structural fix**,
not as the path to ratio-of-1. Each candidate from Section 4B is sized accordingly:

**2A — Allocation/GC.** `RenderedNoteAudio` allocations may add GC pressure. Worth
measuring. Even if eliminated, this attacks at most a few percent of the 278ms — not
ratio-of-1. Time-box: 1–2 days, only if GC pause time turns out to be measurable in JFR.

**2B — Cache hit rate.** `NoteAudioCache` already caches by frame offset. If hit rate is
already high, no change. If low, raising it removes JNI dispatches one by one — strictly
better than nothing, but it does not change the structural bottleneck.

**2C — `Process.optimized()`.** Do **NOT** apply `Process.optimized()` to the per-note
chain. The benchmark showed this **adds ~15% overhead** vs the un-optimized chain on a
linear 4-kernel pipeline (no isolation targets to restructure). The optimization pass is
designed for graphs with isolation targets (reuse points, shared sub-expressions); on a
flat chain it is pure overhead. Future agents should default to the un-optimized path for
this kind of pipeline.

Phase 2 outputs are nice-to-haves. They do **not** gate Phase 3.

### Phase 3: Batched Compilation (Required Path)

Phase 3 is the architectural fix. The benchmark established four load-bearing facts:

1. **Per-note JNI dispatch dominates.** ~99.4% of the sequential per-note cost is dispatch,
   not arithmetic. Batching eliminates this directly.
2. **Kernel fusion is real.** The combined 4-kernel chain (1 evaluate per note, 4 fused
   kernels) is 1.73× faster than running the same kernels separately (4 evaluates per
   note). Composition matters; the framework already fuses adjacent ops in a single
   compiled graph.
3. **Batching wins regardless of chain length.** A 2-kernel batched chain at 64 notes/m
   runs in 0.90ms (921× speedup vs sequential 829ms). The 4-kernel padded-FIR batched
   chain runs in 1.89ms. Both are far below threshold. JNI dispatch elimination is the
   load-bearing mechanic; chain length is secondary.
4. **The FIR-batching question is resolved.** Padding each row with `filterOrder/2` zero
   samples on each side allows the standard `MultiOrderFilter` to operate on a
   `[N, NOTE_SIZE + filterOrder]` input with cross-row bleed landing in pad zones. Zero
   `MultiOrderFilter` changes required. Demonstrated working at all five density points
   (Addition 5).

Phase 3 builds the production batched-render path. The benchmark exists at the kernel
level — production needs to integrate this with `PatternLayerManager` / `PatternFeatures`
note scheduling and the rest of the orchestration. Implementation guidance, informed by
the exploration:

- **Sequencing — minimum viable batched form first, then grow.** Addition 4 demonstrated
  that a 2-kernel batched form (volume * envelope + accumulate) already delivers 921×
  speedup at 64 notes/m. The full 4-kernel chain is not a prerequisite for the bulk of
  the win. A pragmatic Phase 3 sequencing: land the 2-kernel batched form first as a
  proof-of-integration with `PatternFeatures.render`, then incrementally add resample
  and FIR. Each milestone is independently below the threshold.
- **FIR strategy — padded-row.** Use `pad(input, 0, filterOrder/2)` (or equivalent) to
  produce `[N, NOTE_SIZE + filterOrder]`, then apply the existing `MultiOrderFilter`.
  No `MultiOrderFilter` modification needed; ~4% memory overhead at production filter
  order. The other strategies (per-row index clamping in `MultiOrderFilter`,
  `weightedSum()`-based primitives) remain valid alternatives but are not necessary.
- **Output shape — reduce to `[NOTE_SIZE]`, not tile to `[N × NOTE_SIZE]`.** Addition 6
  showed that the framework folds the across-batch sum reduction into the same kernel as
  the upstream operations: reduction costs the same as (or less than) tile at production
  density. Phase 3 should produce a compact `[NOTE_SIZE]` accumulator buffer per pattern
  layer per tick — no `[N × NOTE_SIZE]` intermediate, no Java-side post-sum.
- **Variable note positions.** Notes have different start times, lengths, and pitches.
  The benchmark used uniform notes for kernel-cost measurement; production needs to
  express variable scheduling as a static computation graph. Options: pad all notes to
  max length and mask out unused samples (consistent with the FIR padding approach);
  tile by pitch class for resample caching; group notes into fixed-shape batches per
  pattern layer. This is the central remaining design question for Phase 3 — the
  benchmark intentionally does not prescribe an answer.
- **Integration with `PatternFeatures.render`.** The current orchestration iterates
  `PatternElement` instances and calls `evaluate()` per note. Phase 3 needs a different
  shape: gather all notes for a pattern layer's tick into one tensor input, compile
  once, dispatch once per tick. The `note.getProducer(-1).evaluate()` per-note pattern
  in `PatternFeatures.render` (line ~135 / ~155) is the integration point that has to
  change.

Phase 3 is the architectural commitment. The benchmark exploration narrows the
implementation guidance — minimum-viable batched form first, padded-row FIR, reduction
output shape — without prescribing the full design. The variable-note-scheduling
question is the central remaining unknown for Phase 3 implementation.

### Phase 4: Convergence (Aspirational, No Timeline)

The long-term direction is AudioScene as a single PDSL computation graph — DSP,
pattern generation, and the whole rendering pipeline. Patterns become tensor operations
(element positions → index tensors, note audio → learned embeddings, assembly →
matrix-multiply-accumulate). The framework compiles the entire graph to a single native
kernel per tick.

This is stated as the trajectory, not as a near-term deliverable. The trajectory matters
for design decisions in earlier phases: choices in Phases 1–3 should preserve the option
to keep moving toward Phase 4. Specifically:

- The DSP cutover should use Block-based execution (not invent new CellList abstractions)
  because Block composes directly with PDSL.
- Pattern generation fixes should not entrench the per-note-evaluate pattern in new ways
  that make batch compilation harder to add later.
- New data structures introduced for pattern optimization should be expressible as
  tensor shapes, even if they aren't yet evaluated as tensors.

The endpoint is not "PDSL for DSP only." It is "PDSL for everything that benefits from
being a tensor operation" — which, over time, means the full pipeline.

---

## 6. Scope

### In Scope

- `delay_network` PDSL primitive (Phase 1 prerequisite)
- MixdownManager production cutover from Cell-based to PDSL-based (Phase 1)
- EfxManager migration to PDSL (Phase 1)
- Variable channel count support in the PDSL rendition (Phase 1, if needed for cutover)
- CellList retirement as callers migrate (ongoing with Phase 1)
- Pattern generation incremental cleanup: cache hit-rate, allocation reduction (Phase 2,
  optional, not gating)
- Pattern generation batched compilation: single Producer per pattern layer per tick,
  including the FIR strategy decision (Phase 3, required)

### Out of Scope (Trajectory Only)

- Wholesale pattern → PDSL conversion (Phase 4, aspirational)
- AudioScene as a single PDSL graph (Phase 4, aspirational)
- Real-time streaming or low-latency targets below ratio-of-1: the realtime goal is
  ratio-of-1 (render time ≤ audio duration), not sub-millisecond latency.
- Timeline or release estimates

---

## 7. What the Original Plan Got Right and What Changed

**Still valid:**
- The monolithic kernel is a real performance problem for the DSP path.
- Per-channel splitting is the right architectural goal.
- `prepareBatch()` / Java-side overhead is real (53ms measured, reduced but not
  eliminated in subsequent work).
- The goal of reducing per-tick render time to below 92.9ms (ratio-of-1) is correct.

**Overtaken by events:**
- Options A–D (Channel-Scoped CellLists, Flat Buffer Pipeline, Hybrid+graph-analysis,
  Stream-Oriented) are all moot. PDSL is the answer to all of them. The `for each channel`
  construct delivers per-channel kernels; the Block model eliminates the Cell/CachedStateCell
  copy chain; the declarative structure makes graph analysis unnecessary.
- The graph-analysis pass (Option C) for copy-chain elimination is not needed: PDSL's
  expression tree handles this at the Producer level.
- The "Performance" framing as pure DSP/mixdown is incorrect: pattern generation dominates.
- The success criteria table targeting kernel time and prepareBatch time is incomplete
  without a corresponding row for pattern generation cost.

**Investigation questions from the original plan:** Q1 (channel independence), Q2
(kernel dispatch overhead), Q3 (memory consolidation), Q4 (CellList API compatibility),
Q5 (feedback loops), Q6 (prepareBatch redundancy) — these remain worth understanding but
their answers inform the PDSL cutover design rather than a Cell-graph analysis pass.

---

## 8. Success Criteria (Revised)

| Metric | Current | Target | Notes |
|--------|---------|--------|-------|
| Total per-tick time (32m) | 311ms | <93ms (ratio-of-1) | Both pattern + DSP must improve |
| Pattern generation time | ~278ms | <30ms (Phase 3) | Batched compilation is required path; benchmark floor on JNI is 1.89ms (4-kernel padded-FIR batched at 64 notes/m) |
| DSP kernel time | ~33ms | <20ms (estimate) | PDSL cutover + per-channel splitting |
| prepareBatch time | 53ms | <25ms | Partially addressed; cache improvements |
| DSP kernel count | 1 monolithic | N per-channel | Automatic from PDSL |
| Pattern dispatches per tick | N (one per note) | 1 (one per pattern layer) | Phase 3 batched compilation |
| Acoustic match (PDSL vs Cell) | N/A | Verified by MixdownManagerPdslVerificationTest | With reverb path enabled post-delay_network |

---

## 9. Key Files

### DSP Path (Phase 1)

| File | Role | Expected Changes |
|------|------|-----------------|
| `compose/.../arrange/MixdownManager.java` | Cell-based DSP pipeline | `createCells()` replaced by PDSL path |
| `compose/.../arrange/MixdownManagerPdslAdapter.java` | Adapter: genome → PDSL args | Minor updates as cutover lands |
| `compose/.../arrange/EfxManager.java` | Cell-based EFX | Migration to PDSL `efx_channel.pdsl` |
| `compose/.../arrange/AutomationManager.java` | Automation producers | Minor — continues supplying `Producer` args to PDSL |
| `ml/.../resources/pdsl/audio/*.pdsl` | PDSL layer definitions | `delay_network` primitive added |
| `compose/.../dsl/audio/AudioDspPrimitives.java` | PDSL primitive registration | `delay_network` primitive registered |
| `compose/.../dsl/audio/MultiChannelDspFeatures.java` | Multi-channel block builders | `delay_network` block implementation |
| `compose/.../AudioScene.java` | Pipeline orchestrator | Switch to Block-based cells path |

### Pattern Generation Path (Phase 2)

| File | Role | Expected Changes |
|------|------|-----------------|
| `music/.../pattern/PatternLayerManager.java` | Pattern rendering coordinator | Targeted fixes based on profiling |
| `music/.../pattern/PatternFeatures.java` | Per-note evaluation + accumulation | Targeted fixes based on profiling |
| `music/.../pattern/PatternElement.java` | Pattern element: positions + note audio | Targeted fixes based on profiling |
| `music/.../notes/RenderedNoteAudio.java` | Note audio producer factory | Targeted fixes based on profiling |
| `music/.../notes/NoteAudioCache.java` | Per-tick note audio cache | Cache strategy changes based on profiling |
