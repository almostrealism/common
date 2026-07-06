# Known Issues & Platform Constraints

> Live constraints relevant to the audio-scene-redesign / real-time / PDSL DSP work.
> The PDSL mixdown path is merged to `master`. Verify against current code before
> acting — entries reflect what was true when written. Referenced from
> [NEXT_STEP.md](NEXT_STEP.md) and the other docs in this folder.
>
> §1–§5 and §7 are **live**. §6 and §8 are a **resolved-issues record** kept so the
> fixes are not re-litigated or re-broken.

---

## 1. Hybrid routing is mandatory — never force `AR_HARDWARE_DRIVER`

The framework is designed to use JNI (CPU) and Metal *together*; the default (unset)
router assigns each operation to the backend that suits it. Pinning one backend breaks:

- **`AR_HARDWARE_DRIVER=mtl` fails to compile the mixdown loop.** Metal limits a kernel
  to **31 buffer arguments** (indices 0–30); the full fx/mixdown per-frame loop
  (`f_loop_*`) exceeds it → `MetalProgram.compile`: *"'buffer' attribute parameter is
  out of bounds: must be between 0 and 30."*
- **`AR_HARDWARE_DRIVER=native` is ~14× over budget.** The parallel pattern kernels
  belong on Metal; putting them on the CPU is wasteful.
- **Default hybrid is ~3–4× faster than native-only** and is the only configuration
  that both compiles and approaches real-time.

The 31-buffer limit is unaddressed and is the reason the recurrent DSP loop stays on
CPU/JNI; the optimization lever is moving the *parallelizable* parts (across
channels/voices, non-recurrent ops) onto Metal, not forcing the whole loop onto one
backend.

## 2. Continuous rendering requires `-DAR_PATTERN_CACHE_PERSIST=true`

Without it, a sustained render leaks native (Metal/CL) memory (~150 MB/buffer observed)
from per-loop note-audio deallocation churn, and the per-tick ratio explodes from ~1.1×
to 70×+ within a couple hundred buffers before hitting the memory cap (OOM). With it,
the note-audio cache is never evicted, memory is bounded by arrangement length, and the
render holds its steady-state ratio.

`PatternLayerManager.cachePersist` is a `static final` read at class load, so it **must
be a JVM `-D` arg** (not a runtime `System.setProperty`, and not a bare Maven `-D` that
fails to fork into the test JVM — it must be inside the surefire `argLine`). Callers that
switch arrangements at runtime must leave it disabled (or call `invalidateCaches()`) so
stale audio is evicted.

## 3. Metal `floor()` resample compile ambiguity (watch)

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
plan to refactor these into pure Producers was deliberately **not** done because the
batched path bypasses them. Only revisit if the legacy per-note path is retired.

## 5. Design constraint — Block-outward (never wrap a Block in a Cell/CellList)

A `Block` is essentially a `Cell` (forward) plus a second cell that runs the other
direction (backprop); audio DSP needs no backprop, so a `Block` and a `Cell` are
approximately the same thing. When integrating a compiled PDSL `Block` into a
`Cell`/`CellList` consumer, **make the consumer accept a `Block`** — do not make the
`Block` masquerade as a `CellList`. If a compatibility adapter is unavoidable, `Block`
stays on the outside (adapt `Cell` → `Block`, never the reverse): a consumer that accepts
`Block` can hold any `Cell` implementation, so this is the universal direction.

**In practice the chosen cutover does not adapt at the cell level at all.** The
integration contract for real-time rendering is `TemporalCellular`
(`setup()`/`tick()`/`reset()`), **not** `CellList` — every consumer outside `AudioScene`
(the health computation, `BufferedOutputScheduler`, `RealtimeContinuousRenderer`) drives
that contract and is blind to whatever backs it. So the PDSL path is a **Block-forward
`TemporalCellular` runner** (`AudioSceneRealtimeRunner`): its `tick()` keeps the Java
pattern-prepare phase, calls `compiledModel.forward(buffer)` once per buffer for the DSP,
and writes the result straight to the output line. `wrapBlockAsCellList` is **not** used.

Two runtime constraints remain live for callers:
- **Single channel or zero-based contiguous multi-channel.** `AudioSceneRealtimeRunner.supportsPdsl`
  accepts any single-channel selection (including `AudioScene.renderChannel`'s non-zero
  `[c]`, mapping its genome reads to the selected scene channel via
  `MixdownManagerPdslAdapter.Config.channel`) and the zero-based contiguous prefix
  `[0,1,…,n-1]`. A *non-contiguous* multi-channel subset (e.g. `[0,2]`) falls back to the
  CellList path — the cross-channel transmission feedback grid is indexed by bank
  position rather than scene channel, so an arbitrary subset would not reproduce the
  routing.
