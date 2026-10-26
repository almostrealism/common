# Automatic Differentiation: from `delta` to the Backward Pass

Automatic differentiation is what makes the computation graph a *learning* system rather than
only a fast one. The [forward story](end-to-end-computation.md) — Producer to
[process tree](computation-graph-to-process-tree.md) to
[backend compilation](backend-compilation-and-dispatch.md) to the
[native runtime](native-runtime-lifecycle.md) — is told elsewhere. This page is the backward
half: how a derivative is obtained, how each computation produces one from its own structure, how
the expression level represents a Jacobian, and how a gradient reaches an optimizer.

A single method is the whole public contract:

```java
// CollectionProducer.delta(Producer, ReduceAxis, Producer)
CollectionProducer delta = y.delta(x, ReduceAxis.OUT, upstreamGradient);
```

Everything below is reached through it.

---

## 1. The contract

`CollectionProducer.delta(target, axis, weight)` is the only method through which a derivative is
obtained. It computes the Jacobian `J` of this producer with respect to `target`, with one axis of
`J` summed away after weighting each entry along it, or none. The
[`ReduceAxis`](../../compute/algebra/src/main/java/org/almostrealism/calculus/ReduceAxis.java)
argument names which axis the caller is about to contract, and the computation produces that
contracted result directly rather than materializing `J` first.

The Jacobian of `f` with respect to `x` has an **output axis** (index 0, one entry per element of
`f`) and an **input axis** (index 1, one entry per element of `x`). `ReduceAxis` selects the one
to sum away:

| Axis | Result | Shape of result | Weight | Mode |
|------|--------|-----------------|--------|------|
| `NONE` | the Jacobian `J` itself | `(out, in)` — output shape then target shape | none | — |
| `OUT` | vector-Jacobian product `gᵀJ` | shape of the **target** | `g`, shape of the output | reverse |
| `IN` | Jacobian-vector product `J t` | shape of the **computation** | `t`, shape of the target | forward |

The axis is named by the caller, not inferred, because the caller knows which product it needs: a
backward pass contracts the output axis (reverse mode, `OUT`), a directional derivative contracts
the input axis (forward mode, `IN`). Naming it lets each computation emit a contracted kernel whose
size matches the axis that *remains*, never the full `(out, in)` Jacobian. `ReduceAxis.resultShape`
and `ReduceAxis.weightShape` give the two shapes above; a `null` weight means every entry along the
reduced axis is weighted by one.

Two convenience overloads fill in the common arguments:

```java
y.delta(x)                      // ReduceAxis.NONE, no weight — the full Jacobian J
y.delta(x, ReduceAxis.OUT)      // reduced along OUT, every output weighted by one
y.delta(x, ReduceAxis.OUT, g)   // reduced along OUT, weighted by the upstream gradient g
```

```java
// Example: y = Σ x²,  dy/dx = 2x
CollectionProducer x = cp(data);           // shape (n)
CollectionProducer y = x.pow(2).sum();     // shape (1)
CollectionProducer dydx = y.delta(x);      // J, shape (1, n) — equals 2·xᵢ in column i
```

---

## 2. Identity cases and the default

The `delta` default on `CollectionProducer` answers only the cases that a producer's **identity**
settles, independent of what it computes:

- **The target is absent from the graph** (`cannotMatch(target)`): the derivative is zero. The
  default returns `zeros` in the shape of the remaining axis.
- **The target is this producer** (`refersTo(target)` with the producer matching the target): the
  Jacobian is the identity matrix, so `NONE` returns `identity(...)`, and a reduced delta returns
  the weight itself (or ones when there is no weight) — the identity contracted.
- **The producer depends on the target but has no derivative of its own**: for `NONE` there is
  nothing to answer and the default throws `UnsupportedOperationException` (there is no Jacobian);
  for a reduced axis the default contracts the full Jacobian through
  `GradientFeatures.jacobianContraction`.

A producer that *does* know its derivative overrides `delta` and defers to this default for the
identity cases (`cannotMatch`/`refersTo`). `ReshapeProducer` is the smallest such override: a
reshape only reinterprets the layout of its producer's values, so its delta delegates to the
underlying producer and then reshapes — the Jacobian to the reshape's shape, an `OUT` weight to the
input shape, an `IN` delta back to the output shape.

