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
attributed. That delay is paid every time a scene's real-time runner is built —
on every scene, since (as shown below) the compiled kernels are not reused
across scenes. The task asks us to profile the build (compile vs optimisation vs
instruction-set reuse), identify the dominant cost with profile evidence, and
reduce `runnerBuildMs` — or record, with evidence, why it cannot be reduced.

## What `runnerBuildMs` actually measures

`runnerBuildMs` is logged by `PdslSetupBreakdownTest.setupBreakdown`
(`studio/compose`), wrapping the single call
`scene.runnerRealTime(output, null, bufferSize)`, which delegates to
`AudioSceneRealtimeRunner.create(...)` → `createPdsl(...)`. It is strictly the
*construction* cost: `setup()` and `tick()` are run (and timed separately) only
afterward.

The build-phase steps inside `createPdsl`, in order, are:

1. **`scene.prepareRenderBuffers(channels, bufferSize, frameSupplier)`**
   (`AudioScene`) — constructs the render-cell graph for both stereo sides and
   consolidates the render/filter buffers. It returns a *deferred* setup
   `Supplier<Runnable>` (run later in `setup()`), but the graph construction and
   buffer allocation happen synchronously here.
2. **`adapter.buildArgsMap()`** (`MixdownManagerPdslAdapter`) — allocates the
   argument `PackedCollection`s (FIR coefficient banks, transmission grid, delay
   buffers, automation slots, …).
3. **`loader.parseResource("/pdsl/audio/mixdown_manager.pdsl")`** — memoised in
   `PdslLoader.RESOURCE_CACHE`; cheap after the first build in a JVM.
4. **`compileMixdownModel(...)`** — `loader.buildLayer(...)` interprets the PDSL
   and builds the whole DSP `Block` graph (per-channel FIR filters of order
   `pdslFilterOrder = 40`, the delay network, reverb), then `model.compile(false)`
   → `CompiledModel.compile`, which runs `Process.optimized(setup).get()` and
   `forward.flatten().optimize()` followed by `p.get()` (scope generation and
   code generation for the setup and forward passes).
5. **`compiled.forward(pdslInput)`** — a throwaway pass used only to capture the
   stable output handle. Because instruction sets compile lazily on first
   `getOperator()` (see `INSTRUCTION_CACHING.md`), this first forward is where
   native kernel compilation (Metal shader compilation) actually fires for every
   distinct kernel in the forward pass.
6. **`new PatternRenderStream(...)`** — allocates the render-ahead ring only; the
   per-slot copy kernels (`slotCopies`) are built lazily on first use, so
   `renderAheadSlots = 24` does **not** add build-time compilation.

### Prior hypothesis, already grounded in code

The dominant cost is expected to be **native kernel compilation of the DSP
graph** — steps 4–5 — not optimisation-pass count or Java graph construction.
This is consistent with `INSTRUCTION_CACHING.md`: "Compiling a computation to
native code (JNI, OpenCL, Metal) is expensive: it involves scope generation,
simplification, code generation, and compilation." The profiling step must
confirm this with evidence rather than assume it.

### Why it is paid once *per scene*, not once *per JVM*

`KernelCacheReuseAcrossScenesTest` already asks whether a second `AudioScene`
reuses the first scene's compiled kernels. It does not: instruction-set reuse
keys on a signature that, at provider leaves, includes the referenced buffer's
`offset` and `memLength` (`CollectionProviderProducer.signature()`), documented
in `INSTRUCTION_CACHING.md` under "Buffer Offset Defeats Cross-Instance Reuse."
The mixdown graph reads its arguments through `cp(x)`/`p(x)` provider leaves over
`PackedCollection`s freshly allocated per scene, so two structurally identical
scenes produce different signatures and recompile every kernel. The doc names
the escape: computations built over `Input.value(shape, argIndex)` placeholders
(runtime arguments) instead of provider leaves avoid the buffer-layout
dependence entirely.

## Scope

In scope:

1. **Attribute `runnerBuildMs`** to its sub-phases (1–6 above) with profile
   evidence, separating: PDSL interpretation / graph construction (`buildLayer`),
   `Process.optimize` (optimisation passes), and native kernel compilation
   (`getOperator`/first-forward). Use `ar-profile-analyzer` on an
   `OperationProfile`/`OperationProfileNode` capture of the compile, plus a
   distinct-kernel compile count (`NativeCompiler.getTotalInstructionSets()` /
   the instruction-cache listeners referenced by `INSTRUCTION_CACHING.md`).
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
  largest potential win (it would make the ~16 s a once-per-JVM cost), but it is
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
`profile.save("results/...xml")` after — and to log the distinct-kernel compile
count delta across the build and across a second scene (reproducing
`KernelCacheReuseAcrossScenesTest`'s cross-scene question inside the attribution
run). `Model.compile(OperationProfile)` already exists if a tighter capture of
just the compile is wanted. The saved XML is then read with `ar-profile-analyzer`
(`load_profile`, `find_slowest`, `find_slowest_by_category`, `get_source`) to
name the dominant operations and confirm whether the cost is codegen,
optimisation, or native compilation.

**Where a reduction would land.** Depending on the evidence:
- intra-build reuse / signature fixes → the PDSL audio primitives or adapter in
  `studio/compose` (`org.almostrealism.studio.dsl.audio`,
  `MixdownManagerPdslAdapter`), or the signature logic in `base/hardware` if a
  general defect is found (to be raised separately, not widened into here);
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
   from `PdslSetupBreakdownTest.setupBreakdown` on the same scene and machine —
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
   These are `@TestDepth(1)`/`excludeProfiles = PIPELINE` curated-library
   harnesses; run them as a bounded selection, not a module suite.

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
