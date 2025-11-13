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

import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputableProcessContext;
import io.almostrealism.compute.ParallelProcessContext;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

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
 * <p>When {@link #setReplaceLoop(boolean)} is enabled and certain conditions are met,
 * the iterative aggregation can be replaced with a direct memory access pattern using
 * unique offset calculation. This significantly improves performance for large reductions.</p>
 *
 * <h2>Subclass Implementation Pattern</h2>
 * <pre>{@code
 * public class CollectionSumComputation<T extends PackedCollection<?>>
 *         extends AggregatedProducerComputation<T> {
 *
 *     public CollectionSumComputation(Producer<PackedCollection<?>> input) {
 *         super("sum", outputShape, elementCount,
 *             (args, index) -> new DoubleConstant(0.0),  // Initial value
 *             (accumulator, element) -> accumulator.add(element),  // Sum operation
 *             input);
 *         setReplaceLoop(true);  // Enable optimization
 *     }
 * }
 * }</pre>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Sum reduction (via subclass):</strong></p>
 * <pre>{@code
 * CollectionProducer<PackedCollection<?>> data = c(1.0, 2.0, 3.0, 4.0, 5.0);
 * CollectionSumComputation<PackedCollection<?>> sum = new CollectionSumComputation<>(data);
 * PackedCollection<?> result = sum.get().evaluate();
 * // Result: [15.0]  (1 + 2 + 3 + 4 + 5)
 * }</pre>
 *
 * <p><strong>Max reduction (via subclass):</strong></p>
 * <pre>{@code
 * CollectionProducer<PackedCollection<?>> data = c(3.0, 7.0, 2.0, 9.0, 5.0);
 * CollectionMaxComputation<PackedCollection<?>> max = new CollectionMaxComputation<>(data);
 * PackedCollection<?> result = max.get().evaluate();
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
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see TraversableRepeatedProducerComputation
 * @see CollectionSumComputation
 * @see CollectionMaxComputation
 * @see org.almostrealism.collect.CollectionFeatures#sum(Producer)
 * @see org.almostrealism.collect.CollectionFeatures#max(Producer)
 *
 * @author Michael Murray
 */
public class AggregatedProducerComputation<T extends PackedCollection<?>> extends TraversableRepeatedProducerComputation<T> {
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
	 * The aggregation expression that combines the accumulator with each new element.
	 * This binary function is applied repeatedly: accumulator = expression(accumulator, element).
	 */
	private BiFunction<Expression, Expression, Expression> expression;

	/**
	 * Flag indicating whether to replace the iterative loop with a direct memory access pattern.
	 * When true and applicable, uses unique offset calculation for improved performance.
	 */
	private boolean replaceLoop;

	private TraversableExpression<Double> inputArg;
	private DefaultIndex row, ref;
	private Expression<Integer> uniqueOffset;
	private Expression<? extends Number> uniqueIndex;

	private Map<String, Expression<?>> indexCache;

	/**
	 * Constructs an aggregated producer computation with specified aggregation parameters.
	 *
	 * <p>This constructor sets up the aggregation operation by defining:</p>
	 * <ul>
	 *   <li>The output shape (typically reduced from input size)</li>
	 *   <li>The number of elements to aggregate per output element</li>
	 *   <li>The initial accumulator value</li>
	 *   <li>The aggregation expression applied iteratively</li>
	 * </ul>
	 *
	 * @param name The operation identifier (e.g., "sum", "max", "product")
	 * @param shape The {@link TraversalPolicy} defining the output shape (typically smaller than input)
	 * @param count The number of input elements to aggregate per output element
	 * @param initial Function providing the initial accumulator value (e.g., 0.0 for sum, -infinity for max)
	 * @param expression Binary function combining accumulator with each element:
	 *                   {@code (accumulator, element) -> newAccumulator}
	 * @param arguments The input {@link Producer}s providing data to aggregate
	 *
	 * @see CollectionSumComputation
	 * @see CollectionMaxComputation
	 */
	public AggregatedProducerComputation(String name, TraversalPolicy shape, int count,
										 BiFunction<TraversableExpression[], Expression, Expression> initial,
										 BiFunction<Expression, Expression, Expression> expression,
										 Producer<PackedCollection<?>>... arguments) {
		super(name, shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;

		if (enableIndexCache)
			indexCache = new HashMap<>();

		if (enableLogging)
			log("Created AggregatedProducerComputation (" + count + " items)");
	}

	/**
	 * Returns whether loop replacement optimization is enabled for this aggregation.
	 *
	 * @return {@code true} if loop replacement is enabled, {@code false} otherwise
	 * @see #setReplaceLoop(boolean)
	 */
	public boolean isReplaceLoop() {
		return replaceLoop;
	}

	/**
	 * Enables or disables loop replacement optimization for this aggregation.
	 *
	 * <p>When enabled and applicable (single memory length, fixed count, unique offset available),
	 * the iterative aggregation loop is replaced with a direct memory access pattern using
	 * unique offset calculation. This can significantly improve performance for large reductions.</p>
	 *
	 * @param replaceLoop {@code true} to enable loop replacement, {@code false} to use standard iteration
	 * @return This computation instance for method chaining
	 *
	 * @see #prepareScope(ScopeInputManager, KernelStructureContext)
	 */
	public AggregatedProducerComputation<T> setReplaceLoop(boolean replaceLoop) {
		this.replaceLoop = replaceLoop;
		return this;
	}

	/**
	 * Indicates whether signature generation is supported for this aggregation.
	 * Signatures are used for caching and deduplication of computations.
	 *
	 * <p>Aggregations typically do not support signatures due to their complex
	 * and dynamic nature.</p>
	 *
	 * @return Always {@code false} for base aggregated computations
	 */
	protected boolean isSignatureSupported() { return false; }

	@Override
	public boolean isChainRuleSupported() {
		return enableChainRule || super.isChainRuleSupported();
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		if (!replaceLoop || getMemLength() > 1 || getIndexLimit().isEmpty())
			return;

		if (context == null) {
			throw new UnsupportedOperationException();
		}

		if (isFixedCount()) {
			inputArg = getCollectionArgumentVariable(1);
			if (inputArg == null) return;

			row = new DefaultIndex(getNameProvider().getVariablePrefix() + "_g");
			row.setLimit(getShape().getCountLong());

			ref = new DefaultIndex(getNameProvider().getVariablePrefix() + "_i");
			getIndexLimit().ifPresent(ref::setLimit);

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

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		if (uniqueIndex == null) return super.getScope(context);

		Scope<T> scope = new Scope<>(getFunctionName(), getMetadata());

		Expression<?> out = getDestination(new KernelIndex(context), ref, e(0));
		Expression<?> val = inputArg.getValueAt(uniqueIndex.withIndex(row, new KernelIndex(context)));
		scope.getStatements().add(out.assign(val));
		return scope;
	}

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

			TraversableExpression args[] = getTraversableArguments(index);

			Expression value = initial.apply(args, e(0));
			if (enableLogging)
				log("Generating values for aggregation " + getShape().toStringDetail());

			for (int i = 0; i < count; i++) {
				value = expression.apply(value, args[1].getValueAt(index.multiply(count).add(e(i))));

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
		return expression.apply(currentValue, args[1].getValueAt(globalIndex.multiply(count).add(localIndex)));
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

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<?> delta = attemptDelta(target);
		if (delta != null) return (CollectionProducer) delta;

		if (enableTransitiveDelta && getInputs().size() == 2 && getInputs().get(1) instanceof CollectionProducer) {
			int outLength = ((CollectionProducer<T>) getInputs().get(1)).getShape().getTotalSize();
			int inLength = shape(target).getTotalSize();

			if (AlgebraFeatures.match(getInputs().get(1), target)) {
				delta = identity(shape(inLength, outLength))
						.reshape(inLength, outLength, 1).traverse(0);
			} else {
				delta = ((CollectionProducer) getInputs().get(1)).delta(target);
				delta = delta.reshape(outLength, inLength);
				delta = delta.transpose();
			}

			delta = delta.enumerate(1, count).traverse(2);
			return new AggregatedProducerComputation<>(getName(), shape(delta).replace(shape(1)),
						count, initial, expression, (Producer) delta)
					.setReplaceLoop(isReplaceLoop())
					.reshape(getShape().append(shape(target)));
		} else {
			delta = super.delta(target);
			if (delta instanceof ConstantRepeatedDeltaComputation) {
				TraversableDeltaComputation<T> traversable = TraversableDeltaComputation.create("delta", getShape(), shape(target),
						args -> CollectionExpression.create(getShape(), this::getValueAt), target,
						getInputs().stream().skip(1).toArray(Producer[]::new));
				traversable.addDependentLifecycle(this);
				((ConstantRepeatedDeltaComputation) delta).setFallback(traversable);
			}

			return (CollectionProducer) delta;
		}
	}

	@Override
	public AggregatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		AggregatedProducerComputation<T> c = new AggregatedProducerComputation<>(getName(), getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Producer[]::new));
		c.setReplaceLoop(replaceLoop);
		return c;
	}

	@Override
	public String signature() { return isSignatureSupported() ? super.signature() : null; }
}