- **Stereo write gating.** `WaveOutput.write` gates on the *minimum* frame count across
  channels; the mono master output is streamed to **both** stereo writers (dual-mono) so
  the file is actually written. True stereo is outstanding (see
  [NEXT_STEP.md](NEXT_STEP.md)).

## 6. a2 batched dispatch on the full real-scene pattern path — RESOLVED

The batched-pattern *mechanism* was first correctness-validated on the **synthetic**
sentinel path (`studio/music`: `BatchedDispatchSentinelTest`, `BatchedVsPerNoteRmsTest`,
`BatchedRealtimeTickTest`). It now also fires on the **full curated-library scene**: both
melodic-SSS and percussion note shapes are classified and dispatched
(`BatchedPatternLayerRenderer.dispatchWindow` / `dispatchWindowPercussion`), so the
earlier `peak=0.0` silence on unhandled shapes is gone, and the old argument-aggregation /
`GeneratedOperation`-pool co-blocker is itself resolved (§8.1).
`studio/compose/.../pattern/test/BatchedRealSceneRenderTest.java` is **re-enabled** (no
longer `@Ignore`d); its `singleMelodicChannelFullPipeline` uses a random `realGenome()` and
is therefore flaky (peak can be `0.0` on some seeds). Full treatment:
[A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md).

## 6.1 Batched renderer bound buffers have no destroy chain (scene churn leaks)

Each `BatchedPatternRenderer` compiled for a `(bucket, sourceLength, targetLength)` shape
owns large fixed-shape bound input buffers (`bucketN × sourceLength × 8 B × layers` per
source set — hundreds of MB at the larger buckets), plus compiled evaluables. Nothing
destroys them when their owning `PatternLayerManager`/scene is discarded: the renderer
cache lives on the `BatchedPatternLayerRenderer` per pattern, and neither it nor the
`PatternLayerManager` participates in a `destroy()` cascade. Long-lived scenes
(desktop, real-time playback) are unaffected; **scene-churn workloads leak** — a test
class creating a scene per method OOM-killed the CI fork (exit 137,
`HeapPatternRenderingTest`, 2026-07-06) once `enableBatched` became the default, and
genome-sweep populations that rebuild scenes deserve a check. That test now pins itself
to the per-note path (its actual subject); the structural fix — a destroy chain from
scene → pattern manager → layer renderer → renderer bound buffers — belongs with the
copy/lifecycle migration effort.

## 7. Real-scene tests depend on the absolute-path curated library

The real-scene tests and any full-render experiment read `/Users/Shared/Music/Samples`
and `/Users/Shared/Music/pattern-factory.json` (overridable via `AR_RINGS_LIBRARY` /
`AR_RINGS_PATTERNS`). Tests skip gracefully where they are absent (CI). If a future
render comes out silent / "no working genome," re-check that the pattern factory is still
a valid non-empty JSON (it was briefly `[]` once). Reproducibility additionally depends
on the persisted `results/pdsl-cutover/scene-settings.json` (`AR_RINGS_SETTINGS`):
deleting it makes the next run draw a fresh random arrangement. Note that `results/` is
git-ignored, so these artifacts are local-only.

---

## 8. Resolved (kept so the fixes are not re-broken)

### 8.1 Argument-aggregation / compile-reuse — RESOLVED (the subsystem was rebuilt)

The earlier blocker: structurally-identical computations did not reuse a compiled native
kernel because `CollectionProviderProducer.signature()` returned **`null`** for
argument-aggregation-target buffers (one null leaf nulled the whole graph signature),
disabling instruction-set caching; every rebuild then consumed a slot from a fixed,
monotonically-consumed `GeneratedOperation` pool until a full-scene render exhausted it
and cascaded into unrelated `AudioScene` test failures.

The argument-aggregation subsystem was **torn out and reintroduced with a new design**
(removed `5f0648eab`, 2026-06-20; rebuilt `9732295ff` "A new approach to argument
aggregation", 2026-06-23, PR #317; `aggregate-on-kernel-provider` PR #318; enabled by
default `13ce711c5`; perf `b4b23f0c5`). The null-signature premise is **gone**:
`CollectionProviderProducer.signature()` now emits a real signature with an
`&aggRoot=<rootLen>` qualifier that scopes reuse to compatible aggregate layouts, and
`MemoryDataArgumentMap.isAggregationTarget` is a pure size test
(`enableArgumentAggregation && memLength <= maxAggregateLength`, default 1024). Reuse
works, so identical rebuilds no longer force a fresh kernel.

The fixed `GeneratedOperation` pool (max `GeneratedOperation5999`,
`OperatorPoolExhaustedException` from `AcceleratedOperation.load()`) still physically
exists, but a slot is now reserved only on a **cache miss** — the "every rebuild burns a
slot → exhaustion" mechanism is precisely what was fixed. (The full standalone analysis
that used to live in `../SIGNATURE_AGGREGATION_GAP.md` is obsolete and has been removed;
its premise no longer holds.)

