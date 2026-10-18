# Audio Render Test Determinism

**Goal:** make the audio-rendering tests in the media CI lanes less flaky, starting by
making every run of a given test do the same work for a fixed sample library, so that
timing and failures can be compared from run to run. Runtime optimisation is a later step
and has not been attempted here.

**Backends.** The failures are mostly reported on CUDA (`test-media-cuda`), but no
FlowTree node has CUDA. All measurements below were taken on macOS with Metal
(`-DAR_HARDWARE_DRIVER=mtl`), standing in for GPU timing behaviour. Every statement about
CUDA is either taken from CI logs (marked *CI*) or is an assumption (marked *assumed*).

---

## 1. Survey of media-lane failures (2026-10-04 .. 2026-10-18)

Source: `analysis.yaml` runs on master and PR branches. 100 failed and 40 successful runs
were listed; 17 runs (15 failed, 2 passed) were broken down per job, and 24 job logs were
grepped for `Time elapsed`, `<<< ERROR`, `<<< FAILURE` and `TestTimedOut`. Re-run attempts
whose earlier job ids GitHub no longer exposes (39114603625, 38971995052, 38608139937) could
not be inspected, so failures later retried green on PR branches are under-counted.

Backends (from `-DAR_HARDWARE_DRIVER` in the logs): `test-media` = `native` (CPU),
`test-media-mac` = `*` (Metal), `test-media-cl` = `native,cl` (OpenCL on ROCm),
`test-media-cuda` = `native,cuda`.

Lane failure rate over the inspected runs: `test-media` 0/17, `test-media-cuda` 0/5,
`test-media-cl` 4/7, `test-media-mac` 7/15. No JVM crash, SIGSEGV or "forked VM
terminated" appeared in any inspected job.

| Test | Lane (group) | Backend | Kind | Elapsed | Fails / observed on lane | Runs |
|---|---|---|---|---|---|---|
| `FixedPatternCorrectnessTest#testTwoMinuteContinuousAudio` | test-media-cl (0) | OpenCL | timeout (120 s, in `writeAudioAt` → `Object.wait`) | 120.0 s (≈5 s on CPU lane) | 3 / 7 | 38314716648, 38444121495, 39087091857 (master) |
| `GenerateAudioFileTest#generateAudioFile` | test-media-mac (0) | Metal | timeout (900 s) ×2; crash-like exception ×1 (`HardwareException: Pattern render-ahead failed` ← NPE) | 900 s; 137.9 s | 3 / 15 | 39089129392, 38469043129, 39005852411 |
| `DelayCellTest#filterLoopComparison` | test-media-mac (0) | Metal | timeout (600 s) | 601–602 s | 2 / 15 | 38501802567, 38742189851 |
| `FrequencyToAudioConverterTest#convertProducesNormalizedAudio`, `#silentInputStaysSilent` | test-media-mac (0), (1) | Metal | timeout (120 s) | ≈120 s | 2 runs / 15 | 38501802567, 38742189851 |
| `HeapPatternRenderingTest` (7 methods) | test-media-mac (1) | Metal | timeouts (120/180 s) + repeated native NPE (`argument 1 RAM is null`) | class 1279 s | 1 / 15 | 38104443628 |
| `RealTimeRendererCorrectnessTest#testBufferSizeConsistency` | test-media-mac (2) | Metal | assertion | 5.9 s | 1 / 15 | 38405217901 |
| `RescalingAggregationTests#rescaleModular1` | test-media-cl (4) | OpenCL | timeout (60 s) | 60.0 s | 1 / 7 | 37668304270 |

**The known example, `AudioSceneMultiGenomeTest#multiGenomePatternRender`, did not fail in
any inspected run**, but it is close to its 600 s timeout on CUDA and its runtime is rising
(*CI*, `test-media-cuda` group 5, whole class: 484.8 s → 507.0 s → 530.2 s → 498.6 s → 861.3 s
between 10-13 and 10-18; in run 39120547918 `multiGenomePatternRender` alone ran 15:08:39 →
15:18:12, ≈573 s of 600 s). On `test-media-cl` (5) the class takes 173–255 s. On
`test-media` it returns early in 0.001 s (no sample library mounted), and the survey did not
find it doing real work on `test-media-mac`. The 600 s timeout at issue was therefore most likely hit on
the CUDA lane in a run outside this window or in a re-run attempt that is no longer
inspectable.

