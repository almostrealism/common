# Known Issues & Platform Constraints

> Live constraints relevant to the audio-scene / PDSL work. Verify against current code
> before acting — entries reflect what was true when written. §1–§7 are **live**; §8 is
> a compressed **resolved-issues record** kept so the fixes are not re-broken.

---

## 1. Open defects — PDSL delay/feedback ring arithmetic (2026-07-09)

The block-parallel ring stages violate their own sizing requirements in three places;
each produces a per-buffer splice discontinuity and wrong-lap reads inside or feeding a
feedback loop — the leading mechanical explanation for the reported PDSL-vs-CellList
"grinding" divergence that grows with clip duration and EFX share. Full derivations,
receipts, and the fix plan: [PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md) §2/§6.

- **Feedforward wet-arm delay** (`pdslDelaySamples = 6500` into a one-frame ring): at
  4096 the delay degenerates to a within-frame rotation with a non-causal region; the
  intended 147 ms pre-delay has never been rendered at any buffer size.
- **Reverb taps** (`reverbTapDelays` spans 0.6–1.7 frames of a 2-frame ring): a
  read-first ring of depth 2 supports only `delay == signalSize`, so every tap is
  partially lap-stale every frame, recirculated by the Householder feedback.
- **Efx feedback gene delays**: the 0.25-beat `delayTimes` choice drops below one frame
  at BPM > ~161 (unsupported sub-frame band → ~ring-lap-stale echoes); the fb ring
  formula is also one frame short of `maxDelay + signalSize` at the 6-beat ceiling.

The governing invariant (a ring must span `maxDelay + signalSize`; read-first stages
need `delay ≥ signalSize`) is currently enforced nowhere. The vectorized and scalar
forms of the `delay` primitive also disagree on write-vs-read order.

## 2. Hybrid routing is mandatory — never force `AR_HARDWARE_DRIVER`

The framework uses JNI (CPU) and Metal *together*; the default (unset) router assigns
each operation to the backend that suits it. Pinning breaks:

- **`AR_HARDWARE_DRIVER=mtl` fails to compile the mixdown loop.** Metal limits a kernel
  to **31 buffer arguments**; the full fx/mixdown per-frame loop exceeds it
  (`MetalProgram.compile`: *"'buffer' attribute parameter is out of bounds"*).
- **`AR_HARDWARE_DRIVER=native` is ~14× over budget** — the parallel pattern kernels
  belong on Metal.
- **Default hybrid is ~3–4× faster than native-only** and the only configuration that
  both compiles and reaches real-time.

The 31-buffer limit is why the recurrent DSP loop stays on CPU/JNI; the lever is moving
the *parallelizable* parts onto Metal, not forcing the whole loop onto one backend.

## 3. Continuous rendering requires `-DAR_PATTERN_CACHE_PERSIST=true`

Without it, a sustained render leaks native memory (~150 MB/buffer) from note-audio
deallocation churn and the per-tick ratio explodes within a couple hundred buffers.
`PatternLayerManager.cachePersist` is a `static final` read at class load — it **must
be a JVM `-D` arg** (inside surefire `argLine` for tests). Callers that switch
arrangements at runtime must leave it disabled or call `invalidateCaches()`.

## 4. Metal `floor()` resample compile ambiguity (watch)

`BatchedPatternRenderer.buildResampleProducer` uses `integers(0,N).multiply(ratio)` →
`floor` → gather → lerp. The `floor()` over a `(long)global_id`-derived expression has
hit a Metal math-intrinsic overload ambiguity before (resolved in `385bb1c0a`,
`85cc1f12d`); the construct remains in shipped code — watch when extending the batched
resample path on Metal.

## 5. Production envelope classes remain hybrid (by design, for now)

`ParameterizedVolumeEnvelope.apply()` / `ParameterizedFilterEnvelope.apply()` still use
a hybrid `evaluate()`/`toDouble(0)` pattern. The batched path regenerates envelopes
in-kernel from `[N]` ADSR scalar tensors and does not route through them. Revisit only
if the legacy per-note path is retired.

## 6. Design constraint — Block-outward (never wrap a Block in a Cell/CellList)

