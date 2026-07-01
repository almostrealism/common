# Plan: Remove the `output` (destination) argument from `AcceleratedOperation.apply`

## TL;DR

`AcceleratedOperation.apply(MemoryBank output, Object[] args, ...)` accepts an
optional runtime destination. That single extra parameter creates a *second*,
parallel way to run every kernel and forces the argument at
`getOutputArgumentIndex()` to be managed differently from all other arguments
(override logic in `ProcessDetailsFactory.init`, a bespoke copy-out side-effect
policy in `AcceleratedOperation.apply`, and the `DestinationEvaluable` wrapper
that exists mostly to feed it).

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

---

## 1. Background — how the destination works today

### 1.1 The destination is input index 0 of every `ProducerComputation`

`ProducerComputationBase` fixes a convention: **argument 0 is the output/destination.**

```java
// base/code/src/main/java/io/almostrealism/code/ProducerComputationBase.java:56
@Override
public Variable getOutputVariable() { return getArgument(0); }

// :63
public Evaluable<O> getDestination() { return (Evaluable<O>) getInputs().get(0).get(); }
```

Both `signature()` (`:80`, `skip(1)`) and `description()` (`:99`, `skip(1)`)
deliberately **exclude input 0** from their output.

The destination producer is injected at construction. For the overwhelming
majority of computations that is:

```java
// compute/algebra/src/main/java/org/almostrealism/collect/computations/CollectionProducerComputationBase.java:200-203
List<Producer<PackedCollection>> inputs = new ArrayList<>();
inputs.add(new MemoryDataDestinationProducer<>(this, this::adjustDestination));
inputs.addAll(List.of(arguments));
setInputs(inputs);
```

Other injection sites: `PassThroughProducer` (self-referential destination,
`PassThroughProducer.java:322`) and `KernelTraversalOperation.java:108`.

### 1.2 The destination is *baked into the generated kernel*, not just a runtime value

This is the subtlety that makes the destination more than "just another arg."
During scope generation the output variable is a **written** `ArrayVariable`:

```java
// base/hardware/src/main/java/org/almostrealism/hardware/kernel/KernelTraversalOperation.java:158-162
ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();
for (int i = 0; i < getMemLength(); i++) {
    scope.getVariables().add(output.reference(e(i)).assign(expressions.get(i)));
}
```

The same pattern (`getOutputVariable()` cast to `ArrayVariable`, used to form the
destination expression the kernel assigns into) appears in
`CollectionProducerComputationAdapter.java:313`, `PackedCollectionMap.java:131`,
`RepeatedProducerComputation.java:353`, `LoopedWeightedSumComputation.java:220`,
`AcceleratedTimeSeriesValueAt.java:115`, and `DefaultEnvelopeComputation.java:55`.

**Consequence for the refactor:** whatever we make input 0 be, it must still
resolve to an `ArrayVariable` that the compiler can emit assignments into. A
`PassThroughProducer` already satisfies this — it produces an `ArrayVariable` via
`prepareScope`/`getArgument` — but its `Expectation.NOT_ALTERED` (`PassThroughProducer.java:403`)
signals "read-only," which is wrong for a destination. See §5.4.

### 1.3 Two runtime paths: `evaluate` and `into`

**Path A — `evaluate(args)` (no explicit destination).** The output is *already*
`null` here; the destination is auto-created:

```java
// base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedComputationEvaluable.java:412-425
public T evaluate(Object... args) {
    confirmLoad();
    int outputArgIndex = getInstructionSetManager().getOutputArgumentIndex(getExecutionKey());
    int offset       = getInstructionSetManager().getOutputOffset(getExecutionKey());

    AcceleratedProcessDetails process = apply(null, args);   // <-- output is ALWAYS null
    process.awaitReady();
    waitFor(process.getSemaphore());

    T result = postProcessOutput((MemoryData) process.getOriginalArguments()[outputArgIndex], offset);
    return validate(result);
}
```

The result is extracted from the *argument slot* at `outputArgIndex`, not from a
returned value. Because `output` is null, `ProcessDetailsFactory` leaves that slot
to be produced by input 0's `MemoryDataDestinationProducer`, whose evaluable is a
`MemoryDataDestination` — and the factory calls `createDestination(size)` on it to
allocate a correctly-sized buffer (`ProcessDetailsFactory.java:520-527`).

