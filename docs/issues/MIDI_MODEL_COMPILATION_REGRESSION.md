# MIDI Model Compilation Regression — "Cannot compile greaterThan / subset"

**Date:** 2026-04-20  
**Affected tests:** `MidiTrainingTest`, `MoonbeamMidiTest`, `MoonbeamFineTuningTest`, `SkyTntMidiTest`  
**Status:** Open

---

## 1. The Error at the Framework Level

When any of the affected tests attempt to generate output (autoregressive inference
or training forward pass), compilation of a hardware kernel fails:

```
HardwareException: Hardware Cannot compile greaterThan
HardwareException: Hardware Cannot compile subset
```

Both messages originate from the same site in
`base/hardware/src/main/java/org/almostrealism/hardware/instructions/ComputationScopeCompiler.java`:

```java
throw new HardwareException("Cannot compile " + Named.nameOf(getComputation()), e);
```

The exception that gets wrapped is an `IllegalStateException` thrown by the fail-loud
check added in PR #196:

```java
// ComputationScopeCompiler.getKernelMaximum()
long count = ((FixedCount) comp).getCountLong();
if (count <= 0) {
    throw new IllegalStateException(
        "Fixed-count computation " + comp + " reports getCountLong() = "
        + count + ". A zero-iteration kernel cannot exist...");
}
```

`GreaterThanCollection` (name = `"greaterThan"`) and `PackedCollectionSubset`
(name = `"subset"`) are both fixed-count computations. When either ends up with
an unresolvable or zero element count at kernel compilation time, the fail-loud
check fires.

### Why the names match the error messages

- `GreaterThanCollection` passes `"greaterThan"` to its parent as the kernel name.
- `PackedCollectionSubset` passes `"subset"`.

These names are embedded in the `HardwareException` message verbatim, making the
error traceable back to these two computation classes.

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
// BEFORE (commit parent): returned PackedCollection — eager, materialized
static PackedCollection<?> computeRopeFreqs(double theta, int headDim, int seqLen) {
    ...
    return rf.concat(2, cosVals, sinVals).evaluate();  // evaluated immediately
}