### 8.2 Instruction-set cross-context reuse — RESOLVED (2026-06-12)

The reuse path had an inverse defect: two models with structurally-identical computations
hash to the same signature (leaf signatures are offset/length/shape-based, intentional),
but a compiled kernel is permanently bound to the `ComputeContext` it compiled under. A
second model under a *different* context that adopted the kernel via the bare signature
match encoded its dispatches into the first context's `MetalCommandRunner`, which nothing
in the second pipeline commits — the kernel never executed, no error surfaced, and the
second model produced exactly-zero output. The fix (`19fa029a6`) makes `MetalDataContext`
share a single `MetalComputeContext`, so a cached kernel always encodes into and is
committed by the one command runner; `DefaultComputer.getScopeInstructionsManager` stays
keyed by signature alone. This is what made `AR_PDSL_VECTOR_FOREACH` safe to enable by
default.

### 8.3 Metal sustained-dispatch ceiling — RESOLVED (merged, regression-guarded)

Sustained Metal rendering used to wedge at **~2300–2560 cumulative kernel dispatches**:
the host parked forever in `MTL.waitUntilCompleted` on a committed command buffer that
never reported completion. Root cause: `MetalCommandRunner` spanned one Objective-C
autorelease pool across its separate encode and commit/await tasks, so the *second*
command buffer to commit wedged. Fix: each executor task runs in its own autorelease pool
and command buffers are explicitly retained across tasks (`MetalCommandRunner.runInPool`;
`base/hardware/src/main/cpp/MTL.cpp`). **3000+ sustained dispatches verified;**
regression-guarded by `OperationDispatchBatchingTests` (engine/utils).

**Durable invariants from this fix (govern all future Metal dispatch work):**
- **The platform is a compiler, not a player piano.** N `OperationList` statements
  typically compile to **one** kernel program (one dispatch), not N. Capture dispatch /
  kernel / command-buffer counts from the *compiled program* (`OperationProfileNode`,
  `ar-profile-analyzer`), never infer them from how many operations a tick contains.
- **At most ONE active command buffer per `ComputeContext`.** Within one buffer, Metal's
  hazard tracking orders every read-after-write among encoded dispatches; the buffer
  commits only at a genuine boundary (an explicit host wait — `run()`/`evaluate()`/
  read-back — or a hand-off to a different `ComputeContext`). Per-dispatch `dependsOn` /
  event chaining is a *cross-context* tool only; it is neither needed nor wanted within a
  context.
- Two performance follow-ups were tried and reverted; their lessons: nothing on the
  shared bounded executor pool may block on the runner; never manufacture a second
  command buffer to "order" same-context dispatches; never carve exceptions into the
  completion contract (every provider always returns a real `Semaphore`).

The full durable model lives in
[docs/internals/backend-compilation-and-dispatch.md](../../internals/backend-compilation-and-dispatch.md).
This fix unblocked Metal as a *sustained* backend; it did not by itself make rendering
real-time (that was the DSP/mixdown loop, since migrated to PDSL — see
[NEXT_STEP.md](NEXT_STEP.md)).

### 8.4 First-evaluate probe explosion (setup front-load / mid-stream spike) — RESOLVED (2026-07-03)

Every distinct batched pattern kernel shape paid **14–29 s on its first `evaluate()`** — in
setup (as ~129 s of "setup" cost per scene) and again on any shape first reached mid-stream
(the ~29–33 s dropout spike that motivated the whole-arrangement pre-warm). The cost was the
`replaceLoop` gather-collapse probe (`AggregatedProducerComputation.prepareScope` →
`TraversableExpression.uniqueNonZeroOffset`), which builds an `ExpressionMatrix` over
`[windowWidth × bucketN]` substituting each index into the deep fused chain — and which can
**never succeed** on the batched chains (their scatter-add sums overlapping notes, so no unique
non-zero contributor exists). It burned the time and returned null every time; the production
loop scope was always the fallback anyway.

Fix: `BatchedPatternRenderer.sumNoteAxis` disables `replaceLoop` on the note-axis sums of
`reduceAligned` / `scatterAddFlat`. Zero output change (bit-identical peak). Setup 128.6 s →
~2.3 s; honest `generateRealtimeX` 0.63 → 1.46 (then 1.79 after the 2026-07-04 tooling merge);
`preWarmMaxSeconds` now defaults to 0 and the render ring prefills a single buffer. Do **not**
re-introduce a whole-arrangement pre-warm — front-loading rendering into setup is the
anti-pattern this fix removed. The existing `ScopeSettings.maxGatherAnalysisDepth`/`Nodes`
gates do not cover this case (they inspect only the target *index* expression); a matrix-size
gate is a candidate framework hygiene follow-up. Full record:
[`SETUP_FRONT_LOADING_HANDOFF.md`](SETUP_FRONT_LOADING_HANDOFF.md).
