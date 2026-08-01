# Plan: Remove the `output` (destination) argument from `AcceleratedOperation.apply`

## TL;DR

`AcceleratedOperation.apply(MemoryBank output, Object[] args, Semaphore dependsOn)`
accepts an optional runtime destination. That single extra parameter creates a
*second*, parallel way to run every kernel and forces the argument at
`getOutputArgumentIndex()` to be managed differently from all other arguments
(override logic in `ProcessDetailsFactory.init`, a bespoke copy-out side-effect
policy in `AcceleratedOperation.apply`, and the `DestinationEvaluable` wrapper
whose kernel branch exists mostly to feed it).

The proposal: **delete `output` and carry the destination as `args[0]`**, using
the machinery we already have for runtime argument binding (`ProducerArgumentReference`
/ `PassThroughProducer`) and for automatic destination creation
(`MemoryDataDestination.createDestination`). `apply` becomes `apply(Object[] args)`;
`evaluate(a, b)` delegates as `apply({null, a, b})` and `into(d).evaluate(a, b)`
delegates as `apply({d, a, b})`; a `null` in slot 0 means "create the destination
for me," exactly as `MemoryDataDestinationProducer` does today.

The good news, established by the audit below: the blast radius on `apply` itself
is tiny (2 non-null call sites, 0 overrides), and the framework already contains
every primitive needed. The hard part is a single conceptual hazard — **`Object[] args`
would acquire two meanings** (the public `evaluate` arguments vs. the internal
kernel/apply arguments that are offset by one) — and getting the `+1` offset to
live in exactly one place without breaking standalone `PassThroughProducer` use or
instruction-cache signature stability.

This document maps the current mechanism precisely, proposes a concrete
implementation, and enumerates the risks. It is a study, not an approved change.

> **Revision note.** This document was originally written before the Semaphore
> tech-debt work (chained copy-in/copy-out, per-argument completion delivery via
> `CompletionConsumer`, Metal commit-cause attribution) merged into this branch.
> It has been updated to describe the current code: the copy-out policy is now
> expressed as chained `Submittable` submissions rather than synchronous runs,
> `DestinationEvaluable` and `AcceleratedComputationEvaluable.request` carry the
> streaming completion-delivery contract that must survive this refactor, and the
> validation gate now includes the commit-attribution instruments.

---

## 1. Background — how the destination works today

### 1.1 The destination is input index 0 of every `ProducerComputation`

`ProducerComputationBase` fixes a convention: **argument 0 is the output/destination.**

```java
// ProducerComputationBase
@Override
public Variable getOutputVariable() { return getArgument(0); }

public Evaluable<O> getDestination() { return (Evaluable<O>) getInputs().get(0).get(); }
```

Both `signature()` and `description()` deliberately **exclude input 0** (they
`skip(1)`).

The destination producer is injected at construction. For the overwhelming
majority of computations that is the `CollectionProducerComputationBase`
constructor:

```java
List<Producer<PackedCollection>> inputs = new ArrayList<>();
inputs.add(new MemoryDataDestinationProducer<>(this, this::adjustDestination));
inputs.addAll(List.of(arguments));
setInputs(inputs);
```

Other injection sites: `PassThroughProducer` (self-referential destination) and
`KernelTraversalOperation`.

### 1.2 The destination is *baked into the generated kernel*, not just a runtime value

This is the subtlety that makes the destination more than "just another arg."
During scope generation the output variable is a **written** `ArrayVariable`:

```java
// KernelTraversalOperation.getScope
ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();
for (int i = 0; i < getMemLength(); i++) {
    scope.getVariables().add(output.reference(e(i)).assign(expressions.get(i)));
}
```

The same pattern (`getOutputVariable()` cast to `ArrayVariable`, used to form the
destination expression the kernel assigns into) appears in
`CollectionProducerComputationAdapter`, `PackedCollectionMap`,
`RepeatedProducerComputation`, `LoopedWeightedSumComputation`,
`AcceleratedTimeSeriesValueAt`, and `DefaultEnvelopeComputation`.

**Consequence for the refactor:** whatever we make input 0 be, it must still
resolve to an `ArrayVariable` that the compiler can emit assignments into. A
`PassThroughProducer` already satisfies this — it produces an `ArrayVariable` via
`prepareScope`/`getArgument` — but it registers its argument with
`Expectation.NOT_ALTERED`, signalling "read-only," which is wrong for a
destination. See §5.4.

