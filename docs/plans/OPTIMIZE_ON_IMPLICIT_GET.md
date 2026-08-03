# Optimize on the implicit-`get()` convenience methods

## Proposal

`Producer.into(Object)` and `Producer.evaluate(Object...)` should call
`Process.optimize()` before reaching `Process.get()`.

`Producer.get()` would keep its current behaviour: no optimization. The rule
becomes a legible one — **if you let a convenience method call `get()` for you,
you get optimization; if you call `get()` yourself, you have taken
responsibility for the tree.** Nothing is forced on a caller who wants the
unoptimized path, and the escape hatch is the method that already reads as the
lower-level one.

## Why

Isolation is only consulted during optimization. A graph that is never optimized
embeds every child expression inline, and `Expression.init` rejects anything
deeper than `ScopeSettings.maxDepth`. Because the depth of an inlined child grows
with its input size, the failure is size-dependent: the same graph compiles for a
small collection and stops compiling for a realistic one, surfacing as

```
HardwareException: Cannot compile <operation>
Caused by: ExpressionException: Expression too deep
```

The invariant is documented in at least four places — `base/relation/README.md`,
`io.almostrealism.compute` package javadoc, `PrefixAccumulationComputation`'s
class javadoc, and the project development guidelines. It is still easy to miss,
because the shape that omits it is the shape that reads most naturally:

```java
producer.into(destination).evaluate();
```

Nothing about that line suggests a kernel-boundary decision is being skipped.

### The case that prompted this

`FrequencyToAudioConverter.normalizeAudio` scaled a buffer by a peak obtained
from a whole-collection reduction, and wrote the result with
`producer.into(destination).evaluate()`. The reduction was embedded at every
element. It compiled for a 128-sample reconstruction and failed to compile at
4096 samples. Routing the same graph — unchanged — through
`Process.optimized(...)` fixed it.

Two details worth carrying into the design:

- The reduction (`AggregatedProducerComputation`, `CollectionMaxComputation`)
  declares no `isIsolationTarget`, and optimization still resolved the case. What
  is being skipped by an unoptimized call is therefore not a rare computation's
  standing demand to always be isolated — it is the optimization *strategies*
  making a dynamic choice from the shape and size of the tree in front of them.
  That is the common path, not the exceptional one, which is what makes omitting
  it costly rather than merely suboptimal.
- The first fix attempted was to materialise the reduction by hand. That works,
  and it is the wrong shape: it moves a decision that belongs to the process tree
  into application code, where the next site with the same shape will not have it.
  The convenience methods being unoptimized actively invites this class of
  workaround.

## Risk

This is the concerning part, and the reason this is written down rather than
done.

- **Call-site count.** `into` and `evaluate` are the ordinary way to run a
  producer. The change reaches essentially every consumer in the tree, plus
  tests. Most sites should be behaviourally identical, but "most" is doing real
  work in that sentence.
- **Optimization is not free.** It restructures the tree on every call. For a
  producer evaluated once, that cost is noise. For one evaluated in a loop — the
  autoregressive and per-sample paths especially — paying it per call would be a
  serious regression. Caching the optimized form, or optimizing only on first
  use, needs to be settled before this lands.
- **Behaviour changes where isolation changes results.** Isolation breaks
  expression embedding, which changes what gets compiled. Anything depending on
  the current inlined form — instruction-set sharing and kernel-cache
  identity, profile output, tests asserting on generated source or operation
  counts — can shift without any test asserting the wrong thing.
- **Numerical drift.** Restructuring can reassociate arithmetic. Tolerances that
  currently pass by a small margin may not.
- **Recursion.** `OperationList.get()` already routes through
  `optimize().get()` under some conditions, and guards against re-entry. Adding
  optimization at the `Producer` level needs to avoid optimizing an already
  optimized tree, and avoid a cycle where optimization internally calls a
  convenience method.

## Sketch of an approach

1. Establish the baseline first: how often are `into`/`evaluate` called on a
   producer that is evaluated more than once? That answer decides whether
   per-call optimization is affordable or whether the optimized form has to be
   cached on the producer.
2. Put the behaviour behind a flag defaulted to the current behaviour, in the
   spirit of `OperationList.enableAutomaticOptimization`, so the two paths can be
   compared on the same commit.
3. Turn it on for one module at a time, and compare per-test timing rather than
   only pass/fail — a regression here shows up as duration, not as a failure.
4. Watch the kernel-cache hit rate across the switch. A drop means isolation
   changed instruction-set identity, which is a correctness-adjacent signal even
   when every test passes.
5. Flip the default once a full run is clean, keeping the flag for one release so
   a consumer that regresses has an escape that is not a code change.

## Alternative considered

Leave the methods alone and add optimizing counterparts —
`intoOptimized(...)` alongside the existing `evaluateOptimized(...)`. Lower risk,
and strictly worse at the thing that matters: the default stays the shape that
silently omits optimization, and the omission is only avoided by someone who
already knows to avoid it. That is the situation this proposal exists to end.

## Status

Not started. Recorded so the decision is not rediscovered from a failing test.
