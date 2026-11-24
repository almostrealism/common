---
status: in-progress
created: 2026-11-23
closed:
tracker: [80b892da-f079-456c-b7e3-059bbed7db65]
---

# Render the floor through a refractive sphere by fixing the exit-ray selection's expression-size blow-up

## Category

Code Quality (correctness), with a general compilation-scaling payoff.

## Motivation

The engine still ships **no asserting refraction render test** in CI.
`ReflectionRefractionRenderTest#refractiveSphereShowsFloor` (engine/render) is pinned
`@TestProperties(knownIssue = true)`, so the transparent-material path — which the
ferrofluid glossy proof-of-value track (`PLAN-20261106-ferrofluid-simulation.md`, Phase 2)
depends on — has no guard at all. Its sibling `reflectiveSphereShowsFloor` already asserts in
CI; this plan closes the matching gap for refraction.

The previous cycle (`PLAN-20261119-refraction-compile-dag-memoization.md`, PR #1001) removed
the *first* wall: the refraction graph used to exhaust its 120s compile budget inside
`ScopeLifecycle.resetArguments`, which revisited every shared node of a diamond-shaped graph
once per path. With the visited-set memoization in place the reset traversal is once-per-node,
and the refraction compile no longer times out there. Its implementation session measured the
new behaviour and recorded it precisely in that plan's closing notes: with the flag temporarily
removed, `refractiveSphereShowsFloor` now **fails fast (~9s)**, deep in expression
simplification, with

```
io.almostrealism.expression.ExpressionException: Large expression not improved by simplification
```

while `ComputationScopeCompiler.compile` builds the `LightingEngineAggregator` exit-ray
selection. That is the *second*, independent wall the prior plan named as out of scope — an
expression-**size** problem in simplification, a different mechanism from the reset traversal.
This plan addresses exactly that wall. It is tracked by the follow-up task this plan carries
(`80b892da-f079-456c-b7e3-059bbed7db65`).

This connects to the larger vision in the same way the prior cycle did: a compiler that folds
large, heavily-shared computation graphs is the precondition for both the generative-art render
workloads and the self-hosted language-model work, where graph sharing is pervasive. A compile
step that produces an irreducible, over-budget expression for a modestly-sized scene is a latent
scaling wall; fixing it at its source is foundation work. The fix may also help the sibling
non-identity-transform reflection case and `SimpleRenderTest#renderTwoSpheres`, which the prior
plan reported hitting the same kind of wall. That is not established; Scope item 5 asks for it to
be measured.

## Root cause (symptom verified; construction source to be pinned by the implementation session)

**The throw site is verified by code inspection.** `Expression.process()` is called when an
expression is about to be used in code generation. It short-circuits for small expressions:

```java
// Expression.process()
public Expression<T> process() {
    if (countNodes() <= ScopeSettings.maxNodeCount) {
        return ExpressionCache.match(this);
    }
    Expression<T> simplified = (Expression<T>) simplify();
    if (simplified.countNodes() == countNodes()) {
        throw new ExpressionException(
                "Large expression not improved by simplification",
                simplified.treeDepth(), simplified.countNodes());
    }
    return ExpressionCache.match(simplified);
}
```

So the refraction compile reaches `process()` with an expression whose node count exceeds
`ScopeSettings.maxNodeCount` **and** which `simplify()` cannot shrink by even one node. Two
facts follow, and together they point at the fix:

1. The expression is genuinely large — larger than the size `process()` is willing to render.
2. It is *irreducible* by the existing simplification (n-ary merging, constant folding,
   dedup). An expression that simplification cannot improve is not redundant arithmetic that
   `NAryExpression.mergeOperands` would collapse. It is a large tree being **inlined into a
   single expression** instead of being shared or placed behind a kernel boundary. Note that
   repeated copies of the *same* sub-tree also count as "irreducible" here: `simplify()` does
   not make a repeated sub-tree count once. `countNodes()` sums each child's count, so a
   sub-tree referenced from *k* places counts *k* times. Irreducibility therefore does not
   distinguish re-expansion (family a or c below) from genuine size (family b).

**The construction site is identified; how it grows is not.** The prior session recorded the
throw as `Product.of` → `UniformCollectionExpression.getValueAt` → deep recursion through
`ReshapeProducer` / `TraversableExpressionComputation` / `AggregatedProducerComputation` /
`CollectionConcatenateComputation` / `InequalityComparisonComputation`, while compiling the
`LightingEngineAggregator` exit-ray selection. (Only that summary survives, in the closing
notes of `PLAN-20261119-refraction-compile-dag-memoization.md`. The full stack was not kept, so
step 1 below has to capture it again.) Reading the code gives the shape of the graph. It does
**not** support the explanation that "many candidates are inlined into one expression":

- **The exit-ray selection has one candidate in this scene.** `RefractionShader.shade` builds
  `new LightingEngineAggregator(refractedRay, getOtherSurfaces(), List.of(p.getLight()), p)`.
  `LightingEngineAggregator.init(...)` adds one `IntersectionalLightingEngine` per
  (surface, light) pair, but here the lights are only the current light and the surfaces are
  only the *other* surfaces. In `ReflectionRefractionRenderTest` that is the floor and one
  `PointLight`, so the selection chooses among exactly **one** engine.
- **The selection is a device graph, built by
  `RankedChoiceEvaluableForMemoryData.getClosestChoice(int)`** (reached through
  `RankedChoiceEvaluableForRGB.getClosestColor()`). Each candidate adds a `greaterThan` that
  reads its rank twice (once in the comparison and once as `rank.minus()`), a second
  `greaterThan` that reads the key and the running best key, and a `max` that reads both keys
  again. Every operand is therefore read more than once.
- **The operands are themselves chains of multiply-read stages.** The floor engine's rank and
  colour take the exit ray as input. The exit ray is `ray(origin(exit.get(0)), outside, ...)`,
  where `exit` is the sphere's `intersectAt` of the inner ray, and `outside` is a second
  `refract` that reads `inside` and the exit normal. `inside` is a `refract` of `viewer` and
  `normal`. Each stage reads its inputs several times.
- **The primary-ray aggregator is a different object.** `RayIntersectionEngine.trace` builds a
  `LightingEngineAggregator` over *every* surface-light pair, with `kernel = true`.
  `enableAcceleratedAggregator` defaults to `false`, so it is wrapped in a `DimensionAwareKernel`
  and selected **on the host** in `LightingEngineAggregator.evaluate(Object[])`. That host path
  compiles each engine's rank with `rankProducer.get()` in `initRankCache()`, and the chosen
  engine's colour (which contains the whole refraction graph above) with
  `best.get().evaluate(args)`.

*Inference to be confirmed, not a verified fact:* `ScopeSettings.maxNodeCount` is `1 << 23`
(about 8.4 million nodes). With a single candidate, an expression that size is far bigger than
the computation DAG, so the tree is almost certainly re-expanding shared stages per reader,
multiplicatively along the refract → intersect → refract → intersect → select chain. That is
evidence for family (a) or (c) below and against (b).

What the implementation session must still establish with evidence, not assume, is **which of
three root-cause families** applies, because the correct fix differs. Family (c) is listed first
because it is the first question the project's compile discipline asks (CLAUDE.md Rule 3b, *"is
this graph being optimized?"*). It is also the cheapest to rule out.

- **(c) The failing graph never goes through `Process.optimize`.** `Producer.get()` does not
  optimize. The two compile entry points on the primary host path named above,
  `rankProducer.get()` and `best.get()`, are both plain `get()` calls. The outer
  `RealizableImage.get()` does call `Process.optimized(source)`, but its source reaches the
  per-engine producers only through the `DimensionAwareKernel`/`LightingEngineAggregator`
  `Evaluable`, which the optimizer cannot see into. If the throw happens under one of these
  `get()` calls, no isolation is ever decided for the refraction graph, and a marked reduction
  is embedded into every reader. The test is to evaluate the same engine producer once through
  `evaluate()` / `Process.optimized(producer).get()` and see whether it compiles. If it does,
  the fix is to compile through the optimizing entry point at these call sites in
  `LightingEngineAggregator` (and, where needed, to make the relevant computations declare
  `isIsolationTarget`). It is not a change to the expression layer.
- **(a) A tree-construction defect.** Some structurally-shared sub-expression (the exit-ray
  origin/direction, a per-engine intersection, a repeated `InequalityComparison`) is being
  re-expanded per consumer instead of referenced once, so the tree is larger than the DAG. The
  fix is at the construction/computation site that over-expands it (for example an n-ary merge
  or shared-reference opportunity the builder misses), per the project rule *"Generated code is
  a symptom; fix the expression that produced it."* This is the preferred outcome: it shrinks
  the expression so `process()` succeeds, and it helps every graph with the same shape.
- **(b) The selection is genuinely a kernel boundary.** If the exit-ray selection is simply a
  large computation that *should* compile as its own kernel rather than be inlined into the
  caller, the correct expression of that is a computation that declares `isIsolationTarget` so
  the optimizer isolates it — **never** a graph-building `isolate()` / `Process.isolated(...)`
  call, and **never** a manual hand-split. Kernelizing the *per-pixel* surface selection as a
  performance rewrite is a separate, larger item (ferrofluid Phase 4) and stays out of scope;
  what is in scope here is only whatever boundary declaration is needed to let this render
  compile.

The forbidden non-fixes are explicit (CLAUDE.md, *CODE QUALITY*): do not raise
`ScopeSettings.maxNodeCount`, do not change how the expression is rendered to a string or
post-process the generated source, and do not split the graph by hand with a manual
`isolate()`. Each of those hides the defect instead of fixing it.

## Scope

Concrete, actionable items:

1. **Reproduce, capture the full stack, and profile first (Rule 3b).** `TestDepthRule` skips a
   `knownIssue` test whenever `TestUtils.getSkipKnownIssues()` is true, and that is true not only
   under the `pipeline` test profile or when `AR_KNOWN_ISSUES` is disabled, but also whenever long
   tests are skipped (`getSkipKnownIssues()` returns true when `getSkipLongTests()` does). The
   default depth leaves long tests skipped, so a targeted local run — including the standard MCP
   test runner — **skips** this test rather than executing it (verified: a default run reports it
   skipped). To reproduce, explicitly enable known-issue/long-test execution and avoid the
   `pipeline` profile, or temporarily remove the flag for the reproduction run only (restoring it
   if the fix is not achieved). Capture the **complete** stack
   of the `ExpressionException` and record which entry point is compiling when it is thrown:
   `LightingEngineAggregator.initRankCache()` (`rankProducer.get()`), `evaluate(Object[])`
   (`best.get()`), or a path through `RealizableImage.get()`'s `Process.optimized(source)`.
   That alone settles whether family (c) applies. Then run the failing graph under an
   `OperationProfile` and read its operation tree and generated source with
   `ar-profile-analyzer`. Because the compile throws before a kernel is produced, the failing
   operation may have no generated source. In that case, profile a small reproduction of the
   same producer (the floor engine's rank and colour over the refracted exit ray), as Rule 3b
   describes. Establish *which* sub-expression grows past `maxNodeCount`, and whether it is a
   shared node re-expanded per consumer (family a) or a single large computation (family b).
   Record the node count and tree depth reported by the throw so the "after" is measurable.
2. **Fix at the source the evidence points to.**
   - family (c): compile the engine producers through the optimizing entry point
     (`Process.optimized(...)`, or `evaluate()`) at the `LightingEngineAggregator` call sites
     the stack names. Re-measure before deciding whether (a) or (b) is still needed.
   - family (a): correct the computation/expression builder that over-expands the shared
     sub-expression so the DAG is not flattened into a larger tree (e.g. a missed n-ary merge, a
     shared reference that should be reused, a `concat`/comparison that need not be rebuilt per
     engine);
   - family (b): have the selection computation declare `isIsolationTarget` so the optimizer
     places it behind a kernel boundary — no graph-building `isolate()`/`Process.isolated`.
   Do not add new lower-level `get()`/`evaluateExplicit()` compile paths, which skip
   optimization. (The test itself calls `r.realize(params).get().evaluate()`. That is
   `RealizableImage.get()`, which already optimizes its source internally, so the test's entry
   point is not the problem.)
3. **Clear the known-issue flag.** Remove `@TestProperties(knownIssue = true)` from
   `refractiveSphereShowsFloor` so the existing `assertDiscShowsScene` assertion runs in CI, and
   drop the "second wall" paragraph from the test's class Javadoc. Keep `@Test(timeout = 120000)`
   as-is; if the compile is now fast but the render is close to the bound, record the margin and
   let the approver decide — do **not** inflate the timeout.
4. **Pin the fix with a backend-independent regression test.** Add a test that builds the exit-ray
   selection shape (a `getClosestChoice` over a refracted-and-re-intersected ray, or the
   minimal construction the profile points to) and asserts a structural property, not a
   wall-clock threshold, so the fix is guarded on any node. "Stays within
   `ScopeSettings.maxNodeCount`" (about 8.4 million nodes) is too loose to serve as that
   assertion: a regression could grow the tree a thousandfold and still pass. Assert instead
   that the node count grows at most linearly as stages or candidates are added (for example,
   compare the count for one and for two refraction stages, or for one and for three
   candidates). Alternatively, assert a concrete bound measured from the fixed tree.
5. **Report the incidental effect** on the sibling cases the prior plan named: the
   non-identity-transform reflection expression blow-up and the `renderTwoSpheres` compile
   timeout. State whether this fix resolves them; if it does, note it (a welcome bonus), if not,
   leave their existing follow-ups untouched.

Out of scope (name as follow-ups, do not expand the branch to cover them):
- Kernelizing the *per-pixel* surface selection and supersampling for throughput (ferrofluid
  §5 item 2 / Phase 4) — a performance rewrite, distinct from making this render compile.
- The `LightingEngineAggregator` "sum lights per surface instead of ranked choice" redesign noted
  in its own TODO — an architectural change, not a compile fix.

## Approach

Follow the "slow/too-deep/failing compile" discipline (base/relation README, Rule 3b): evidence
before theory, and fix the tree, never the renderer or the limit.

1. First answer "is this graph being optimized?" from the full stack (family c). Then profile
   the failing compile with `ar-profile-analyzer` before changing any code. The graph now gets
   far enough to throw (it no longer hangs in `resetArguments`), but it throws before a kernel
   exists, so a profiled reproduction of the failing producer may be needed to see generated
   source. Read the operation tree and the source to locate the expansion.
2. Decide among families (c), (a) and (b) from that evidence, then apply the minimal fix:
   optimizing entry point, construction site, or `isIsolationTarget`. **Do not** add code to
   `Expression.java`, which is already at 1597 of its 1600-line checkstyle limit. That is
   another reason the fix belongs at the construction source or the compile call site, not the
   simplifier or the renderer.
3. Verify correctness is unchanged: `reflectiveSphereShowsFloor` stays green, and the broader
   expression-simplification and render tests (`ExpressionSimplificationTests`,
   `ExpressionStructureTests`, the reflection render) still pass.
4. Run the targeted tests, run `ar-build-validator` (checkstyle, code_policy, test_timeouts,
   duplicate_code), update the test Javadoc, and clear the flag only if the success criteria are
   met.

## Success Criteria

- `ReflectionRefractionRenderTest#refractiveSphereShowsFloor` passes with
  `@TestProperties(knownIssue = true)` removed — the sphere's disc shows the lit floor, no NaN —
  well within its 120s timeout, compiling without throwing "Large expression not improved by
  simplification".
- `reflectiveSphereShowsFloor` still passes (no regression to the reflection path).
- A new backend-independent regression test pins the structural property of the fixed exit-ray
  selection: bounded, at most linear, node-count growth, or a measured concrete bound (see Scope
  item 4). Being under `ScopeSettings.maxNodeCount` is not enough on its own.
- The fix lives at the source the evidence names: the optimizing compile entry point, the
  expression/computation construction site, or an `isIsolationTarget` declaration. It is not a
  raised `maxNodeCount`, a renderer change, a hand-split or a manual `isolate()`. The
  before/after node count and tree depth, and the entry point that was compiling when the throw
  happened, are recorded in the plan's closing notes.
- `ar-build-validator` is clean; no existing test is weakened, disabled, skip-gated, or has its
  timeout inflated.
- **If un-flagging is not achievable in a focused session** (for example the compile needs the
  per-pixel kernelization that is out of scope), the flag is restored net-unchanged, the measured
  node counts and the diagnosed family are recorded, a follow-up is filed, and the known-issue
  Javadoc is updated — exactly as the prior cycle did for its own second wall. Reporting the
  wall honestly is a complete outcome; weakening the test is never one.

## Dependencies

None. Builds on the reset-traversal memoization already on `master` (PR #1001). The fix lives in
`base/code` / `base/collect` (the expression and computation layer) and/or
`compute/geometry` / `engine/render` (the selection construction), and the test in
`engine/render`; all are present. It requires no curated sample library, no model download, and
no particular backend — the render is a synthetic 32×32 host render — so it runs on any FlowTree
node and **no workstream settings file accompanies this plan**.

## Estimated Complexity

Medium–large. The throw site and the construction site are both identified, but the root-cause
family is not yet pinned, and the fix sits in the foundational compilation path, so the care is
in the profiled diagnosis and in proving correctness is unchanged across the
expression-simplification and render tests. The honest risk, flagged by the prior cycle, is that
making this render compile cleanly may depend on the per-pixel kernelization that is out of
scope; the success criteria account for that by treating a well-diagnosed, well-reported
"not yet un-flaggable" outcome as complete rather than as a license to weaken the test.

## Relationship to existing work

- Direct continuation of `PLAN-20261119-refraction-compile-dag-memoization.md` (PR #1001): this
  is the "second wall" that plan measured, named out of scope, and tracked as a follow-up.
- Unblocks `PLAN-20261106-ferrofluid-simulation.md` Phase 2 (the glossy/transparent look), which
  needs a working, CI-guarded refraction path.
- Complements the standing kernel-boundary discipline plan
  (`PLAN-20261114-manual-isolation-prevention.md`): if the fix is family (b), it is expressed
  through `isIsolationTarget`, keeping the boundary decision in the optimizer — the exact
  discipline that plan defends — and never through a manual `isolate()`.
- Shares its machinery with the recent expression-merging work merged on `master`
  (`NAryExpression.mergeOperands` / `createMerged`, PR #1028): if the fix is family (a), it is a
  construction-time merge or sharing correction in the same spirit.

## Open questions

Raised by the documentation review, for the person deciding whether this plan goes ahead.

1. **Is family (c) the real cause?** The code shows that the primary host selection in
   `LightingEngineAggregator` compiles its engines with plain `Producer.get()`, which does not
   optimize. The aggregator is an opaque `Evaluable`, so `RealizableImage.get()`'s
   `Process.optimized(source)` cannot reach inside it. What the code does not show is whether
   the throw happens under one of those `get()` calls, because only a summary of the stack was
   recorded. If it does, the fix is mostly in `engine/render` at the compile call sites, not in
   the expression layer the plan originally pointed at. The estimated complexity may drop, and
   Scope item 4's regression test should then guard "the selection is compiled optimized"
   rather than an expression-construction property. Should the plan be narrowed to that once
   the stack is captured?
2. **A many-candidates explanation does not fit this scene.** The exit-ray selection has exactly
   one candidate here (the floor, under the current light). So the size has to come from the
   chain of multiply-read stages feeding it, not from the number of engines. Any fix that
   targets "fewer candidates" (for example the sum-lights-per-surface redesign in the
   `LightingEngineAggregator` TODO) would not resolve this render, and is rightly out of scope.
3. **The primary path is per-pixel host selection.** In kernel mode,
   `LightingEngineAggregator.evaluate(Object[])` runs once per pixel on the host and calls
   `best.get().evaluate(args)` on the chosen engine. If making the graph compile requires moving
   that selection onto the device, that is the out-of-scope ferrofluid Phase 4 kernelization.
   The success criteria already treat "diagnosed, reported, still flagged" as a complete
   outcome. The approver should expect that outcome to be a real possibility.
4. **Regression-test placement.** A test of `getClosestChoice` over a refracted ray needs
   `RefractionShader`/`LightingEngineAggregator` (`engine/render`). A test of the optimizing
   compile entry point would sit beside `ReflectionRefractionRenderTest`. A construction-layer
   fix (family a) would instead want an `engine/utils` expression test next to
   `ExpressionStructureTests`. Decide the placement once the family is known, not before.

## Session findings (implementation attempt)

An implementation session reproduced the failure, captured the full stack, and diagnosed the
cause. The outcome is the plan's blessed "diagnosed, reported, still flagged" result: the render
is **not yet un-flaggable in a focused session**, so the flag was restored net-unchanged and a
follow-up filed.

- **Root cause is family (c), confirmed.** With the flag temporarily removed, the compile throws
  `ExpressionException: Large expression not improved by simplification` at `Expression.process`
  (node count over `ScopeSettings.maxNodeCount`, `1 << 23`). The entry point compiling when it
  throws is `LightingEngineAggregator.evaluate(Object[])`'s kernel-mode host selection
  (`best.get().evaluate(args)`) — a plain, **unoptimized** `Producer.get()`. The shared refraction
  sub-graph (the exit ray feeds the ranked-choice rank twice and the colour once) is therefore
  inlined per reader instead of folded behind kernel boundaries. The node count / tree depth the
  throw carries were not captured as numbers (the runner surfaced only the message); the entry
  point and throw site above are the recorded "before".
- **The minimal family-(c) fix is correct but exposes broader optimizer work.** Routing that call
  site through the optimizing `Producer.evaluate(args)` removes the expression-size throw, but
  optimizing *this* graph then crashes **before scope generation** — so it is not even confirmed
  that optimizing resolves the blow-up. The crash is a cascade of optimizer-isolation gaps in the
  rendering graph node types: (1) `ProducerWithRankAdapter` is a `Producer` but not a `Process`,
  so it is wrapped in a non-`Producer` `Process.of` shell and a later `generate` cast fails;
  (2) `GeneratedColorProducer.generate` throws the unsupported default; (3) the selection's zero
  default branch (`getClosestChoice` seeds `best` with `zeros(...)`) is bulk-isolated, but
  `CollectionZerosComputation.isolate()` forbids it by design (re-enabling it is prohibited — it
  reintroduces a known compile timeout). Fixing these without re-enabling zeros isolation or
  raising `maxNodeCount`, and without regressing `reflectiveSphereShowsFloor` (which shares the
  same primary-aggregator call site), is a focused base/relation optimizer hardening — the
  "broader foundational work" this plan flagged as a real possibility. It is tracked as a
  follow-up task refining the original tracker item.
- **No test was weakened.** `refractiveSphereShowsFloor` keeps `@TestProperties(knownIssue = true)`
  (net-unchanged); its class Javadoc was updated to describe the current cause.
  `reflectiveSphereShowsFloor` still passes.
