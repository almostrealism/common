# Audio CI Stability Investigation

**Status:** ROOT CAUSE CONFIRMED (2026-07-20) — a Metal command-completion deadlock introduced by
the `feature/async-destination-reuse` work. The earlier compile-storm / aggregation-signature
theories below are **refuted** by a thread dump and retained only as investigation history.
**Opened:** 2026-07-20
**Branch where symptoms observed:** `feature/audio-test-cleanup` (a branch whose *only*
intent was to reduce test-job runtime; it did not touch the render machinery).

## CONFIRMED ROOT CAUSE — Metal completion deadlock

Reproduced locally on this M1 (`KernelCacheReuseAcrossScenesTest`, real library): the render
**deadlocks** during the tick. Two thread dumps 69 s apart showed identical stacks and identical
CPU times (main thread 29952 ms CPU over 861 s elapsed — i.e. parked, not computing), so this is a
hard deadlock, not slowness. The cycle:

- **`ComputeContext-5`** holds the `AcceleratedProcessDetails` monitor (`0x…607adba0`) inside
  `AcceleratedProcessDetails.notifyListeners` (`AcceleratedProcessDetails.java:411`) and then, still
  holding it, a listener synchronously blocks in `MetalCommandRunner.await`
  (`MetalCommandRunner.java:476`) waiting for a Metal command-buffer completion.
- **`pool-4-thread-1`** — the Metal completion callback that would deliver that completion
  (`MetalCommandRunner.whenComplete → runInPool` → `AcceleratedProcessDetails.releaseDestinationLeases`,
  `AcceleratedProcessDetails.java:362`) — is **BLOCKED trying to acquire the same monitor**
  `0x…607adba0`.

So the completion pool can never run the callback that would let `ComputeContext-5` release the
monitor → permanent deadlock. The main `Time-limited test` thread and `ComputeContext-7` are parked
behind the same jammed Metal completion pipeline (`awaitReady` / `MetalCommandRunner.await`).

**Why it matches every constraint:** a deadlock is timing/concurrency-dependent, so it is invisible
in small isolated tests and only manifests at real-scene scale (many concurrent Metal ops + the
`pattern-render-ahead` thread); it is intra-process, so isolating the runner to one M4 Max could
not help; and it produces both hangs (timeouts) and, when a partially-completed render is torn down
at timeout, plausibly `peak=0.0`.

**Introducing change:** `feature/async-destination-reuse` — the destination-lease reuse gated on
async completion:
- `9e5298a51` "Reuse sized argument destinations across invocations, gated on completion"
  (2026-07-16) — introduced `releaseDestinationLeases` and the `AcceleratedProcessDetails` lock.
- `292cda200` "Skip late bridge signals to released events…" (2026-07-17) and
  `efa6b7e43` "Release destination leases passively so Metal batching survives" (2026-07-18) —
  follow-up concurrency patches to the same mechanism that did not close the deadlock.

These landed 2026-07-16..18, matching "master was clean ~a week ago."

**Fix direction (for the owner of that code):** do not hold the `AcceleratedProcessDetails` monitor
across the synchronous `MetalCommandRunner.await` in `notifyListeners`; or make
`releaseDestinationLeases` not require that monitor (or take it in a consistent order); or deliver
Metal completions on an executor that cannot be exhausted by callbacks contending for the same lock.
The precise fix needs the concurrency owner — the shape is "lock held across a blocking GPU-completion
wait, re-entered by the completion callback."

---


## Symptom

`test-media-mac` (the Metal / macOS media job) fails hard on the real-sample AudioScene
render tests. The failures are a mix of **silence** (a render producing `peak=0.0`) and
**timeouts**, including — critically — a timeout *inside scene loading, before any buffer
is rendered*:

```
Failures:
  BatchedRealSceneRenderTest.singleMelodicChannelFullPipeline:177  rendered output is silent (peak=0.0)
Errors:
  BatchedRealSceneRenderTest.allChannelsFullPipeline:315->realGenome:120        TestTimedOut after 1200000 ms
  BatchedRealSceneRenderTest.allChannelsWarmSteadyState:353->...->realGenome:120 TestTimedOut after 1200000 ms
  BatchedRealSceneRenderTest.profileSingleMelodicChannel:376                     TestTimedOut after 900000 ms
  BatchedRealSceneRenderTest.renderAllChannelsContinuousToFile:414              TestTimedOut after 2400000 ms
  BatchedRealSceneRenderTest.singleMelodicChannelWarmSteadyState:293->...        TestTimedOut after 900000 ms
  GenerateAudioFileTest.generateAudioFile:96->AudioSceneTestBase.loadCuratedScene:241  TestTimedOut after 900000 ms
Tests run: 588, Failures: 1, Errors: 6, Skipped: 503
```

