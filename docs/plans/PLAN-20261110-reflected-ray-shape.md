---
status: proposed
created: 2026-11-10
closed:
tracker: [ec549648-4d2f-4515-b65e-cbb875098d3f]
---

# Fix the reflected-ray shading path so a mirror surface renders the scene

## Category

Code Quality (correctness). The migrated ray tracer carries a reflection path that cannot
build its producer graph, pinned by an existing `knownIssue` test and sitting astride a
`FUNDAMENTAL RULE` violation. Fixing it restores a CI render assertion and removes host-Java
element math from a core geometry class.

## Motivation

The rings `raytracer` and `photonfield` libraries were migrated into `common` over the last
week (PRs #869, #875 and the surrounding commits), landing the ray-tracing and photon-field
engines beside the simulation substrate. Two migration reviews and the ferrofluid roadmap
([`PLAN-20261106-ferrofluid-simulation.md`](PLAN-20261106-ferrofluid-simulation.md), §5) all
converge on the same finding: the renderer's **reflection path is broken before a single pixel
is evaluated**, and the only two end-to-end render tests the migration added
(`ReflectionRefractionRenderTest`) are `@TestProperties(knownIssue = true)` — so the engine
ships with **no asserting render test in CI** at all.

A mirror-like, glossy surface is the defining look of a ferrofluid, so this is the first
rendering blocker on that proof-of-value track (its Phase 2). But the value is not confined to
ferrofluid: glossy reflection is a basic capability of any ray tracer, and the fix replaces a
host-Java `Evaluable` — per-element `Math` on the reflected ray, with a `getScope` that throws
`"Not implemented"` — with a compiled Producer graph, which is exactly the orchestration-not-
execution discipline the whole platform is built on. A renderer whose reflection path builds and
runs, guarded by a CI assertion, is a stronger foundation for everything downstream, human- or
model-driven.

## Relationship to existing work — this fix is already implemented on an open PR

**Before scheduling this plan, read it against PR #886 (branch `feature/glossy-reflection`,
"Make ray-traced reflection render and rewrite refraction as a graph"), which is open at the time
of writing and already implements everything this plan proposes — and more.** That branch:

- rebuilds `ReflectedRay` (`compute/geometry`) as a shape-`(6)` `TraversableExpressionComputation`
  (no host-Java `evaluate`, no throwing `getScope`), keeping a public four-argument constructor —
  this plan's **Scope item 1**;
- reworks `ReflectionShader.shade` so the reflected-ray aggregation builds and runs — this plan's
  **Scope item 3** (it reworks the aggregator rather than routing `shadeFront` through the
  `shadeBack` value path, so the *approach* to gap (b) differs from §Approach step 3 below, but it
  reaches the same end: a reflection that renders);
- clears the `knownIssue` flag on `reflectiveSphereShowsFloor` so the render assertion runs in CI —
  this plan's **Scope item 4** (verified: on that branch the method carries `@Test` only);
- goes **beyond this plan's scope** by also rewriting `RefractionShader` as a Producer graph and
  adding a new `docs/internals/ray-traced-reflection-and-refraction.md`, i.e. the refraction
  follow-up this plan explicitly scopes out. Note that `refractiveSphereShowsFloor` still carries
  `knownIssue` on that branch, so the refraction *render assertion* is not yet enabled there.

The defect analysis in the sections below is independently accurate against `origin/master` (every
claim was re-verified), so this plan remains a correct specification of the problem. What it is
**not** is un-started work. The decision for whoever approves this plan is therefore: **review and
merge PR #886 rather than carry this plan out from scratch**, and if #886 is merged, mark this plan
`completed` (or `abandoned` as superseded) rather than dispatching it. If #886 is instead rejected
or substantially reworked, this plan's narrower, two-edit approach remains available as the
fallback. Either way, do not run both in parallel: a second implementation of the same reflection
fix would collide with #886 on `ReflectedRay` and `ReflectionShader`. See also the **Open
questions** section.

## The defect (evidence)

`ReflectionRefractionRenderTest.reflectiveSphereShowsFloor` renders a single sphere carrying only
a `ReflectionShader(0.8, rgb(0.8))` (so `blur == 0`, a perfect mirror) above a lit diffuse floor,
and asserts the sphere's disc is not entirely black. It fails in `RayTracedScene.realize` — while
*building* the producer, before any pixel — for two distinct reasons.

### Gap (a) — the reflected ray has no shape

