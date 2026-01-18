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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.MinimumValue;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs a reduction operation to find the maximum element in a {@link PackedCollection}.
 *
 * <p>This class extends {@link AggregatedProducerComputation} to implement a max reduction,
 * finding the largest value among all elements of an input collection. The result is a
 * single-element collection containing the maximum value.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input collection C with elements [c1, c2, ..., cn], the computation produces:</p>
 * <pre>
 * result = max(c1, c2, c3, ..., cn)
 * </pre>
 * <p>The result is a single-element collection containing the largest input value.</p>
 *
 * <h2>Implementation Details</h2>
 * <p>The maximum is computed using an aggregation pattern:</p>
 * <ul>
 *   <li><strong>Initialization:</strong> Accumulator starts at {@link MinimumValue} (negative infinity)</li>
 *   <li><strong>Accumulation:</strong> Each element is compared to the current maximum</li>
 *   <li><strong>Iteration:</strong> Fixed count equal to the input collection size</li>
 *   <li><strong>Comparison:</strong> Uses {@link Max#of(io.almostrealism.expression.Expression, io.almostrealism.expression.Expression)} for element-wise max</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Finding maximum in a vector:</strong></p>
 * <pre>{@code
 * CollectionProducer vector = c(3.0, 7.0, 2.0, 9.0, 5.0);
 * CollectionMaxComputation<PackedCollection> max =
 *     new CollectionMaxComputation<>(vector);
 *
 * PackedCollection result = max.get().evaluate();
 * // Result: [9.0]
 * }</pre>
 *
 * <p><strong>Finding maximum in a matrix (all elements):</strong></p>
 * <pre>{@code
 * CollectionProducer matrix = c(shape(2, 3),
 *     1.0, 8.0, 3.0,
 *     4.0, 2.0, 6.0);
 *
 * CollectionMaxComputation<PackedCollection> max =
 *     new CollectionMaxComputation<>(matrix);
 *
 * PackedCollection result = max.get().evaluate();
 * // Result: [8.0]
 * }</pre>
 *
 * <p><strong>Using via CollectionFeatures:</strong></p>
 * <pre>{@code
 * // More commonly used through helper methods
 * CollectionProducer data = c(-5.0, 10.0, 3.0);
 * CollectionProducer maximum = max(data);
 * // Result: [10.0]
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the number of elements</li>
 *   <li><strong>Memory:</strong> Single-element output collection</li>
 *   <li><strong>Parallelization:</strong> Can use parallel reduction strategies</li>
 *   <li><strong>Numerical Stability:</strong> Handles negative infinity as initial value</li>
 * </ul>
 *
 * <h2>Comparison with Related Operations</h2>
 * <ul>
 *   <li><strong>CollectionMaxComputation:</strong> Reduction to single max value (this class)</li>
 *   <li><strong>CollectionSumComputation:</strong> Reduction to sum</li>
 *   <li><strong>Min Operation:</strong> Similar but finds minimum value</li>
 *   <li><strong>ArgMax:</strong> Finds the index of the maximum value</li>
 * </ul>
 *
 * @see AggregatedProducerComputation
 * @see CollectionSumComputation
 * @see io.almostrealism.expression.Max
 * @see io.almostrealism.expression.MinimumValue
 * @see org.almostrealism.collect.CollectionFeatures#max(io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 */
public class CollectionMaxComputation extends AggregatedProducerComputation {
	/**
	 * Constructs a max computation that reduces the input collection to a single maximum value.
	 * The input shape is automatically determined from the producer.
	 *
	 * @param input The {@link Producer} providing the collection to find the maximum of
	 */
	public CollectionMaxComputation(Producer<PackedCollection> input) {
		this(CollectionFeatures.getInstance().shape(input), input);
	}

	/**
	 * Constructs a max computation with an explicit shape specification.
	 * This constructor sets up the aggregation with:
	 * <ul>
	 *   <li>Output shape: single element (shape.replace(shape(1)))</li>
	 *   <li>Iteration count: total input size</li>
	 *   <li>Initial value: negative infinity ({@link MinimumValue})</li>
	 *   <li>Aggregation function: max(out, arg)</li>
	 * </ul>
	 *
	 * @param shape The {@link TraversalPolicy} of the input collection
	 * @param input The {@link Producer} providing the collection to find the maximum of
	 */
	protected CollectionMaxComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("max", shape.replace(new TraversalPolicy(1)), shape.getSize(),
				(args, index) -> new MinimumValue(),
				(out, arg) -> Max.of(out, arg),
				input);
	}

	/**
	 * Generates a new max computation with the specified child processes.
	 * This method creates a new instance with the same configuration but using
	 * the child process at index 1 as the input source.
	 *
	 * @param children List of child {@link Process} instances where children.get(1) is the input
	 * @return A new {@link CollectionMaxComputation} for the child input
	 */
	@Override
	public CollectionMaxComputation generate(List<Process<?, ?>> children) {
		return new CollectionMaxComputation((Producer<PackedCollection>) children.get(1));
	}

	/**
	 * Indicates that this computation supports signature generation for caching
	 * and deduplication purposes.
	 *
	 * @return {@code true} to enable signature support
	 */
	@Override
	protected boolean isSignatureSupported() { return true; }
}