### 1.3 Two runtime paths: `evaluate` and `into`

**Path A — `evaluate(args)` (no explicit destination).** The output is *already*
`null` here; the destination is auto-created:

```java
// AcceleratedComputationEvaluable.evaluate
AcceleratedProcessDetails process = apply(null, args);   // <-- output is ALWAYS null
process.awaitReady();
waitFor(process.getSemaphore());

T result = postProcessOutput((MemoryData) process.getOriginalArguments()[outputArgIndex], offset);
return validate(result);
```

The result is extracted from the *argument slot* at `outputArgIndex` (obtained
from the instruction-set manager), not from a returned value. Because `output` is
null, `ProcessDetailsFactory` leaves that slot to be produced by input 0's
`MemoryDataDestinationProducer`, whose evaluable is a `MemoryDataDestination` —
and the factory calls `createDestination(size)` on it to allocate a
correctly-sized buffer.

`AcceleratedComputationEvaluable.request` follows the same shape (`apply(null, args)`
then `awaitReady`), but delivers the result downstream instead of returning it —
immediately together with the completion `Semaphore` when the downstream is a
`CompletionConsumer`, or after an `onComplete` wait otherwise. That streaming
contract postdates the original draft of this plan and must be preserved by the
re-plumbing in step 3.

**Path B — `into(d).evaluate(args)` (explicit destination).** `into` wraps the
kernel in a `DestinationEvaluable`, which is the *only* place a non-null `output`
ever reaches `apply`:

```java
// DestinationEvaluable.evaluate (and, with the same shape, request)
AcceleratedProcessDetails details = ((AcceleratedOperation) operation)
    .apply(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
```

The full chain for a collection computation is:
`producer.get()` → `HardwareEvaluable` → `.into(d)` →
`HardwareEvaluable.withDestination` → `new DestinationEvaluable(kernel, d)` →
`.evaluate(args)` → `apply(d, args)`.

`DestinationEvaluable.request` is also the streaming path for kernel-argument
evaluation: `ProcessDetailsFactory.construct` builds
`kernelArgEvaluables[i].into(result).async(executor)` for each argument that needs
a sized destination, and relies on the resulting `DestinationEvaluable` delivering
`(value, completion)` to the factory's `CompletionConsumer` downstream so the
consuming kernel can chain on the argument's completion instead of the host
waiting for it. **This role is new since the Semaphore tech-debt work and is the
reason `DestinationEvaluable` can no longer simply be deleted** (see §2 and step 5).

### 1.4 What `output` actually does inside `apply` / `ProcessDetailsFactory`

Two things, and only two:

**(a) It overrides the value of the output argument slot** (`ProcessDetailsFactory.init`):

```java
if (outputArgIndex < 0 && output != null) {
    throw new UnsupportedOperationException();     // no output slot, but one was supplied
}
...
} else if (i == outputArgIndex && output != null) {
    kernelArgs[i] = output;                        // <-- override slot with caller's buffer
    continue i;
}
int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));
if (refIndex >= 0) {
    kernelArgs[i] = (MemoryData) args[refIndex];   // normal arg binding
}
```

**(b) It selects the aggregation copy-out policy** (`AcceleratedOperation.apply`,
inside the `whenReady` listener):

```java
boolean aggregating = argumentMap != null && argumentMap.hasReplacements();
boolean aggregateCopyOut = aggregating
        && (output == null || MemoryDataArgumentMap.enableStrictSideEffects);
...
if (aggregateCopyOut) {
    Semaphore copyOut = Submittable.submit(output == null ?
            argumentMap.getPostprocessOperations(null) :
            argumentMap.getPostprocessOperations((MemoryData) output), completion);
    if (copyOut != null) completion = copyOut;
}
```

The policy: `output == null` copies every aggregated input slice back; `output != null`
(default) copies none (the caller's explicit buffer is taken to be the only result
of interest); `output != null` with strict side-effects copies all *except* the
slice that aliases `output` (so an in-place `x = x + y` is not clobbered). See
`MemoryDataArgumentMap.getPostprocessOperations`.