Passing durations of other scene tests (*CI*): `GenerateAudioFileTest` 336.8 s (mac 0),
75–465 s (cl 0); `BatchedRealSceneRenderTest` 155–162 s (mac 0), 412.8 s (cl 4);
`AudioSceneOptimizerStemTest` 22–36 s (mac), 110–136 s (cl); `AudioSceneBaselineTest`
12.6 s (mac), 41.7 s (cl); `StableDurationHealthComputationTest` 980.7 s (cl 4).

---

## 2. Determinism findings

### 2.1 Sources of variation found

1. **Scene construction drew from `Math.random()`** — the dominant source.
   `ParameterFunction.random()` used `Math.random()`, and every randomly initialised
   selection function in the pattern system goes through it:
   `PatternSystemManager.Settings.defaultSettings` (factory and active selection per
   pattern), `PatternLayerManager.init`, `PatternElementFactory.initSelectionFunctions`,
   `NoteAudioChoice.initSelectionFunctions`, the volume/filter envelopes and
   `ChordProgressionManager.Settings.defaultSettings`. `AudioSceneTestBase.createBaselineScene`
   builds its settings with `AudioSceneLoader.Settings.defaultSettings`, so **every call
   produced a different arrangement**.
2. **The genome seed search ran against a different scene than the render.**
   `multiGenomePatternRender` (and `AudioSceneBaselineTest`, `AudioSceneBufferConsolidationTest`,
   `RealTimeTestHelper.createSceneWithWorkingSeed`, several `AudioSceneRealTimeCorrectnessTest`
   methods) call `findWorkingGenomeSeed` on one `createBaselineScene` and then render a freshly
   constructed one. Because of (1) the chosen seed had no relationship to the scene rendered,
   and both the chosen seed and the per-genome work changed every run.
3. **Curated scenes were random on every CI job.** `loadCuratedScene` reuses
   `results/pdsl-cutover/scene-settings.json` when it exists, but `results/` is gitignored and
   CI checkouts are clean, so on CI the file never exists and the curated arrangement was
   drawn from `Math.random()` for each job. Locally the file persists, which hides this.
   Affects `GenerateAudioFileTest`, `KernelCacheReuseAcrossScenesTest`,
   `ArrangementGenerationNoHeapTest`, `AudioScenePdslCutoverTest`, `PdslHotPathBreakdownTest`,
   `PdslSetupBreakdownTest`, `AudioScenePdslBenchmarkTest`, `AudioSceneSingleVsMultiChannelTest`,
   `AudioSceneOptimizerStemTest`.
