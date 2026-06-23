# `Assignment.get()` compilation path — `addInPlace` (verified asset)

Authoritative, tool-produced record of how `addInPlace`'s `Assignment` produces an
`Evaluable`-backed result instead of compiling its own kernel. Produced by the
`AR_TRACE_ASSIGN` diagnostic in `Assignment.get()` (logs which branch fired, the
destination/value supplier classes, the resolved value `Evaluable`, the short-circuit
decision, the returned object's class, and the call stack). No inference — every field
below is copied from the run output.

## How to regenerate

```
mcp__ar-test-runner__start_test_run
  module: engine/utils
  test_methods: [{class: CollectionMathTests, method: addInPlace}]
  jvm_args: [-DAR_HARDWARE_DRIVER=native, -DAR_HARDWARE_ARGUMENT_AGGREGATION=enabled, -DAR_TRACE_ASSIGN=1]
```
Then `get_run_output` filtered on `assignTrace`. (Run id captured here: `a6ad9515`.)

## Verified trace (verbatim)

```
assignTrace path=DestinationEvaluable/short-circuit out=org.almostrealism.collect.computations.ReshapeProducer in=org.almostrealism.collect.computations.CollectionAddComputation providerOut=true ev=org.almostrealism.collect.computations.DefaultCollectionEvaluable shortCircuit=true result=org.almostrealism.hardware.DestinationEvaluable
    at org.almostrealism.hardware.computations.Assignment.get(Assignment.java:393)
    at org.almostrealism.hardware.computations.Assignment.get(Assignment.java:187)
    at org.almostrealism.collect.computations.test.CollectionMathTests.addInPlace(CollectionMathTests.java:535)
    ...
```

## Step by step (each step grounded in the trace above + the cited line)

1. `CollectionMathTests.addInPlace` (line 535) calls `.get()` on the (optimized) `Assignment`.
   Stack confirms entry at `Assignment.get` (the `Supplier`-bridge frame at `:187`, the actual
   return at `:393`).
2. `Assignment.get()` reads its two inputs (`Assignment.java:341-342`):
   - `out` (destination, input 0) = **`ReshapeProducer`** (this is `traverseEach(p(a))`).
   - `in` (value, input 1) = **`CollectionAddComputation`** (the `add`).
3. Shape check (`:344-355`) passes (destination and value have equal size/count), so it does
   **not** take the shape-mismatch `super.get()` path.
4. **`ev = in.get()` (`:357`) compiles the value — the `add` — into an `Evaluable`:**
   `ev` = **`DefaultCollectionEvaluable`**. This is the step where "compiling" yields an
   `Evaluable`: it is the *value* computation (`CollectionAddComputation`), not the
   `Assignment`, that is turned into an `Evaluable` here.
5. `Computable.provider(out)` (`:361`) = **true** (`providerOut=true`) — the destination is a
   provider, so the provider-optimization block is entered.
6. `destination = out.get().evaluate()` (`:362`) resolves the destination `MemoryBank`.
7. The value is not a constant source (`:368-379` not taken — no `JVMMemory.fill`).
8. `shortCircuit = ev instanceof AcceleratedOperation || ev instanceof Provider` (`:383`) =
   **true** (`shortCircuit=true`) — i.e. `DefaultCollectionEvaluable` satisfies that test.
9. **`return new DestinationEvaluable(ev, destination)` (`:386`):** `result` =
   **`DestinationEvaluable`**.

## What this establishes (read directly off the trace, not inferred)

- `Assignment.get()` did **not** compile the `Assignment`'s own scope into a kernel for this
  case. It took the short-circuit branch.
- The object returned from `get()` is a **`DestinationEvaluable`** — a `Runnable` that wraps
  (a) the value's compiled `Evaluable` (`DefaultCollectionEvaluable`, the `add`) and (b) the
  resolved destination memory, and on `run()` evaluates the value into that destination.
- Therefore the kernel that actually runs is the **value's** (`add`'s) evaluable — consistent
  with `docs/plans/ARGVAR_addInPlace.md`, where the compiled scope is `f_collectionAddComputation_3`
  and `_v0`'s producer is the `add`'s `MemoryDataDestinationProducer`.
- So "compiled an `Assignment` but ended up with an `Evaluable`" = the
  `DestinationEvaluable/short-circuit` branch of `Assignment.get()`: it compiles the *value*
  to a `DefaultCollectionEvaluable` and returns a `DestinationEvaluable` rather than producing
  an `Assignment`-scope kernel.

## Runtime execution of the returned `DestinationEvaluable` (verified)

When the returned `DestinationEvaluable` is `run()`/`evaluate()`d, the `AR_TRACE_DESTEVAL`
diagnostic in `DestinationEvaluable.evaluate()` recorded (run `ef7b2e8a`, same flags plus
`-DAR_TRACE_DESTEVAL=1`):

```
destEvalTrace operation=org.almostrealism.collect.computations.DefaultCollectionEvaluable branch=AcceleratedOperation.apply destinationClass=org.almostrealism.collect.PackedCollection destMemId=1968969629 destOffset=0 destLength=10 argCount=0
```

Read directly off this:
- `operation` (the wrapped value evaluable) = `DefaultCollectionEvaluable`, and it is an
  `AcceleratedOperation` → the **`AcceleratedOperation.apply` branch** is taken
  (`DestinationEvaluable.evaluate` line 281-291): `((AcceleratedOperation) operation).apply(destination, args)`.
- `destination` = a `PackedCollection`, `destMemId` = 1968969629, `destOffset` = 0,
  `destLength` = **10** — i.e. the in-place target `a` (length 10).
- `argCount` = 0 (no extra args passed to `apply`; the add's own inputs are bound internally).

Cross-referenced with the `argvarDump` from the same run (kernel `f_collectionAddComputation_3`):
- `_v0` = the add's `MemoryDataDestinationProducer` output; at runtime `apply(destination=a)`
  binds that output to `a` (memId 1968969629).
- `_v1` = `AggregateProducer` → `Bytes` (memId 1308640970 this run), `memLength` 20 — the
  aggregate holding the read inputs `a`@0..9 and `b`@10..19.

### Net (verified, no inference)

`addInPlace`'s `a = a + b`, with aggregation on, runs as: the **add** (`DefaultCollectionEvaluable`,
an `AcceleratedOperation`) executes via `apply(destination = a)`, writing its kernel output
`_v0` **directly into `a`** (length 10, memId 1968969629), while reading both operands from the
**aggregate** `_v1` (`Bytes` length 20). There is **no separate "assign the output back to `a`"
step** — the add's output is redirected to `a` via the `apply(destination, ...)` output bank.
So `a (real) = _v1[b-slice] + _v1[a-slice]`, where `_v1[a-slice]` is a copy of `a`'s prior
contents placed there by copy-in.

## Open / not captured here

- Whether `DefaultCollectionEvaluable` is an `AcceleratedOperation` or a `Provider` (which of
  the two made `shortCircuit` true in `get()`) — the `get()` trace records only that the
  disjunction is true; the `destEvalTrace` shows it took the `AcceleratedOperation.apply`
  branch, so it is (at least) an `AcceleratedOperation`.
- Whether/how the aggregation **copy-out** (when enabled) interacts with this
  `apply(destination=a)` execution — i.e. the actual mechanism by which copy-both regressed
  `addInPlace` — is NOT captured by any asset yet. Requires tracing the copy operations and
  `a`'s memory before/after under copy-out.
