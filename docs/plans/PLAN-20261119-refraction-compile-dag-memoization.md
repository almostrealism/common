---
status: proposed
created: 2026-11-19
closed:
tracker: []
---

# Render the floor through a refractive sphere by memoizing the argument-reset DAG traversal

## Category

Code Quality (correctness), with a general compilation-performance payoff.

## Motivation

The ray-tracer migration (PRs #869, #875) and the reflected-ray fix (PR #886) gave the
engine a working *reflection* render path and restored one asserting render test in CI
(`ReflectionRefractionRenderTest#reflectiveSphereShowsFloor`). Its sibling,
`refractiveSphereShowsFloor`, is still pinned `@TestProperties(knownIssue = true)`, so the
engine ships **no asserting refraction render test** — the transparent-material path, which
the ferrofluid glossy proof-of-value track (`PLAN-20261106-ferrofluid-simulation.md`,
Phase 2) depends on, has no CI guard at all.

The reason it is a known issue is not a missing feature. `RefractionShader.shade` is already
a complete `Producer` graph (rewritten by PR #886): it refracts the view ray into the sphere,
re-intersects the surface to find where the ray leaves, refracts it back out, and selects the
closest lit surface along the exit ray with a `LightingEngineAggregator`. The graph *builds*;
it simply does not finish **compiling** within the test's timeout. The test's own Javadoc and
the glossy-reflection session notes both name the cause precisely:

> compiling that graph spends its time in `ScopeLifecycle.resetArguments`, which revisits
> every shared node of the graph once per path that reaches it.

This is a general defect in the compilation machinery that a deeply-shared graph happens to
expose. The refraction graph is diamond-shaped — the intersection point, the surface normal,
and the view direction each feed several downstream sub-expressions (the inbound refraction,
the surface re-intersection, the outbound refraction, the Fresnel term, the aggregator) — so a
traversal that descends into shared nodes once *per path* instead of once *per node* is
exponential in the number of diamonds. Fixing it clears the refraction known issue and makes
every deep, heavily-shared graph cheaper to compile.

This connects directly to the larger vision: a platform whose compiler folds large computation
graphs quickly is the precondition for both the generative-art render workloads and the
self-hosted language-model work, where graph sharing is pervasive. A compile step that is
exponential in node-sharing is a latent scaling wall; removing it is foundation work.

## Root cause (verified)

`ComputationBase.resetArguments()` clears this computation's cached argument state and then
recurses into every input:

```java
// ComputationBase.resetArguments()
public void resetArguments() {
    super.resetArguments();                          // ComputableBase: this.arguments = null
    ScopeLifecycle.resetArguments(getInputs().stream());
}
```

`ScopeLifecycle.resetArguments(Stream)` calls `resetArguments()` on each input, which recurses
again — with **no record of which nodes have already been reset**. A node reachable by *k*
distinct paths from the root is therefore reset *k* times, and its whole sub-DAG is re-walked
each time.

The asymmetry is the tell. The sibling traversal `prepareScope` does *not* blow up, because
`ComputationBase.prepareScope` carries a guard that short-circuits a node already prepared under
the current manager **before** it recurses into `getInputs()`:

```java
// ComputationBase.prepareScope(...)
if (getArgumentVariables() != null && previous == manager) return;   // <-- stops re-descent
...
ScopeLifecycle.prepareScope(getInputs().stream(), manager, context);
```

`resetArguments()` has no equivalent guard, so it re-descends unconditionally. That single
missing guard is the whole bug.

## Scope

Concrete, actionable items:

1. **Memoize the argument-reset traversal so each node is reset exactly once per pass.**
   Thread an identity-based visited set through the recursive `resetArguments` descent:
   - Add a traversal-carrying overload on `ScopeLifecycle` — e.g.
     `default void resetArguments(Set<ScopeLifecycle> visited)` that, by default, delegates to
     the existing no-arg `resetArguments()` — and a static
     `ScopeLifecycle.resetArguments(Stream<?>, Set<ScopeLifecycle>)` that skips any element
     already in the set.
   - Have `ComputationBase.resetArguments()` seed a `Collections.newSetFromMap(new IdentityHashMap<>())`
     and call the visited-set overload, which recurses through
     `ScopeLifecycle.resetArguments(getInputs().stream(), visited)`.
   - Preserve the public no-arg `resetArguments()` contract and its existing single-node
     semantics (clearing `arguments`); only the *recursive descent* gains the visited set.
   - Identity semantics (an `IdentityHashMap`), not `equals`, because distinct graph nodes can
     compare equal yet carry independent argument state.

2. **Confirm `prepareScope`/`getPreparedArgumentBound` are not the bottleneck** and, if the
   profiler shows a shared-node re-descent there too, apply the same visited-set treatment at
   that traversal site. (The `prepareScope` guard already bounds the common case; verify before
   touching it.)

3. **Clear the known-issue flag.** Remove `@TestProperties(knownIssue = true)` from
   `ReflectionRefractionRenderTest#refractiveSphereShowsFloor` so the refraction render
   assertion runs in CI. The assertion already exists (`assertDiscShowsScene`); this is
   additive. Keep the existing `@Test(timeout = 120000)`; if the compile is now fast but the
   render itself is close to the bound, tighten or justify the timeout rather than inflating it.
   Update the test's class Javadoc to drop the "known issue" paragraph once the cause is gone.

4. **Pin the traversal cost with a backend-independent structural test.** Add a test that
   builds a small diamond-shared graph (a handful of shared nodes feeding several consumers)
   and asserts `resetArguments()` visits each node a bounded number of times — e.g. via a
   counting `ScopeLifecycle` stub, so the guarantee is "once per node per pass," not a
   wall-clock threshold. This guards the fix against regression on any node.

Out of scope (name as follow-ups, do not expand the branch to cover them):
- The non-identity-transform reflection expression blow-up ("Large expression not improved by
  simplification") and `renderTwoSpheres` compile timeout. These are *expression-size* problems
  in simplification, a different mechanism from the reset traversal; if memoizing the reset also
  resolves them, that is a welcome bonus to report, not a requirement.
- Kernelizing the per-pixel surface selection (a performance item already tracked under the
  ferrofluid plan, Phase 4).

## Approach

Follow the project's "slow/too-deep compile" discipline (Rule 3b) and let evidence, not
theory, drive each step:

1. **Reproduce and measure first.** Run `refractiveSphereShowsFloor` with the known-issue flag
   temporarily off, under an `OperationProfile`, and confirm where the time goes. The expected
   signature is `resetArguments` dominating; verify it with `ar-profile-analyzer`
   (`find_slowest`, the operation tree) before changing code. Record the before/after compile
   time in the plan's closing notes.
2. **Check the optimizer question before restructuring.** The test currently realizes through
   `r.realize(params).get().evaluate()`. Confirm whether evaluating the render graph through
   `evaluateOptimized()` (equivalently `Process.optimized(...).get().evaluate()`) changes the
   compile cost — the canonical first move when a graph is slow to compile. The documented cause
   is the reset traversal, not a missing optimization, so this is a confirmation step, not the
   expected fix; do **not** split the graph by hand or add a manual `isolate()`.
3. **Apply the visited-set memoization** at the reset traversal site (item 1 above). This is a
   deduplication of a cleanup walk, not a change to kernel boundaries — it introduces no
   `Process.isolated`/`isolate()` and does not alter what the optimizer decides. Each shared
   node is still reset; it is reset once instead of once-per-path.
4. **Verify correctness is unchanged.** Because `resetArguments()` clears cached state, visiting
   a node once is sufficient and a second visit is pure waste; the memoization changes cost, not
   result. Confirm with the existing reflection render test and the broader reset-dependent
   tests (below) that recompilation after an argument-provider change still works.
5. **Validate and run the targeted tests**, then update the test Javadoc and clear the flag.

## Success Criteria

- `ReflectionRefractionRenderTest#refractiveSphereShowsFloor` passes with
  `@TestProperties(knownIssue = true)` removed, asserting the sphere's disc is not entirely
  black and contains no NaN — i.e. the lit floor is visible through the refractive sphere — well
  within its timeout.
- `reflectiveSphereShowsFloor` still passes (no regression to the reflection path).
- A new backend-independent structural test demonstrates that `resetArguments()` visits each
  node of a diamond-shared graph a bounded (once-per-pass) number of times.
- The measured compile time of the refraction render drops from "exceeds timeout" to a reported
  figure, recorded in the plan's closing notes.
- `ar-build-validator` is clean (checkstyle, code_policy, test_timeouts, duplicate_code,
  javadoc); no existing test is weakened, disabled, skip-gated, or has its timeout inflated.

## Open questions

These are the risks the approver should weigh; the implementation session should resolve each
with evidence rather than assume the optimistic answer.

- **Is memoizing the reset traversal sufficient on its own to bring the refraction compile under
  the 120s timeout?** The plan diagnoses one exponential cost (the un-memoized reset walk), but it
  also names a *second*, independent cost it puts out of scope: the expression-size blow-up in
  simplification ("Large expression not improved by simplification") that afflicts the
  non-identity-transform reflection and `renderTwoSpheres` cases. The refraction graph is larger
  and more deeply shared than the single-bounce reflection case, so it may hit that second wall
  once the reset cost is removed. If the post-fix profile still shows the compile exceeding the
  budget, clearing `knownIssue` is premature — report the measured compile time and keep the flag
  rather than inflate the timeout, and the success criterion "passes well within its timeout" is
  not met.
- **Does the refraction render leave enough CI-speed margin to un-flag safely?** Even with the
  compile fixed, the render does strictly more work than the reflection sibling (two refractions,
  a surface re-intersection, and the `LightingEngineAggregator` selection). The sibling
  `reflectiveSphereShowsFloor` was observed consuming a substantial fraction of its 120s budget on
  the node where its `knownIssue` flag was cleared, so the refraction case could land near or over
  the bound on a slower CI node — re-introducing exactly the flakiness the flag was guarding
  against. If the margin is thin, the right move is to record it and let the approver decide
  whether to un-flag now or wait for the per-pixel kernelization (ferrofluid Phase 4) that would
  cut the render cost; it is **not** to raise `@Test(timeout = ...)` past 120000.
- **"Verified" means verified against the code, not yet against a profile.** The root cause is
  established by reading `resetArguments`/`prepareScope` and the test's own Javadoc, not by a
  profile run (the known-issue flag means no profiled run of this graph exists yet). The Approach's
  first step closes this gap; until it runs, treat the `resetArguments`-dominates hypothesis as
  strongly-evidenced but unconfirmed.

## Dependencies

None. The fix lives in `base/code` (`ScopeLifecycle`, `ComputationBase`) and the test in
`engine/render`; both are present. It builds on the reflected-ray fix (PR #886) already on
`master`. It requires no curated sample library, no model download, and no particular backend —
the render is a synthetic 32×32 host render — so it runs on any FlowTree node and no
workstream settings file accompanies this plan.

## Estimated Complexity

Medium. The fix itself is small and surgical (a visited set on one recursive traversal), but it
sits in the foundational compilation path, so the care is in the verification: confirming the
profile, proving correctness is unchanged across the reset-dependent tests, and pinning the
guarantee with a structural test. The diagnosis is already done and documented here; the
implementation session's main work is measurement and careful validation.

## Relationship to existing work

- Direct continuation of the reflected-ray cycle (`PLAN-20261110-reflected-ray-shape.md`,
  PR #886) — this is the "fix refraction / clear `refractiveSphereShowsFloor`" sibling that
  cycle's plan named as open.
- Unblocks `PLAN-20261106-ferrofluid-simulation.md` Phase 2 (the glossy/transparent look), which
  needs a working, CI-guarded refraction path.
- Complements the standing kernel-boundary discipline plans
  (`PLAN-20261114-manual-isolation-prevention.md`): this fix keeps the boundary decision in the
  optimizer and only deduplicates a traversal, deliberately avoiding any manual isolation.
