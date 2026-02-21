# NormTests Failure Analysis on `feature/lora-gradients`

## Summary

The CI pipeline fails at the test step for the `utils` module on `feature/lora-gradients`.
The root cause is that the branch's changes to delta (gradient) computation create expression
trees that grow too large for the norm backward pass at scale, causing tests to hang until
they time out.

**The branch broke the tests by modifying code that the tests cover (option 3).**

---

## Failing Test

| Test Method | Class | Parameters | Annotation |
|---|---|---|---|
| `backwardsTrainableVeryLarge1` | `NormTests` | c=1600, groups=4 | `@TestDepth(2)`, timeout=120min |

This test hangs indefinitely during expression tree construction / native code generation.
CI runs utils tests with **no `AR_TEST_DEPTH` limit**, so this @TestDepth(2) test is included.

### Symptoms

1. Forward tests (normComputation, normLayer, normModel) pass quickly
2. Small and medium backward tests (c <= 120) pass
3. `backwardsTrainableVeryLarge1` (c=1600) **hangs** with these warnings:
   ```
   WARN: Inner sum simplify failed because 400 * 400 != 640000
   WARN: IdentityCollectionExpression: Unable to create ExpressionMatrix for IdentityCollectionExpression
   ```
4. No further output is produced; the test eventually times out

### What the warnings mean

- c=1600, groups=4 => groupSize = 400
- The Jacobian matrix dimension is c * groupSize = 1600 * 400 = 640000
- `Mod.simplify` (in `code/src/main/java/io/almostrealism/expression/Mod.java`) expects
  `constant * constant == m`, i.e., 400 * 400 = 160000 == 640000, which is false
- When this simplification fails, the expression tree cannot be properly optimized
- The result is exponential growth of the expression tree, leading to an infinite hang
  during native code generation

### Test depth behavior

| Depth | Tests Run | Skipped | Failures | Outcome |
|---|---|---|---|---|
| 0 | 29 | 14 | 0 | PASS |
| 1 | 29 | 8 | 0 | PASS |
| 2 | 29 | ~4 | 0 (hang) | HANG on `backwardsTrainableVeryLarge1` |
| unlimited (CI) | 29 | 0 | 0 (hang) | HANG on `backwardsTrainableVeryLarge1` |

---

## Branch Changes That Cause the Failure

The test file `NormTests.java` itself is **NOT modified** on the branch. The failure is caused
by changes to the delta (gradient) computation infrastructure that NormTests exercises.

### Changed Files (Relevant to NormTests)

#### 1. `CollectionFeatures.java` - Switched `subset()` and `concat()` to new implementations

```
- return new PackedCollectionSubset(shape, collection, position);
+ return new CollectionSubsetComputation(shape, (Producer<PackedCollection>) collection, position);
```

```
- return concat(new TraversalPolicy(dims), producers);
+ return new CollectionConcatenateComputation(new TraversalPolicy(dims), axis, producers);
```

**Impact:** ALL subset and concat operations now use the new computation classes, which have
different delta() implementations. The norm backward pass uses subset and concat internally
when computing gradients across groups.

#### 2. `CollectionSubsetComputation.java` (NEW)

A new computation class for subset extraction with Producer-level differentiation.
Replaces `PackedCollectionSubset` and provides isolated gradient computation via
`TransitiveDeltaExpressionComputation`.

**Impact:** Changes how gradients propagate through subset (slice) operations in the
norm backward pass. The `delta()` method creates different Jacobian matrix shapes than
the original `PackedCollectionSubset`.

#### 3. `CollectionConcatenateComputation.java` (NEW)

A new computation class for concatenation with Producer-level differentiation.
Includes `ConcatProjectionComputation` for sparse Jacobian optimization.

**Impact:** Changes how gradients propagate through concatenation operations used in
the norm backward pass to reassemble per-group gradients.

#### 4. `CollectionProductComputation.java` - Added `attemptDelta` shortcut

```java
+ CollectionProducer delta = MatrixFeatures.getInstance().attemptDelta(this, target);
+ if (delta != null) return delta;
```

**Impact:** Changes the delta computation path for product (multiply) operations.
The norm backward pass multiplies by weights, so this directly affects gradient flow.

#### 5. `CollectionMinusComputation.java` - Added custom `delta()` method

Added a new `delta()` override that computes `minus(inputDelta)` directly instead of
going through the default chain rule path that would compute `matmul(-I, dg/dx)`.

**Impact:** Changes gradient computation for negation operations in the norm backward pass.

#### 6. `DeltaFeatures.java` - Added zero-check in `expandAndMultiply`

```java
+ if (Algebraic.isZero(matrix) || Algebraic.isZero(vector)) {
+     return zeros(matrix.getShape());
+ }
```

