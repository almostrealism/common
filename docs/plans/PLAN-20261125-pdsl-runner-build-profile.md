---
status: proposed
created: 2026-11-25
closed:
tracker: [60b1a161-80d3-4450-b7fd-492f547de3c2]
---

# Profile and reduce the one-time PDSL mixdown model build (`runnerBuildMs`)

**Tracker task:** `60b1a161-80d3-4450-b7fd-492f547de3c2` — *Profile the one-time
PDSL mixdown model build (runnerBuildMs)*
**Release:** Common 0.78

## Motivation

Building the PDSL mixdown model for the real-time runner takes about 16 s
(`runnerBuildMs`) before the first buffer renders, and the cost has never been
attributed. That delay is paid every time a scene's real-time runner is built,
and it is expected to be paid on every scene, because the compiled kernels are
not expected to be reused across scenes. That expectation is an unmeasured
inference from the code, explained below. The task asks us to profile the
build (compile vs optimisation vs instruction-set reuse), identify the dominant cost with profile evidence, and
reduce `runnerBuildMs` — or record, with evidence, why it cannot be reduced.

## What `runnerBuildMs` actually measures

`runnerBuildMs` is logged by `PdslSetupBreakdownTest.setupBreakdown`
(`studio/compose`), wrapping the single call
`scene.runnerRealTime(output, null, bufferSize)`, which delegates to
`AudioSceneRealtimeRunner.create(...)` → `createPdsl(...)`. It is strictly the
*construction* cost: `setup()` and `tick()` are run (and timed separately) only
afterward.

The harness sets `MixdownManager.enableEfx = true` (and
`enableEfxFilters = true`), so the layer `createPdsl`
builds is **`mixdown_master_wet`** (input shape `[2 * channels, bufferSize]`,
adapter constructed with the scene's `EfxManager`), not the single-input
`mixdown_master`. The build-phase steps inside `createPdsl`, in the order the
code runs them, are:

1. **`scene.prepareRenderBuffers(channels, bufferSize, frameSupplier)`**
   (`AudioScene`) — consolidates the render and efx-filter buffers and creates
   the render cells for both stereo sides (MAIN and, with efx on, WET voicings).
   It returns a *deferred* setup `OperationList` (run later in `setup()`), but
   the cell construction and buffer allocation happen synchronously here. This
   call is already timed by `AudioScene`'s `getCells` `TimingMetric`.
2. **`new MixdownManagerPdslAdapter(...)`** and the zero-copy input view
   `consolidated.range(inputShape, 0)` — construction only.
3. **`renderOps.get()`** — builds the pattern render-ahead `Runnable` (a
   `consolidated.clear()` plus one `prepareBatch(false)` per render cell). This
   is a `get()` on an `OperationList` executed at build time, so any operation
   compilation it performs is part of `runnerBuildMs`; it must be measured, not
   assumed negligible.
4. **`loader.parseResource("/pdsl/audio/mixdown_manager.pdsl")`** — memoised in
   `PdslLoader.RESOURCE_CACHE`; cheap after the first build in a JVM.
5. **`adapter.buildArgsMap()`** — allocates the argument `PackedCollection`s
   (FIR coefficient banks, transmission grid, delay buffers, automation slots,
   stem buffers, …).
6. **`compileMixdownModel(...)`** — `loader.buildLayer(...)` interprets the PDSL
   and builds the whole DSP `Block` graph (per-channel FIR filters of order
   `pdslFilterOrder = 40`, the efx chain, the delay network/reverb), then
   `model.compile(false)` → `CompiledModel`, which runs
   `Process.optimized(setupPass).get()` and `forward.flatten().optimize()`
   followed by `get()` on the result (scope generation and code generation for
   the setup and forward passes).
7. **`adapter.automationRefresh(args)`** — returns a `Supplier` only; it is not
   built (`get()`) until `tick().get()`, so it is outside `runnerBuildMs`.
8. **`compiled.forward(pdslInput)`** — a throwaway pass used only to capture the
   stable output handle. Instruction sets compile lazily on first
   `getOperator()` (see `base/hardware/docs/INSTRUCTION_CACHING.md`), so this
   first forward is *expected* to be where Metal shader compilation fires for
   kernels not already compiled in step 6. Whether native compilation lands in
   step 6 or step 8 is one of the things the profile must establish.
9. **Output-loop and stem-append construction** — builds `OperationList`s only;
   they are compiled by `tick().get()`, outside `runnerBuildMs`.
10. **`new PatternRenderStream(...)`** — allocates the render-ahead ring only;
    the per-slot copy kernels (`slotCopies`) are built lazily on first use, so
    `renderAheadSlots = 24` does **not** add build-time compilation.

### Prior hypothesis, already grounded in code

The dominant cost is expected to be **native kernel compilation of the DSP
graph** — steps 6 and 8 — not optimisation-pass count or Java graph construction.
This is consistent with `INSTRUCTION_CACHING.md`: "Compiling a computation to
native code (JNI, OpenCL, Metal) is expensive: it involves scope generation,
simplification, code generation, and compilation." The profiling step must
confirm this with evidence rather than assume it.

### Why it is expected to be paid once *per scene*, not once *per JVM*

This section is an inference from the code and documentation. No measurement
has confirmed it yet.

Instruction-set reuse keys on a signature that, at provider leaves, includes the
referenced buffer's `offset` and `memLength` (`CollectionProviderProducer.signature()`
in `compute/algebra`, plus `&aggRoot=<length>` for aggregated arguments). `INSTRUCTION_CACHING.md`
documents this under "Buffer Offset Defeats Cross-Instance Reuse". The mixdown
graph reads its arguments through `cp(x)`/`p(x)` provider leaves over
`PackedCollection`s freshly allocated per scene, so two structurally identical
scenes are expected to produce different signatures and recompile every kernel.
The doc names the escape: computations built over `Input.value(shape, argIndex)`
placeholders (runtime arguments) instead of provider leaves avoid the
buffer-layout dependence entirely.