**Path B — `into(d).evaluate(args)` (explicit destination).** `into` wraps the
kernel in a `DestinationEvaluable`, which is the *only* place a non-null `output`
ever reaches `apply`:

```java
// base/hardware/src/main/java/org/almostrealism/hardware/DestinationEvaluable.java:281-283
} else if (operation instanceof AcceleratedOperation) {
    AcceleratedProcessDetails details = ((AcceleratedOperation) operation)
        .apply(destination, Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
```

The full chain for a collection computation is:
`producer.get()` → `HardwareEvaluable` (`CollectionProducerComputationBase.java:664`)
→ `.into(d)` → `HardwareEvaluable.withDestination` → `new DestinationEvaluable(kernel, d)`
(`HardwareEvaluable.java:309`) → `.evaluate(args)` → `apply(d, args)`.

### 1.4 What `output` actually does inside `apply` / `ProcessDetailsFactory`

Two things, and only two:

**(a) It overrides the value of the output argument slot.**

```java
// base/hardware/src/main/java/org/almostrealism/hardware/ProcessDetailsFactory.java:382-398
if (outputArgIndex < 0 && output != null) {
    throw new UnsupportedOperationException();                 // no output slot, but one was supplied
}
...
for (int i = 0; i < arguments.size(); i++) {
    ...
    } else if (i == outputArgIndex && output != null) {
        kernelArgs[i] = output;                                // <-- override slot with caller's buffer
        continue i;
    }
    int refIndex = getProducerArgumentReferenceIndex(arguments.get(i));
    if (refIndex >= 0) {
        kernelArgs[i] = (MemoryData) args[refIndex];           // normal arg binding
    }
    ...
}
```

**(b) It selects the aggregation copy-out policy.**

```java
// base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java:569-618
boolean aggregating = argumentMap != null && argumentMap.hasReplacements();
boolean aggregateCopyOut = aggregating
        && (output == null || MemoryDataArgumentMap.enableStrictSideEffects);
...
if (aggregateCopyOut) {
    (output == null ? argumentMap.getPostprocessData()
                    : argumentMap.getPostprocessData((MemoryData) output)).get().run();
}
```

The policy: `output == null` copies every aggregated input slice back; `output != null`
(default) copies none (the caller's explicit buffer is taken to be the only result of
interest); `output != null` with strict side-effects copies all *except* the slice that
aliases `output` (so an in-place `x = x + y` is not clobbered). See
`MemoryDataArgumentMap.java:83-91, 314-329`.

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
`DestinationEvaluable` kernel branch (and, arguably, `DestinationEvaluable` itself)
disappears because `into(d)` becomes "prepend `d` to args," which any evaluable can do.

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
// ... second pass: kernelArgs[i]==null  ==>  createDestination(size)  (unchanged, :520-527)
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
  (`ProcessDetailsFactory.java:494-502, 520-527`) would see the prepended array
  with un-shifted indices — a latent mismatch. Works only because
  `PassThroughProducer`s are resolved via `refIndex` and never enter
  `evaluateAhead`; fragile if that invariant ever changes.

- **Option C — shift in the delegation, store shifted.** `evaluate`/`into`
  prepend the slot; `PassThroughProducer` stores the already-shifted apply-space
  index. Same net effect as A but the shift is explicit at every construction site
  (touches all 287 `v(...)` calls). Rejected — too broad, per the "never reference
  version numbers"-style aversion to sweeping mechanical edits.

**Recommendation: Option A**, gated on the bare-passthrough audit. It concentrates
the change in one factory method and keeps the public `v(n)` = "n-th input"
mental model intact.

---

## 3. Evidence / blast radius (audited)

### 3.1 `apply(output, …)` call sites — tiny

Exhaustive search of the repo (all modules, incl. tests, excl. `/target/`):

**Non-null `output` — 2 sites, both in `DestinationEvaluable`:**
- `DestinationEvaluable.java:283` (`evaluate`)
- `DestinationEvaluable.java:343` (`request`)

**Null `output` — 4 sites (internal):**
- `AcceleratedOperation.java:357` (`run`)
- `AcceleratedOperation.java:383` (`submit`)
- `AcceleratedComputationEvaluable.java:420` (`evaluate`)
- `AcceleratedComputationEvaluable.java:454` (`request`)

**Overrides of `apply` — 0.** No subclass overrides it anywhere.

### 3.2 Argument-index creation is a chokepoint

