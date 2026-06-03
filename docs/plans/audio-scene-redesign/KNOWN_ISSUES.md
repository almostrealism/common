# Known Issues & Platform Constraints

> Live constraints relevant to the audio scene redesign / real-time / PDSL DSP work,
> as of 2026-06-03 (branch `feature/batched-audio-mtl`). These are referenced from
> [STATE_OF_PLAY.md](STATE_OF_PLAY.md) and the other docs in this folder. Verify
> against current code before acting — these reflect what was true when written.

## 1. Hybrid routing is mandatory — never force `AR_HARDWARE_DRIVER`

The framework is designed to use JNI (CPU) and Metal *together*; the default (unset)
router assigns each operation to the backend that suits it. Pinning one backend breaks:

- **`AR_HARDWARE_DRIVER=mtl` fails to compile the mixdown loop.** Metal limits a kernel
  to **31 buffer arguments** (indices 0–30); the full fx/mixdown per-frame loop
  (`f_loop_*`) exceeds it → `MetalProgram.compile`: *"'buffer' attribute parameter is
  out of bounds: must be between 0 and 30."* This is a strong reason the production
  real-time path historically forced `native` — but `native` for *everything* is the
  wrong fix (below).
- **`AR_HARDWARE_DRIVER=native` is ~14× over budget.** Putting the parallel pattern
  kernels on the CPU is wasteful; they belong on Metal.
- **Default hybrid is ~3–4× faster than native-only** and is the only configuration
  that both compiles and approaches real-time.

**Implication for the PDSL DSP migration:** the recurrent DSP loop will keep running on
CPU/JNI; the goal is to move the *parallelizable* parts (across channels/voices, and
non-recurrent ops) onto Metal — not to force the whole loop onto one backend.

## 2. Continuous rendering requires `-DAR_PATTERN_CACHE_PERSIST=true`

Without it, a sustained render leaks native (Metal/CL) memory (~150 MB/buffer observed)
from per-loop note-audio deallocation churn, and the per-tick ratio explodes from ~1.1×
to 70×+ within a couple hundred buffers before hitting the memory cap (OOM). With it,
the note-audio cache is never evicted, memory is bounded by arrangement length, and the
render holds ~1.1× real-time.

`PatternLayerManager.cachePersist` is a `static final` read at class load, so it **must
be a JVM `-D` arg** (not a runtime `System.setProperty`, and not a bare Maven `-D` that
fails to fork into the test JVM — it must be inside the surefire `argLine`). Callers that
switch arrangements at runtime must leave it disabled (or call `invalidateCaches()`) so
stale audio is evicted.

## 3. Metal `floor()` resample compile ambiguity (live)

`BatchedPatternRenderer.buildResampleProducer` uses `integers(0,N).multiply(ratio)` →
`floor` → gather → lerp. The `floor()` over a `(long)global_id`-derived expression has
hit a Metal math-intrinsic overload ambiguity at codegen in the past. A prior instance
was resolved (commits `385bb1c0a`, `85cc1f12d`), but the construct remains in shipped
code and is worth watching when extending the batched resample path on Metal.

## 4. Production envelope classes remain hybrid (by design, for now)

`ParameterizedVolumeEnvelope.apply()` / `ParameterizedFilterEnvelope.apply()` still use
a hybrid `evaluate()`/`toDouble(0)` pattern. The batched path does **not** route through
them — it regenerates the envelope curves in-kernel from the `[N]` ADSR scalar tensors
(`BatchedPatternRenderer`), leaving the legacy per-note classes untouched. The earlier
plan called for refactoring these into pure Producers; that was deliberately **not**
done because the batched path bypasses them. Only revisit if the legacy per-note path is
retired.