`KernelCacheReuseAcrossScenesTest` is the existing cross-scene probe, but it
does not answer the `runnerBuildMs` question as written:

- it calls `scene.runnerRealTime(...)`, `setup().get()` and `tick().get()`
  *outside* its timed region, and times only `setup.run()`, the first tick and
  the render loop;
- despite its javadoc, it logs no distinct-kernel compile count;
- no recorded result of the probe exists in the repository.

The `PdslSetupBreakdownTest` javadoc also records that this probe showed
`setupSeconds` is per-scene *rendering* work, not kernel compilation. That is a
different phase from `runnerBuildMs`, and it does not contradict the hypothesis
above, but it is the only cross-scene evidence on record. The attribution run
(Approach) must therefore measure build-time recompilation for a second scene
directly, not cite this test as having shown it.

## Scope

In scope:

1. **Attribute `runnerBuildMs`** to its sub-phases (steps 1–10 above) with
   profile evidence, separating: render-buffer and cell construction (step 1),
   the build-time render op `get()` (step 3), PDSL interpretation / `Block`
   construction (`buildLayer`), `Process.optimize` (optimisation passes), and
   native kernel compilation (`getOperator`, in compile or first forward). Use
   `ar-profile-analyzer` on an `OperationProfileNode` capture of the build, plus
   the Metal compile count and total time. On the Metal backend those come from
   `MetalProgram.compileTime`, the `mtlCompile` `TimingMetric`, via
   `getCount()`/`getTotal()`. Entries are recorded as `System.nanoTime()`
   deltas, so convert them before comparing with `runnerBuildMs`. `NativeCompiler.getTotalInstructionSets()` counts
   only JNI native libraries, so it will not reflect Metal shader compiles.
2. **Reduce the dominant cost** with the lowest-risk change the evidence
   supports, and record before/after `runnerBuildMs` from the same harness. The
   candidate levers, in increasing scope:
   - confirm single-scene instruction reuse is already maximal (structurally
     identical per-channel chains should share one compiled kernel) and fix it
     if a spurious signature difference is defeating intra-build dedup;
   - remove any redundant optimisation/compilation work in the build path (e.g.
     an avoidable recompile, or work that the throwaway forward duplicates);
   - if neither materially moves `runnerBuildMs`, record the evidence that the
     cost is irreducible native compilation and escalate cross-scene reuse as a
     dependency (see below).

Explicitly out of scope (candidate follow-ups, not this task):