- `new PassThroughProducer(...)` — **7 sites total**, 5 of them inside
  `Input.java`/`PassThroughProducer.java` themselves. **Exactly one user site**:
  `engine/audio/.../WaveDataProviderAdapter.java:63-65` (indices 0, 1, 2).
- `Input.value(...)` — 30 call sites. `v(...)` (2-arg form) — ~287 call sites, but
  all funnel through `Input.value` → `PassThroughProducer`.
- `getReferencedArgumentIndex()` has **4 consumers**: `ProcessDetailsFactory.java:604,609`
  (binding), `AlgebraFeatures.java:447` and `MemoryDataArgumentMap.java:148` (matching).
  A *uniform* `+1` shift preserves all matching semantics.

### 3.3 `getOutputVariable` / `getOutputArgumentIndex` / `into`

- `getOutputVariable()` — default `null` in `OutputSupport`/`ComputationBase`;
  the real definition is `ProducerComputationBase:56` (`getArgument(0)`);
  `AcceleratedComputationOperation:689` delegates to the computation. ~7 computations
  cast it to `ArrayVariable` in `getScope` (see §1.2). All keep working as long as
  input 0 remains an `ArrayVariable`-producing writable slot.
- `getOutputArgumentIndex()` — abstract in `AcceleratedOperation:286`; **one**
  concrete impl (`AcceleratedComputationOperation:410`, delegating to
  `ScopeInstructionsManager.getOutputArgumentIndex`, returns `-1` when unset).
  Unaffected: this is the *kernel* argument index (discovered at `postCompile`,
  `AcceleratedComputationEvaluable.java:333-363`), a different index space from the
  evaluate-arg index and orthogonal to removing `output`.
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
- Collapse or remove `DestinationEvaluable`'s kernel branch; `into(d)` becomes
  "prepend `d`."
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
  never through `ProcessDetailsFactory`'s `evaluateAhead` branch (they are today,
  `ProcessDetailsFactory.java:400-413`), so Option A's array never reaches their
  raw `get()`.

### 5.2 Instruction-cache signature stability

`PassThroughProducer.signature()` embeds the index: `param(index{shape})`
(`PassThroughProducer.java:530`). Shifting indices changes those signature strings,
so **every cached kernel signature changes once**. This is benign — the cache is
process-local and keyed by signature (per `INSTRUCTION_CACHING.md`), and the shift
is *uniform*, so two computations equal before the change remain equal after.

The destination itself is **not** a new signature risk: `ProducerComputationBase.signature()`
already `skip(1)`s input 0, so replacing `MemoryDataDestinationProducer` with a
hybrid `ProducerArgumentReference` at input 0 does not newly expose it to the
signature. (Independent, pre-existing hazard flagged by the consultant: producers
over *aggregation targets* can return `null` signatures and disable reuse — unrelated
to this change but worth not perturbing.)

### 5.3 Copy-out side-effect policy must move, not vanish

The `aggregateCopyOut` / `getPostprocessData(skipOutput)` logic
(`AcceleratedOperation.java:569-618`, `MemoryDataArgumentMap.java:314-329`) is
correctness-critical for in-place `x = x + y` and for Metal batching behavior
(there is prior CI history here — the `feature/argument-prep-assignment` Metal
"produces 0.0" investigations). Re-keying `output` → `args[0]` is mechanical, but
this is the highest-value area to cover with existing aggregation/in-place tests
before and after.

### 5.4 The destination slot is *written*, and `PassThroughProducer` says `NOT_ALTERED`

A `PassThroughProducer` argument is registered with `Expectation.NOT_ALTERED`
(`PassThroughProducer.java:403`); the destination is written by the kernel
(`output.reference(i).assign(...)`). The hybrid destination producer must register
its argument with the correct write expectation (as `MemoryDataDestinationProducer`
effectively does today via `ComputationBase.assignArguments` →
`Expectation.EVALUATE_AHEAD`). Getting the `Expectation` wrong risks the optimizer
eliding or reordering the destination write, or the aggregation copy-out treating it
as read-only. Must be verified against `MemoryReplacementManager` behavior.

### 5.5 Operations without a result

`run()`/`submit()` dispatch side-effecting operations (assignments, `OperationList`
members) with `apply(null, {})` and `outputArgIndex == -1`. Under the proposal these
pass `apply({})` — no slot-0 prepend — so the "args[0] is the destination"
convention must be genuinely opt-in and not assumed by `ProcessDetailsFactory` when
there is no output slot. The existing `if (outputArgIndex < 0 && output != null)
throw` guard (`ProcessDetailsFactory.java:382`) becomes `... && args[0] != null` and
must be reconciled with the prepend so a resultless op is never handed a phantom
slot 0.

