/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputableProcessContext;
import io.almostrealism.compute.ParallelProcessContext;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.Expression;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for computations that aggregate elements of a {@link PackedCollection}
 * through iterative reduction operations.
 *
 * <p>This class extends {@link TraversableRepeatedProducerComputation} to provide a foundation
 * for reduction operations that combine multiple input elements into fewer output elements
 * through a repeated application of an aggregation function. Common examples include sum,
 * max, min, and product operations.</p>
 *
 * <h2>Aggregation Pattern</h2>
 * <p>The aggregation process follows this general pattern:</p>
 * <pre>
 * 1. Initialize accumulator with initial value
 * 2. For each input element (count times):
 *    accumulator = expression(accumulator, input[i])
 * 3. Return final accumulator value
 * </pre>
 *
 * <h2>Mathematical Formulation</h2>
 * <p>For an aggregation function f and input elements [x1, x2, ..., xn]:</p>
 * <pre>
 * result = f(...f(f(initial, x1), x2)..., xn)
 * </pre>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li><strong>Initial Value:</strong> Starting accumulator state (e.g., 0 for sum, -infinity for max)</li>
 *   <li><strong>Aggregation Expression:</strong> Binary function combining accumulator with new element</li>
 *   <li><strong>Iteration Count:</strong> Number of elements to aggregate</li>
 *   <li><strong>Loop Replacement:</strong> Optional optimization to replace iteration with direct expression</li>
 * </ul>
 *
 * <h2>Optimization Features</h2>
 * <p>The class provides several optimization strategies controlled by static flags:</p>
 * <ul>
 *   <li><strong>{@link #enableTransitiveDelta}:</strong> Enables efficient gradient computation for aggregations</li>
 *   <li><strong>{@link #enableChainRule}:</strong> Supports chain rule application in automatic differentiation</li>
 *   <li><strong>{@link #enableIndexSimplification}:</strong> Simplifies index expressions to reduce complexity</li>
 *   <li><strong>{@link #enableIndexCache}:</strong> Caches computed index values to avoid recomputation</li>
 *   <li><strong>{@link #enableLogging}:</strong> Enables verbose logging for debugging</li>
 * </ul>
 *
 * <h2>Loop Replacement Optimization</h2>
 * <p>When loop replacement is enabled at construction and certain conditions are met,
 * the iterative aggregation can be replaced with a direct memory access pattern using
 * unique offset calculation. This significantly improves performance for large reductions.
 * Use {@link #withReplaceLoop(boolean)} to obtain an equivalent aggregation with a
 * different setting.</p>
 *
 * <h2>Subclass Implementation Pattern</h2>
 * <pre>{@code
 * public class CollectionSumComputation<T extends PackedCollection>
 *         extends AggregatedProducerComputation<T> {
 *
 *     public CollectionSumComputation(Producer<PackedCollection> input) {
 *         super("sum", outputShape, elementCount,
 *             true,  // Enable loop replacement
 *             input);
 *     }
 *
 *     @Override
 *     protected Expression<?> getInitialValue(TraversableExpression[] args, Expression index) {
 *         return new DoubleConstant(0.0);  // Initial value
 *     }
 *
 *     @Override
 *     protected Expression<?> combine(Expression<?> accumulator, Expression<?> element) {
 *         return accumulator.add(element);  // Sum operation
 *     }
 * }
 * }</pre>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Sum reduction (via subclass):</strong></p>
 * <pre>{@code
 * CollectionProducer data = c(1.0, 2.0, 3.0, 4.0, 5.0);
 * CollectionSumComputation<PackedCollection> sum = new CollectionSumComputation<>(data);
 * PackedCollection result = sum.get().evaluate();
 * // Result: [15.0]  (1 + 2 + 3 + 4 + 5)
 * }</pre>
 *
 * <p><strong>Max reduction (via subclass):</strong></p>
 * <pre>{@code
 * CollectionProducer data = c(3.0, 7.0, 2.0, 9.0, 5.0);
 * CollectionMaxComputation<PackedCollection> max = new CollectionMaxComputation<>(data);
 * PackedCollection result = max.get().evaluate();
 * // Result: [9.0]
 * }</pre>
 *
 * <h2>Gradient Computation</h2>
 * <p>When {@link #enableTransitiveDelta} is true, the {@link #delta(Producer)} method
 * implements efficient gradient propagation through the aggregation. For sum operations,
 * the gradient is distributed equally to all inputs. For max operations, the gradient
 * flows only to the maximum element.</p>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the iteration count</li>
 *   <li><strong>Memory:</strong> Output size determined by output shape (typically much smaller than input)</li>
 *   <li><strong>Parallelization:</strong> Parallel reduction strategies when applicable</li>
 *   <li><strong>Loop Optimization:</strong> Can replace iteration with direct access via unique offset</li>
 * </ul>
 *
 * @see TraversableRepeatedProducerComputation
 * @see CollectionSumComputation
 * @see CollectionMaxComputation
 * @see org.almostrealism.collect.CollectionFeatures#sum(Producer)
 * @see org.almostrealism.collect.CollectionFeatures#max(Producer)
 *
 * @author Michael Murray
 */
public abstract class AggregatedProducerComputation extends TraversableRepeatedProducerComputation {
	/**
	 * Enables efficient gradient computation for aggregations using transitive delta propagation.
	 * When true, {@link #delta(Producer)} computes gradients by distributing them through
	 * the aggregation operation rather than using the default chain rule.
	 * Default: {@code true}.
	 */
	public static boolean enableTransitiveDelta = true;

	/**
	 * Enables chain rule application in automatic differentiation.
	 * When true, allows the computation to participate in chain rule gradient calculations.
	 * Default: {@code false}.
	 */
	public static boolean enableChainRule = false;

	/**
	 * Enables simplification of index expressions to reduce computational complexity.
	 * When true, {@link Expression#simplify()} is called on index expressions before evaluation.
	 * Default: {@code true}.
	 */
	public static boolean enableIndexSimplification = true;

	/**
	 * Enables caching of computed index values to avoid redundant calculations.
	 * When true, maintains a {@link Map} of previously computed index expressions.
	 * Default: {@code false}.
	 */
	public static boolean enableIndexCache = false;

	/**
	 * Enables verbose logging for debugging aggregation operations.
	 * When true, logs detailed information about aggregation progress, index calculations,
	 * and optimization decisions.
	 * Default: {@code false}.
	 */
	public static boolean enableLogging = false;

	/**
	 * Flag indicating whether to replace the iterative loop with a direct memory access pattern.
	 * When true and applicable, uses unique offset calculation for improved performance.
	 * Fixed at construction because it participates in {@link #signature()}.
	 */
	private final boolean replaceLoop;

	/** The traversable expression referencing the primary input argument during scope preparation. */
	private TraversableExpression<Double> inputArg;

	/** The loop row index used to iterate over input elements during aggregation kernel generation. */
	private DefaultIndex row;

	/** The reference index used for index cache lookups and offset calculations. */
	private DefaultIndex ref;

	/** The unique integer offset used when loop replacement is active. */
	private Expression<Integer> uniqueOffset;

	/** The unique numeric index used when loop replacement is active. */
	private Expression<? extends Number> uniqueIndex;

	/** Cache mapping variable prefix strings to index expressions for repeated aggregation operations. */
	private Map<String, Expression<?>> indexCache;

	/**
	 * Constructs an aggregated producer computation with specified aggregation parameters.
	 *
	 * <p>The initial accumulator value and the operation that combines it with each element
	 * are defined by the concrete subclass, through
	 * {@link #getInitialValue(TraversableExpression[], Expression)} and
	 * {@link #combine(Expression, Expression)}.</p>
	 *
	 * @param name The operation identifier (e.g., "sum", "max", "product")
	 * @param shape The {@link TraversalPolicy} defining the output shape (typically smaller than input)
	 * @param count The number of input elements to aggregate per output element
	 * @param arguments The input {@link Producer}s providing data to aggregate
	 *
	 * @see CollectionSumComputation
	 * @see CollectionMaxComputation
	 */
	protected AggregatedProducerComputation(String name, TraversalPolicy shape, int count,
										 Producer<PackedCollection>... arguments) {
		this(name, shape, count, false, arguments);
	}

	/**
	 * Constructs an aggregated producer computation with specified aggregation
	 * parameters and loop replacement setting.
	 *
	 * @param name The operation identifier (e.g., "sum", "max", "product")
	 * @param shape The {@link TraversalPolicy} defining the output shape (typically smaller than input)
	 * @param count The number of input elements to aggregate per output element
	 * @param replaceLoop {@code true} to replace the iterative aggregation loop with
	 *                    a direct memory access pattern when applicable
	 * @param arguments The input {@link Producer}s providing data to aggregate
	 */
	protected AggregatedProducerComputation(String name, TraversalPolicy shape, int count,
										 boolean replaceLoop,
										 Producer<PackedCollection>... arguments) {
		super(name, shape, count, arguments);
		this.count = count;
		this.replaceLoop = replaceLoop;

		if (enableIndexCache)
			indexCache = new HashMap<>();

		if (enableLogging)
			log("Created AggregatedProducerComputation (" + count + " items)");

		// Refresh the signature captured before the aggregation state was assigned
		init();
	}

	/**
	 * Combines the accumulator with one more element of the group being aggregated. The
	 * accumulator starts at {@link #getInitialValue(TraversableExpression[], Expression)}.
	 *
	 * @param accumulator the value aggregated so far
	 * @param element     the next element of the group
	 * @return the value aggregated after including the element
	 */
	protected abstract Expression<?> combine(Expression<?> accumulator, Expression<?> element);

	/**
	 * Returns whether loop replacement optimization is enabled for this aggregation.
	 *
	 * @return {@code true} if loop replacement is enabled, {@code false} otherwise
	 * @see #withReplaceLoop(boolean)
	 */
	public boolean isReplaceLoop() {
		return replaceLoop;
	}

	/**
	 * Returns an aggregation like this one with the given loop replacement setting.
	 *
	 * <p>When enabled and applicable (single memory length, fixed count, unique offset available),
	 * the iterative aggregation loop is replaced with a direct memory access pattern using
	 * unique offset calculation. This can significantly improve performance for large reductions.</p>
	 *
	 * <p>The setting participates in {@link #signature()}, so it is fixed at construction;
	 * changing it produces a new computation rather than mutating this one.</p>
	 *
	 * @param replaceLoop {@code true} to enable loop replacement, {@code false} to use standard iteration
	 * @return This computation when the setting already matches, otherwise a new
	 *         aggregation of the same concrete type over the same inputs
	 *
	 * @see #prepareScope(ArgumentProvider, KernelStructureContext)
	 */
	public abstract AggregatedProducerComputation withReplaceLoop(boolean replaceLoop);

	/**
	 * Indicates whether signature generation is supported for this aggregation.
	 * Signatures are used for caching and deduplication of computations.
	 *
	 * <p>The signature produced by {@link #signature()} captures the aggregation
	 * state that determines the generated kernel: the iteration count and the loop
	 * replacement setting, together with the concrete class (appended by
	 * {@link RepeatedProducerComputation#signature()}) that fixes the initial and
	 * combining behaviour.</p>
	 *
	 * @return Always {@code true} for aggregated computations
	 */
	protected boolean isSignatureSupported() { return true; }

	@Override
	protected boolean isChainRuleSupported() {
		return enableChainRule || super.isChainRuleSupported();
	}

	/** An aggregation reduces each group of input elements to a single output. */
	@Override
	public boolean isReduction() {
		return true;
	}

	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		if (!replaceLoop || getMemLength() > 1 || getIndexLimit().isEmpty())
			return;

		if (context == null) {
			throw new UnsupportedOperationException();
		}

		if (isFixedCount()) {
			inputArg = getCollectionArgumentVariable(1);
			if (inputArg == null) return;

			row = new DefaultIndex(getVariablePrefix() + "_g", getShape().getCountLong());
			ref = new DefaultIndex(getVariablePrefix() + "_i", getIndexLimit().getAsInt());

			Expression index = Index.child(row, ref);
			uniqueOffset = inputArg.uniqueNonZeroOffset(row, ref, index);
			if (uniqueOffset == null) {
				if (enableLogging)
					log("Unable to determine unique offset for AggregatedProducerComputation:" +
							getMetadata().getId() + " (" + count + " items)");

				return;
			}

			uniqueIndex = row
					.multiply(Math.toIntExact(ref.getLimit().getAsLong()))
					.add(uniqueOffset);

			if (enableLogging) {
				log("Unique offset for AggregatedProducerComputation:" +
						getMetadata().getId() + " (" + count + " items) is " +
						uniqueOffset.getExpressionSummary());
			}
		}
	}

	/**
	 * Generates the kernel scope for this aggregation computation.
	 * When loop replacement is active (i.e., a unique index has been identified),
	 * produces an optimized scope that reads a single element directly instead of
	 * iterating. Otherwise delegates to the parent implementation.
	 *
	 * @param context the kernel structure context for scope generation
	 * @return the kernel scope implementing the aggregation operation
	 */
	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		if (uniqueIndex == null) return super.getScope(context);

		Scope<PackedCollection> scope = new Scope<>(getFunctionName(), getMetadata());

		Expression<?> out = getDestination(new KernelIndex(context), ref, e(0));
		Expression<?> val = inputArg.getValueAt(uniqueIndex.withIndex(row, new KernelIndex(context)));
		scope.getStatements().add(out.assign(val));
		return scope;
	}

	/**
	 * Looks up a previously computed index expression from the cache.
	 * Returns null if the cache is disabled or the expression is too large to cache.
	 *
	 * @param index the index expression to look up
	 * @return the cached expression, or null if not found or caching is disabled
	 */
	protected Expression<?> checkCache(Expression<?> index) {
		if (indexCache == null || index.countNodes() > 100) return null;

		String key = index.getExpression(Expression.defaultLanguage());
		if (indexCache.containsKey(key)) {
			if (enableLogging)
				log("Using cached value for index " + key);

			return indexCache.get(key);
		}

		return null;
	}

	/**
	 * Stores a computed index expression result in the cache for future reuse.
	 * Does nothing if the cache is disabled or the expression is too large to cache.
	 *
	 * @param index the index expression used as the cache key
	 * @param result the computed expression value to store
	 * @return the result expression, unchanged
	 */
	protected Expression<Double> cache(Expression<?> index, Expression<Double> result) {
		if (indexCache == null || index.countNodes() > 100) return result;

		String key = index.getExpression(Expression.defaultLanguage());
		indexCache.put(key, result);
		return result;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (uniqueIndex == null) {
			if (enableIndexSimplification) {
				index = index.simplify();
			}

			Expression e = checkCache(index);
			if (e != null) return e;

			TraversableExpression[] args = getTraversableArguments(index);

			Expression value = getInitialValue(args, e(0));
			if (enableLogging)
				log("Generating values for aggregation " + getShape().toStringDetail());

			for (int i = 0; i < count; i++) {
				value = combine(value, args[1].getValueAt(index.multiply(count).add(e(i))));

				if (enableLogging)
					log("Added value " + i + "/" + count + " (" + value.countNodes() + " total nodes)");

				value = value.generate(value.flatten());

				if (enableLogging && value.countNodes() > 10000000) {
					log("Returning early due to excessive node count (" + value.countNodes() + ")");
					return value;
				}
			}

			return cache(index, value);
		} else {
			Expression uniqueIndex = index
					.multiply(Math.toIntExact(ref.getLimit().getAsLong()))
					.add(uniqueOffset.withIndex(row, index));
			return inputArg.getValueAt(uniqueIndex);
		}
	}

	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		CollectionVariable var = (CollectionVariable) args[0];

		Expression k = globalIndex instanceof KernelIndex ? globalIndex : new KernelIndex();
		Expression currentValue = var.reference(k.multiply(var.length()));
		return combine(currentValue, args[1].getValueAt(globalIndex.multiply(count).add(localIndex)));
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (uniqueOffset == null)
			return null;

		return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	public ParallelProcessContext createContext(ProcessContext ctx) {
		return ComputableProcessContext.of(ctx, this, count);
	}

	/**
	 * Computes the derivative of this aggregation with respect to the target.
	 *
	 * <p>When transitive-delta propagation applies, the enumerated Jacobian groups are
	 * reduced by a named {@link CollectionSumComputation} rather than a self-constructed
	 * aggregation. This is correct for every aggregation that reaches this branch because
	 * they are all additive: {@link CollectionSumComputation} and
	 * {@link org.almostrealism.algebra.computations.LoopedWeightedSumComputation} combine
	 * with addition, while {@link CollectionMaxComputation} overrides {@code deltaDependent}
	 * and routes through its selection, so it never reaches here. The sum reproduces the
	 * output shape ({@code shape(delta).replace(shape(1))}) and iteration count (the delta's
	 * per-group size, which equals {@code count}) that a raw aggregation would have had.</p>
	 *
	 * @param target the {@link Producer} to differentiate with respect to
	 * @return a {@link CollectionProducer} computing the derivative
	 */
	@Override
	protected CollectionProducer deltaMatrix(Producer<?> target) {
		CollectionProducer delta;

		if (enableTransitiveDelta && getInputs().size() == 2 && getInputs().get(1) instanceof CollectionProducer) {
			int outLength = ((CollectionProducer) getInputs().get(1)).getShape().getTotalSize();
			int inLength = shape(target).getTotalSize();

			if (getInputs().get(1).refersTo(target)) {
				delta = identity(shape(inLength, outLength))
						.reshape(inLength, outLength, 1).traverse(0);
			} else {
				delta = ((CollectionProducer) getInputs().get(1)).delta(target);
				delta = delta.reshape(outLength, inLength);
				delta = delta.transpose();
			}

			delta = delta.enumerate(1, count).traverse(2);

			// Every aggregation reaching this branch is additive, so the reduction is a named sum
			return new CollectionSumComputation(shape(delta), delta)
					.reshape(getShape().append(shape(target)));
		} else {
			ConstantRepeatedDeltaComputation repeated = new ConstantRepeatedDeltaComputation(this, target);
			TraversableDeltaComputation traversable = TraversableDeltaComputation.create("delta", getShape(), shape(target),
					args -> CollectionExpression.create(getShape(), this::getValueAt), target,
					getInputs().stream().skip(1).toArray(Producer[]::new));
			traversable.addDependentLifecycle(this);
			repeated.setFallback(traversable);
			return repeated;
		}
	}

	@Override
	public abstract AggregatedProducerComputation generate(List<Process<?, ?>> children);

	/**
	 * Extends the standard computation signature with the aggregation state
	 * that determines the generated kernel: the iteration count and the loop
	 * replacement setting. The concrete class — which fixes the initial and
	 * combining behaviour, since the base is abstract and every subclass defines
	 * its own loop body — is already appended by
	 * {@link RepeatedProducerComputation#signature()}, so two aggregations that
	 * share a name and shape but combine values differently (a different class)
	 * never collide.
	 *
	 * @return The signature string, or null when the base signature is
	 *         unavailable or the aggregation state has not been assigned yet
	 */
	@Override
	public String signature() {
		if (!isSignatureSupported()) return null;

		String signature = super.signature();
		if (signature == null) return null;

		return signature + "{count:" + count +
				",replaceLoop:" + replaceLoop + "}";
	}
}