- **Cross-scene instruction reuse** via rebuilding the mixdown over
  `Input.value(...)` placeholders instead of provider leaves. This is the single
  largest potential win (if the per-scene inference holds, it would make the
  ~16 s a once-per-JVM cost), but it is
  an architectural change to how `MixdownManagerPdslAdapter`/the PDSL audio
  primitives bind buffers, with its own correctness/parity surface. It is named
  here as a dependency/open question, not folded into this task.
- Reducing per-tick (steady-state) cost, pattern-render cost, or `setupSeconds`
  — those are separate and already have their own harnesses.

## Approach

Grounded in the classes above; no new Maven module, and the reduction edit (if
any) lands in existing files.

**Profiling harness.** Extend the existing, purpose-built
`PdslSetupBreakdownTest` (which already walks the setup `OperationList`
stage-by-stage) rather than adding a new test class. The build itself is a
single opaque call today; the plan is to wrap the `createPdsl` build in an
`OperationProfileNode` the way `MixdownLayerPerformanceTest.reverbBusForwardProfile`
does — `Hardware.getLocalHardware().assignProfile(profile)` before the build,
`profile.save("results/...xml")` after — and to log the `mtlCompile` count and
total-time deltas across the build, for this scene and for a second
identically configured scene built in the same JVM. That second build answers,
for the build phase, the cross-scene question `KernelCacheReuseAcrossScenesTest`
leaves unmeasured. `Model.compile(boolean backprop, OperationProfile)` already
exists if a tighter capture of just the compile is wanted. The runner compiles
with `backprop = false`, so use `compile(false, profile)`, not the
single-argument `compile(OperationProfile)` overload, which builds the backward
graph too. Calling it from the test needs the `compileMixdownModel` path to
accept a profile, which is a small change to `AudioSceneRealtimeRunner`. The
simplest route is the hardware-assigned profile, which needs no production
change. `Hardware.assignProfile` installs the profile as the runtime listener
(`HardwareOperator.timingListener`), the compilation listener
(`AbstractComputeContext.compilationTimingListener`) and the scope/expression
timing listeners (`ComputationScopeCompiler`, `Scope`, `ScopeSettings`,
`Expression`). `MetalComputeContext` reports every kernel it compiles, with its
source and elapsed nanoseconds, to the compilation listener. The capture
therefore covers native compilation and code generation across every build step,
including steps 1 and 3, and not only kernel execution. These listeners are
static fields, so the capture is process-wide for as long as the profile is
assigned. Keep the assignment window to the build, and clear it with
`assignProfile(null)` before `setup()` runs. The saved XML is then read with `ar-profile-analyzer`
(`load_profile`, `find_slowest`, `find_slowest_by_category`, `get_source`) to
name the dominant operations and confirm whether the cost is codegen,
optimisation, or native compilation.

**Where a reduction would land.** Depending on the evidence:
- intra-build reuse / signature fixes → the PDSL audio primitives or adapter in
  `studio/compose` (`org.almostrealism.studio.dsl.audio`,
  `MixdownManagerPdslAdapter`), or the provider-leaf signature logic
  (`CollectionProviderProducer` in `compute/algebra`) or the instruction cache
  in `base/hardware` if a general defect is found (to be raised separately, not
  widened into here);
- redundant-work removal in the build path →
  `AudioSceneRealtimeRunner.createPdsl` / `compileMixdownModel`.

**Record before/after.** The task's "Done when" requires it: capture
`runnerBuildMs` from `PdslSetupBreakdownTest` before the change and after, on the
same machine and scene (dense curated genome seed 58, 120 BPM, 64 measures,
8192-frame buffer — the values already pinned in the harness), and attach the
`ar-profile-analyzer` attribution.

## Success criteria

A reviewer can confirm the task is done when:

1. The dominant contributor to `runnerBuildMs` is identified with
   `ar-profile-analyzer` evidence (a saved profile and the analyzer's
   attribution), distinguishing graph construction vs optimisation vs native
   compilation.
2. Either `runnerBuildMs` is measurably reduced — shown by before/after numbers
   from `PdslSetupBreakdownTest.setupBreakdown` on the same scene and machine,
   each taken as the median of at least three fresh-JVM runs (the first build in
   a JVM is the one measured, so in-JVM repetition would hit warm caches), with
   the run-to-run spread reported so a reduction can be told apart from noise —
   **or** the plan/PR records, with that same evidence, why it cannot be reduced
   within scope (e.g. the cost is irreducible first-encounter native compilation
   and the only material lever is the out-of-scope cross-scene-reuse change).