Computations do **not** override `delta` themselves;
[`CollectionProducerComputationBase`](../../compute/algebra/src/main/java/org/almostrealism/collect/computations/CollectionProducerComputationBase.java)
overrides it on their behalf (dispatching the identity cases to the default and everything else to
the extension points below), so a `CollectionProducerComputation` never touches `delta` directly.

---

## 3. Inside a computation: the extension points

`CollectionProducerComputationBase.delta` is **not overridden** by computations. It routes an
identity case (`cannotMatch`/`refersTo`) to the `CollectionProducer` default of §2, and every other
case — a target the computation depends on and is not — to `deltaDependent`, where the derivative of
a computation begins. There are **three** overridable extension points plus one enabling flag:

| Member | Kind | Override when… |
|--------|------|----------------|
| `deltaDependent(target, axis, weight)` | dispatcher **and** override point | the computation has a rule of its own that must take precedence over every strategy below |
| `deltaElement(int index)` | element-wise partial | the computation is element-wise in an input (output `i` depends on that input only through element `i`) |
| `deltaMatrix(target)` | own Jacobian, last resort | the computation can produce its own full Jacobian and nothing cheaper applies |
| `isChainRuleSupported()` | flag (default `false`) | the computation's expression can be differentiated with respect to one input held as a variable |

`deltaPartial(input, axis, weight)` is **not** an extension point. It is an internal helper the
dispatcher uses to differentiate the computation with respect to one input: it replaces that input
with an [`InputStub`](../../compute/algebra/src/main/java/org/almostrealism/calculus/InputStub.java)
in a regenerated copy and differentiates the copy with respect to the stub. Its own Javadoc states
it is a tool for the extension points, not one of them, and is not overridden — do not implement it
when adding a computation.

### `deltaDependent` is the default dispatcher

The base `deltaDependent` is both the method a subclass overrides to supply its own rule **and**,
when not overridden, the dispatcher that tries the other points in order. For a **reduced** delta it
tries:

1. **`deltaElementReduced`** — the element-wise route. If every input that may depend on the target
   has a `deltaElement` partial, the reduced delta is built from those partials and the inputs' own
   reduced deltas, for any number of inputs and either axis. This is the cheapest route.
2. **`deltaInput`** — when the target *is* one input of the computation (not a nested dependency),
   the delta is the `deltaPartial` with respect to that input, which requires
   `isChainRuleSupported()`.
3. **The multivariate chain rule** — when exactly one input depends on the target and
   `isFixedCount() && isChainRuleSupported()`, the reduction is passed through that input with
   `deltaPartial`:
   ```
   OUT:  gᵀ d(this)/dx = Σ_h h.delta(x, OUT, this.delta(h, OUT, g))
   IN:   d(this)/dx t  = Σ_h this.delta(h, IN, h.delta(x, IN, t))
   ```
4. **`jacobianContraction`** — when none of the above applies, the full Jacobian is formed and
   contracted. This is the fallback that a reduced form exists to avoid.

For the full Jacobian (`NONE`) the order is analogous: `deltaElementMatrix` (from `deltaElement`
partials), then `deltaInput` (the direct-input route of step 2 above, when the target *is* one
input), then the chain-rule matrix, then `deltaMatrix` as the last resort.

### Worked examples (each verified against source)

| Computation | Overrides | Rule |
|-------------|-----------|------|
| `ReshapeProducer` | `delta` (at the `CollectionProducer` level) | pass-through: delegate to the wrapped producer, reshape the result |
| `CollectionAddComputation` | `deltaDependent` | each operand receives the same weight (derivative 1) |
| `CollectionProductComputation` | `deltaElement` (+ `deltaDependent` for the `NONE` Jacobian) | product rule: `deltaElement(i)` is the product of the *other* operands at that element |
| `CollectionQuotientComputation` | `deltaElement` | quotient rule: `1/b` w.r.t. the numerator, `-a/b²` w.r.t. the denominator |
| `CollectionSumComputation` | `deltaDependent` | every element of a group gets the group output's weight; a tangent is summed over each group |
| `PackedCollectionRepeat` | `deltaDependent` | each input element appears in `r` copies with derivative 1, so `OUT` sums the copies' weights and `IN` repeats the input's tangent |
| `CollectionMaxComputation` | `deltaDependent` | the derivative of the selected (argmax) element; `max` is not linear |
| `CollectionExponentComputation` | `deltaDependent` | power rule `d/dx[uᵛ] = v·u^(v-1)·du/dx` when the base depends on the target and the exponent is constant |

