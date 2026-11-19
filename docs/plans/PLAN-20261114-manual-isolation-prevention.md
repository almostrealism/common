---
status: proposed
created: 2026-11-14
closed:
tracker: []
---

# Stop graph-building code from deciding kernel boundaries

Graph-building code must not decide kernel boundaries. Doing so is a workaround for the
optimizer, not a fix for it. The spectral-analysis work on `feature/spectral-2d` added a
`CollectionFeatures.isolated(producer)` helper and wrapped thirteen reductions in
`SpectralFeatures` and `SpectralGrid` with it. This document records three things: what was
actually wrong, how to detect and block the pattern, and which instructions let it happen.

## 1. What was actually wrong

Removing every manual `isolated(...)` call and re-running the spectral tests separated two
causes that the isolation had hidden.

**The graphs were evaluated without optimization.** The tests called `Producer.evaluate()`,
which is `get().evaluate()` and never calls `Process.optimize`. Kernel boundaries are decided
only during optimization, so every grid-wide reduction (the mean, the maximum, the radial
spectrum, the centroid sums) was embedded in each of its readers. All four
`dominantWavelength*` tests ran past their 120 s timeout inside
`AggregatedProducerComputation.getValueAt`. Through `evaluateOptimized()`, with no manual
isolation, the same graphs pass in about one second each. `Process.optimize` isolates these
reductions correctly: `TraversableRepeatedProducerComputation.isIsolationTarget` marks any
reduction longer than `isolationCountThreshold`. Optimization was simply never reached.

**The optimized path had a real code-generation defect.** On the optimized path,
`spectralPeakOfParabola` and `dominantWavenumberOfConstantField` failed with
`UnsupportedOperationException` thrown by the `Mod` constructor. The failing path is
`Scope.simplify`, then `Scope.processReplacements`, then `Expression.replace`, then
`Mod.recreate`. `processReplacements` hoists a repeated subexpression into a
`StaticReference` whose type is the subexpression's `getDeclarationType()`. For an integer
expression that depends on the kernel index, or whose bounds are unknown, that type is
`Long`. Here the hoisted expression was a branch of a nested integer `Conditional`: the
wrapped index of the `indexOfMax` search that `spectralPeakBin` uses. `Conditional.create`
then had an `Integer` branch and a `Long` branch, and typed *any* mismatch as `Double`. That
turned the index computation floating-point, and the integer `Mod` wrapping it rejected it.
Manual isolation hid this, because it split the graph so that the maximum search was never
simplified together with the gathers that read it.

**Fix.** `Conditional.create` now gives mismatched branches the type that the operands of an
arithmetic operation would get (`NAryExpression.promotedType`): `Integer` and `Long` give
`Long`, and only a floating-point branch gives `Double`. `ConditionalTypeTest` covers the
promotion directly, and also the replacement-under-`imod` sequence that failed.
`SpectralFeaturesTest` now evaluates the composite spectral graphs through
`evaluateOptimized()` and passes with no manual isolation. `CollectionFeatures.isolated` is
removed, along with its test.

## 2. Proposed detection

Four layers, cheapest first. All of them key on the same fact: outside the optimizer, nothing
calls `Process.isolated(...)`, `Process.isolate()`, or any wrapper around them.

### 2a. Edit-time hook: `block-manual-isolation.py` (PreToolUse on Edit/Write)

This hook would follow the shape of `block-interface-bypass.py`. Scan only the lines the edit
*adds* to a file under `src/main/java`. Block when an added line contains any of:

- `Process.isolated(` or `Process::isolated`;
- a call to `.isolate()` on anything other than `this`/`super` inside an `isolate()`,
  `optimize(...)` or `generate(...)` override;
- `new IsolatedProcess` or a subclass constructor of it;
- a new method whose body returns the result of any of the above, which is how the
  `isolated(producer)` helper was shaped.

**Allowed locations** — the optimizer and the process types' own delegation:

- `base/relation/.../compute/` (`Process`, `ParallelProcess`, the optimization strategies);
- `isolate()` and `optimize(ProcessContext)` overrides that delegate to a wrapped process,
  such as `OperationWithInfo`, `ReshapeProducer`, `PackedCollectionRepeat`,
  `IndexProjectionProducerComputation` and `CollectionProducerComputationAdapter`.

The block message names the alternative: make the computation report
`isIsolationTarget(ProcessContext)` correctly, and evaluate at the top of the call stack
through `evaluateOptimized()` or `Process.optimized(p).get()`. Hooks are under the CI-file
lock, so the hook lands on a `ci/...` branch.

### 2b. CI rule in `CodePolicyViolationDetector`

The same pattern, as a policy violation in the `code_policy` check, gives CI a backstop that
does not depend on the hook having run. The detector lives in `engine/utils` and is locked on
every branch, so the project owner has to make this change.

**Existing call sites the rule would flag.** Each needs either a fix or an explicit
allowlist decision before the rule can be enforced:

- `TemporalRunner` (`Process.isolated(tick)`, behind `enableIsolation`);
- `DelayNetwork` (`op.add(Process.isolated(tick))`);
- `PatternSystemManager` (`Process.isolated(cp(destination).traverse(0).max())`);
- `KernelTraversalOperationGenerator` (`operation.isolate()`).

