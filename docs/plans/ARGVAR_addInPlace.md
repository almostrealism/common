# ArrayVariable structure — `addInPlace` (verified asset)

Authoritative, tool-produced record of the exact `ArrayVariable` behind every variable
reference in the generated kernel for `CollectionMathTests.addInPlace`. Produced by the
`AR_DUMP_ARGVARS` diagnostic in `ScopeInstructionsManager.getScope()` (dumps every
`Scope` argument in `argArr` order, with producer / delegate / resolved memory). No
inference — every field below is copied from the run output.

## How to regenerate

```
mcp__ar-test-runner__start_test_run
  module: engine/utils
  test_methods: [{class: CollectionMathTests, method: addInPlace}]
  jvm_args: [-DAR_HARDWARE_DRIVER=native, -DAR_HARDWARE_ARGUMENT_AGGREGATION=enabled, -DAR_DUMP_ARGVARS=1]
```
Then `get_run_output` filtered on `argvarDump`. (Run id captured here: `92fdb0cf`.)
The test source: `a = a + b` via
`a(traverseEach(p(a)), add(traverseEach(p(a)), traverseEach(p(b)))).optimize().get()`,
sizes: `a`,`b` are `PackedCollection(shape(10))`.

## Generated kernel

Function: `f_collectionAddComputation_3` (the fused add-into-assignment), 2 kernel buffers:

```c
double *_v0 = ((double *) argArr[0]);
double *_v1 = ((double *) argArr[1]);
for (long long global_id = global_index ; global_id < global_total; global_id += 10) {
  _v0[global_id + _v0Offset] = _v1[global_id + _v1Offset + 10] + _v1[global_id + _v1Offset];
}
```

Variable references appearing in the code: `_v0`, `_v1` (the latter at offsets `+0` and `+10`).

## Argument structure (verbatim from `argvarDump scope=f_collectionAddComputation_3 argCount=2`)

### `argArr[0]` → `_v0`  (the write target / output)
- `expect` = **WILL_EVALUATE**
- `delegate` = **null**  (it is a root buffer, not a view; `delegateOffset` therefore inapplicable)
- `totalOffset` = 0
- `arraySize` = null
- `physicalScope` = GLOBAL
- `producer` = **`org.almostrealism.hardware.mem.MemoryDataDestinationProducer`**
- `producer.describe` = `"p(10) + p(10) | 10x (fixed) (10)"`
- `producer.getDelegate()` (`producerDelegate[0]`) = **`org.almostrealism.collect.computations.CollectionAddComputation`**, describe `"p(10) + p(10) | 10x (fixed) (10)"` — i.e. the `add` computation itself (`MemoryDataDestinationProducer` is constructed as `new MemoryDataDestinationProducer<>(this, ...)` in `CollectionProducerComputationBase:201`, so its delegate is the owning computation). The chain stops there (`CollectionAddComputation` is not itself `Delegated`).
- `producer.get()` = **`MemoryDataDestination`** (NOT a `Provider`) → no resolvable memory at compile time

### `argArr[1]` → `_v1`  (the aggregate buffer holding the read inputs)
- `expect` = **WILL_EVALUATE**
- `delegate` = **null**  (it is the aggregate root)
- `totalOffset` = 0
- `arraySize` = null
- `physicalScope` = GLOBAL
- `producer` = **`org.almostrealism.hardware.mem.MemoryDataArgumentMap$AggregateProducer`**
- `producer.get()` = **`Provider`** → `providerValue` = **`Bytes`**, `memId` = 1158119142, `memOffset` = 0, `memLength` = **20**

## What this establishes (read directly off the table, not inferred)

- The kernel binds exactly two buffers. `_v0` is the output, backed by the `add`'s
  `MemoryDataDestinationProducer` (a dynamic destination whose `get()` is a
  `MemoryDataDestination`, not a `Provider`).
- `_v1` is one consolidated `Bytes` of length 20 produced by
  `MemoryDataArgumentMap$AggregateProducer`. The two read inputs (`a` at element offset 0,
  `b` at element offset 10 — visible as `_v1[..]` and `_v1[..+10]` in the code) are folded
  into this single buffer; they are NOT separate `argArr` entries (they are delegate views
  into `_v1`, so they do not appear in the argument list).
- The in-place value `a` is therefore represented by two distinct kernel buffers in this
  compilation: `_v0` (output, via `MemoryDataDestinationProducer`) and the offset-0 region
  of `_v1` (input, via `AggregateProducer`).

## Raw dump

```
argvarDump scope=f_collectionAddComputation_3 argCount=2
  [0] name=_v0 expect=WILL_EVALUATE delegate=null delegateOffset=io.almostrealism.expression.IntegerConstant@5a4 totalOffset=0 arraySize=null physicalScope=GLOBAL
      producer=org.almostrealism.hardware.mem.MemoryDataDestinationProducer describe="p(10) + p(10) | 10x (fixed) (10)"
        producerDelegate[0]=org.almostrealism.collect.computations.CollectionAddComputation describe="p(10) + p(10) | 10x (fixed) (10)" producer.get=MemoryDataDestination
  [1] name=_v1 expect=WILL_EVALUATE delegate=null delegateOffset=io.almostrealism.expression.IntegerConstant@5a4 totalOffset=0 arraySize=null physicalScope=GLOBAL
      producer=org.almostrealism.hardware.mem.MemoryDataArgumentMap$AggregateProducer producer.get=Provider providerValue=Bytes memId=1158119142 memOffset=0 memLength=20
```

(`delegateOffset` prints as `IntegerConstant@<hash>` because both args have `delegate=null`,
so the offset is unused; the dump tool can be extended to print the resolved offset value
for kernels that actually use delegate views.)