### 5.6 `getOriginalArguments()[outputArgIndex]` result extraction

`evaluate` reads the result from the kernel arg slot, not a return value
(`AcceleratedComputationEvaluable.java:424`). This continues to work unchanged: the
destination (whether from `args[0]` or auto-created) lands in that same slot. Worth
an explicit test that `into(d).evaluate(...)` returns `d` and that plain
`evaluate(...)` returns a fresh buffer, both via this extraction path.

### 5.7 `HardwareEvaluable` and the createDestination coupling

`HardwareEvaluable` holds a separate `destination` evaluable used purely for
`createDestination(size)` sizing (`HardwareEvaluable.java:231, 315`;
`CollectionProducerComputationBase.java:664-667` passes `getDestination()`). That is
independent of the `apply` `output` param and should be left intact; only
`HardwareEvaluable.into`/`withDestination` (which build `DestinationEvaluable`) are
re-plumbed.

### 5.8 External API surface

`AcceleratedOperation.apply` is `protected`; `Evaluable.into`/`evaluate` are public
but their contracts are preserved. The signature change to `apply` is
source-incompatible for any out-of-repo subclass that calls it (none in-repo besides
`DestinationEvaluable`). Low risk, but note it.

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

4. **Fold the copy-out policy** in `AcceleratedOperation.apply` from `output` onto
   `args[0]`; adjust `ProcessDetailsFactory.init` guard (§5.5). Delete the
   `i == outputArgIndex && output != null` override branch.

5. **Delete `output`.** Change `apply` to `apply(Object[] args, Semaphore)`; remove
   the parameter from `getProcessDetails`, `ProcessDetailsFactory.init`, and the two
   `DestinationEvaluable` call sites; simplify or remove `DestinationEvaluable`'s
   kernel branch.

6. **Cleanup.** Remove now-dead code (`enableStrictSideEffects` semantics may
   simplify; `getPostprocessData(MemoryData)` may fold into the null-keyed form).
   Re-audit `into` implementations for any that only existed to feed `output`.

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
3. `mcp__ar-build-validator__start_validation` — no new checkstyle/code-policy
   violations.
4. Run on **both** native and Metal contexts where possible — the copy-out /
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

## Appendix — key file references

| Concern | Location |
|---|---|
| `apply(output, args, dependsOn)` + copy-out policy | `base/hardware/.../AcceleratedOperation.java:551-624` |
| `output` override + arg binding | `base/hardware/.../ProcessDetailsFactory.java:335-429` |
| Destination auto-create (`createDestination`) | `base/hardware/.../ProcessDetailsFactory.java:520-527` |
| `evaluate` delegates `apply(null, args)` + result extraction | `base/hardware/.../AcceleratedComputationEvaluable.java:412-460` |
| `outputArgIndex` discovery at `postCompile` | `base/hardware/.../AcceleratedComputationEvaluable.java:333-363` |
| Input 0 = destination convention | `base/code/.../ProducerComputationBase.java:56-97` |
| Destination injection (main site) | `compute/algebra/.../CollectionProducerComputationBase.java:200-203` |
| Output variable written in scope | `base/hardware/.../kernel/KernelTraversalOperation.java:156-165` |
| `PassThroughProducer` (`get`, `argIndex`, `signature`, `Expectation`) | `base/hardware/.../PassThroughProducer.java:311-403, 425-427, 530` |
| `Input.value`/`v` factory (shift point) | `base/hardware/.../Input.java:171-194` |
| `MemoryDataDestination(Producer)` (auto-create + `into`) | `base/hardware/.../mem/MemoryDataDestination.java`, `.../mem/MemoryDataDestinationProducer.java` |
| Non-null `apply` callers | `base/hardware/.../DestinationEvaluable.java:281-283, 341-343` |
| `into` → `apply` for collections | `.../HardwareEvaluable.java:288-310`; `CollectionProducerComputationBase.java:656-667` |
| Copy-out slice-skip policy | `base/hardware/.../mem/MemoryDataArgumentMap.java:83-91, 314-329` |
| One user `new PassThroughProducer` | `engine/audio/.../WaveDataProviderAdapter.java:63-65` |
