/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MaximumValue;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs a reduction operation to find the minimum element in a {@link PackedCollection}.
 *
 * <p>This class extends {@link AggregatedExtremumComputation} to implement a min reduction,
 * finding the smallest value among all elements of an input collection. The result is a
 * single-element collection containing the minimum value. It is the mirror of
 * {@link CollectionMaxComputation}, differing only in the direction of the comparison.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input collection C with elements [c1, c2, ..., cn], the computation produces:</p>
 * <pre>
 * result = min(c1, c2, c3, ..., cn)
 * </pre>
 * <p>The result is a single-element collection containing the smallest input value.</p>
 *
 * <h2>Implementation Details</h2>
 * <p>The minimum is computed using an aggregation pattern:</p>
 * <ul>
 *   <li><strong>Initialization:</strong> Accumulator starts at {@link MaximumValue} (the largest finite value)</li>
 *   <li><strong>Accumulation:</strong> Each element is compared to the current minimum</li>
 *   <li><strong>Iteration:</strong> Fixed count equal to the input collection size</li>
 *   <li><strong>Comparison:</strong> Uses {@link Expression#min(Expression)} for element-wise min</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Finding minimum in a vector:</strong></p>
 * <pre>{@code
 * CollectionProducer data = c(-5.0, 10.0, 3.0);
 * CollectionProducer minimum = min(data);
 * // Result: [-5.0]
 * }</pre>
 *
 * @see AggregatedExtremumComputation
 * @see CollectionMaxComputation
 * @see CollectionSumComputation
 * @see io.almostrealism.expression.Min
 * @see io.almostrealism.expression.MaximumValue
 * @see org.almostrealism.collect.AggregationFeatures#min(io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 */
public class CollectionMinComputation extends AggregatedExtremumComputation {
	/**
	 * Constructs a min computation that reduces the input collection to a single minimum value.
	 * The input shape is automatically determined from the producer.
	 *
	 * @param input The {@link Producer} providing the collection to find the minimum of
	 */
	public CollectionMinComputation(Producer<PackedCollection> input) {
		this(CollectionFeatures.getInstance().shape(input), input);
	}

	/**
	 * Constructs a min computation with an explicit shape specification.
	 * This constructor sets up the aggregation with:
	 * <ul>
	 *   <li>Output shape: single element (shape.replace(shape(1)))</li>
	 *   <li>Iteration count: total input size</li>
	 *   <li>Initial value: the largest finite value ({@link MaximumValue})</li>
	 *   <li>Aggregation function: min(out, arg)</li>
	 * </ul>
	 *
	 * @param shape The {@link TraversalPolicy} of the input collection
	 * @param input The {@link Producer} providing the collection to find the minimum of
	 */
	protected CollectionMinComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("min", shape, input);
	}

	/**
	 * Returns the maximum representable value, the identity of the minimum.
	 *
	 * @param args  the traversable arguments
	 * @param index the position within the output block
	 * @return the maximum value
	 */
	@Override
	protected Expression<?> getInitialValue(TraversableExpression[] args, Expression index) {
		return new MaximumValue();
	}

	/**
	 * Returns the smaller of the accumulator and the element.
	 *
	 * @param accumulator the minimum so far
	 * @param element     the next element
	 * @return {@code min(accumulator, element)}
	 */
	@Override
	protected Expression<?> combine(Expression<?> accumulator, Expression<?> element) {
		return accumulator.min(element);
	}

	/**
	 * Generates a new min computation with the specified child processes.
	 * This method creates a new instance with the same configuration but using
	 * the child process at index 1 as the input source.
	 *
	 * @param children List of child {@link Process} instances where children.get(1) is the input
	 * @return A new {@link CollectionMinComputation} for the child input
	 */
	@Override
	public CollectionMinComputation generate(List<Process<?, ?>> children) {
		return new CollectionMinComputation((Producer<PackedCollection>) children.get(1));
	}

	/**
	 * Returns this computation unchanged when the setting already matches. A min reduction
	 * is constructed without loop replacement and has no reason to toggle it, so a change to
	 * the setting is not supported here (matching the weighted-sum precedent).
	 *
	 * @param replaceLoop the requested loop replacement setting
	 * @return this computation
	 * @throws UnsupportedOperationException if the setting differs from the current one
	 */
	@Override
	public CollectionMinComputation withReplaceLoop(boolean replaceLoop) {
		if (isReplaceLoop() == replaceLoop) return this;
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the index of the minimum element of each group of the input, which locates the
	 * element on which the minimum and its derivative depend.
	 *
	 * @param input the input collection, traversed at the axis whose items are the groups
	 * @return a computation of the position of the minimum element of each group
	 */
	@Override
	protected CollectionProducer indexOfExtremum(Producer<PackedCollection> input) {
		return indexOfMin(input);
	}

	/**
	 * Returns the operation name of the selection projection for the minimum.
	 *
	 * @return {@code "projectMin"}
	 */
	@Override
	protected String projectionName() {
		return "projectMin";
	}
}