Note the copy-out is a **chained `Submittable` submission on the kernel's
completion semaphore**, not a synchronous run — and the chain base `ready` that
the kernel dispatches against is itself the merge (via `Semaphore.all`) of the
caller's `dependsOn` with any per-argument completions recorded on
`AcceleratedProcessDetails`. Step 4 edits this chained code; the policy *key* is
what moves from `output` to `args[0]`, the chaining stays exactly as it is.

That is the entire footprint of `output`. Everything else about the destination —
its identity as `ArrayVariable`, its `outputArgIndex`, its auto-creation — is
already handled by the argument machinery and is *independent* of the `output`
parameter.

---

## 2. The proposed design

Carry the destination as `args[0]`. The public `Evaluable.evaluate` API is
unchanged for callers; the delegation bumps arguments over by one slot.

| Call | Today | Proposed |
|------|-------|----------|
| `x().multiply(y()).evaluate(a, b)` | `x`=arg 0, `y`=arg 1; `apply(null, {a, b})`; destination auto-created at input 0 | `x`=arg 1, `y`=arg 2; `apply({null, a, b})`; `args[0]==null` ⇒ auto-create |
| `x().multiply(y()).into(d).evaluate(a, b)` | `apply(d, {a, b})` | `apply({d, a, b})` |
| `op.run()` (no result) | `apply(null, {})` | `apply({})` — no result slot, opt-out (see §5.5) |

`apply` collapses to a single signature `apply(Object[] args, Semaphore dependsOn)`.
The `output == null` copy-out policy re-keys onto `args[0] == null`. The
`DestinationEvaluable` kernel branch reduces to "prepend `args[0]`, call `apply`" —
but the class itself survives as the streaming adapter that delivers
`(value, completion)` downstream (§1.3, Path B).

### 2.1 Recommended implementation — a hybrid destination producer

The cleanest way to make `args[0]` behave as "destination, or auto-create if null"
is to give input 0 a producer that is **both**:

1. a `ProducerArgumentReference` returning index 0 (so `ProcessDetailsFactory`
   binds it from `args[0]`), **and**
2. backed by a `MemoryDataDestination` `get()` (so when `args[0]` is null the
   existing auto-create path in `ProcessDetailsFactory.construct` fires).

With that single producer type, `ProcessDetailsFactory.init` needs *no* special
case at all — the existing generic logic already does the right thing:

```java
// Existing generic binding, with the destination as a ProducerArgumentReference(0):
int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));   // == 0 for the destination
if (refIndex >= 0) {
    kernelArgs[i] = (MemoryData) args[refIndex];   // args[0]: the caller's buffer, or null
}
// ... second pass in construct(): kernelArgs[i]==null  ==>  createDestination(size)  (unchanged)
```

`args[0] != null` → the destination slot is bound to the caller's buffer (the
old Path B). `args[0] == null` → the slot stays null and is auto-created by the
same `MemoryDataDestination.createDestination` call we use today (the old Path A).
**The two paths merge into one.**

This is essentially `MemoryDataDestinationProducer` gaining
`implements ProducerArgumentReference { int getReferencedArgumentIndex() { return 0; } }`
plus the input-index shift of §2.2.

### 2.2 The `+1` offset — the one genuinely hard decision

Reserving `args[0]` for the destination means every *input* reference must shift
from `N` to `N+1`. `x().multiply(y())` inputs become `PassThroughProducer(1)` and
`PassThroughProducer(2)`. There are three places the shift could live; they are not
equivalent.

- **Option A — shift in the factory (`Input.value` / `v`).** `Input.value(shape, n)`
  builds `new PassThroughProducer(shape, n + 1)`. User code keeps writing
  `v(shape, 0)` for "first input." Only the ~1 direct `new PassThroughProducer`
  user site needs a manual fix. **Hazard:** a *bare* top-level `v(0).get().evaluate(x)`
  (a `PassThroughProducer` used as the whole producer, whose `get()` returns
  `args -> args[argIndex]` and never flows through `apply`/prepend) would read
  `args[1]` of a one-element array. Must audit for bare passthrough evaluation
  (see §5.1).

