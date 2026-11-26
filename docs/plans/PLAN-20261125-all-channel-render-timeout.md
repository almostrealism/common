---
status: completed
created: 2026-11-25
closed: 2026-11-25
tracker: [afdcf400-bfb1-4a69-8129-098e0679395e]
---

# Fix the sustained all-channel render timeout and restore the test to CI

Plans tracker task **afdcf400-bfb1-4a69-8129-098e0679395e** — *"Fix the sustained
all-channel render timeout and restore the test to CI"*.

## Motivation

`BatchedRealSceneRenderTest.renderAllChannelsContinuousToFile` is the only coverage
the suite has for a *sustained* all-channel render: it drives the production
`RealtimeContinuousRenderer` over roughly five minutes of audio and asserts the
looped master mix (`results/rt-live.wav`) is written and non-silent. It is the
closest automated proxy for the real-app playback load running indefinitely.

Today the test carries `@TestProperties(excludeProfiles = TestUtils.PIPELINE)`, so it
never runs in CI: it times out on a sustained all-channel render. A test that is
excluded proves nothing, and a sustained-render regression (a native-memory leak or a
Metal-kernel stall that only appears after minutes of looping) would ship unnoticed.
The task is to find and fix the underlying cause of the timeout and then remove the
exclusion, so the suite again gates on sustained rendering.

## Background: how the timeout arises

`renderAllChannelsContinuousToFile` sets `AR_RT_CHANNELS=0..5`, `AR_RT_MEASURES=64`,
`AR_RT_HOURS=5/60`, `AR_RT_LIVE_OUTPUT=true` (which is what makes the renderer write the
looped `results/rt-live.wav` the test asserts on) and `AR_RT_LOG_EVERY=32`, then calls
`RealtimeContinuousRenderer.main`. The renderer:

- builds one 64-measure arrangement (~2 min of audio) and **loops it** for the target
  duration — `AR_RT_SWITCH_LOOPS` defaults to `0`, so the arrangement never changes;
- paces submission to playback time (`AR_RT_PACE_RATE` defaults to `1.0` = realtime)
  so the GPU queue stays shallow — it does not render flat-out.

Each buffer's pattern audio is produced through `PatternLayerManager.sum()`. Whether
that call re-evaluates note audio is governed by `PatternLayerManager.cachePersist`:

```java
private static final boolean cachePersist =
        Boolean.getBoolean("AR_PATTERN_CACHE_PERSIST");
```

In `sum()` (see `PatternLayerManager.sum`), when the cache epoch is unchanged and
`cachePersist` is **false**, the note-audio cache is cleared at frame 0 and
`evictBefore(frame)` otherwise, and cleared again when a pass reaches the arrangement
end. So without the flag every looped pass re-evaluates and re-frees the note audio —
the deallocation churn the class javadoc describes, which "intermittently stalls the
render kernel" and (per `docs/internals/pdsl-audio-dsp.md`) "leaks native memory on
every buffer." Over a multi-minute looped render those stalls accumulate: a stalled
buffer exceeds its paced budget (so the pacing sleep is zero and wall time balloons),
and native memory climbs. The render falls far behind realtime and exceeds the test's
`timeout = 2_400_000` ms (40 min).

With `AR_PATTERN_CACHE_PERSIST=true`, the first pass renders the note audio and every
later pass reuses it: no re-evaluation, no per-buffer deallocation churn, memory
bounded by arrangement length. `RealtimeContinuousRenderer` is exactly the safe case
for persistence — it loops a single fixed arrangement and only invalidates on an
arrangement switch (which this test never triggers).

`cachePersist` is read **once, at class load**, via `Boolean.getBoolean` (system
property only — note it does *not* consult the environment the way
`SystemUtils.getProperty` does). `PatternLayerManager` is already loaded by the time
the test body runs (the method touches `PatternLayerManager.enableBatched` first), so
the test cannot set the property at runtime. Enabling it requires a JVM `-D` argument
present before class load — a surefire `argLine`, which lives in `pom.xml`.

## Scope

Within the task:

1. **Confirm the cause** on a macOS/Metal host with the curated library: reproduce the
   timeout, capture a thread dump and an `ar-jmx` native-memory timeline of the
   timed-out run, and confirm render pacing is actually engaged. Verify that enabling
   `AR_PATTERN_CACHE_PERSIST` brings the sustained all-channel render back within
   budget (and, if the evidence points elsewhere, fix whatever it actually shows).
2. **Make the flag reach the test in CI** — the owner-dependent step (see
   Dependencies). The investigation produces the evidence that justifies the request.