When integrating a compiled PDSL `Block` into a `Cell`/`CellList` consumer, **make the
consumer accept a `Block`** — never make the `Block` masquerade as a `CellList`. In
practice the cutover does not adapt at the cell level at all: the real-time integration
contract is `TemporalCellular` (`setup()`/`tick()`/`reset()`), and the PDSL path is a
Block-forward `TemporalCellular` runner (`AudioSceneRealtimeRunner`); every consumer
outside `AudioScene` drives that contract and is blind to what backs it.
`wrapBlockAsCellList` is **not** used in the production path.

Two runtime constraints remain live for callers:
- **Single channel or zero-based contiguous multi-channel.**
  `AudioSceneRealtimeRunner.supportsPdsl` accepts any single-channel selection (genome
  reads mapped via `MixdownManagerPdslAdapter.Config.channel`) and the contiguous
  prefix `[0..n-1]`; a non-contiguous subset falls back to CellList (the feedback grid
  is indexed by bank position).
- **Stereo write gating.** `WaveOutput.write` gates on the minimum frame count across
  channels; the mono master streams to **both** stereo writers (dual-mono). True
  stereo/pan is outstanding ([NEXT_STEP.md](NEXT_STEP.md)).

## 7. Real-scene tests depend on the absolute-path curated library

Real-scene tests read `/Users/Shared/Music/Samples` and
`/Users/Shared/Music/pattern-factory.json` (`AR_RINGS_LIBRARY` / `AR_RINGS_PATTERNS`),
skipping gracefully where absent (CI). If a render comes out silent, re-check the
pattern factory is valid non-empty JSON. Reproducibility additionally depends on the
persisted `results/pdsl-cutover/scene-settings.json` (`AR_RINGS_SETTINGS`) — deleting
it draws a fresh random arrangement. `results/` is git-ignored (local-only).

---

## 8. Resolved record (compressed — do not re-break, do not re-litigate)

- **Argument aggregation / compile reuse** — the null-signature blocker is gone; the
  subsystem was rebuilt (PR #317/#318, default-on). `CollectionProviderProducer`
  signatures carry an `&aggRoot` qualifier; `GeneratedOperation` pool slots are consumed
  only on cache miss.
- **Instruction-set cross-context reuse** — `MetalDataContext` shares a single
  `MetalComputeContext` (`19fa029a6`), so a signature-matched kernel always encodes into
  the runner that commits it. This made `AR_PDSL_VECTOR_FOREACH` safe by default.
- **Metal sustained-dispatch ceiling** — per-task autorelease pools + explicit command
  buffer retention (`MetalCommandRunner.runInPool`); regression-guarded by
  `OperationDispatchBatchingTests`. Durable invariants: the platform is a compiler
  (count dispatches from the compiled program, never from op counts); at most ONE active
  command buffer per `ComputeContext`; nothing on the shared executor pool may block on
  the runner. Full model:
  [backend-compilation-and-dispatch.md](../../internals/backend-compilation-and-dispatch.md).
- **First-evaluate probe explosion** (129 s setup front-load / mid-stream spikes) — the
  `uniqueNonZeroOffset` gather-collapse probe can never succeed on scatter-add chains;
  disabled via `BatchedPatternRenderer.sumNoteAxis` (`setReplaceLoop(false)`),
  bit-identical output. Do **not** re-introduce a whole-arrangement pre-warm; setup must
  stay at seconds. (Full record in git history: `SETUP_FRONT_LOADING_HANDOFF.md`,
  removed 2026-07-09.)
- **a2 batched dispatch on real scenes** — fires on the full pattern path (sentinel
  tests guard it); mechanism documented in the `BatchedPatternLayerRenderer` /
  `BatchedPatternRenderer` javadocs. Per-shape renderers are shared JVM-wide
  (`79b297c5b`) so scene churn cannot accumulate bound-buffer memory.
- **Realtime performance** — the 5× goal was met (owner-measured ~0.163 s per generated
  second on M4, 2026-07, including compile/warmup) after the probe fix, adjustVolume
  skip, Semaphore copy-chaining (`a3b20e285`), `MTLSharedEvent` bridging (PR #337), and
  the operation-list subdivision / argument-preparation chaining arc (PR #340).
  Performance is no longer this folder's concern.
