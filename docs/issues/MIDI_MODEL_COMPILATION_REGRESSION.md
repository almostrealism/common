# MIDI Model Compilation Regression — "Cannot compile greaterThan / subset"

**Date:** 2026-04-20  
**Affected tests:** `MidiTrainingTest`, `MoonbeamMidiTest`, `MoonbeamFineTuningTest`, `SkyTntMidiTest`  
**Status:** Fixed — see §6

---

## 1. The Observed Failure

When any of the affected tests attempt to generate output (autoregressive inference
or training forward pass), the JVM hangs indefinitely inside `NativeCompiler.compile()`.
The native C compiler subprocess is spawned but never returns:

```
at java.lang.Object.wait(Native Method)
at java.lang.ProcessImpl.waitFor(ProcessImpl.java:434)
at org.almostrealism.hardware.jni.NativeCompiler.lambda$runner$0(NativeCompiler.java:499)
at org.almostrealism.hardware.jni.DefaultLinkedLibraryGenerator.generateLibrary(...)
at org.almostrealism.hardware.jni.NativeCompiler.compile(NativeCompiler.java:441)
```

The C compiler is not erroring out — it is running, processing an enormous generated C
source file, and consuming CPU until a timeout kills it. In some environments a
`HardwareException` wrapper is also observed:

```
HardwareException: Hardware Cannot compile greaterThan
HardwareException: Hardware Cannot compile subset
```

The `HardwareException` wraps an `IllegalStateException` thrown by the fail-loud check
added in PR #196 when a fixed-count computation reports `getCountLong() <= 0`, which is
a secondary symptom of the same root cause.

---

## 2. Breaking Change

**Commit:** `99bc5d0b841b85f3e1f58f05e77d86fefd257005`  
**Author:** Michael Murray  
**Date:** 2026-04-15  
**Message:** "Adjusted RotationFeatures methods to return CollectionProducer."

This commit changed `RotationFeatures.computeRopeFreqs()` from returning an
*evaluated* `PackedCollection` (a materialized buffer) to a *lazy*
`CollectionProducer` (an unevaluated computation graph):

```java
// BEFORE: returned PackedCollection — eager, materialized
static PackedCollection<?> computeRopeFreqs(double theta, int headDim, int seqLen) {
    ...
    return rf.concat(2, cosVals, sinVals).evaluate();
}

// AFTER: returns CollectionProducer — lazy, unevaluated
static CollectionProducer<?> computeRopeFreqs(double theta, int headDim, int seqLen) {
    ...
    return rf.concat(2, cosVals, sinVals);
}
```

The corresponding change in `HeadGroupConfig.java` updated the `freqCis` field type:

```java
// BEFORE: PackedCollection freqCis;
// AFTER:  CollectionProducer freqCis;
```

And callers in `SkyTntMidi.java` and `AttentionFeatures.java` were updated to accept
`CollectionProducer` instead of `PackedCollection`.

**PR #196 made this latent bug visible.** Before PR #196 was merged on 2026-04-19,
the zero-count kernel path was silently tolerated via the `if (ctx > 0)` guard that
PR #196 replaced with a fail-loud `IllegalStateException`. Tests may have produced
wrong output silently; after PR #196 they fail loudly (or hang).

---

## 3. Root Cause — Kernel Expression Size Explosion

### 3.1 The structural difference

Before commit `99bc5d0b8`, `freqCis` was a `PackedCollection`. After it, `freqCis` is a
lazy `CollectionProducer` whose graph is:

```
concat(axis=2,
  cos(matmul(reshape(integers(0,seqLen), (seqLen,1)),
             reshape(exp(multiply(integers(0,freqDim), scalar)), (1,freqDim))))
        .reshape((seqLen, freqDim, 1)),
  sin(...)
        .reshape((seqLen, freqDim, 1))
)
```

`concat` is implemented as `add(pad(shape, pos0, p0), pad(shape, pos1, p1))` using
`PackedCollectionPad`, which generates conditional branch expressions in the kernel.

### 3.2 Where the explosion occurs — `mraRopeRotation`

`mraRopeRotation` (unlike `ropeRotation`) constructed six additional index maps as
lazy `CollectionProducer` expression trees instead of materialized `PackedCollection`
arrays:

| Variable | Lazy expression (before fix) |
|---|---|
| `cosRelIndexMap` | `mod(integers(0, totalHeadFreq), c(freqDim)).multiply(c(2.0))` |
| `sinRelIndexMap` | `cosRelIndexMap.add(c(1.0))` |
| `x1IndexMap` | `integers(0, totalHeadFreq).multiply(c(2.0))` |
| `x2IndexMap` | `x1IndexMap.add(c(1.0))` |
| `outputSourceMap` | `floor(divide(integers(0, outputSize), c(2.0)))` |
| `componentMap` | `mod(integers(0, outputSize), c(2.0))` |
| `groupMasks[g]` (×N) | `indices.greaterThanOrEqual(c(maskStart)).multiply(indices.lessThan(c(maskEnd)))` |

Each of these lazy producers was **inlined** into the C kernel expression at compilation
time. A kernel that references `cosRelIndexMap` does not read from a buffer — it
generates a complete inline arithmetic sub-expression tree at each reference site.

