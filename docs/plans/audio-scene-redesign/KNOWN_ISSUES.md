# Known Issues & Platform Constraints

> Live constraints relevant to the audio-scene / PDSL work. Verify against current code
> before acting — entries reflect what was true when written. §1–§7 are **live**; §8 is
> a compressed **resolved-issues record** kept so the fixes are not re-broken.

---

## 1. RESOLVED — PDSL delay/feedback ring arithmetic (found 2026-07-09, fixed 2026-07-09/11)

Four ring-stage defects, each generating per-buffer splices or wrong-age reads inside
or feeding a feedback loop — the mechanical explanation for the PDSL-vs-CellList
"grinding" divergence. All fixed; full derivations, receipts, and mechanisms:
[PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md) §2.

- **Feedforward delay into a one-frame ring** (§2.1) — fixed: rings sized from actual
  delays; write/read order unified write-first across the vectorized and scalar forms.
- **Sub-frame reverb taps** (§2.2) — fixed: seconds-denominated ring, taps floored at
  one frame.
- **Efx feedback gene delays below one frame at high BPM** (§2.3) — fixed: device-side
  band clamps (write-first `delay ≤ ring − signalSize`; read-first
  `signalSize ≤ delay ≤ ring`) enforced in every ring kernel.
- **Deferred tap reads under accum arms** (§2.4, found during C5) — every stateful
  stage hosted under an `accum_blocks` arm read post-write state: one frame short of
  the requested delay, or the current frame outright at the band floor (at 8192 the
  reverb tap floor pinned there → a per-frame leak recirculated by the Householder
  matrix). Fixed: taps materialized at their ops position
  (`MultiChannelDspFeatures`), consumer-independent.
- **Bus-delay drift integral** (found by ear after C5: full-scale buzz from ~40 s,
  spectrogram reads music → tightening chirp → solid full-band saturation) — the C2
  modulation accumulated `(1 − s)·signalSize` per buffer without bound, dragging
  every bus line to the one-frame floor within a minute, where the C5-faithful
  unscaled recirculation blew up. The legacy `AdjustableDelayCell` advances BOTH
  cursors at the rate, so the effective delay is the bounded, memoryless ratio
  `gene / s(clock)` (PDSL_DIFFERENCES §6-C2, corrected). Fixed: `bus_delay_samples`
  is a per-buffer-refreshed slot computing that ratio; no drift state exists.

Do not re-break: the band clamps, the tap materialization, and the ratio (never
integral) delay modulation are load-bearing; the regression guards are
`DelayBankBehaviorTest`, `DelayNetworkBehaviorTest`,
`MixdownManagerPdslVerificationTest.mainArmCarriesApplyEcho`, and
`MixdownManagerPdslVerificationTest.busDelayFollowsCursorRate`.

## 1b. OpenCL single-channel render silence on CI (unreproduced; gated off-Metal)

The `test-media-cl` job saw `AudioSceneSingleVsMultiChannelTest`'s single-channel
mode render total silence on the CI runners while the multi-channel mode rendered
fine, and the same mode passes locally under `native,cl` with both the curated and
the synthetic libraries — an environmental effect (runner memory pressure and/or
the per-run arrangement roll) not yet reproduced. Since CL is not a primary
backend, the single/silenced parity modes are skipped off-Metal (the multi render
remains the CL smoke check), the CL job now runs the native provider on malloc
blocks (`AR_HARDWARE_NATIVE_DIRECT_BUFFERS=disabled`, matching the other CL jobs),
and evaluation renders shorten off-Metal. Revisit when the CL backend effort
resumes; the gate is in `singleVsMultiChannel` and keys on
`AudioSceneTestBase.isMetalAvailable`.

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

## 7b. Continuous all-channel render times out — HIGH PRIORITY, near-term fix

`BatchedRealSceneRenderTest.renderAllChannelsContinuousToFile` renders ~5 minutes of
all-channel audio through `RealtimeContinuousRenderer.main()` (manual-listening artifact
`results/rt-live.wav`). It **times out (40 min)** on the library-mounted Metal runner (the
M4 Max), so it is excluded from the CI pipeline for now
(`@TestProperties(excludeProfiles = TestUtils.PIPELINE)`) to let the gate pass. The sustained
continuous-render path is a real production concern and needs a near-term fix before it can
gate CI again.

This is **not** the destination-lease Metal deadlock (see §8, shared-executor invariant): that
deadlock also broke the *finite* all-channel render (`allChannelsFullPipeline`), which now
**passes** after destination reuse was disabled by default
(`ProcessDetailsFactory.enableDestinationReuse=false`, `AR_HARDWARE_DESTINATION_REUSE`). So the
shared render machinery is deadlock-free; the remaining timeout is specific to the sustained
render. Confirmed indirectly (finite all-channel passes); not yet root-caused by thread dump.

Leading suspects, to verify before fixing:
- **`AR_PATTERN_CACHE_PERSIST` is not set for the test JVM (see §3).** Sustained rendering
  without it leaks ~150 MB/buffer and the per-tick ratio explodes within a couple hundred
  buffers. `PatternLayerManager.cachePersist` is a `static final` read at class load, so the
  test's runtime `System.setProperty` cannot set it — it must be in the surefire `argLine`,
  and no `argLine` in `studio/compose/pom.xml` sets it. A CI continuous render therefore very
  likely runs on the leaking path and slows to a crawl. This is the most probable cause.
- **Realtime pacing.** `RealtimeContinuousRenderer` paces to realtime by default
  (`AR_RT_PACE_RATE=1.0`), so 5 minutes of audio is ≥5 minutes wall even when healthy; a
  per-buffer explosion on top of that reaches the 40-minute timeout.
- **Shared-executor invariant (§8), for the interim fix.** Re-enabling destination reuse
  requires taking `AcceleratedProcessDetails.releaseDestinationLeases()` off the monitor-holding
  path, so the Metal completion pool never blocks on the runner. Until then reuse stays off.

Near-term options: wire `-DAR_PATTERN_CACHE_PERSIST=true` (and confirm pacing) into the media-job
surefire config and re-measure with an `ar-jmx` native-memory timeline / mid-render thread dump;
or keep this render out of CI and exercise it through a dedicated paced/native harness.

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
