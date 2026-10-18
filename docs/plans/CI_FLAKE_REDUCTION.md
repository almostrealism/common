# CI Flake Reduction

**Status:** plan, agreed in outline with the owner (2026-10-02). Work happens on a new
branch after PR #601 merges.

## Why

A pipeline gets three attempts before it counts as failed. Every test whose failure
rate can be driven down raises the chance of a clean result inside that budget. One
flaky test would be absorbed by three attempts; the problem is that there are many.

## Evidence

Every individual test failure was catalogued from 26 failed `analysis.yaml` runs
between 2026-09-29 and 2026-10-02, across master and six branches. Only the latest
attempt of each run is visible through the API, so the counts are lower bounds.

| Test | Lane | Failures seen | On master |
|---|---|---|---|
| `FixedArgumentBindingTest#requestIsNotHeldUntilDependencyCompletes` | test-cl | 6 | 2 |
| `DelayNetworkAmplificationTest#gainSemanticsAreSizeInvariant` | test-media | 4 | 1 |
| `SimilarityOverheadTest#pairwiseSimilarityAtScale` | test | 4 | 3 |
| `StableDurationHealthComputationTest#genomesFromPopulation` | test-media-cl | 3 | 0 (fixed on PR #601) |
| `DiskStoreAudioLibraryTest` (several methods) | test-media-cl | 2 runs | 0 (fixed on PR #601) |
| Silent-render cluster (`BatchedRealSceneRenderTest`, `FixedPatternCorrectnessTest`, `GenerateAudioFileTest`) | test-media-mac, test-media-cl | 3 runs | 2 |
| `WaveCellEnvelopeTest#defaultEnvelopeComputationUsesNotePosition` | test-media | 2 | 0 |
| `SyntheticNormTrainingTest` | test | 2 | 0 |

## Work items, in order of payoff

### 1. Merge PR #601

`FixedArgumentBindingTest#requestIsNotHeldUntilDependencyCompletes` fails when argument
preparation waits on the host for a dependency that is still pending — the behaviour
PR #601 removes. It never failed on that branch and passes in 0.12 s on its CL lane.

### 2. Keep a real performance guard on pairwise similarity at scale

`SimilarityOverheadTest#pairwiseSimilarityAtScale` runs 499,500 comparisons in about
560 s against a 600 s timeout and has no assertions, so it fails whenever a runner is
slow and proves nothing when it passes.

The owner's requirement: pairwise similarity performed at substantial scale must stay
performant, and something must enforce that. Removing this test from the pipeline is
not acceptable on its own. The replacement should:

- assert on a measured throughput (comparisons per second, or per-comparison cost),
  rather than relying on a wall-clock timeout as the only signal;
- be sized so that a healthy run finishes far inside its timeout, with the margin
  coming from the throughput assertion rather than from the timeout;
- run on the code path production uses (`WaveDetailsFactory.productSimilarity` and the
  batched similarity path), so a regression there is caught.

The profiling harness can remain for JMX investigation once the guard exists.

### 3. Stop driving audio cells one sample at a time from Java

These tests advance a cell or network once per sample from a Java loop, with a kernel
dispatch (and often a JNI read) per iteration. Their run time is dominated by
per-dispatch overhead, so it swings with runner load:

- `DelayNetworkAmplificationTest.measureDcSteadyState` — 88,200 iterations of
  `ev.evaluate().toDouble(0)` plus `tick.run()` per network; `gainSemanticsAreSizeInvariant`
  measures two networks. The class has taken anywhere from 820 s to 1,800 s.
- `WaveCellEnvelopeTest#defaultEnvelopeComputationUsesNotePosition` — 66,150
  iterations of `push.run()` plus `tick.run()` against a 180 s timeout.
- To check: `TimeCellAutomationTest`, `SequenceTest`,
  `SineWaveComputationTest#outputStabilityOverTime`.

Fix: run the ticks in a compiled loop that writes into a buffer, then make the same
assertions on the buffer once. This is also what the project's "Java is orchestration"
rule requires.

### 4. Find out why curated renders go silent

Several studio tests render a real arrangement and retry with a new random arrangement
whenever the output is exactly silent (`MAX_RENDER_ATTEMPTS = 8`). Every attempt is
pre-filtered to have notes on the target channel, yet on every backend roughly 60–75%
of attempts render exactly 0.0. The retry loop turns that rate into an occasional
failure (all eight silent). Determine whether the silence is legitimate (the curated
pattern factory references samples that cannot be loaded on the runners — CI logs show
missing files and unsupported WAV compression) or a render bug, then make the test
choose its material deterministically instead of retrying.

### 5. Seed the randomness tests depend on

- `rand(shape)` / `randn(shape)` default to an unseeded `java.util.Random`; 57 test
  files call them without a seed.
- Layer weight initialisation (`LayerFeatures.dense(...)`, `randnInit(...)`) uses the
  same unseeded source, so every training test starts from different weights.
- Arrangement randomness uses `Math.random()` (`ParameterFunction.random()`,
  `ParameterSet`, `GrainSet`, `AudioSceneOptimizer`).

Introduce one seedable default random source behind these, reset to a fixed seed per
test method by `TestSuiteBase`, so each test sees the same random numbers on every run
regardless of test order. Production behaviour is unchanged unless a seed is set.
`SyntheticCompositionTrainingTest` stopped being flaky once it was seeded by hand.

## Related

- The native lane is slower on PR #601 than on master for dispatch-heavy tests
  (`DelayNetworkAmplificationTest` 820 s on master versus 1,117–1,611 s on the branch).
  That amplifies items 3 and 5 and still needs a root cause.
- The test-media-mac coverage gaps (tests that skip because they look for assets in the
  working directory) are being handled separately on a `ci/` branch.