`ReflectedRay` (`compute/geometry`, `org.almostrealism.geometry.ReflectedRay`) is an old-style
`ProducerComputation<Ray>` whose `get()` returns an anonymous host-Java `Evaluable`: it calls
`.evaluate()` on the `point`, `normal`, and `reflected` producers and does the blur/vector math in
Java, and its `getScope(...)` throws `RuntimeException("Not implemented")`. The value it produces
carries **no `TraversalPolicy`**.

`ReflectionShader.shade` builds `reflectedRay = new ReflectedRay(loc, nor, n, blur)` and hands it
to a `LightingEngineAggregator`, which constructs an `IntersectionalLightingEngine` per surface.
Intersecting the reflected ray needs its origin and direction, and
`RayFeatures.origin(r) == subset(shape(3), r, 0)` requires the input to carry a shape. Because
`ReflectedRay` does not, `subset` throws **"Subset cannot be performed without a
TraversalPolicy"**. This is the first failure and must be fixed before gap (b) is even reached.

### Gap (b) — the accelerated selection path is unimplemented

Once a shaped reflected ray reaches the aggregator, the `shadeFront` branch of
`ReflectionShader.shade` calls `new LightingEngineAggregator(reflectedRay, ...).getAccelerated()`.
`LightingEngineAggregator extends RankedChoiceEvaluableForRGB`, whose `getAccelerated()` delegates
to `RankedChoiceEvaluableForMemoryData.getAccelerated(int, Supplier, IntFunction)`, which throws
`UnsupportedOperationException`.

The relevant observation: the **`shadeBack` branch of the same method** does *not* call
`getAccelerated()` — it uses the aggregator directly as a value supplier (`() -> aggregator`),
driving the aggregator's own overridden `evaluate(...)` (host ranked-choice selection, the path
`RankedChoiceEvaluableForRGB` already implements). The primary camera ray renders correctly today
through this selection machinery. So the front branch's `getAccelerated()` call is an unimplemented
detour around a path that already works.

## Scope

In scope:

1. **Rebuild `ReflectedRay` as a shape-carrying Producer graph.** For the mirror case, the ray is
   `ray(point, reflect(incident, normal))`, where `RayFeatures.ray(origin, direction)` is
   `concat(shape(6), origin, direction)` and `GeometryFeatures.reflect(vector, normal)` already
   returns the reflected-direction Producer (shape 3). The result carries `shape(6)`, so
   `origin()`/`direction()` subsets succeed, and the host-Java `evaluate()` loop and the throwing
   `getScope` are gone. Keep the constructor signature
   `ReflectedRay(point, incident, normal, blur)` so both call sites in `ReflectionShader` are
   unaffected.
2. **Handle the glossy blur path.** The blur perturbation (random tangent-plane offset of the
   reflected direction) is currently host-Java `Math.random()`. For `blur == 0` (the mirror case
   the test exercises and the near-opaque ferrofluid look wants) the graph is exact and needs no
   randomness. For `blur != 0`, express the perturbation with the framework's random features
   (`AR_RANDOM`/`CounterRandom`) if it falls out cleanly; otherwise keep a correct `blur == 0`
   graph and record the glossy-blur graph form as a documented follow-up on the tracker task. The
   blur path must not reintroduce a host-Java per-element evaluate.
3. **Route the `shadeFront` reflected-ray aggregation through the working selection path** the
   `shadeBack` branch and the primary ray already use, instead of the unimplemented
   `getAccelerated()`. Do not weaken or special-case the aggregator; use its existing
   host ranked-choice `evaluate(...)`.
4. **Clear the `knownIssue` flag on `reflectiveSphereShowsFloor`** so the render assertion runs in
   CI. This is additive — the assertion already exists ("the sphere's disc is entirely black"
   fails if nothing renders); no existing test is weakened.

Out of scope (named so the boundary is explicit):

- **Refraction** (`refractiveSphereShowsFloor`, `RefractionShader`): a separate defect
  (`GeneratedColorProducer.getShape` `ClassCastException`); its `knownIssue` flag stays set. §5
  item 3 of the ferrofluid plan; a distinct follow-up.
- **Kernelizing the per-pixel selection** (implementing a real accelerated `getAccelerated()`, or
  moving `RankedChoiceEvaluable`/`SuperSampler` selection into a compiled kernel): a *performance*
  item (ferrofluid plan §5 item 2 / Phase 4), not a correctness blocker. The host selection path
  renders correct pixels; speed is a later cycle.
- The dead/stubbed `RayMarchingEngine` / `DistanceEstimationLightingEngine` and the unwired
  photon renderer (§5 item 4).

## Approach