3. **Fix the underlying cause** so the sustained render completes within the timeout.
4. **Remove the exclusion**: delete `@TestProperties(excludeProfiles = TestUtils.PIPELINE)`
   from `renderAllChannelsContinuousToFile` (and the now-unused `TestProperties` /
   `TestUtils` imports if nothing else in the file uses them), restoring the test to CI.

Explicitly **out of scope** (name as dependencies / follow-ups, do not widen into):

- Editing `pom.xml` to add the surefire `argLine` — "Do Not Modify pom.xml Files";
  only the owner may. This plan requests it with evidence.
- Editing `.github/workflows/analysis.yaml` — under the CI-file lock; requires a
  `ci/...` branch, not this one.

## Approach

**Files involved**

- `studio/compose/src/test/java/org/almostrealism/studio/pattern/test/BatchedRealSceneRenderTest.java`
  — the test; the exclusion is removed here.
- `studio/compose/src/main/java/org/almostrealism/studio/optimize/RealtimeContinuousRenderer.java`
  — the renderer under test; pacing and the per-buffer loop live here.
- `studio/music/src/main/java/org/almostrealism/music/pattern/PatternLayerManager.java`
  — `cachePersist`, `sum()`, `invalidateCaches()`; the cache behaviour that drives the cost.
- `studio/compose/pom.xml` — where a surefire `argLine` would set the `-D` property
  (owner change).

**Step 1 — reproduce and instrument.** On a macOS/Metal node with the curated library
present (`/Users/Shared/Music/Samples` + `pattern-factory.json`), run the test under
the pipeline profile with the hybrid backend (driver unset — the mixdown needs JNI +
Metal hybrid routing; forcing a single backend is documented to fail). While it is
timing out, capture:
- a thread dump (`ar-jmx get_thread_dump`) to see whether the render thread is blocked
  in Metal completion / `waitUntilCompleted` (the stall signature);
- an `ar-jmx` native-memory timeline (`get_memory_timeline` / `get_native_memory`) to
  see whether allocated native bytes climb monotonically (the leak signature); the
  renderer already logs `allocatedNativeBytes()` per `AR_RT_LOG_EVERY`, cross-check it.
Confirm pacing is engaged (`AR_RT_PACE_RATE` effective, buffers sleeping) so the cause
is isolated to cache churn rather than flat-out queue saturation.

**Step 2 — confirm the fix.** Re-run the same render with
`-DAR_PATTERN_CACHE_PERSIST=true`. Expect: flat native-memory timeline after the first
loop, no render-thread stalls, per-buffer ratio near/under realtime, completion well
within the timeout. This is the evidence that the persistent cache is the fix.

**Step 3 — deliver the flag to CI.** Request that the owner add
`-DAR_PATTERN_CACHE_PERSIST=true` to the `studio/compose` surefire `argLine` (the test's
module). See the open question on blast radius — a blanket module-wide flag may be
unsafe for other compose tests, so the owner may prefer a narrower mechanism.

**Step 4 — restore to CI.** Remove the `excludeProfiles` exclusion and clean up imports.
With the flag in place the test runs in `test-media-mac` under the pipeline profile and
must pass within its timeout.

## Success criteria

- A captured thread dump and `ar-jmx` native-memory timeline of a *timed-out* run, with
  a written diagnosis of whether the cause is kernel stall, native-memory growth, or
  both — stored as a branch memory.
- With `AR_PATTERN_CACHE_PERSIST=true`, `renderAllChannelsContinuousToFile` completes
  within its 40-minute timeout on the macOS/Metal lane, writes a non-silent
  `results/rt-live.wav`, and its logged per-buffer native-memory figure is flat after
  the first loop.
- `@TestProperties(excludeProfiles = TestUtils.PIPELINE)` is gone from the method; the
  test is no longer excluded from the pipeline profile.
- The sibling tests in `BatchedRealSceneRenderTest` (`singleMelodicChannelFullPipeline`,
  `allChannelsFullPipeline`, `singleMelodicChannelWarmSteadyState`,
  `allChannelsWarmSteadyState`) still pass under the same configuration, proving the
  flag / fix did not regress the other full-scene renders.
- CI's `test-media-mac` pipeline lane passes with the test included.

## Dependencies

- **Owner must enable `AR_PATTERN_CACHE_PERSIST` for the test in CI (blocking).** This
  agent may not edit `pom.xml` or the CI workflow. The implementation produces the
  diagnostic evidence; the owner decides where the `-D` lands (compose surefire
  `argLine`, or the `test-media-mac` pipeline command on a `ci/...` branch). Until the
  flag reaches the CI JVM, removing the exclusion would reintroduce the timeout, so the
  exclusion must not be removed before the flag is in place.
- **Hardware:** the test requires a Metal backend and the curated sample library, so the
  implementation runs on a macOS node. Captured in
  `docs/plans/task-20261125-181223-workstream.yaml` (`requiredLabels: platform: macos`).