- **Option B — shift at the resolution boundary only.** Keep `PassThroughProducer`
  indices 0-based; have `ProcessDetailsFactory` resolve input references as
  `args[refIndex + 1]` and the destination as `args[0]`. **Hazard:** the `args`
  array that flows through `ProcessDetailsFactory.construct` is the prepended
  array, and any producer whose `get()` is invoked in the `evaluateAhead` path
  would see the prepended array with un-shifted indices — a latent mismatch. Works
  only because `PassThroughProducer`s are resolved via `refIndex` and never enter
  `evaluateAhead`; fragile if that invariant ever changes.

- **Option C — shift in the delegation, store shifted.** `evaluate`/`into`
  prepend the slot; `PassThroughProducer` stores the already-shifted apply-space
  index. Same net effect as A but the shift is explicit at every construction site
  (touches all ~287 `v(...)` calls). Rejected — too broad, per the "never reference
  version numbers"-style aversion to sweeping mechanical edits.

**Recommendation: Option A**, gated on the bare-passthrough audit. It concentrates
the change in one factory method and keeps the public `v(n)` = "n-th input"
mental model intact.

---

## 3. Evidence / blast radius (audited; re-verified after the Semaphore merge)

### 3.1 `apply(output, …)` call sites — tiny

Exhaustive search of the repo (all modules, incl. tests, excl. `/target/`):

**Non-null `output` — 2 sites, both in `DestinationEvaluable`:**
- `DestinationEvaluable.evaluate`
- `DestinationEvaluable.request`

**Null `output` — 4 sites (internal):**
- `AcceleratedOperation.run`
- `AcceleratedOperation.submit`
- `AcceleratedComputationEvaluable.evaluate`
- `AcceleratedComputationEvaluable.request`

**Overrides of `apply` — 0.** No subclass overrides it anywhere.

### 3.2 Argument-index creation is a chokepoint

- `new PassThroughProducer(...)` — **7 sites total**, 5 of them inside
  `Input.java`/`PassThroughProducer.java` themselves. **Exactly one user site**:
  `WaveDataProviderAdapter` (engine/audio; indices 0, 1, 2).
- `Input.value(...)` — ~30 call sites. `v(...)` (2-arg form) — ~287 call sites, but
  all funnel through `Input.value` → `PassThroughProducer`.
- `getReferencedArgumentIndex()` has consumers in `ProcessDetailsFactory`
  (binding), `AlgebraFeatures` and `MemoryDataArgumentMap` (matching). A *uniform*
  `+1` shift preserves all matching semantics.

### 3.3 `getOutputVariable` / `getOutputArgumentIndex` / `into`

- `getOutputVariable()` — default `null` in `OutputSupport`/`ComputationBase`;
  the real definition is `ProducerComputationBase` (`getArgument(0)`);
  `AcceleratedComputationOperation` delegates to the computation. ~7 computations
  cast it to `ArrayVariable` in `getScope` (see §1.2). All keep working as long as
  input 0 remains an `ArrayVariable`-producing writable slot.
- `getOutputArgumentIndex()` — abstract in `AcceleratedOperation`; **one**
  concrete impl (`AcceleratedComputationOperation`, delegating to
  `ScopeInstructionsManager.getOutputArgumentIndex`, returns `-1` when unset).
  Unaffected: this is the *kernel* argument index (discovered at `postCompile`),
  a different index space from the evaluate-arg index and orthogonal to removing
  `output`.
- `into(Object)` — ~10 implementations (`AcceleratedComputationEvaluable`,
  `HardwareEvaluable`, `DynamicProducerForMemoryData`, `MemoryDataDestination`,
  `CollectionProvider`, `Random`, `LightingEngineAggregator`, `SuperSampler`,
  `CachedMeshIntersectionKernel`), ~63 non-test + ~134 test call sites. **Its
  contract is preserved** ("write into this destination"), so call sites do not
  change; only the two implementations that reach `apply` via `DestinationEvaluable`
  are re-plumbed to prepend `args[0]`. The custom `into`s that already bypass
  `apply` (e.g. `CollectionProvider.into` → `MemoryDataCopy`) are untouched.

---

## 4. Goal

- Single dispatch path: `apply(Object[] args, Semaphore dependsOn)` (+ a
  no-`dependsOn` convenience). No `output` parameter.