1. Reproduce: run `ReflectionRefractionRenderTest.reflectiveSphereShowsFloor` with
   `AR_LONG_TESTS=enabled AR_KNOWN_ISSUES=enabled` and confirm the "Subset cannot be performed
   without a TraversalPolicy" failure at the realize stage.
2. Rewrite `ReflectedRay.get()` (and drop the throwing `getScope`) to produce
   `ray(point, reflect(incident, normal))`. Verify the produced ray reports `shape(6)` and that
   `origin()`/`direction()` subsets build. Confirm `ReflectedRay` remains on the
   `DuplicateCodeDetector` whitelist pair only if still warranted; otherwise it may no longer need
   the suppression.
3. Switch the `shadeFront` branch of `ReflectionShader.shade` off `getAccelerated()` and onto the
   same aggregator-as-value path the `shadeBack` branch uses. Keep both branches consistent.
4. Run the test with the `knownIssue` flag removed; confirm a non-trivial lit disc and no `NaN`.
5. Validate: `mvn clean install -DskipTests`, then the build validator (`checkstyle`,
   `code_policy` — especially the Producer-pattern / no-host-math checks on the rewritten
   `ReflectedRay`, `test_timeouts`, `duplicate_code`), then the targeted tests below.

## Success Criteria

- `ReflectionRefractionRenderTest.reflectiveSphereShowsFloor` passes **without** the `knownIssue`
  flag (it runs in the normal CI lane and asserts a non-black disc).
- `ReflectedRay` produces a value carrying `shape(6)`, contains no host-Java per-element math and
  no throwing `getScope`; `code_policy` is clean on it.
- `refractiveSphereShowsFloor` remains `knownIssue` (unchanged) and no other test is weakened,
  disabled, skip-gated, or has its tolerance/dimensions altered.
- `mvn clean install -DskipTests` succeeds; the build validator reports no new violations.
- The glossy `blur != 0` path is either a clean Producer graph or a recorded follow-up; it is not
  a host-Java evaluate loop.

## Targeted tests

- `ReflectionRefractionRenderTest` (engine/render) — the pinned render test (both methods; run
  with known-issue and long-test env toggles while verifying, then confirm `reflectiveSphereShowsFloor`
  runs green in the default lane).
- Any `compute/geometry` ray/reflection tests that exercise `ReflectedRay`, `reflect`, or
  `RayFeatures.ray/origin/direction` (e.g. ray-shape/geometry feature tests in that module).

Bounded selection only (≤5 classes); broad verification is CI's job.

## Dependencies

None. The raytracer/photonfield migration has landed on `master`. The work is
backend-independent: the failure is a graph-construction error and the render is driven on the
host, so it reproduces and is verifiable on any node — **no Metal node and no workstream settings
file are required.**

## Estimated Complexity

Medium. Two localized edits (`ReflectedRay` in `compute/geometry`; the `shadeFront` branch of
`ReflectionShader` in `engine/render`) plus clearing one `knownIssue` flag. The risk is in making
the rewritten `ReflectedRay` graph exactly reproduce the old mirror reflection and in the
glossy-blur decision; both are bounded and testable.

## Open questions

- **This is already implemented — approve the existing PR, do not re-run this plan.** PR #886
  (`feature/glossy-reflection`) is open and implements all of Scope items 1, 3 and 4 (and the
  out-of-scope refraction graph rewrite). The primary decision is whether to merge that PR and close
  this plan as superseded, versus dispatching this plan as fresh work. Running both produces two
  conflicting rewrites of `ReflectedRay`/`ReflectionShader`. See **Relationship to existing work**.
- **Gap (b) approach is not settled.** This plan proposes routing `shadeFront` through the
  `shadeBack` aggregator-as-value path (no change to the aggregator). PR #886 instead reworks the
  aggregator itself. If this plan were carried out independently, the reviewer should decide which
  route is preferred; the two should not both land.
- **Refraction coupling.** This plan scopes refraction out, but PR #886 fixes reflection and
  refraction together in one changeset touching shared types (`ShaderContext`, `LightingEngine`,
  `LightingEngineAggregator`). If the owner prefers to keep the reflection fix small and separate,
  that is an argument *for* this plan's narrower scope and *against* #886 as-is — a trade-off for
  the approver, not a settled matter.
- **Glossy `blur != 0` graph form** remains genuinely open (the mirror case `blur == 0` is exact and
  unambiguous); whichever implementation lands must decide whether to express the tangent-plane
  perturbation with the framework's random features or record it as a documented follow-up. It must
  not reintroduce a host-Java per-element `evaluate`.