4. **`renderUntilAudible` retries depended on chance.** It rebuilds the arrangement
   (`AudioSceneOptimizer::createScene`) up to 8 times until a render is audible; how many
   attempts ran (and so the test's runtime) was random.
5. **Hash-ordered render grouping.** `PatternLayerManager.getAllElementsByChoice` and
   `PatternSystemManager.getPatternElements` grouped elements in a `HashMap` keyed by
   `NoteAudioChoice`, which has identity `hashCode`. The render (`sumInternal`) and
   `warmNoteCache` iterate that map, so the order in which choices were rendered and summed
   into the destination — and kernel compile order — could change between JVMs.
6. **Sample-directory order** — *not* a source. `FileWaveDataProviderNode.getChildren` uses
   unsorted `File.listFiles`, but `TreeNoteSource.refresh` sorts its providers before use,
   and the baseline scene names its sample files explicitly.
7. **Synthetic fallback samples** — deterministic (`AudioTestFeatures` seeds its noise from
   the frequency).
8. **Genome values** — deterministic (`fixedGenome` uses a seeded `Random`).
9. **Thread/timing dependence.** The pattern render-ahead thread
   (`AudioSceneRealtimeRunner.renderAheadSlots`) is timing-dependent; the
   `GenerateAudioFileTest` "Pattern render-ahead failed ← NPE" failure is a race in that
   machinery, not an input-determinism issue. Not addressed here.
10. **State carried between tests.** `MixdownManager.enable*` and
    `PatternSystemManager.enableWarnings` are static flags each test sets itself; the
    instruction cache is shared across tests in a fork, which changes *time* (cold vs warm
    compile) but not output.

Not observed on Metal and not testable here: any CUDA-specific variation (e.g.
non-deterministic reduction order in CUDA kernels). *Assumed* absent until a CUDA host can
repeat the fingerprint below.

### 2.2 Evidence — `AudioSceneMultiGenomeTest#multiGenomePatternRender` on Metal

Before the fix (two JVM runs of the same commit and library):

| | Run 1 | Run 2 |
|---|---|---|
| `Best seed` | 42 with 197 elements | 49 with 304 elements |
| Genome 0 elements on the render scene | 15 (search said 197) | 84 (search said 304) |
| seed 50 elements | 97 | 73 |
| Time elapsed | 113.2 s | 116.4 s |

After the fix:

| | Run 1 | Run 2 |
|---|---|---|
| `Best seed` | 55 with 272 elements | 55 with 272 elements |
| Genome 0 elements on the render scene | 272 | 272 |
| per-genome `elements` / `buffersWithAudio` (100 genomes) | identical in both runs | |
| Time elapsed | 118.5 s | 120.0 s |

A temporary (uncommitted) fingerprint test rendered the same path for 5 genomes × 172
buffers and hashed every sample; both JVM runs produced bit-identical hashes
(e.g. genome 0 `hash=8d805d1ae4a10e11 energy=175334.61793912295`).

### 2.3 Evidence — `AudioSceneBaselineTest` on Metal (after the fix)

`baselineAudioGeneration` + `baselineHealthComputation`, two JVM runs: both chose
`seed 55 (272 elements)` in both methods and produced the identical health score
`0.019522776572668113`. Class time 15.35 s / 12.36 s (render 3.4 s / 2.9 s; the difference
is cold vs warm native compilation, not different work).

### 2.4 Tests not re-run here

The curated-scene tests (`GenerateAudioFileTest` and the others listed under 2.1.3) take
the same `loadCuratedScene` path and are covered by the same fix, but they were not re-run
twice in this session; locally the persisted `scene-settings.json` already masked most of
their variation, so the visible change will be on CI.

---

## 3. What was fixed

- `ParameterFunction` draws its coefficients from one static, reseedable `Random`
  (`ParameterFunction.setRandomSeed(long)`). Unless reseeded it behaves as before, so
  production arrangements stay random.
- `AudioSceneTestBase` reseeds it with `ARRANGEMENT_SEED` before `createBaselineScene` and
  `loadCuratedScene`, so every baseline scene is the same arrangement (the seed search now
  applies to the scene that is rendered) and curated scenes are the same on every host,
  including clean CI checkouts. `renderUntilAudible` reseeds with `ARRANGEMENT_SEED + attempt`,
  so its attempts still differ from each other but the sequence is fixed.
- `PatternLayerManager.getAllElementsByChoice` and `PatternSystemManager.getPatternElements`
  return `LinkedHashMap`s, so choices are rendered in traversal order.

No existing test method was edited and no timeout was changed. A new
`ParameterFunctionTest#randomSeedReproducesDraws` covers the reseeding contract.

Consequence to expect in CI: because the arrangement is now fixed, each affected test's
runtime should become stable from run to run — but at whatever level the fixed arrangement
implies, which may be higher or lower than the previous average. A test that was passing
"on average" can now pass or fail consistently.

---

## 4. Metal durations and phase breakdown

`multiGenomePatternRender`, Metal, after the fix: **≈119 s** inside the test (Surefire
`Time elapsed`), ≈164 s per Maven invocation.

| Phase | Approx. time | Notes |
|---|---|---|
| Scene construction + seed search (20 seeds, element counting only) | ≈3 s | test start to `Best seed` |
| Second scene construction + initial GC | ≈1–2 s | |
| Fixed sleeps | 17 s | `INITIAL_PAUSE_MS` 5 s + 4 × `JMX_PAUSE_MS` 3 s, for JMX attachment |
| Rendering 100 genomes × 172 buffers of 1024 frames | ≈90 s | sum of per-genome `time=`; 0.45–2.2 s per genome, first genome slowest (cold compile) |
| 100 × `System.gc()` and heap reporting | ≈7 s | remainder of the ≈99 s between the end of the initial pause and the last genome, less the 12 s of JMX pauses |

On CUDA the same method took ≈573 s (*CI*), ≈4.8× Metal. Assuming (*assumed*) the sleeps and
search cost the same on CUDA, the render loop is ≈550 s there, ≈5.5 s per genome, i.e.
≈32 ms per 1024-frame `patterns.sum` call versus ≈5 ms on Metal.

---

## 5. Proposed next step (runtime and timeout risk)

1. **Repeat the fingerprint on CUDA first.** Run `multiGenomePatternRender` twice on the
   CUDA host and diff the `Best seed` / per-genome lines. If they match, the timeout risk is
   pure throughput; if not, there is a CUDA-specific source of variation to find before
   anything else.
2. **Profile one genome's buffer loop on CUDA** (`OperationProfile` +
   `ar-profile-analyzer`). *Assumption to check:* each `patterns.sum(...).get().run()` builds
   and dispatches many small kernels per note, and CUDA's per-launch and host↔device sync
   overhead — not arithmetic — dominates, which would explain a ~5× gap to Metal on unified
   memory. If true, the fix is fewer, larger dispatches per buffer (the batched pattern layer
   renderer path, `PatternLayerManager.enableBatched`), not a longer timeout.
