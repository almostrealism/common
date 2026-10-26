# Full-Jacobian weight gradient: 32-bit arithmetic on hoisted index variables

## Symptom

For the dense output projection of the documentation language model (weights `256 × 64`, input
`(1, 64, 64)`), `GradientFeatures.jacobianGradient` with respect to the weight disagreed with the
gradient computed on the host from its definition (`dW = Σ_r g_r ⊗ x_r`) in about a tenth of the
16,384 entries. The errors always started at flat index 4098 (weight row 64). Earlier runs on Metal
measured 1,728 and 1,536 wrong entries. In this session's reproduction it was 1,536 on each of three
evaluations within one process, with the first wrong entry 12.54 expected and 5.60 produced. The
`192 × 64` and `128 × 64` projections were correct.

## Kernel

A profiled reproduction of exactly that gradient (the graph `DenseVectorJacobianProductTest` builds,
evaluated through `Process.optimized`) compiles to two kernels:

- `f_packedCollectionRepeat_23`, which writes the `(64, 256, 64)` repeat of the input:
  `_v24[global_id] = _v25[(global_id / 16384) * 64 + global_id % 64]`. Correct.
- `f_collectionSumComputation_48`, which has one thread per weight entry (16,384). Each thread loops
  `_48_i` over the 16,384 outputs and sums 64 diagonal-masked terms per iteration. Its arguments are
  `_v1` (the repeat buffer), `_v15` (the upstream gradient) and `_v22` (the result).

The sum kernel's flattened position has the form `((_48_i * 16384) + ((long) global_id)) * 64 + k`
(up to `2^34`). Metal renders the kernel index as `((long)global_id)`, so this arithmetic is 64-bit
inline. Before the loop body, however, the kernel declares hoisted subexpressions such as

```
int f_collectionSumComputation_48_body_4 = (((((_48_i * 16384) + ((long)global_id)) * 64) + 16) % 1048576) / 64;
```

and the accumulation then uses them as `f_collectionSumComputation_48_body_4 * 1048512`. Each hoisted
value fits in 32 bits (it is below 16,384), but because the variable is `int`, the product with
`1048512` is computed in 32-bit arithmetic and overflows, up to about `1.7 × 10^10`.

## Evidence

The sum kernel's source was taken from `ar-profile-analyzer get_source`. Its loop body was then
evaluated on the host for threads 10 and 4098 by an interpreter of the C expression grammar,
against the same repeat buffer and gradient:

- **Exact integer arithmetic:** both threads equal the host reference (−10.151323 and 9.082511), so
  the generated expression itself is correct.
- **Static C typing** (`int` for the hoisted variables, the loop index and literals, `long` wherever
  `(long)global_id` takes part): thread 10 has no 32-bit overflow and is correct. Thread 4098 has
  786,432 overflowing `int` multiplications, all of the form `body_N * 1048512`, and produces
  −14.516737 instead of 9.082511.

So the defect is the declared type of the hoisted variables. The other candidate causes do not
apply: the result needs neither argument aggregation nor kernel ordering to go wrong, and the index
expression is correct.

The run-to-run variation in the count (1,728 versus 1,536) fits this mechanism: which subexpressions
get hoisted, and therefore how many of the 64 terms go through a 32-bit product, can differ between
compilations. That last point was not verified separately.

## Cause in the code generator

`Scope.processReplacements` declares each common subexpression it extracts as
`new StaticReference<>(e.getType(), ...)`. The loop-invariant extraction in `Repeated` declared its
variables with the expression's type promoted only when bounds analysis showed the value itself
could exceed 32 bits. In the expression tree the kernel index is a `KernelIndex` typed `Integer`,
while every generated language (C, OpenCL, Metal) renders it as a 64-bit integer. An `Integer`-typed
expression that contains the kernel index is therefore evaluated in 64 bits inline. Hoisting it into
an `int` variable keeps its value but silently narrows every arithmetic operation the variable then
takes part in.

## Fix

`Expression.getDeclarationType()` (which replaces `promoteIfOverflows()`) returns `Long` for an
`Integer` expression that refers to the kernel index (`Expression.containsKernelIndex()`), or whose
bounds exceed or cannot bound the 32-bit range. Both hoisting sites, `Scope.processReplacements` and
the loop-invariant extraction of `Repeated`, declare their variables with it. The fix is in the
general code generator, so every kernel that hoists kernel-index arithmetic benefits, not only this
gradient.

## Verification

- `DenseVectorJacobianProductTest.outputProjection` now asserts the full-Jacobian weight and input
  gradients against the definition, forming and evaluating the full-Jacobian gradient three times.
  With the fix reverted it fails at `gradient[4098]` (9.55 expected, 0.906 produced); with the fix
  the whole class passes.
- `ScopeReplacementOrderTest.kernelIndexReplacementIsDeclaredLong` checks the declaration types
  directly: a replaced kernel-index expression is declared `Long`, and a bounded expression that
  does not refer to the kernel index stays `Integer`. It fails with the fix reverted.
- `LoopInvariantHoistingTest` passes with the fix.

## Convolution

`ConvolutionJacobianGradientTest` differentiates `convolution2dOperator` (the operator that
`convolution2d` layers use, extracted so that tests can reach it) with respect to its input. That
gradient takes the full-Jacobian fallback: the largest computation in its reduced delta is
`7200 × 8192 = 58,982,400`, against a forward size of 7,200. It agrees with the host definition on
three evaluations both with and without the fix, so at this size the convolution does not share the
defect.

The same test exposed an unrelated defect. The operator binds its filters as
`cp(filters.reshape(...))`, while the layer differentiates with respect to `cp(filters)`, and the
filter gradient (`ConvolutionJacobianGradientTest.filterGradient`) came out identically zero. Both
producers are provider match roots, and `CollectionProviderProducer` matched another provider only
when it held the very same `PackedCollection` instance, which a reshape view is not. Providers now
also match when their values are views that `MemoryData.hasSameElements` recognizes as the same
elements of the same memory in the same order, so a reshape of a collection and the collection
itself are one input to the derivative, and the filter gradient agrees with the host definition.