The product computation shows the pattern clearly: `deltaElement(i)` returns the product of every
*other* operand, which the dispatcher turns into both reduced deltas and the Jacobian. The override
of `deltaDependent` there handles only the `NONE` Jacobian specially (via `expandAndMultiply`),
deferring to `super.deltaDependent` for the reduced axes.

### Decision guide: adding a computation

1. **Is the computation element-wise in its inputs** (output `i` from input element `i`, or from a
   single input element)? Override **`deltaElement(int index)`** to return the per-element partial.
   You get both reduced deltas and the Jacobian for free, for any number of inputs. This is the
   cheapest and most common case — the product and quotient rules are nothing more than this.
2. **Can its expression be differentiated with respect to one stubbed input** (so the chain rule can
   flow a reduction through that input)? Return `true` from **`isChainRuleSupported()`**. The
   dispatcher will use `deltaPartial`/`InputStub` for you; you do not implement `deltaPartial`.
3. **Does it have a genuinely bespoke rule** (a selection like `max`, a broadcast like `repeat`, a
   reduction like `sum`) that none of the above expresses? Override **`deltaDependent`**, handle
   your cases, and `return super.deltaDependent(...)` for the rest.
4. **Can it only produce its own full Jacobian?** Override **`deltaMatrix(target)`** as the last
   resort; reduced deltas will contract it.

**Verify it** against §7: finite differences for end-to-end correctness, and an A/B against the
forced Jacobian (`jacobianContraction`) and a host definition for the rule itself. Check with
[`ar-profile-analyzer`](profiling.md) that the reduced delta's graph is no larger than the forward
operator — a reduced delta that falls through to `jacobianContraction` silently is the bug to catch.

---

## 4. How a matrix multiply's gradient falls out of its parts

A dense layer's gradients are not computed by code that recognizes a matrix multiply. `matmul`'s
multiply-and-sum lowering is built from exactly the computations in §3 — a product, a sum, a
repeat, a reshape — so the dense operator (`LayerFeatures.denseOperator`) yields the weight gradient
`Σ_r g_r ⊗ x_r`, the input gradient `Wᵀ · g_r`, and the row-summed bias gradient by the chain rule
alone. The full account, with measured iteration-space costs, is in
[Training a Language Model — Weight gradients of dense layers](training-a-language-model.md).

The weighted sum (`WeightedSumComputation`) that `matmul` chooses for outputs wider than 1000 has
its own reduced form for both axes: `deltaDependent` builds each operand's reduced delta from a
**transposed weighted sum** of the upstream gradient with the other operand, so the standard
large-output `matmul` path never materializes a full Jacobian. It falls back to
`jacobianContraction` only for a traversal that cannot be transposed into a weighted sum — a sliding
window such as a convolution's — which is the known-limit of §8 rather than a property of the
contract.

---

## 5. The expression level

Below the Producer graph, derivatives are also defined on `Expression` trees. Several
`Expression` and `CollectionExpression` types carry their own `delta`:

- `CollectionExpression.delta(CollectionExpression target)` is the entry point; the default throws
  `UnsupportedOperationException`, and subtypes override it.