3. **Remove fixed overhead that is not part of what the test measures.** 17 s of
   `Thread.sleep` for JMX attachment and 100 forced GCs run unconditionally; gating them on
   JMX monitoring being requested would save ≈20 s on every backend. This is an edit to an
   existing test method, so it needs the owner's sign-off.
4. **Re-baseline the curated-scene tests** (`GenerateAudioFileTest`, `DelayCellTest`,
   `FixedPatternCorrectnessTest` on CL) now that their inputs are fixed: their CI times should
   stop wandering, and a consistent overrun then points at throughput rather than at an
   unlucky arrangement.
5. The `GenerateAudioFileTest` render-ahead NPE and the `HeapPatternRenderingTest`
   `argument 1 RAM is null` failures are correctness races, not timing; they should be
   investigated separately.

---

## 6. Missing-resource silent passes: audit and fix (2026-10-18)

**Why.** A new CI runner without the curated sample library let tests that should have
failed report a pass, and damaging changes merged to master. The policy now enforced:

- A missing resource may make a test decline to run **only** on a host with no GPU
  accelerator at all, and only as a JUnit *skip*, never as a pass.
- On any GPU host (Metal, OpenCL on a GPU device, CUDA), and on any host that declares the
  resource's location (`AR_RINGS_LIBRARY`, `AR_RINGS_PATTERNS`, ...), a missing resource
  **fails**.

### 6.1 The shared decision

`TestFeatures` (engine/utils), inherited by every `TestSuiteBase`, now decides this in one
place:

- `isGpuAvailable()`: `Hardware.isAvailable(ComputeRequirement.GPU)`. It filters the
  initialized contexts strictly, so the CPU-only `native` lane reports false and Metal, CL
  (GPU device) and CUDA report true. It moved here from `AudioSceneTestBase`.
- `requireResource(resource, locationProperty, available)`: returns when the resource is
  present. When it is absent, the test fails if `locationProperty` is set or a GPU is
  present, and otherwise skips.
- `requireLocalResource(resource, locationProperty, available)`: for resources **no lane
  carries** (model weights, developer-local data, the application's local-destination
  `pattern-factory.json`). It fails only if `locationProperty` is set, and otherwise
  skips. It exists because applying the GPU rule to model weights would turn every GPU
  lane red at once: `engine/ml` runs on `test-mac` / `test-cl` / `test-cuda` and no lane
  provisions weights. This is a deliberate, reviewable choice. If the owner wants weights
  to be mandatory on some lane, that lane sets the location property and the same tests
  start failing there.

`AudioSceneTestBase.requireSampleLibrary()` (library only) and `requireCuratedLibrary()`
(library plus pattern factory) delegate to `requireResource`. Behaviour changes from the
old `requireCuratedLibrary`:

1. A CPU-only host that **declares** `AR_RINGS_LIBRARY` now fails instead of skipping.
2. A library directory that exists but is **empty** (a bind-mount of a missing host path,
   or a sync that never ran) fails on every host.

### 6.2 Sites converted (curated sample library: fail on GPU / declaring hosts)