The most diagnostic line is the last error: `generateAudioFile` timed out **inside
`loadCuratedScene`** (i.e. `AudioScene.load` + setup), before rendering. Scene loading
should take seconds. A 15-minute hang there means the anomaly is in the **setup / compile
path**, not render throughput.

## Circumstances (what has been controlled for)

The M4 Max host (the one with `/Users/Shared/Music` mounted) was reconfigured to run a
**single** GitHub Actions runner with **all CPU throttling disabled**. The first job to
reach that isolated, more-powerful runner failed *worse* than most previous runs. So the
instability is **not** GPU/CPU contention between concurrent runners.

## Ruled out

- **Runner collision / resource contention.** Eliminated by the isolated single-runner M4
  Max setup above.
- **H (machine-specific / Metal command-buffer leak).** Considered and set aside — does not
  fit "master was clean ~1 week ago," and a documented `MetalOperator` command-buffer leak
  (stalls ~buffer 2000) would not explain a *load-time* hang.
- **H (false-green: tests never really ran before).** Considered and essentially set aside
  by the owner.
- **Object-level defect in the thing under test.** Not a competing hypothesis: if the audio
  render were genuinely broken, the test *should* fail — that is the test doing its job, not
  a mystery to explain.

## Live theory

Each recent change below was **tested and worked in isolation.** Therefore the failure is
most likely an **emergent sensitivity in InstructionSet / instruction caching that only
appears when these compositions run at full real-scene scale** (a dense curated arrangement:
hundreds of notes across all 6 channels), not a defect in any single change. The real
samples matter only insofar as they drive a denser, larger arrangement than the synthetic
fallback used previously.

Candidate mechanism to prove or kill: at real-scene scale, setup compiles (or recompiles)
far more instruction sets than expected — e.g. a per-element compile that never showed up in
small isolated tests, or a signature miss/collision that defeats instruction reuse — turning
scene setup into a compilation storm (→ load timeout) and/or binding the wrong buffer region
(→ `peak=0.0`).

## Recent commits in scope (compute/hardware/audio, ~last 2 weeks, on master)

These moved per-scalar host writes onto the device and changed instruction reuse — the areas
most likely to interact badly with instruction caching at scale:

- `2074384c0` Compute biquad filter coefficients on the device
- `b4d746dc1` Compute the ADSR envelope tick on the device
- `81ebdf4ff` Modulate the synth filter cutoff on the device
- `0cc0db105` **Revert** the engine/audio setMem migrations that wrapped scalar writes in kernels
- `fc8e2e5f1` Support signature-based instruction reuse for aggregations
- `35ea7e044` Address review feedback on aggregation signature support
- `37654eab7` Switch the default real-time buffer to 1024

The existence of the revert (`0cc0db105`) confirms the team already hit a
"scalar-write-wrapped-in-a-kernel" problem once; see `docs/plans/SETMEM_ENFORCEMENT_POSTMORTEM.md`.

## Findings so far (2026-07-20)

Read the two device-migration diffs directly:

- `2074384c0` (biquad on device) and `b4d746dc1` (ADSR on device) are **careful fixes for**
  the kernel-per-value problem, not causes. Each computes its result as a producer / single
  `HybridScope` op that **compiles once and runs every tick**, and each ships a test that
  explicitly asserts a runtime-varying input does **not** compile a kernel per value. This
  matches the owner's point that the individual changes work in isolation.
- **But the biquad migration is partial.** Its own message: *"callers that still pass host
  doubles are unchanged until they are migrated to supply producers."* Under the
  literals-only setMem enforcement, a host-double coefficient write compiles a distinct kernel
  per value. So **any unmigrated caller on the real render/mixdown filter path would still hit
  the kernel-per-value explosion** — and that path is only exercised at real-scene scale, not
  by the isolated parity tests.

Refined lead, in priority order:

1. **Aggregation signature-based instruction reuse (`fc8e2e5f1`, follow-up `35ea7e044`) — the
   primary suspect.** Read the commit: `AggregatedProducerComputation` *previously disabled
   signature generation entirely*, so every aggregation always recompiled (safe, and it
   worked). This commit turns on signature-keyed reuse, building the signature by rendering the
   initial/combining functions applied to placeholder references. The commit itself names both
   failure modes:
   - a signature that is **too coarse** lets *two different aggregations collide on one
     compiled kernel* → wrong buffer bound → **silence (`peak=0.0`)**;
   - a signature that is **null or non-deterministic** across rebuilds yields **no reuse** →
     recompile per instance → **compile storm → load/setup timeout**.

   Aggregation `delta()` builds a gradient "for every sum and max in an autodiff graph." A real
   6-channel scene has *many* sums/maxes; an isolated test has few — so a collision or a
   reuse-miss only bites at real-scene scale. This matches every constraint: emergent at scale,
   invisible in isolation, a direct InstructionSet-cache behavior change, produces *both*
   silence and timeouts, landed ~1 week ago.
   *Decisive experiment:* if aggregation signature generation can be disabled (restoring the
   pre-`fc8e2e5f1` always-recompile behavior), does the real scene load/render recover? Also
   check whether the rendered signature is stable across two rebuilds of the same aggregation.

2. **An unmigrated host-double caller** in the real filter path still compiling a kernel per
   value. Weakened: `AudioSynthesizer` and the mixdown/efx filters are already on the producer
   path; the only remaining host `calculate*Coefficients` calls are inside `BiquadFilterCell`'s
   own update methods. Confirm whether those run per note/tick on the real render path.

## Reproduction

This M1 experiment host **also** mounts `/Users/Shared/Music`, so the real scene can be
loaded and rendered here. Same code + real library ⇒ if it hangs/goes silent here too, the
M4 Max is exonerated and the cause is in the code (bisect the commits above).

Fast, direct probes (run under the default profile, not pipeline):
- `KernelCacheReuseAcrossScenesTest.reuseAcrossScenes` — logs per-scene `setupMs`,
  `firstTickMs`, `renderLoopMs`; a compilation storm shows up as a huge `setupMs` on scene 0,
  and broken cross-scene reuse shows as scene 1 not getting cheaper.
- `GenerateAudioFileTest.generateAudioFile` — the failing representative render (all channels,
  real samples, 1024 buffer). Times out in `loadCuratedScene` in CI.

To count compilations directly, attach an `OperationProfileNode` (as
`AudioScenePdslBenchmarkTest.pdslTickProfile` does) and inspect with `ar-profile-analyzer`.

## Reproduction attempt 1 (2026-07-20, this M1, KernelCacheReuseAcrossScenesTest)

After a clean `mvn install -DskipTests -pl studio/compose -am` (the ar-test-runner uses `-pl`
without `-am`, so a rebuild is mandatory — a first attempt with stale `~/.m2` ran 0 tests):

- **The load-time hang did NOT reproduce here.** `loadCuratedScene` finished in ~20 s
  (`10:37.26` hardware init → `10:37.49` building the 6-channel render). CI timed out at 900 s in
  the *same* call. Most likely difference: a fresh CI checkout has no persisted `SCENE_SETTINGS`,
  so CI takes the **first-run rebuild** path (`AudioScene.load` with `settings == null`, which
  draws fresh randomized settings and saves them), while this host reloads cached settings. That
  first-run path is the thing to time on a clean checkout.
- **Setup IS slow here.** After load, graph construction crawled — 30–60 s gaps between log lines
  (`10:37.49` → `10:38:19` → `10:38:22`) with no per-scene `setupMs` printed after 2.5 min. So the
  setup/compile cost partially reproduces even on the fast path.
- **Local-env noise to ignore:** a JaCoCo `IllegalClassFormatException` ("Unsupported class file
  major version 68") fires while loading a JDK class during `AudioScene.load` — it is swallowed
  (loading continues) and is a Java-24 + JaCoCo-0.8.11 mismatch, not the bug. CI may differ here.

Refined discriminator: time `AudioScene.load` on a **fresh checkout with no `SCENE_SETTINGS`**
(delete `results/pdsl-cutover/scene-settings.json` first) to hit the CI first-run path.

## Decisive A/B for the primary hypothesis

`AggregatedProducerComputation.isSignatureSupported()` currently returns `true` (the switch
`fc8e2e5f1` flipped; there is no runtime flag). To test Lead 1: override it to return `false`
(restoring the pre-`fc8e2e5f1` always-recompile behavior), rebuild the compute/engine layer, and
re-run the real scene. If the setup slowness and/or the `peak=0.0` silence clears, `fc8e2e5f1` is
confirmed. Equivalent: `git bisect` with `fc8e2e5f1` as a suspected first-bad commit.

## Next steps

1. Reproduce on this M1 (KernelCacheReuseAcrossScenesTest / GenerateAudioFileTest) to confirm
   the load-time hang is code, not machine.
2. Read the device-migration diffs (`2074384c0`, `b4d746dc1`) for a per-element `get()`/compile.
3. Profile a real-scene setup and count distinct instruction-set compiles at scale.
4. If confirmed, bisect the commits above against the real-scene load time.