- The destination is `args[0]` by convention for result-producing evaluables;
  `null` ⇒ auto-create. Operations with no result (the `run()`/`submit()` path)
  simply pass no slot-0 — the convention is **opt-in**, matching the user's intent
  that we "not force it on everyone."
- Delete the special-case override branch in `ProcessDetailsFactory.init`, and
  fold the copy-out policy onto `args[0]`.
- Collapse `DestinationEvaluable`'s kernel branch to a prepend; retain the class
  as the streaming `(value, completion)` adapter.
- Net result: one way to run a kernel, one place the destination is bound, and the
  removal of the "output variable is managed differently" special-casing that
  motivated this study.

---

## 5. Risks and open questions

### 5.1 (Highest) `Object[] args` acquires two meanings

Today `args` uniformly means "the `evaluate` arguments." The proposal overloads
`args[0]` to mean "destination," so *inside* the kernel/apply boundary the array is
offset by one from the *public* `evaluate` array. Everything hinges on that offset
living in exactly one place (§2.2, Option A) and on **no code path evaluating a
shifted `PassThroughProducer` against an un-prepended array** (bare passthrough).

- *Action:* audit for top-level `PassThroughProducer`/`Input.value` producers whose
  `.get().evaluate(...)` is called directly (not embedded in a larger computation),
  especially in tests. If any exist, they must go through the same prepend or be
  exempted.
- *Action:* confirm `PassThroughProducer`s are always resolved via `refIndex` and
  never through `ProcessDetailsFactory`'s `evaluateAhead` branch (they are today),
  so Option A's array never reaches their raw `get()`.

### 5.2 Instruction-cache signature stability

`PassThroughProducer.signature()` embeds the index: `param(index{shape})`.
Shifting indices changes those signature strings, so **every cached kernel
signature changes once**. This is benign — the cache is process-local and keyed by
signature (per `INSTRUCTION_CACHING.md`), and the shift is *uniform*, so two
computations equal before the change remain equal after.

The destination itself is **not** a new signature risk:
`ProducerComputationBase.signature()` already skips input 0, so replacing
`MemoryDataDestinationProducer` with a hybrid `ProducerArgumentReference` at
input 0 does not newly expose it to the signature. (Independent, pre-existing
hazard flagged by the consultant: producers over *aggregation targets* can return
`null` signatures and disable reuse — unrelated to this change but worth not
perturbing.)

### 5.3 Copy-out side-effect policy must move, not vanish

The `aggregateCopyOut` / `getPostprocessOperations(skipOutput)` logic is
correctness-critical for in-place `x = x + y` and for Metal batching behavior
(there is prior CI history here — the `feature/argument-prep-assignment` Metal
"produces 0.0" investigations). Since the Semaphore merge this logic is expressed
as `Submittable` submissions chained on the kernel's completion semaphore, and the
kernel itself chains on `Semaphore.all(dependsOn, argumentCompletions...)` —
re-keying `output` → `args[0]` must not disturb that chaining. Mechanically simple,
but this is the highest-value area to cover with existing aggregation/in-place
tests **and with the commit-attribution instruments** (§7) before and after.

### 5.4 The destination slot is *written*, and `PassThroughProducer` says `NOT_ALTERED`

A `PassThroughProducer` argument is registered with `Expectation.NOT_ALTERED`;
the destination is written by the kernel (`output.reference(i).assign(...)`). The
hybrid destination producer must register its argument with the correct write
expectation (as `MemoryDataDestinationProducer` effectively does today via
`ComputationBase.assignArguments` → `Expectation.EVALUATE_AHEAD`). Getting the
`Expectation` wrong risks the optimizer eliding or reordering the destination
write, or the aggregation copy-out treating it as read-only. Must be verified
against `MemoryReplacementManager` behavior.

### 5.5 Operations without a result

`run()`/`submit()` dispatch side-effecting operations (assignments, `OperationList`
members) with `apply(null, {})` and `outputArgIndex == -1`. Under the proposal these
pass `apply({})` — no slot-0 prepend — so the "args[0] is the destination"
convention must be genuinely opt-in and not assumed by `ProcessDetailsFactory` when
there is no output slot. The existing `if (outputArgIndex < 0 && output != null)
throw` guard becomes `... && args[0] != null` and must be reconciled with the
prepend so a resultless op is never handed a phantom slot 0.

