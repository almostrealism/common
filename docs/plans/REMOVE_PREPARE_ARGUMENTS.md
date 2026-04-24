# Plan: Remove `ScopeLifecycle.prepareArguments`

## Background

`ScopeLifecycle.prepareArguments(ArgumentMap)` is called by every
compilation driver before `prepareScope`. The intent appears to have been a
two-phase compilation:

1. **Registration phase** (`prepareArguments`): walk the computation tree and
   pre-register every input with the `ArgumentMap`, so the map could make
   global decisions (e.g. aggregation grouping) before any variable lookups.
2. **Query phase** (`prepareScope` → `assignArguments`): ask the map for an
   `ArrayVariable` per input, by then fully informed.

In practice, no `ArgumentMap` implementation ever made global decisions in
its `add()` method. `MemoryDataArgumentMap` and friends decide lazily inside
`get(key, p)` during `assignArguments`. The registration phase has no
observable effect.

## Evidence

`ArgumentMap.add(K)` has **one** implementation across the entire codebase:

```java
// base/code/src/main/java/io/almostrealism/code/SupplierArgumentMap.java:96
@Override
public void add(Supplier key) { }
```

It is **never overridden**. `MemoryDataArgumentMap`, `ProviderAwareArgumentMap`,
and `OutputVariablePreservationArgumentMap` all inherit the empty `add`.

The 19 implementations of `prepareArguments` in the codebase do one of:
- `{ }` (the `ScopeLifecycle` default)
- recurse over inputs and call `super.prepareArguments(...)`
- `getInputs().forEach(map::add)` (which is a no-op, see above)
- `map.add(this)` (only `PassThroughProducer.prepareArguments`; still no-op
  in `add`)

So the entire `prepareArguments` traversal walks the computation tree on
every compilation and produces zero side effects. It was the wrong layer for
the cache-invalidation fix in
`feature/skytnt-midi-model`'s `ComputationBase.prepareScope` change for
exactly this reason: the path is never invoked in a way that matters.

See companion design notes:
- `studio/compose/docs/argument-cache-leak.md` — the bug it failed to catch.
- `ComputationBase.prepareScope` (current code) — where the actual cache
  invalidation now lives.

## Goal

Delete `prepareArguments` from `ScopeLifecycle` and all overrides plus call
sites. Result: smaller compilation surface, one less method to keep in
sync, no more confusion about which phase should host
cache-invalidation logic.

## Risks

- **External consumers.** `ScopeLifecycle` is in `io.almostrealism.code`,
  which is a public API surface. Any project outside this repo that
  implements `ScopeLifecycle` and overrides `prepareArguments` will
  silently lose the call. Mitigation: the method is currently a no-op on
  every codebase impl, so an external impl that relied on it being called
  was relying on something that already does nothing — but the API removal
  itself is binary-incompatible.
- **Hidden side effects.** Need to verify that no override does anything
  beyond walk-and-no-op. The audit below covers all 19 in this repo.
- **Future intent.** If someone is planning to add a registration-phase
  optimization, removing the hook makes that harder. Counter: the hook
  has been there long enough to be exploited, and isn't.

## Audit (19 in-tree implementations)

All confirmed walk-and-no-op or pure-recursion as of today:

```
base/code/src/main/java/io/almostrealism/code/ScopeLifecycle.java          (default no-op)
base/code/src/main/java/io/almostrealism/code/ComputationBase.java         (recurse + map.add)
base/code/src/main/java/io/almostrealism/code/AdaptProducer.java           (recurse)
base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java
base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedComputationOperation.java
base/hardware/src/main/java/org/almostrealism/hardware/OperationList.java
base/hardware/src/main/java/org/almostrealism/hardware/PassThroughProducer.java   (super + map.add(this))
base/hardware/src/main/java/org/almostrealism/hardware/instructions/ComputationScopeCompiler.java
base/hardware/src/main/java/org/almostrealism/hardware/computations/Assignment.java
base/hardware/src/main/java/org/almostrealism/hardware/computations/Loop.java
base/hardware/src/main/java/org/almostrealism/hardware/computations/Periodic.java
compute/algebra/src/main/java/org/almostrealism/algebra/computations/ProducerWithRankAdapter.java
compute/algebra/src/main/java/org/almostrealism/algebra/computations/Switch.java
compute/algebra/src/main/java/org/almostrealism/collect/computations/CollectionProducerComputationBase.java
compute/algebra/src/main/java/org/almostrealism/collect/computations/ConstantRepeatedDeltaComputation.java
compute/algebra/src/main/java/org/almostrealism/collect/computations/ReshapeProducer.java
compute/algebra/src/main/java/org/almostrealism/collect/computations/TraversableDeltaComputation.java
compute/time/src/main/java/org/almostrealism/time/TemporalRunner.java
domain/color/src/main/java/org/almostrealism/color/computations/GeneratedColorProducer.java
```

`ScopeLifecycle.prepareArguments(Stream<?>, ArgumentMap)` static helper at
`base/code/src/main/java/io/almostrealism/code/ScopeLifecycle.java:80` is
the recursion driver and goes with the method.

## Plan

Three commits, each independently revertible.

### 1. De-fang `ComputationBase.prepareArguments`

Replace the body with `{ }` (or remove the override entirely so it inherits
the default no-op). Run the full test suite + build validator. If clean,
the recursion was load-bearing nowhere.

This is the lowest-risk first move — it removes the only override that
populates anything (`map::add` for inputs), proving operationally that the
recursion's effect was zero.

### 2. Remove all override sites

Delete the override in every class on the audit list. Each is one stanza
removed; no behavior change since the method is a no-op end-to-end. Run
tests after each module's batch.

### 3. Delete the method from `ScopeLifecycle`

Remove the `default void prepareArguments(ArgumentMap map)` declaration
and the `static prepareArguments(Stream<?>, ArgumentMap)` helper. Remove
the call sites in:
- `AcceleratedOperation.prepareScope` (line 350: `prepareArguments(argumentMap);`)
- Any other driver that originates the call.

After this commit, `ArgumentMap.add` may also become unused — check and
delete if so.

## Validation Gate

Each step must pass:
1. `mvn install -DskipTests`
2. Full test suite (use `mcp__ar-test-runner` on each module touched).
3. `mcp__ar-build-validator__start_validation` — no new violations.
4. Run the `ProducerEvalCachesKernelTest` — every test still passes (the
   `prepareScope` fix is the actual cache-invalidation; removing
   `prepareArguments` must not regress it).

## When to Resume

After confirming that the `ComputationBase.prepareScope` fix on
`feature/skytnt-midi-model` resolves the audio-generation high-pass-filter
artifact end-to-end. Until then this is paused — do not start step 1 while
the cache-leak fix is still being validated, since regressions would be
ambiguous.