The README of `studio/music` shows the `PatternSystemManager` form as an example and would
need the same correction.

### 2c. Runtime guard on the unoptimized path

The hooks catch the workaround. This guard catches the cause: reaching `get()` without
optimization. On the path that compiles without optimizing — `Producer.get()` /
`evaluate()` reaching `CollectionProducerComputationBase.getEvaluable` — walk the process
tree once. If any descendant reports `isIsolationTarget(ProcessContext.base())` and was not
isolated, fail in the test profile and warn otherwise. The message names the unoptimized
call. That turns "my graph takes two minutes to build, so I'll split it by hand" into an
immediate diagnosis. It needs a measurement of the tree-walk cost on large models before it
is enabled outside tests.

### 2d. Test-integrity pattern

`detect-test-hiding.sh` could add a pattern for a branch that adds `Process.isolated`,
`.isolate()` or a new isolation helper in production code while also changing tests from
`evaluateOptimized()` to `evaluate()`. That pair is the signature of hiding an optimizer
problem. This is lower value than 2a to 2c, and it is under the same locks.

## 3. How the instructions allowed this

Each item is something an agent could read as permission, or the absence of a prohibition.

1. **The only rule about isolation is phrased about the mechanism, not the decision.**
   `CLAUDE.md` says "Only `IsolatedProcess` breaks expression embedding". `Process.isolated(...)`
   returns an `IsolatedProcess`, so a manual call satisfies the letter of the rule.
   `compute/algebra/README.md` says the same ("ONLY `IsolatedProcess` is empowered to break
   expression embedding"). Neither says *who* may decide to isolate. **Proposed wording:**
   "Only `Process.optimize` decides kernel boundaries. Graph-building code never calls
   `Process.isolated`, `isolate()` or a wrapper of them; a computation that needs its own
   kernel says so through `isIsolationTarget`."

2. **The prohibition on hand-splitting names only one form of it.** `base/relation/README.md`
   warns against "materialising an intermediate by hand (an extra `evaluate()` ...)". Manual
   `isolated(...)` is the same kernel-boundary decision made in application code. It is not
   mentioned, so it read as the sanctioned alternative to the forbidden form.

3. **One internals document explicitly leaves the door open.** In
   `docs/internals/isolation-patterns.md`, "Anti-Pattern 3: Isolation Without Context" calls
   manual `process.isolate()` bad, but its fix is "Let strategies decide, *or understand the
   trade-offs first*". An agent that has measured a slow compile believes it understands the
   trade-offs. The second clause should be removed.

4. **The canonical example teaches the unoptimized call.** `CLAUDE.md` gives
   `cp(source).multiply(2.0).evaluate()` as *the* Producer pattern, and nearly every test in
   the repository uses plain `.evaluate()`. The rule that a composite graph must be
   evaluated through `evaluateOptimized()` appears only in a table in
   `base/relation/README.md`. `Process.optimize()` before `Process.get()` is stated, but
   `Producer.evaluate()` is not recognisable as a `Process.get()`. **Proposed:** state in
   `CLAUDE.md` that `Producer.evaluate()` and `get()` do not optimize, and that any graph
   containing a reduction is evaluated with `evaluateOptimized()`.

5. **Production code models the pattern.** `PatternSystemManager`, `DelayNetwork` and
   `TemporalRunner` isolate by hand, and the `studio/music` README shows one of them as an
   example. Precedent in the codebase reads as approval.

6. **Nothing mechanical objects.** Neither checkstyle, nor `CodePolicyViolationDetector`, nor
   any hook flags `Process.isolated(...)`, so the helper and all thirteen call sites passed every
   gate. Several automated review rounds (design review, deduplication, placement, Copilot)
   reviewed the helper's *implementation* — its casts and its non-`Process` inputs — and
   none asked whether it should exist.

7. **The diagnosis stopped at the symptom.** The earlier session found the slow compile with
   an `ar-jmx` thread dump and then codified "isolate the reduction itself" as a gotcha in
   `docs/internals/spectral-analysis.md`. The documented debugging checklist in
   `base/relation/README.md` asks first whether `optimize()` is being called. It is listed
   under "Debugging Isolation Issues", which an agent that does not yet think it has an
   isolation issue never opens. The `ar-profile-analyzer`-first rule in `CLAUDE.md` covers
   wrong values, not slow or failed compiles, so no rule routed this case to the optimizer
   question. **Proposed:** extend that rule. A compile that is slow, too deep or failing
   starts with "is this graph being optimized?", answered by evaluating it once through
   `evaluateOptimized()`, before any restructuring of the graph.

8. **Docs written by the branch carried the workaround forward.**
   `docs/internals/spectral-analysis.md` taught `isolated(isolated(x.sum()).divide(c(n)))` as
   the fix, so later sessions on the branch, and its reviewers, treated it as settled. It now
   describes the optimizing path instead.

## Open questions for the owner

- Should 2c fail or only warn outside the test profile?
- For each of the four existing call sites in 2b: fix it, or allowlist it with a stated
  reason?