The kernel for the final `greaterThan(componentMap, 0.5, out2Vals, out1Vals)` references:
- `componentMap` → inline `mod(integers, c)` expression
- `out2Vals` → references `out2` → references `cos` and `sin`
  - `cos` is gathered from `combinedFreqCis` (a `concat` of N lazy freqCis groups) using `cosIdx`
  - `cosIdx` = `groupBaseOffsets` + `posOffset` + `cosRelIndexMap` (inline `mod` expression)
  - `posOffset` = sum over groups of `groupMasks[g].multiply(c(headGroups[g].position))`, each `groupMasks[g]` an inline comparison chain
  - `combinedFreqCis` = `concat(freqCis[0], ..., freqCis[N-1])`, each `freqCis[g]` being the full lazy `computeRopeFreqs` graph containing `matmul`, `exp`, `integers`, `cos`, `sin`, `reshape`, `PackedCollectionPad`

The result is a single C expression with **exponential fanout** — each sub-expression
that is referenced multiple times is expanded at each reference site, and the compound
expression for the full kernel is millions of characters of C source. The C compiler
(gcc/clang) processes it, but the compile time grows super-linearly with expression
depth and eventually hangs.

### 3.3 Why `ropeRotation` passes

`ropeRotation` uses `PackedCollection` + `setMem` loops at model-construction time for
all of the equivalent index maps (`cosRelativeIndexMap`, `sinRelativeIndexMap`,
`x1IndexMap`, `x2IndexMap`, `outputSourceMap`, `componentMap`,
`weightsFreqSizeArray`). In the compiled kernel, these are buffer read operations —
a single pointer dereference per element. The `greaterThan` kernel expression is
small and compiles in milliseconds.

### 3.4 SkyTNT `subset` failure

The `subset` error in SkyTNT traces to the same root cause: the lazy
`CollectionProducer` chain from `computeRopeFreqs()` propagates into the attention
computation graph, causing downstream `PackedCollectionSubset` operations to inherit
an unresolvable element count. The PR #196 fail-loud check fires when `getCountLong()`
returns ≤ 0.

### 3.5 GRUDecoder secondary change

`GRUDecoder.java` was also modified in commit `99bc5d0b8` to remove `.evaluate()`
from hidden-state extraction:

```java
// BEFORE: h[l] = cp(output).subset(shape(dh), l * dh).evaluate();
// AFTER:  h[l] = cp(output).subset(shape(dh), l * dh);
```

This changes `h` from `PackedCollection[]` to `CollectionProducer[]`. Downstream
code that uses `h[l]` as a concrete buffer will silently build a deeper lazy graph,
potentially triggering the same expression-size explosion.

---

## 4. Fix Applied

**File:** `engine/ml/src/main/java/org/almostrealism/ml/RotationFeatures.java`  
**Method:** `mraRopeRotation`

All six lazy `CollectionProducer` index maps and all `groupMasks[g]` were replaced with
`PackedCollection` arrays computed via `setMem` loops at model-construction time, exactly
as `ropeRotation` does for its equivalent maps:

```java
// BEFORE (broken — each inlined as arithmetic expression tree in the kernel):
CollectionProducer cosRelIndexMap = mod(integers(0, totalHeadFreq), c(freqDim)).multiply(c(2.0));
CollectionProducer sinRelIndexMap = cosRelIndexMap.add(c(1.0));
CollectionProducer x1IndexMap = integers(0, totalHeadFreq).multiply(c(2.0));
CollectionProducer x2IndexMap = x1IndexMap.add(c(1.0));
int outputSize = totalHeads * freqDim * 2;
CollectionProducer outputSourceMap = floor(divide(integers(0, outputSize), c(2.0)));
CollectionProducer componentMap = mod(integers(0, outputSize), c(2.0));
// ... groupMasks[g] = indices.greaterThanOrEqual(...).multiply(indices.lessThan(...))

// AFTER (fixed — each becomes a buffer read in the kernel):
PackedCollection cosRelIndexMap = new PackedCollection(shape(totalHeadFreq));
PackedCollection sinRelIndexMap = new PackedCollection(shape(totalHeadFreq));
for (int h = 0; h < totalHeads; h++) {
    for (int f = 0; f < freqDim; f++) {
        int idx = h * freqDim + f;
        cosRelIndexMap.setMem(idx, f * 2);
        sinRelIndexMap.setMem(idx, f * 2 + 1);
    }
}
// ... x1IndexMap, x2IndexMap, outputSourceMap, componentMap, groupMasks[] similarly
```

All materialized collections are added to the `captured` list so the kernel holds
references to them for their lifetime. Lambda references wrap them with `p()` /
`c(p(...))` as appropriate.

**Test coverage:** `RopeCompilationRegressionTest.mraRopeRotationWithLazyFreqCis` now
passes in ~37 seconds (previously timed out at 60 seconds waiting for the C compiler).

---

## 5. Remaining Work

- **GRUDecoder:** The `.evaluate()` removal from `h[l]` extraction was part of the same
  commit. Validate that downstream consumers of `h[l]` can handle a lazy producer, or
  restore `.evaluate()` there.
- **Diagnostic improvement (Fix C from original proposal):** Adding a targeted
  `getCountLong() <= 0` check directly inside `GreaterThanCollection` and
  `PackedCollectionSubset` would shorten future debug cycles by naming the broken
  component at the source rather than at the kernel-compilation wrapper.