### 5.6 `getOriginalArguments()[outputArgIndex]` result extraction

`evaluate` reads the result from the kernel arg slot, not a return value. This
continues to work unchanged: the destination (whether from `args[0]` or
auto-created) lands in that same slot. Worth an explicit test that
`into(d).evaluate(...)` returns `d` and that plain `evaluate(...)` returns a fresh
buffer, both via this extraction path.

### 5.7 `HardwareEvaluable` and the createDestination coupling

`HardwareEvaluable` holds a separate `destination` evaluable used purely for
`createDestination(size)` sizing (`CollectionProducerComputationBase` passes
`getDestination()` when constructing it). That is independent of the `apply`
`output` param and should be left intact; only `HardwareEvaluable.into`/
`withDestination` (which build `DestinationEvaluable`) are re-plumbed.

### 5.8 External API surface

`AcceleratedOperation.apply` is `protected`; `Evaluable.into`/`evaluate` are public
but their contracts are preserved. The signature change to `apply` is
source-incompatible for any out-of-repo subclass that calls it (none in-repo besides
`DestinationEvaluable`). Low risk, but note it.

### 5.9 The streaming completion-delivery contract must survive

Added post-Semaphore-merge. `ProcessDetailsFactory` evaluates kernel arguments by
building `into(result).async(executor)` streaming wrappers whose `request` delivers
`(value, completion)` to a `CompletionConsumer`; `AcceleratedOperation.apply` then
merges those completions into the kernel's dependency chain. Whatever `into(d)`
becomes under this refactor, the resulting object must still implement
`StreamingEvaluable.request` with completion delivery — otherwise per-argument
host waits (and, on Metal, per-argument command-buffer commits) return. The
commit-free delivery guarantee is pinned by
`SemaphoreChainBatchingTest.completionConsumerDeliveryAvoidsCommit`; that test must
stay green through every step.

---

## 6. Phased plan (each step independently buildable + testable)

Ordered to keep behavior identical until the final flip, so a regression is
attributable to one step.

1. **Introduce the hybrid destination producer** (or teach
   `MemoryDataDestinationProducer` to implement `ProducerArgumentReference`
   returning 0) *without* changing any indices yet. Prove it binds from `args[0]`
   when present and auto-creates when absent, behind a feature flag, with `output`
   still present and preferred. No externally visible change.

2. **Apply the `+1` input-index shift at the `Input.value`/`v` chokepoint**
   (Option A), fix the one direct-construction user site
   (`WaveDataProviderAdapter`), and complete the bare-passthrough audit (§5.1).
   Signatures change here (§5.2) — expect and accept a one-time cache rebuild.

3. **Re-plumb the delegation.** `AcceleratedComputationEvaluable.evaluate/request`
   prepend `null`; `into(d)`/`DestinationEvaluable`/`HardwareEvaluable.withDestination`
   prepend `d`. Route both through `apply(argsWithSlot0)` while `output` still exists
   but is always the auto-created/`args[0]` value — i.e. make `output` redundant.
   Preserve the `CompletionConsumer` fast paths in both `request` implementations
   (§5.9) exactly as they are.

4. **Fold the copy-out policy** in `AcceleratedOperation.apply` from `output` onto
   `args[0]`; adjust the `ProcessDetailsFactory.init` guard (§5.5). Delete the
   `i == outputArgIndex && output != null` override branch. The chained
   `Submittable.submit` structure and the `Semaphore.all` argument-completion merge
   are untouched — only the policy key changes.

5. **Delete `output`.** Change `apply` to `apply(Object[] args, Semaphore)`; remove
   the parameter from `getProcessDetails`, `ProcessDetailsFactory.init`, and the two
   `DestinationEvaluable` call sites. `DestinationEvaluable`'s kernel branch becomes
   "prepend, apply" — the class remains as the streaming adapter (§1.3, §5.9).

6. **Cleanup.** Remove now-dead code (`enableStrictSideEffects` semantics may
   simplify; the output-keyed `getPostprocessOperations` overload may fold into the
   null-keyed form). Re-audit `into` implementations for any that only existed to
   feed `output`.

Steps 1–2 are the reversible "prove the primitive" moves; step 5 is the
irreversible flip.