| Test | Before | Now |
|---|---|---|
| `AudioSceneMultiGenomeTest#multiGenomePatternRender`, `#multiGenomeFullRunner` | `if (!samplesDir.exists()) { log("Skipping..."); return; }`, a **silent pass** (reported as 0.001 s passes on `test-media`, §1) | `requireSampleLibrary()` |
| `ArrangementGenerationNoHeapTest#generateArrangementsWithoutHeap` | log + `return` (silent pass) | `requireCuratedLibrary()` |
| `ArrangementGenerationProcessTest` (studio/spatial) `#processAttachesScoredNetworksWithStems`, `#rendersSurviveAcrossCycles` | log + `return` (silent pass) | `requireCuratedScene()` → `requireResource` for library and pattern factory |
| `AudioSceneBaselineTest` (4 methods) | `assumeTrue(samplesDir.exists())`, a skip on every host | `requireSampleLibrary()` |
| `RealTimeRendererCorrectnessTest` (`@Before`) | `assumeTrue` | `requireSampleLibrary()` |
| `BatchedRealSceneRenderTest.requireRealAssets` | two `Assume` calls | `requireCuratedLibrary()` |
| `OptimizerSceneDiagnosticTest` (5 methods), `AudioSceneOptimizationTest` (`@Before`), `AudioSceneTest#runScene`, `PatternFactoryTest#runLayers`, `ReproduceRefreshBug` | `Assume` on `AudioSceneOptimizer.LIBRARY` / `AR_RINGS_LIBRARY` (`ReproduceRefreshBug`: log + `return`) | `requireResource(..., "AR_RINGS_LIBRARY", ...)` for the library; local-only inputs are checked first with `requireLocalResource` (below) |

### 6.3 Sites converted to `requireLocalResource` (no lane carries the resource)

- studio/compose: the local-destination / working-directory `pattern-factory.json` and
  `scene-settings.json` (`OptimizerSceneDiagnosticTest`, `AudioSceneOptimizationTest`,
  `PatternFactoryTest`); the protobuf store (`ReproduceRefreshBug`,
  `PrototypeDiscoveryTest`, both previously log + `return`);
  `PrototypeDiscoveryPersistenceTest` (`ar.test.samples`, previously log + `return`);
  `GenerateAudioFileTest#spectrogramExistingRender` (an earlier render's output,
  previously log + `return`); `AudioSceneRealTimeTest` and
  `RealTimeTestHelper.requireLibraryDir` (a developer-local `Library` directory);
  `AudioSynthesizerTests` (local model `Library`).
- Model weights, tokenizers, reference activations and local data sets in engine/ml,
  studio/compose `ml`/`midi` and studio/experiments. These were all log + `return`
  silent passes and are now visible skips (fails where their location property is set).
  See §6.6 for the file list.

### 6.4 CI-side check

`test-media-mac` now declares `AR_RINGS_LIBRARY=/Users/Shared/Music/Samples` and
`AR_RINGS_PATTERNS=/Users/Shared/Music/pattern-factory.json`, as the CL and CUDA media
lanes already did. All three GPU media lanes (`test-media-mac`, `test-media-cl`,
`test-media-cuda`) run a `Verify curated sample library` step right after checkout. It
fails the job when the library is missing or empty or the pattern factory is missing, so
the cause is reported once, before any test runs. `.github/CLAUDE.md` and
`tools/ci/macos/README.md` describe this. The CPU `test-media` lane is unchanged: it has
no GPU and does not declare the library, so library tests skip there.

*CI-file lock.* These edits are under `.github/workflows/` on a `feature/` branch. The job
was submitted with sensitive-file protection lifted. If the harness drops them anyway, the
Java-side policy still fails every library test on the GPU lanes without the library. The
one exception is the optimizer-library tests on `test-media-mac`
(`AudioSceneOptimizer.LIBRARY` defaults to the relative `Library`), which then need the
`AR_RINGS_LIBRARY` declaration to find the fleet's copy.

### 6.5 Left as is, deliberately

- **Synthetic fallback by design** (`getSamplesDir()` / `RealTimeTestHelper.requireSamplesDir()`
  returning `null`): `AudioSceneRealTimeCorrectnessTest`, `AudioSceneBufferConsolidationTest`,
  `HeapPatternRenderingTest`, `AudioScenePdslBenchmarkTest`, the two `getSamplesDir` methods
  of `AudioScenePdslCutoverTest`, `AudioSceneSingleVsMultiChannelTest`,
  `MixdownManagerPdslVerificationTest`. These run real work on synthetic samples and are
  not passes of skipped work. Finding: the baseline scene's sample names are not resolved
  under the curated library at all (they are looked up at the library root, but live in
  subdirectories), so `createBaselineScene` always uses synthetic samples. Filed as
  follow-up task `9834c8a4-e034-434d-850b-ff311ee6779d`.