## Open questions

1. **Blast radius of a module-wide `argLine`.** A surefire `argLine` applies
   `AR_PATTERN_CACHE_PERSIST=true` to *every* `studio/compose` test, but
   `docs/internals/pdsl-audio-dsp.md` warns that callers which switch arrangements at
   runtime must leave it off (or call `invalidateCaches()`). The owner should confirm no
   other compose test switches arrangements without invalidating, or scope the flag more
   narrowly. A cleaner alternative worth the reviewer's consideration: let the sustained
   renderer own the decision (the single-fixed-arrangement loop is precisely the safe
   case) rather than relying on a process-global class-load flag — this would avoid both
   the `pom.xml` dependency and the blast radius, at the cost of changing shared
   infrastructure (`cachePersist` would no longer be `static final`). Recommend the
   implementer evaluate this against the task's stated `-D`/`argLine` route and surface
   the choice in review.
2. **CI wall-time budget.** Paced at realtime, five minutes of audio costs at least five
   minutes of (mostly sleeping) macOS-runner wall time. Consider having the test pace at
   a bounded multiple (e.g. set `AR_RT_PACE_RATE=4.0`, documented as "still bounded")
   to cut wall time to ~75 s while still exercising sustained dispatch — keeps the
   pipeline lane cheap without weakening what the test proves. Unlike
   `AR_PATTERN_CACHE_PERSIST`, this lever carries no owner dependency: `AR_RT_PACE_RATE`
   is read at runtime inside `RealtimeContinuousRenderer.main` (via `System.getProperty`),
   not once at class load, so the test can set it in its own body with `System.setProperty`
   alongside the other `AR_RT_*` properties it already sets — no surefire `argLine` needed.
   The owner/reviewer should decide whether realtime pacing is a deliberate requirement of
   this test.
3. **Should the fix prove to be something other than the persistent cache** (the
   diagnostics are decisive here), the plan's Step 3 owner request may be unnecessary;
   the fix then lands entirely in the renderer / cache code and the exclusion is removed
   once the sustained render is within budget.

## Implementation outcome

The diagnosis overturned the cache-churn hypothesis (open question 3 applied).

- **Reproduction (unfixed, macOS/Metal hybrid, curated library).** The first 64-measure
  pass (690 buffers, ~125 s of audio) rendered at a realtime ratio of ~0.13–0.21, with
  allocated native memory oscillating between ~1.4 and ~2.4 GB, not climbing. The thread
  dump showed the render-ahead producer busy in note rendering, not stalled. At the
  **first loop boundary** the test failed with `IllegalStateException: Render-ahead stream
  is not running` from `PatternRenderStream.awaitSlot`.
- **Cause.** `RealtimeContinuousRenderer` reset the cells between passes (`cells.reset()`,
  or `disableGenome`/`enableGenome` on a switch, which also reset) and kept ticking without
  running `setup()` again. `AudioSceneRealtimeRunner`'s PDSL `reset()` stops the
  render-ahead producer, and only `setup()` restarts it. Until a stopped stream began
  rejecting `awaitSlot()`, that wait blocked forever, which is the historical 40-minute
  timeout. Every other `TemporalCellular` driver (`StableDurationHealthComputation`,
  `AudioSceneBenchmark`, `AudioScenePopulation`) already runs setup after reset.
- **Fix.** The renderer re-runs setup after every loop-boundary reset. With it, the test
  passes under the pipeline profile: 300 s of audio over 3 loops in ~371 s wall, ratio
  ~0.2, non-silent `rt-live.wav`, and memory flat after the first loop.
- **Cache persistence (open question 1).** It is now a per-manager setting instead of a
  class-load-only flag: `PatternLayerManager.setCachePersistent` and
  `PatternSystemManager.setNoteCachePersistent`, which also applies to patterns added
  later. The renderer enables it for its looped fixed arrangement (`AR_RT_CACHE_PERSIST=false`
  turns it off), so **no `pom.xml` or CI change is needed** and the owner dependency is gone.
  A comparison run with persistence off also passed at the same ratio with lower memory
  (~1.0–2.5 GB, no growth), so persistence is not what makes this five-minute test pass.
  It is kept because it saves re-evaluating note audio on every pass of a looped render.
- **Pacing (open question 2).** Left at realtime. The test costs ~8 minutes of runner
  time, well inside its 40-minute timeout, and realtime pacing is what the renderer runs
  in production.

## Estimated complexity

Medium. The code change for restoring the test is a few lines, but the work is
gated on hardware-specific reproduction, `ar-jmx` instrumentation, confirming the
cause before acting, and an owner-dependent `pom.xml` change.