- Per-expression rules in `base/code`: `Exponent` (power rule), `Logarithm` (`1/x`), `Max`
  (select the larger operand's derivative), `Mask` (propagate where unmasked, zero elsewhere),
  `ProductCollectionExpression` (product rule with a diagonal optimization),
  `IndexProjectionExpression` (propagate through an index remapping), and
  `SubsetTraversalWeightedSumExpression` (the windowed-sum / convolution rule).
- `TraversableDeltaComputation` performs reverse-mode differentiation by propagating a delta through
  the expression tree along the path between the delta and its target.
  `TransitiveDeltaExpressionComputation` composes the deltas of individual arguments by the chain
  rule across its transitive argument indices.

[`ExpressionMatrix`](../../base/code/src/main/java/io/almostrealism/kernel/ExpressionMatrix.java)
represents a Jacobian at this level: a two-dimensional matrix of `Expression` values whose rows map
to output positions and columns to input positions. It detects when each row has a single non-zero
column (`uniqueNonZeroOffset`) and collapses further when all rows match, which is what makes a
Jacobian **sparse** in practice. Its size limit (`maxMatrixSize`, a cap on `rows × cols`) stops it
expanding when the matrix grows too large, and that matrix — one entry per (output, input) pair — is
itself a cost a reduced delta avoids: the dense `256 × 64` output projection over 64 rows has
`16,384` output positions and `16,384` weight positions, a `2^28`-entry matrix, against about a
million terms for the reduced product form. `jacobianGradient`'s full contraction is larger still,
and a different structure: output size × weight size × inner dimension, `2^34` positions for that
projection, not this matrix's enumeration.

A position that large exceeds a 32-bit integer, so the generated kernel must compute it in 64 bits.
Every generated language renders the kernel index as a 64-bit integer, which makes any index
expression that contains it 64-bit inline, even though the expression tree types it as `Integer`.
When code generation hoists a repeated part of such an expression into a local variable (the
common-subexpression replacement of `Scope.processReplacements`, or the loop-invariant extraction of
`Repeated`), the variable is declared with `Expression.getDeclarationType()`, which promotes an
integer expression that refers to the kernel index, or whose bounds exceed the 32-bit range, to
`Long`. Declaring it with the expression's own `Integer` type made the products the variable took
part in 32-bit; that is what made the full-Jacobian weight gradient of the `256 × 64` output
projection wrong from weight row 64 onward, in a varying tenth of its entries, until the
declaration type was fixed. `DenseVectorJacobianProductTest.outputProjection` now holds that
gradient to its definition on several evaluations.

---

## 6. From deltas to a backward pass

The layer system turns these deltas into a trainable backward pass.

- **`DefaultGradientPropagation`** (`implements BackPropagation, Learning`) builds a layer's
  backward. Given an upstream gradient, it forms the **input gradient** and each **weight's**
  gradient as the `OUT`-reduced delta of the forward function weighted by the upstream gradient:
  ```java
  function.get().delta(input,      ReduceAxis.OUT, gradient).reshape(inSize).each();
  function.get().delta(weights[i], ReduceAxis.OUT, gradient).reshape(weightSize).each();
  ```
  Whether that gradient is *applied* is decided by the weight's update strategy: each weight carries
  a `ParameterUpdate`, and a frozen weight is given `ParameterUpdate.disabled()`, a no-op that emits
  an empty operation — so its gradient is never folded back into the weight.
- **`BackPropagation`** is the strategy interface for propagating a gradient through a layer;
  **`BackPropagationCell`** bridges it to the cell graph, holding the forward input and invoking the
  strategy when a gradient is pushed during the backward pass.
- **`Learning`** marks a component that holds learnable parameters and needs a `ParameterUpdate`
  installed before the first backward pass; **`ParameterUpdate`** is the strategy that applies a
  gradient to a weight (`scaled(learningRate)` for plain descent, `of(factor)` for a custom
  transform, `disabled()` for frozen).
- Gradient routing: **`LayerRoutingFeatures`** wires the branches of structural layers
  (accumulation, concatenation, residual) so both share the gradient flow, and **`BranchBlock`**
  accumulates each child's gradient into a shared buffer before pushing the total upstream.
- **`CompiledModel.backward(gradient)`** stages the loss gradient and runs the compiled backward
  operation — the composed chain of layer backward cells and parameter updates — optionally
  returning the input gradient.
- **`ModelOptimizer`** owns the training loop. Each iteration runs the forward pass, computes the
  loss and its gradient, calls `model.backward(grad)`, and the parameter updates fold into the same
  backward operation. Never write a training `for`-loop outside it (see
  [Training Loop Examples](training-loop-examples.md)).

```
forward:   input ──► layer₁ ──► layer₂ ──► output ──► loss
backward:  dInput ◄── layer₁ ◄── layer₂ ◄── dOutput ◄── dLoss
                       │           │
                   dWeight₁    dWeight₂   (each: delta(weightᵢ, OUT, upstream))
                       ▼           ▼
                   ParameterUpdate applied in the same backward operation
```

---

## 7. Verifying a derivative

| Technique | Where | What it proves |
|-----------|-------|----------------|
| Finite differences | `CausalLanguageModelTest.gradientsMatchFiniteDifferences`, `GradientTestFeatures` | the analytic gradient of a scalar loss matches central differences of the forward pass within tolerance |
| A/B vs forced Jacobian and host definition | `DenseVectorJacobianProductTest` | the reduced delta equals both `jacobianGradient` (the forced full Jacobian) and the gradient computed on the host from `Σ_r g_r ⊗ x_r` and `Wᵀ · g_r` |
| Fallback vs host definition | `ConvolutionJacobianGradientTest` | the input gradient of `convolution2dOperator`, which contracts a `7200 × 8192` Jacobian of 72-term window sums (more terms in all than a signed 32-bit integer counts), equals the gradient computed on the host from the convolution's definition on every one of several evaluations |
| Local rule tests | `ReducedDeltaTest`, `ReducedDeltaRulesTest`, `TraversableDeltaComputationTests`, `DeltaFeaturesTests` | each computation's reduced delta and Jacobian match host-computed values for both axes |
| Graph-size check | `DenseVectorJacobianProductTest.assertNoLargerThanForward` | no computation in the reduced delta's graph is larger than the largest computation of the forward operator — i.e. the Jacobian was never materialized |

For an accidental full-Jacobian fallback, load the run's profile in
[`ar-profile-analyzer`](profiling.md) and inspect the operation tree and generated kernel: a reduced
delta that quietly contracts its Jacobian shows up as an operation far larger than its forward
operator.

---

## 8. Known limits (as of writing)

Each is backed by a source comment, a test, or the training page's measurement. A limit that no
longer holds should be dropped from this list, not restated.

1. **The weighted-sum lowering has no reduced form for non-transposable traversals.**
   `WeightedSumComputation` (used by `matmul` for outputs wider than 1000, and for reductions of at
   least `loopThreshold` = 256 members) produces its own reduced delta through a transposed weighted
   sum wherever the traversal can be transposed — the matrix multiply, broadcast sums and scaled dot
   products. For a traversal that cannot (a sliding window such as a convolution's) it contracts its
   full Jacobian through `jacobianContraction` instead. Correct, but it is the Jacobian-fallback
   cost a reduced form would remove for those windowed cases.
2. **The transformer resampling block buffers its sections.** `TransformerResamplingFeatures`
   (the resampling layer block used by the Stable Audio 3 autoencoder) materializes its K1, V, Q2
   and K2 sections with `into(...)` so the differential attention reads stored tensors. The
   buffering cuts those projections out of the gradient graph, so they do not train.
3. **A learned differential-attention lambda is read as a constant.** In
   `differential_attention.pdsl`, `scale(lambda, 1)` reads lambda as a constant (each site carries a
   `TODO(review)` marker), so the parameters of a learned lambda receive no gradient.

---

## Related files

| Path | Role |
|------|------|
| `compute/algebra/.../collect/CollectionProducer.java` | the `delta` family — the public contract |
| `compute/algebra/.../calculus/ReduceAxis.java` | the axis a delta contracts, and its result/weight shapes |
| `compute/algebra/.../collect/GradientFeatures.java` | `jacobianContraction` / `jacobianGradient` — the full-Jacobian reference |
| `compute/algebra/.../collect/computations/CollectionProducerComputationBase.java` | the extension points (`deltaDependent`, `deltaElement`, `deltaMatrix`, `isChainRuleSupported`) |
| `compute/algebra/.../calculus/InputStub.java` | isolates one input for the chain-rule `deltaPartial` |
| `base/code/.../kernel/ExpressionMatrix.java` | sparse Jacobian at the expression level |
| `domain/graph/.../layers/DefaultGradientPropagation.java` | a layer's input and weight gradients |
| `engine/optimize/.../optimize/ModelOptimizer.java` | owns the training loop |

## See also

- [Training a Language Model](training-a-language-model.md) — weight gradients of dense layers, measured
- [Training Loop Examples](training-loop-examples.md) — `ModelOptimizer` patterns
- [End-to-End Computation](end-to-end-computation.md) — the forward story this page continues
- [Graph Module](../../domain/graph/README.md) — the cell-based forward/backward architecture
