# ConvTranspose1d Optimization Plan

## Problem Statement

The current `convTranspose1d` implementation uses `weightedSum` with a group size of
`inputChannels × kernelSize`. For the Oobleck decoder's first block:
- inputChannels = 2048
- kernelSize = 16
- Group size = 32,768 elements per output

`WeightedSumExpression` unrolls this into 32K multiply-add operations per output element,
which is extremely slow to compile.

---

## Understanding the Framework

### Scope Generation vs Expression Evaluation

There are two distinct code paths in the AR framework:

1. **Scope Generation (`getScope()`)**: When a computation is the top-level operation,
   it generates a `Scope` that becomes the native kernel code.

   - `RepeatedProducerComputation.getScope()` returns a `Repeated` scope
   - `Repeated.write()` generates an actual native `for` loop:
     ```java
     w.println("for (int " + getIndex().getName() + " = 0; " +
               getCondition().getExpression(...) + ";) {");
     ```

2. **Expression Evaluation (`getValueAt()`)**: When a computation is embedded inside
   another computation, it provides expressions that get composed into the parent.

   - `TraversableRepeatedProducerComputation.getValueAt()` iterates in Java:
     ```java
     for (int i = 0; i < count; i++) {
         value = expression.apply(args, value).getValueAt(e(i));
         value = value.generate(value.flatten());
     }
     ```
   - This UNROLLS the loop into the expression tree

### WeightedSumExpression Behavior

`WeightedSumExpression` always unrolls at compile time:
```java
for (int i = 0; i < input.length; i++) {
    result = result.add(input[i].multiply(weights[i]));
    result = result.generate(result.flatten());
}
```

This means:
- A weightedSum of 16 elements → 16 multiply-add expressions (fast)
- A weightedSum of 32K elements → 32K multiply-add expressions (too slow)

---

## Proposed Solution: Nested Loop with Small WeightedSum

### Concept

Split the convTranspose1d operation into two levels:

1. **Outer Level**: A `Repeated` scope that loops over `inputChannels` (generates native loop)
2. **Inner Level**: A `WeightedSumExpression` over `kernelSize` (unrolled, but small)

### Mathematical Decomposition

Current approach (single large sum):
```
output[oc, o] = Σ(ic=0..2047, k=0..15) upsampled_input[ic, o+k] × filter[ic, oc, k]
```

Proposed approach (nested structure):
```
// Outer loop (native for-loop)
for ic in 0..2047:
    // Inner weighted sum (16 elements, unrolled)
    partial[ic] = Σ(k=0..15) upsampled_input[ic, o+k] × filter[ic, oc, k]

// Accumulate (inside the loop)
output[oc, o] += partial[ic]
```

### Generated Code Structure

The generated kernel should look like:
```c
// For each output element (parallelized via kernel)
double result = 0.0;

// Native for-loop (not unrolled)
for (int ic = 0; ic < 2048; ic = ic + 1) {
    // Small unrolled weighted sum (16 operations)
    double partial = 0.0;
    partial = partial + input[...] * filter[...];  // k=0
    partial = partial + input[...] * filter[...];  // k=1
    ...
    partial = partial + input[...] * filter[...];  // k=15

    result = result + partial;
}

output[...] = result;
```

### Comparison

| Approach | Expression Nodes | Native Loops | Compile Behavior |
|----------|-----------------|--------------|------------------|
| Current (32K weightedSum) | ~32,768 per output | 0 | Very slow |
| Proposed (loop + 16 weightedSum) | ~16 per output | 1 | Fast |

---

## Implementation Strategy

### Option A: Create LoopedWeightedSumComputation

Create a new computation class that combines:
- `AggregatedProducerComputation` for the outer loop (over inputChannels)
- `SubsetTraversalWeightedSumExpression` for the inner weighted sum (over kernelSize)

```java
public class LoopedWeightedSumComputation extends AggregatedProducerComputation {
    // Outer: loop over inputChannels
    // Inner: WeightedSumExpression over kernelSize
}
```

The key insight: `AggregatedProducerComputation` when used standalone produces a
`Repeated` scope. Inside the loop body, we can use an expression that includes
a small `WeightedSumExpression`.

### Option B: Modify LayerFeatures.convTranspose1d

Restructure `convTranspose1d` to build a computation graph that naturally results
in the nested loop structure:

```java
// Pseudocode
// Step 1: For each inputChannel, compute partial sums over kernelSize
// This should use a small weightedSum (kernelSize elements)
CollectionProducer partial = weightedSum(...kernelSize elements...);

// Step 2: Sum over inputChannels using an operation that produces a native loop
// This needs AggregatedProducerComputation or similar
CollectionProducer result = loopSum(partial, inputChannelsDimension);
```

The challenge is ensuring the graph structure leads to `getScope()` being called
(producing a `Repeated`) rather than `getValueAt()` (which would unroll).

### Option C: Extend WeightedSumComputation

Modify `WeightedSumComputation` to recognize large group sizes and automatically
split into a `Repeated` scope for the large dimension:

```java
// In WeightedSumComputation or a subclass
if (groupSize > threshold) {
    // Split: outer dimension uses Repeated, inner uses WeightedSumExpression
    return createLoopedWeightedSum(...);
}
```

---

## Key Technical Challenges

### 1. Ensuring Scope Generation, Not Expression Unrolling

The proposed solution only works if `AggregatedProducerComputation.getScope()` is called
(producing a native loop), not `getValueAt()` (which unrolls).

This happens when the computation is:
- At the top level of the kernel
- Isolated as a separate kernel (via `isIsolationTarget()`)
- Not embedded inside another expression that calls `getValueAt()`

### 2. Index Calculation Inside the Loop

The loop body needs to correctly compute indices for:
- The upsampled input: function of (outputPosition, inputChannel, kernelIndex)
- The filter weights: function of (inputChannel, outputChannel, kernelIndex)

These index calculations must work correctly within the `Repeated` scope's
loop variable context.

### 3. Preserving Gradient Computation

The delta/gradient computation for automatic differentiation must still work
correctly with the restructured computation.

---

## Recommended Approach

**Start with Option A** (create `LoopedWeightedSumComputation`):

1. Create a new computation class that extends `AggregatedProducerComputation`
2. The loop iterates over `inputChannels` dimension
3. Inside the loop body, use `SubsetTraversalWeightedSumExpression` for `kernelSize` elements
4. Test with the Oobleck decoder block configuration

This approach:
- Builds on existing primitives (`AggregatedProducerComputation`, `WeightedSumExpression`)
- Clearly separates the loop structure (native) from the inner sum (unrolled)
- Is explicit about the optimization strategy

---

## Testing Plan

1. Create a minimal test case:
   - inputChannels = 2048
   - outputChannels = 1024
   - kernelSize = 16
   - seqLen = 2

2. Verify compile time is reasonable (< 30 seconds)

3. Verify numerical correctness against the original `weightedSum` implementation

4. Run the full Oobleck decoder block test

---

## Files to Modify/Create

1. **New file**: `org.almostrealism.algebra.computations.LoopedWeightedSumComputation`
   - Extends `AggregatedProducerComputation`
   - Implements the nested loop structure

2. **Modify**: `org.almostrealism.layers.LayerFeatures.convTranspose1d`
   - Use `LoopedWeightedSumComputation` for large inputChannels

3. **Tests**: Add compilation time tests for large channel counts