## 7. Validation gate (every step)

1. `mvn clean install -DskipTests` must succeed (per CLAUDE.md; not `compile` alone).
2. `mcp__ar-test-runner__start_test_run` on each touched module. Prioritize:
   - argument-binding / pass-through: tests exercising `Input.value`/`v` and
     multi-arg `evaluate`.
   - **in-place & aggregation** (§5.3): the `x = x + y` / add-in-place and
     argument-aggregation tests — this is where copy-out regressions hide.
   - `into(...)` round-trips: assert `into(d).evaluate(...) == d` and plain
     `evaluate(...)` allocates fresh.
   - instruction reuse: signature-based caching tests (§5.2), confirm reuse still
     fires post-shift.
   - streaming delivery: `SemaphoreChainBatchingTest` (chaining, attribution, and
     commit-free `CompletionConsumer` delivery) must stay green (§5.9).
3. **Commit-cause regression check** (new since the Semaphore merge): run
   `CommitCauseMeasurementTest` before and after each behavior-affecting step and
   compare the breakdown (commits/step, host vs. `MAX_OPEN`, top requesters). The
   copy-out policy is Metal-batching-sensitive; a regression here can show up as a
   changed `mtlBlitCopy`/host-wait profile while correctness tests stay green. See
   `base/hardware/docs/COMMIT_ATTRIBUTION.md`.
4. `mcp__ar-build-validator__start_validation` — no new checkstyle/code-policy
   violations.
5. Run on **both** native and Metal contexts where possible — the copy-out /
   command-buffer batching behavior (§5.3) has a documented Metal-specific history;
   a green native run is not sufficient evidence for the aggregation path.

## 8. Recommendation

Proceed to a prototype of **steps 1–2 only**, behind a flag, and measure: does the
hybrid producer + factory shift preserve every argument-binding, in-place, and
instruction-reuse test unchanged? If yes, the central hazards (§5.1–5.4) are
retired empirically and the remaining steps are mechanical. If the bare-passthrough
audit (§5.1) turns up real standalone usage, resolve that policy question before
committing to Option A.

The change is well-scoped and the payoff is real — one dispatch path, one
destination-binding site, and deletion of the "output variable is special" logic —
but it is **not** a trivial signature edit: the value is entirely in getting the
single `+1` offset and the copy-out re-keying exactly right, which is why the plan
front-loads proving those two things.

---

## Appendix — key code references

| Concern | Location |
|---|---|
| `apply(output, args, dependsOn)` + chained copy-in/out + completion merge | `AcceleratedOperation.apply` (base/hardware) |
| `output` override + arg binding | `ProcessDetailsFactory.init` |
| Destination auto-create (`createDestination`) + streaming arg wiring | `ProcessDetailsFactory.construct` |
| `evaluate`/`request` delegate `apply(null, args)` + result extraction | `AcceleratedComputationEvaluable.evaluate`, `.request` |
| `outputArgIndex` discovery at compile | `AcceleratedComputationEvaluable.postCompile` |
| Input 0 = destination convention | `ProducerComputationBase.getOutputVariable`, `.signature` |
| Destination injection (main site) | `CollectionProducerComputationBase` constructor |
| Output variable written in scope | `KernelTraversalOperation.getScope` (and the ~7 classes in §1.2) |
| `PassThroughProducer` (`get`, `argIndex`, `signature`, `Expectation`) | `PassThroughProducer` |
| `Input.value`/`v` factory (shift point) | `Input.value` |
| `MemoryDataDestination(Producer)` (auto-create + `into`) | `MemoryDataDestination`, `MemoryDataDestinationProducer` (base/hardware mem) |
| Non-null `apply` callers + streaming delivery | `DestinationEvaluable.evaluate`, `.request` |
| `into` → `apply` for collections | `HardwareEvaluable.withDestination`; `CollectionProducerComputationBase.get` |
| Copy-out slice-skip policy | `MemoryDataArgumentMap.getPostprocessOperations` |
| One user `new PassThroughProducer` | `WaveDataProviderAdapter` (engine/audio) |
| Completion-delivery contract + commit-free guarantee | `CompletionConsumer` (base/code); `SemaphoreChainBatchingTest.completionConsumerDeliveryAvoidsCommit` |