**Impact:** Adds an early-exit for zero matrices in the expand-and-multiply operation
used during delta computation. This changes the shape of returned results when zeros
are involved.

#### 7. `Sum.java` - Removed `throw UnsupportedOperationException` in `simplify()`

```java
- } else {
-     throw new UnsupportedOperationException();
  }
```

**Impact:** When `Sum.simplify` encounters children that are all single-index-masked but
`enableGenerateReordering` is false, it now falls through to `generate(children).populate(this)`
instead of throwing. This allows malformed expression trees to proceed silently rather than
failing fast, contributing to the hang.

#### 8. `ParallelProcess.java` - Changed `optimize()` fallback

```java
- throw new UnsupportedOperationException();
+ return (ParallelProcess) generate(new ArrayList<>(children));
```

**Impact:** When `ProcessOptimizationStrategy` returns null, the process now falls through
to `generate()` instead of throwing. This can allow unoptimized processes to proceed,
potentially creating larger expression trees.

#### 9. `NativeCompiler.java` - Added reduced optimization pragma for large code

Added `#pragma GCC optimize("O1")` for generated C files > 50000 characters.

**Impact:** Compilation speed improvement for large generated code, but does not address
the root cause of expression tree explosion.

---

## Root Cause Analysis

The fundamental problem is that the branch's new delta computation classes
(`CollectionSubsetComputation`, `CollectionConcatenateComputation`) create Jacobian
matrices with dimensions that are incompatible with the existing `Mod.simplify`
optimization in the expression compiler.

### The Failure Chain

1. `NormTests.backwardsTrainableVeryLarge1` creates a norm layer with c=1600, groups=4
2. The backward pass computes gradients via `delta()` calls through the norm computation graph
3. The norm computation involves: `input.subtractMean().divide(variance.sqrt())` with
   group-wise operations using `subset` and `concat`
4. The branch's new `CollectionSubsetComputation.delta()` and
   `CollectionConcatenateComputation.delta()` produce Jacobian matrices of shape
   `[1600, 400]` (c x groupSize)
5. When `Mod.simplify` encounters these dimensions (constant=400, m=640000=1600*400),
   it expects `constant^2 == m` (400^2=160000 != 640000), and the simplification fails
6. Without this simplification, the expression tree grows without bound
7. The `Sum.simplify` change (removing the `throw`) allows the malformed tree to continue
   growing instead of failing fast
8. The `ParallelProcess.optimize` change (returning `generate()` instead of throwing)
   similarly allows unoptimized processes to proceed
9. The result is an infinite expression tree expansion that hangs the JVM

### Why Small Tests Pass

For small group sizes (groupSize <= ~30), the expression tree stays manageable even without
the Mod simplification. The tree size grows roughly as O(groupSize^2) in the worst case,
so c=1600 (groupSize=400) creates expression trees ~100x larger than c=120 (groupSize=30).

### Comparison with Master

On master:
- `subset()` returns `PackedCollectionSubset`, which uses index projection for gradients
- `concat()` returns the default concat implementation
- `CollectionProductComputation.delta()` does not call `attemptDelta`
- `Sum.simplify` throws on malformed trees (fail-fast)
- `ParallelProcess.optimize` throws when strategy returns null (fail-fast)

These master behaviors either produce expression trees that satisfy `Mod.simplify` assumptions,
or fail fast when they don't, preventing infinite hangs.

---

## Verification Steps Performed

1. Confirmed NormTests.java is NOT modified on the branch (`git diff` shows no changes)
2. Identified all changed source files via `git diff origin/master..HEAD --name-only`
3. Traced the dependency chain from NormTests through the changed delta computation classes
4. Ran NormTests at depth=0: **29 tests, 0 failures** (14 skipped)
5. Ran NormTests at depth=1: **29 tests, 0 failures** (8 skipped)
6. Ran NormTests at depth=2: **hangs** (never completes)
7. Ran `backwardsTrainableVeryLarge1` in isolation: **hangs** with "400 * 400 != 640000" warnings
8. Ran `backwardsTrainableLarge1` (c=120) in isolation: **passes** in 4.3s
9. Confirmed CI runs with no depth limit, so the hanging test is always included

---

## Recommendation

The fix should ensure that the new delta computation classes produce Jacobian matrices
whose dimensions satisfy the `Mod.simplify` invariant (`constant^2 == m`), OR the
`Mod.simplify` should be generalized to handle non-square Jacobian dimensions that arise
from group-wise norm operations. Additionally, the `Sum.simplify` and `ParallelProcess.optimize`
fallthrough changes should be reconsidered, as they mask expression tree problems that
would otherwise fail fast.