- **"No working genome found - skipping test" returns** (about 20 sites in
  `AudioSceneRealTimeCorrectnessTest`, `AudioSceneBufferConsolidationTest`,
  `AudioSceneMultiGenomeTest`, `AudioSceneBaselineTest`). These are silent passes, but not
  missing resources, and this job's edits to existing tests were limited to the resource
  policy. Filed as follow-up task `3e55373a-5241-4608-94ff-fa2884aa3b16`.
- **Backend guards** (`if (metal == null) return;` and similar in engine/utils hardware
  tests, CL provider checks), and audio or MIDI device checks. These are not provisioned
  resources.
- **studio/music `Library/<file>.wav` fixtures** (`PatternAudioTest`, `SequenceTest`,
  `GrainTest`, `EnvelopeTests`). These are already `Assume` skips, which are reported as
  skips rather than passes, of developer-local files that no lane carries.
- Several converted tests contain no assertions at all (`AudioSynthesizerTests`,
  `PatternFactoryTest`, `PrototypeDiscovery*Test`, most `OptimizerSceneDiagnosticTest`
  variants, `multiGenomePatternRender`). Assertions were out of scope for this change.
- `AudioSceneRealTimeTest` / `RealTimeTestHelper.LIBRARY_PATH` names a private
  downstream checkout as its default path. This predates this change.

### 6.6 Model-weight and local-data sites (`requireLocalResource`)

Each one was a log + `return` silent pass. The location property is in parentheses where
the resource has one.

- engine/ml: `OobleckValidationBase.requireWeights()`, used by `OobleckValidationTest`,
  `OobleckLayerValidationTest`, `OobleckBlock3ValidationTest` and
  `ResidualBlockSubComponentTest`; `SAMEResamplingParityTest`, `SAMEAutoEncoderParityTest`
  (`AR_SAME_WEIGHTS`, `AR_SAME_REFERENCES`); `StableAudio3ConditionerParityTest`,
  `StableAudio3TransformerParityTest`, `StableAudio3GenerationTest` (`AR_SA3_*`,
  `AR_T5GEMMA_*`); `T5GemmaEncoderParityTest`, `T5GemmaEncoderStageParityTest`;
  `SentencePieceBPETokenizerTest` (`AR_T5GEMMA_TOKENIZER`); `Qwen3TokenizerTest`;
  `DiffusionTransformerTests` (`ditWeightsDir`); `EndToEndFineTuningTest`;
  `EncoderCompilationProfileTest`.
- studio/compose: `StableAudio3WavOutputTest`, `DiffusionTransformerForwardTest`,
  `AudioTrainingDataCollectorTest`, `MoonbeamFineTuningTest`.
- studio/experiments: `DrawingGenerationEndToEndTest`.

### 6.7 Verification (Metal host with the curated library present)

- New `ResourceRequirementTest` (engine/utils), which pins the policy: 5/5 pass.
- With the library present, `RealTimeRendererCorrectnessTest#testBufferSizeConsistency`
  and `AudioSceneBaselineTest#baselineAudioGeneration` both pass.
- With `-DAR_RINGS_LIBRARY=/nonexistent/Samples`, the same `RealTimeRendererCorrectnessTest`
  method and `AudioSceneMultiGenomeTest#multiGenomePatternRender` both **fail** with
  "Required resource not available: curated sample library ...". Before this change
  `multiGenomePatternRender` returned and reported a pass.
- `OobleckBlock3ValidationTest` with no weights: 5 skipped, 0 passed. Before, all 5
  reported a pass.
- Build validator (checkstyle, code policy, test timeouts, duplicate code, invalid
  files): all passed. The Pattern 2 (net assertion) rule of `detect-test-hiding.sh`,
  applied to the working-tree diff, found no net assertion loss in any changed file.
- Not run here: the spatial `ArrangementGenerationProcessTest` (15–25 min renders). It
  compiles, and its change is the same two-line `requireResource` call.