// AFTER (99bc5d0b8): returns CollectionProducer — lazy, unevaluated
static CollectionProducer<?> computeRopeFreqs(double theta, int headDim, int seqLen) {
    ...
    return rf.concat(2, cosVals, sinVals);  // NOT evaluated — computation graph node
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
wrong output silently; after PR #196 they fail loudly.

---

## 3. Affected Code Paths

### 3.1 Moonbeam models → `greaterThan` failure

```
MoonbeamMidiTest / MoonbeamFineTuningTest / MidiTrainingTest
  └─ AutoregressiveModel.next() / Model.forward()
       └─ AttentionFeatures.attention()  (MRA path via HeadGroupConfig[])
            └─ RotationFeatures.mraRopeRotation(CollectionProducer freqCis, ...)
                 └─ CellularLayer lambda uses freqCis as weights in:
                      c(shape(totalHeadFreq), weights, cosIdx)
                    where weights = freqCis = lazy CollectionProducer
                 └─ greaterThan(componentMap, c(0.5), out2Vals, out1Vals)
                      ←── kernel compilation fails here ("Cannot compile greaterThan")
```

`mraRopeRotation()` (and the simpler `ropeRotation()`) build a `CellularLayer`
whose lambda closes over `freqCis` directly:

```java
// RotationFeatures.java — inside mraRopeRotation()
CollectionProducer result = greaterThan(componentMap, c(0.5), out2Vals, out1Vals, true);
```

When `freqCis` is a `CollectionProducer` rather than a materialized buffer, the
shape/count derivation that the framework uses to size the `greaterThan` kernel
cannot be statically resolved. The fixed-count check then sees count ≤ 0 and throws.

### 3.2 SkyTNT model → `subset` failure

```
SkyTntMidiTest
  └─ SkyTntMidi.generate()
       └─ buildTransformerModel(CollectionProducer freqCis, ...)
            └─ PDSL transformer (PdslInterpreter)
                 └─ callRopeRotation() → RotationFeatures.ropeRotation(freqCis, ...)
                      └─ CellularLayer captures lazy freqCis as data source
                 └─ attention block subset operations:
                      cp(netEmbedTokens).subset(shape(hiddenSize), tokenRow[0] * hiddenSize)
                      ←── kernel compilation fails here ("Cannot compile subset")
```

The `subset` error in SkyTNT traces to the same root cause: the lazy `CollectionProducer`
chain from `computeRopeFreqs()` propagates into the attention computation graph, causing
a downstream `PackedCollectionSubset` operation to inherit an unresolvable element count.

### 3.3 GRUDecoder secondary change

`GRUDecoder.java` was also modified in commit `99bc5d0b8` to remove `.evaluate()`
from hidden-state extraction:

```java
// BEFORE: h[l] = cp(output).subset(shape(dh), l * dh).evaluate();
// AFTER:  h[l] = cp(output).subset(shape(dh), l * dh);  // stays lazy
```

This changes `h` from `PackedCollection[]` to `CollectionProducer[]`. Downstream
code that uses `h[l]` as a concrete buffer (e.g., passing it to a method expecting
`PackedCollection`) will silently build a deeper lazy graph. If any such consumer
performs a fixed-count compilation, it will fail for the same reason.

---

## 4. Root Cause Summary

The change to make `computeRopeFreqs()` return a lazy `CollectionProducer` was
architecturally correct by project convention — the framework prefers lazy
computation graphs. However, `ropeRotation()` and `mraRopeRotation()` construct
`CellularLayer` closures that treat the `freqCis` argument as a **data source with
a statically-known shape**. A lazy `CollectionProducer` whose shape is only
resolvable at evaluation time does not satisfy that contract.

The framework's fixed-count kernel compilation requires that every computation node
in the graph can report a concrete, positive element count before any kernel is
compiled. A lazy producer that wraps a `concat` of two cosine/sine tables is not
inherently unsizable — `concat` does know its output shape — but the way the
`CellularLayer` closes over it breaks the count-derivation path the compiler walks.

---

## 5. Proposed Fixes

### Fix A: Evaluate `computeRopeFreqs()` at the call site (minimal, low-risk)

At each call site that constructs a `HeadGroupConfig` or calls `ropeRotation()`,
evaluate the lazy producer immediately before passing it in:

```java
// HeadGroupConfig constructor call sites (e.g., MoonbeamConfig, SkyTntMidi):
PackedCollection freqCis = RotationFeatures.computeRopeFreqs(theta, headDim, seqLen).evaluate();
HeadGroupConfig cfg = new HeadGroupConfig(headCount, freqCis, position);
```

`ropeRotation()` and `mraRopeRotation()` would revert their parameter type back to
`PackedCollection` (or overload to accept both). `HeadGroupConfig.freqCis` reverts
to `PackedCollection`.

**Tradeoffs:**
- Minimal diff; easy to verify correctness.
- Evaluation happens once at model-construction time (not per-token), so no
  per-inference overhead.
- Reverts the architectural intent of commit `99bc5d0b8` for this particular path.
- `GRUDecoder.h[]` should also revert to `PackedCollection[]` with `.evaluate()` restored.

### Fix B: Propagate shape through the CellularLayer closure (correct, higher effort)

Ensure that `ropeRotation()` and `mraRopeRotation()` explicitly fix the shape of
the `freqCis` producer before embedding it in the `CellularLayer` closure, so the
compiler can statically resolve the element count:

```java
// Inside ropeRotation() / mraRopeRotation(), before building the closure:
CollectionProducer<PackedCollection> fixedFreqCis =
    c(shape(totalHeadFreq), weights);  // explicitly impose the known shape
```

Then use `fixedFreqCis` inside the `greaterThan` / `subset` sub-expressions so the
count is derivable from the imposed shape rather than from the lazy producer's
self-reported shape.

**Tradeoffs:**
- Preserves the lazy-producer architecture of `computeRopeFreqs()`.
- Requires understanding exactly where the count-derivation path breaks in
  `CellularLayer` and `GreaterThanCollection` — more investigation needed.
- Risk: if the imposed shape does not match actual runtime data, produces silent
  wrong output rather than a compile error.

### Fix C: Add an explicit shape-validation guard in `GreaterThanCollection` / `PackedCollectionSubset`

In the count-returning method of `GreaterThanCollection` (and `PackedCollectionSubset`),
detect when an input producer's shape is not statically resolvable and throw a
descriptive exception pointing at the call site, rather than returning 0 or negative:

```java
@Override
public long getCountLong() {
    long count = super.getCountLong();
    if (count <= 0) {
        throw new IllegalStateException(
            Named.nameOf(this) + " cannot determine element count: "
            + "one or more input producers has no static shape. "
            + "Ensure all inputs to " + Named.nameOf(this)
            + " are either PackedCollection or producers with a fixed shape. "
            + "Inputs: " + inputs());
    }
    return count;
}
```

**Tradeoffs:**
- Does not fix the root cause — it improves diagnostics only.
- The existing fail-loud check in `ComputationScopeCompiler` already catches this;
  a more targeted message inside the computation itself would shorten debug cycles.
- Should be done regardless of which of A or B is chosen.

---

## 6. Recommended Action

**Fix A** is the lowest-risk path to restoring test correctness quickly. The
`freqCis` values are computed once per model construction from static hyperparameters
(theta, headDim, seqLen) and are unchanged during inference. Materializing them at
construction time is consistent with how static weight tensors are handled everywhere
else in the model (`wq`, `wk`, `wv`, etc.).

**Fix C** (diagnostic improvement) should be filed as a follow-up regardless.

The `GRUDecoder` change (removing `.evaluate()` from `h[l]` extraction) should be
independently validated: if any consumer of `h[l]` expects a `PackedCollection`,
restoring `.evaluate()` there is also required.

---

## 7. Files to Change for Fix A

| File | Change |
|---|---|
| `engine/ml/.../RotationFeatures.java` | `computeRopeFreqs()` return type → `PackedCollection`, restore `.evaluate()` |
| `engine/ml/.../RotationFeatures.java` | `ropeRotation()`, `mraRopeRotation()` param → `PackedCollection weights` |
| `engine/ml/.../HeadGroupConfig.java` | `freqCis` field → `PackedCollection` |
| `engine/ml/.../AttentionFeatures.java` | RoPE call sites — accept `PackedCollection` |
| `engine/ml/.../GRUDecoder.java` | Restore `.evaluate()` on `h[l]` extraction |
| `engine/ml/.../PdslInterpreter.java` | `callRopeRotation()` — cast to `PackedCollection` (remove `toFreqCis()` adapter) |
| `studio/compose/.../SkyTntMidi.java` | `freqCis` local variable and parameter → `PackedCollection` |

`Llama2.java`, `Qwen3.java`, and their tests were also touched in `99bc5d0b8` and
may need corresponding reversions or call-site evaluations.
