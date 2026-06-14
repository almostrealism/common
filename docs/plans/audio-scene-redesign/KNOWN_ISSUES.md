# Known Issues & Platform Constraints

> Live constraints relevant to the audio scene redesign / real-time / PDSL DSP work,
> as of 2026-06-11 (`feature/audio-scene-pdsl`). These are referenced from
> [STATE_OF_PLAY.md](STATE_OF_PLAY.md) and the other docs in this folder.
> Verify against current code before acting — these reflect what was true when written.

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

## 5. Design constraint — Block-outward (never wrap a Block in a Cell/CellList)

A `Block` is essentially a `Cell` (forward) plus a second cell that runs the other
direction (backprop); audio DSP needs no backprop, so a `Block` and a `Cell` are
approximately the same thing. When integrating a compiled PDSL `Block` into a
`Cell`/`CellList` consumer (e.g. the MixdownManager → AudioScene path), **make the
consumer accept a `Block`** (or `List<Block>`), do not make the `Block` masquerade as a
`CellList`. The existing `MixdownManagerPdslAdapter.wrapBlockAsCellList(Block)` is the
wrong direction and must not be the cutover mechanism. If a compatibility adapter is
unavoidable, `Block` stays on the outside (adapt `Cell` → `Block`, never the reverse) —
a consumer that accepts `Block` can hold any `Cell` implementation, so this is the
universal direction. See `PDSL_AUDIO_DSP.md` §14.

**In practice the chosen cutover does not adapt at the cell level at all.** The
integration contract for real-time rendering is `TemporalCellular`
(`setup()`/`tick()`/`reset()`), **not** `CellList` — every consumer outside `AudioScene`
(the health computation, `BufferedOutputScheduler`, `RealtimeContinuousRenderer`) drives
that contract and is blind to whatever backs it. So the PDSL path is a **Block-forward
`TemporalCellular` runner**: its `tick()` keeps the Java pattern-prepare phase
(`PatternAudioBuffer.prepareBatch`), calls `compiledModel.forward(buffer)` once per buffer
for the DSP, and writes the result straight to the output line. `wrapBlockAsCellList` is
**not** used. This is why the Block never needs to masquerade as a `CellList`.

**Implemented.** The runner exists as `AudioSceneRealtimeRunner` (`studio/compose`),
strategies selected by `MixdownManager.enablePdslMixdown`; parity validated (see
[EFX_PDSL_PARITY_PLAN.md](EFX_PDSL_PARITY_PLAN.md)). Two runtime constraints remain live
for callers:
- **`channels ≥ 2`, zero-based contiguous.** `MixdownManagerPdslAdapter.buildArgsMap`
  concatenates per-channel producers (`concat` rejects a single input) and reads genes
  positionally, so `AudioSceneRealtimeRunner.supportsPdsl` falls back to the CellList
  path for any other selection (e.g. `AudioScene.renderChannel`'s single channel).
- **Stereo write gating.** `WaveOutput.write` gates on the *minimum* frame count across
  channels; the mono master output is streamed to **both** stereo writers (dual-mono)
  so the file is actually written.

## 6. Compile-reuse / `GeneratedOperation` pool exhaustion (cross-cutting blocker)

Structurally-identical computations do **not** reuse a compiled native kernel: building
the *same* `AudioScene` twice with the same genome produces ~44 then ~43 fresh native
programs (essentially no reuse). The cause is that `CollectionProviderProducer.signature()`
returns `null` for argument-aggregation-target buffers (and one null leaf nulls the whole
graph's signature), which disables instruction-set caching; every rebuild then consumes a
slot from a fixed, monotonically-consumed `GeneratedOperation` pool. A full-scene render
climbs past the pool size and **cascades into failures of unrelated `AudioScene` tests**.

The pool was expanded (currently up to `GeneratedOperation5999`) to buy headroom, but that
is a **stopgap, not a fix** — the underlying recompilation churn remains.

The reuse path had the inverse defect as well — **root-caused and fixed 2026-06-12**.
When two models contain structurally-identical computations, both hash to the same
signature (leaf signatures are offset/length/shape-based, not buffer-identity-based —
intentional). The instruction-set cache (`DefaultComputer.getScopeInstructionsManager`)
was keyed by signature alone, but a compiled kernel is permanently bound to the
`ComputeContext` it compiled under — its `MetalOperator` encodes dispatches into that
context's `MetalCommandRunner`. A second model running under a *different* context that
adopted the kernel via the bare signature match encoded its commands into the first
context's runner, which nothing in the second pipeline ever commits: the kernel never
executed, no error surfaced, and the second model produced exactly-zero output.
(Argument substitution via `ProcessArgumentMap` was verified correct throughout — both
kernel arguments rebind to the right buffers; only the dispatch route was wrong.)
The fix scopes the cache key to signature + compute-context identity, so reuse still
applies within a context (the common case) but never crosses contexts. The former
reproducer (`MixdownManagerPdslTest` square then rectangular efx bus in one JVM with
vectorized for-each) now passes with reuse enabled, and `AR_PDSL_VECTOR_FOREACH` is
enabled by default. Reuse diagnostics remain available via
`AR_HARDWARE_REUSE_LOGGING=enabled` (substitution, argument coverage, and process-tree
dumps at each reuse event).

This gap gates re-enabling `BatchedRealSceneRenderTest` and any sustained full-scene
render. Full analysis and candidate fixes:
[../SIGNATURE_AGGREGATION_GAP.md](../SIGNATURE_AGGREGATION_GAP.md).

## 7. a2 batched dispatch does not fire for the full real-scene pattern path

The batched-pattern *mechanism* is correctness-validated on the **synthetic** sentinel
path (`studio/music`: `BatchedDispatchSentinelTest`, `BatchedVsPerNoteRmsTest`,
`BatchedRealtimeTickTest` — all passing, batched matches per-note within <1% RMS, 3000
sustained dispatches). But on the **full curated-library scene**, batched dispatch does not
reliably fire for the real pattern path — some methods render `peak=0.0` (silence). This is
why `studio/compose/.../pattern/test/BatchedRealSceneRenderTest.java` is `@Ignore`d (it also
trips issue §6). The a1→a2 seam is "classify-and-dispatch" over a closed set of note shapes
(see `NOTE_GRAPH_SHAPES.md`); an unhandled real shape falls through to silence rather than
erroring, so the fix is to ensure every production note shape is classified and dispatched.

## 8. Real-scene tests depend on the absolute-path curated library

The real-scene tests and any full-render experiment read `/Users/Shared/Music/Samples`
and `/Users/Shared/Music/pattern-factory.json` (overridable via `AR_RINGS_LIBRARY` /
`AR_RINGS_PATTERNS`). Both are present and valid on the M1; tests skip gracefully where
they are absent (CI). If a future render comes out silent / "no working genome,"
re-check that the pattern factory is still a valid non-empty JSON (it was briefly `[]`
once). Reproducibility additionally depends on the persisted
`results/pdsl-cutover/scene-settings.json` (`AR_RINGS_SETTINGS`): deleting it makes the
next run draw a fresh random arrangement.