3. Tests proving it:
   - `PdslSetupBreakdownTest` (the `runnerBuildMs` attribution harness) runs and
     its streamed output remains finite and non-silent (the harness already
     asserts this, so the measured build is the one that produces audio);
   - `KernelCacheReuseAcrossScenesTest` still characterises cross-scene reuse
     correctly after any change;
   - `MixdownManagerPdslVerificationTest` / `AudioScenePdslCutoverTest` confirm
     the mixdown output is unchanged (parity preserved) if any build-path code
     is touched.
   `PdslSetupBreakdownTest` and `KernelCacheReuseAcrossScenesTest` are
   `@TestDepth(1)`, `excludeProfiles = PIPELINE` curated-library harnesses.
   `AudioScenePdslCutoverTest`'s real-scene methods are `@TestDepth(2)`,
   `excludeProfiles = PIPELINE` curated-library harnesses too. In
   `MixdownManagerPdslVerificationTest` the methods are `@TestDepth(2)` with no
   curated-library or `PIPELINE` exclusion, so CI runs them as well. Run all of
   them as a bounded selection (named classes or methods), not a module suite.

## Dependencies and open questions

- **Machine requirement.** Reproducing the ~16 s build and measuring before/after
  requires the real runner path: the Metal backend and the curated audio library
  (`requireCuratedLibrary()`), which only macOS FlowTree nodes provide. The
  existing harnesses are `excludeProfiles = PIPELINE` for exactly this reason.
  Implementation of this task must therefore run on a macOS node — recorded in
  `docs/plans/task-20261125-182605-workstream.yaml` (`requiredLabels:
  platform: macos`). On a CPU/JNI backend the compile profile and timings would
  not represent the deployed cost.
- **Cross-scene reuse is the largest lever and is out of scope.** If profiling
  confirms native compilation dominates and no low-risk intra-build reduction
  exists, the material win is making compiled kernels reusable across scenes by
  building the mixdown over `Input.value(...)` placeholders rather than provider
  leaves. That is a separate architectural task (binding contract + parity) and
  should be filed as a follow-up rather than attempted here. **Open question for
  the approver:** should this task deliver only the profile + any low-risk
  reduction, with cross-scene reuse filed as a dependency, or should the
  cross-scene change be pulled in and the task re-scoped as large?
- **The ~16 s baseline comes from the task description.** It has not been
  reproduced on this branch, and nothing in the repository records the machine
  or configuration it was taken on. The first implementation step is to capture
  the "before" number with the protocol in Success criteria 2. If that number
  differs materially from 16 s, record it here before choosing a lever.
- **Step 3 (`renderOps.get()`) is a build-time compile the original draft of
  this plan omitted.** If it turns out to be material, moving it is a design
  question: it could be deferred to `setup()` or built lazily by
  `PatternRenderStream`. Either option moves cost from `runnerBuildMs` into
  `setupSeconds` without removing it. A reduction that only relocates work
  between phases should be reported as such, not as a reduction. Related prior
  work: `AudioSceneRealtimeRunner.preWarmMaxSeconds` (default `0`, so off)
  is an existing setup-time render sweep. It was disabled because it
  front-loaded real-time rendering into setup. Its javadoc names a bounded,
  compile-only warm as the right replacement if compile stalls return. It runs
  in `setup()`, so it does not affect `runnerBuildMs`. `KernelCacheReuseAcrossScenesTest`
  pins it to `0` explicitly; `PdslSetupBreakdownTest` relies on the default.
  The attribution runs must keep it at `0` so that `setupSeconds` stays comparable.
- **If no reduction is achievable in scope,** the task's own "Done when" permits
  closing it on the profile evidence alone. The approver should confirm that
  outcome is acceptable before implementation, so the session does not stretch
  into the out-of-scope architectural change to force a number down.

## Estimated complexity

**Medium.** The profiling and attribution are well-supported by existing harness
and tooling (`PdslSetupBreakdownTest`, `OperationProfileNode`,
`ar-profile-analyzer`) and are mostly measurement. A low-risk intra-build
reduction, if the evidence supports one, is a small edit in `studio/compose`. The
large lever (cross-scene reuse) is deliberately excluded; pulling it in would
make the task large.
