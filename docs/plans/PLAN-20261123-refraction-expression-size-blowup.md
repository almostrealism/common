---
status: proposed
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
scaling wall; fixing it at its source is foundation work, and the fix is expected to help the
sibling non-identity-transform reflection and `renderTwoSpheres` cases that hit the same wall.

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
   `NAryExpression.mergeOperands` would collapse; it is a large tree of genuinely distinct
   operations that is being **inlined into a single expression** instead of being shared or
   placed behind a kernel boundary.

**The construction site is verified.** The stack recorded by the prior session runs
`Product.of` → `UniformCollectionExpression.getValueAt` → deep recursion through
`ReshapeProducer` / `TraversableExpressionComputation` / `AggregatedProducerComputation` /
`CollectionConcatenateComputation` / `InequalityComparisonComputation`, compiling the
`LightingEngineAggregator` exit-ray selection. Reading `LightingEngineAggregator` (a
`RankedChoiceEvaluableForRGB`) explains the shape: `init(...)` adds one
`IntersectionalLightingEngine` per **surface-light pair**, and the host selection path composes
every engine's rank comparison and RGB contribution into one nested ranked-choice expression.
`RefractionShader.shade` builds such an aggregator for the exit ray *in addition to* the primary
ray, over a graph that already re-intersects the surface and refracts twice — so the selection's
operands each embed the (shared) exit-ray geometry, and inlining them all into one expression
produces the over-budget, irreducible tree.

What the implementation session must still establish with evidence — not assume — is **which of
two root-cause families** applies, because the correct fix differs:

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

1. **Reproduce and profile first (Rule 3b).** Temporarily remove the `knownIssue` flag and run
   `refractiveSphereShowsFloor` under an `OperationProfile`; load the profile with
   `ar-profile-analyzer` and read the generated source / argument bindings for the
   `LightingEngineAggregator` exit-ray selection. Establish *which* sub-expression is expanded
   past `maxNodeCount` and whether it is a shared node re-expanded per consumer (family a) or a
   single large computation (family b). Record the node count and tree depth reported by the
   throw so the "after" is measurable.
2. **Fix at the construction source.** Based on the profile:
   - family (a): correct the computation/expression builder that over-expands the shared
     sub-expression so the DAG is not flattened into a larger tree (e.g. a missed n-ary merge, a
     shared reference that should be reused, a `concat`/comparison that need not be rebuilt per
     engine);
   - family (b): have the selection computation declare `isIsolationTarget` so the optimizer
     places it behind a kernel boundary — no graph-building `isolate()`/`Process.isolated`.
   Confirm the render still evaluates through `evaluate()` (optimized by default); do not switch
   it to the lower-level `get()`/`evaluateExplicit()` entry points, which skip optimization.
3. **Clear the known-issue flag.** Remove `@TestProperties(knownIssue = true)` from
   `refractiveSphereShowsFloor` so the existing `assertDiscShowsScene` assertion runs in CI, and
   drop the "second wall" paragraph from the test's class Javadoc. Keep `@Test(timeout = 120000)`
   as-is; if the compile is now fast but the render is close to the bound, record the margin and
   let the approver decide — do **not** inflate the timeout.
4. **Pin the fix with a backend-independent regression test.** Add a test that builds the exit-ray
   selection shape (a ranked choice over a handful of surface-light engines, or the minimal
   construction the profile fingers) and asserts the compiled expression stays within
   `ScopeSettings.maxNodeCount` — i.e. the structural property, not a wall-clock threshold — so
   the fix is guarded on any node.
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

1. Profile the failing compile with `ar-profile-analyzer` before changing any code; the profile
   now exists because the graph compiles far enough to throw (it no longer hangs in
   `resetArguments`). Read the generated source and the operation tree to locate the expansion.
2. Decide family (a) vs (b) from that evidence, then apply the minimal construction-site or
   `isIsolationTarget` fix. Keep the change in the computation/expression layer that produces the
   tree; **do not** add code to `Expression.java`, which is already at 1593 of its 1600-line
   checkstyle limit — another reason the fix belongs at the construction source, not the
   simplifier or renderer.
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
- A new backend-independent regression test demonstrates the exit-ray selection compiles to an
  expression within `ScopeSettings.maxNodeCount`.
- The fix lives at the expression/computation construction source — not a raised
  `maxNodeCount`, not a renderer change, not a hand-split or manual `isolate()`. The before/after
  node count and tree depth are recorded in the plan's closing notes.
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
