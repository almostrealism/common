# How the Signature Was Computed, and How Reuse Was Decided

Companion to `UNSAFE_REUSED_KERNEL.md`. This explains, node by node, the signature that keyed the
reuse of `f_collectionConcatenateComputation_3388` (the kernel whose line 24 hardcodes `16`/`8`/`144`),
and shows precisely why that signature does **not** encode the geometry the kernel depends on.

The Computation is a `CollectionConcatenateComputation` with output shape `(1, 2, 9, 16)`, two operands,
concatenating along its axis. Its two operands, as recorded in the kernel's argument list, are:

- operand A = `delegate(f_collectionAddComputation_3385)`
- operand B = `delegate(f_packedCollectionSubset_3387)`

Both are wrapped in `DelegatedProducer` (the `delegate(...)` and `IsolatedProcess(...)` you see in the
profile tree).

## The signature, computed bottom-up

### 1. Each operand's signature — `DelegatedProducer.signature()`
`base/hardware/.../computations/DelegatedProducer.java:329`

```java
public String signature() {
    if (MemoryDataArgumentMap.isAggregationTarget(op)) return null;        // not taken here
    return "delegate|" + getCountLong() + "|" + isFixedCount();
}
```

So each operand contributes the string **`"delegate|<count>|<isFixedCount>"`** — one integer and one
boolean. The wrapped computation `op` (an add of shape `(1,2,9,8)`, a subset of shape `(1,2,9,8)`)
is collapsed to its **count** and its **fixed-ness**. Its `TraversalPolicy` — the full
`(1, 2, 9, 8)` shape, the dimension along which it will be read, the per-dimension strides — is **not**
in the string. This is a lossy projection of a multi-dimensional shape onto a single number: it cannot
be injective, so two operands with different shapes but the same count map to the *same* signature
fragment.

### 2. The concat's base signature — `ProducerComputationBase.signature()`
`base/code/.../code/ProducerComputationBase.java:79`

```java
List<String> signatures = getInputs().stream().skip(1)         // skip(1) drops the destination buffer
        .map(Signature::of).collect(Collectors.toList());
if (signatures.stream().anyMatch(Objects::isNull)) return null;
String requirements = …compute-requirement names…;
return Signature.md5(getName() + "/" + requirements + "|" + String.join(":", signatures));
```

The tree of nodes that feed the MD5 is therefore exactly:

```
md5(
  getName()                         = the concat function/op name
  + "/" + requirements              = sorted ComputeRequirement names (e.g. "" / "MTL")
  + "|"
  + signature(operand A)            = "delegate|<countA>|<fixedA>"     ← from step 1 (lossy)
  + ":"
  + signature(operand B)            = "delegate|<countB>|<fixedB>"     ← from step 1 (lossy)
)
```

Note `skip(1)`: input 0 is the destination buffer and is excluded; only the two operands feed the hash.

### 3. The collection wrapper adds the output shape — `CollectionProducerComputationBase.signature()`
`compute/algebra/.../CollectionProducerComputationBase.java:765`

```java
public String signature() {
    String signature = super.signature();           // the MD5 from step 2
    if (signature == null) return null;
    return signature + getShape().toStringDetail();  // the OUTPUT shape, e.g. "(1, 2, 9, 16)…"
}
```

### 4. The concat adds its axis — `CollectionConcatenateComputation.signature()`
`compute/algebra/.../CollectionConcatenateComputation.java:324`

```java
public String signature() {
    String signature = super.signature();
    if (signature == null) return null;
    return signature + "{axis=" + axis + "}";
}
```

### 5. The cache key adds the distinct-child count — `ComputationScopeCompiler.signature()`
`base/hardware/.../instructions/ComputationScopeCompiler.java:480`

```java
String signature = getMetadata().getSignature();                 // steps 1–4
if (computation instanceof Process<?,?>) {
    int distinct = ((Process<?,?>) computation).children().collect(Collectors.toSet()).size();
    return signature + "&distinct=" + distinct + ";";
}
```

## The complete set of facts the final cache key encodes

```
md5( name / requirements | delegate|countA|fixedA : delegate|countB|fixedB )
  + outputShape("(1, 2, 9, 16)…")
  + "{axis=" + axis + "}"
  + "&distinct=" + distinctChildCount + ";"
```

Encoded: the op name, compute requirements, the two operands' **counts** and fixed-ness, the **output
shape**, the concat axis, and the number of distinct children.

**Not encoded:** the operands' `TraversalPolicy` shapes. Each operand's shape was reduced to a single
`count` in step 1. The kernel on line 24, however, indexes its operands with `% 144` (per-operand
element count), `* 8` (per-operand contribution along the axis) and `% 16` — geometry that is a
function of the **operand shapes**, not just their counts. Because step 1 keeps only the count, the
cache key cannot distinguish two `CollectionConcatenateComputation`s that share (name, requirements,
operand counts, output shape, axis, distinct-child count) but differ in operand shape. They receive the
**same** key.

## How reuse was decided

`base/hardware/.../AcceleratedComputationOperation.java`:

```java
// getExecutionKey() (≈ line 407)
String signature = getMetadata().getSignature();
if (ScopeSettings.enableInstructionSetReuse && signature != null)
    return new ScopeSignatureExecutionKey(signature);          // key = the signature above

// getInstructionSetManager() (≈ line 372)
if (ScopeSettings.enableInstructionSetReuse && signature != null) {
    instructions = computer.getScopeInstructionsManager(signature, getComputation(), …);
}
```

`DefaultComputer.getScopeInstructionsManager(signature, …)` (`base/hardware/.../DefaultComputer.java`)
keys a **process-wide** cache by that signature string alone:

```java
String cacheKey = Objects.requireNonNull(signature);
return instructionsCache.computeIfAbsent(cacheKey, () -> new ScopeInstructionsManager<>(…));
```

So the decision to reuse is: **compute the signature above; if a `ScopeInstructionsManager` already
exists in `DefaultComputer.instructionsCache` under that exact string, use its already-compiled
`InstructionSet`** (the kernel of `UNSAFE_REUSED_KERNEL.md`) and bind this operation's buffers/offsets
onto it via `ProcessArgumentMap`. No part of that lookup re-checks the operand shapes that line 24
depends on, because step 1 already discarded them.

## The defect, stated precisely

`DelegatedProducer.signature()` advertises an operand by `count` + `fixedness` only. A consumer
(`CollectionConcatenateComputation`, and equally the `CollectionSumComputation` reductions) compiles a
kernel that bakes in the operand's full shape (line 24's `16`/`8`/`144`). The cache key the consumer is
stored under is therefore **broader** than the kernel's true validity domain: it claims "valid for any
operands with these counts," while the kernel is valid only for "operands with these exact shapes."
That is the formal version of the review objection — the kernel is *not* reusable for "any operand with
matching count," so storing it under a count-only signature is the bug. (`DelegatedProducer.signature`
itself carries a TODO acknowledging the signature is known to be under-determined.)

The fix that matches owner-option (A): make the consumer kernel a true function of its `sizeArr`
arguments (use the runtime sizes it already reads on lines 21–23 instead of the baked-in `16`/`8`/`144`
on line 24), **or** make `DelegatedProducer.signature()` encode the operand's shape so the cache key's
resolution matches the kernel's actual dependence. Either makes "same signature ⇒ same result for the
same inputs" hold for these kernels.
