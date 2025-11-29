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
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A computation that performs a reduction operation to sum all elements in a {@link PackedCollection}.
 *
 * <p>This class extends {@link AggregatedProducerComputation} to implement a sum reduction,
 * aggregating all elements of an input collection into a single scalar value. Unlike
 * {@link CollectionAddComputation} which performs element-wise addition of multiple collections,
 * this computation reduces a single collection to its total sum.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input collection C with elements [c1, c2, ..., cn], the computation produces:</p>
 * <pre>
 * result = c1 + c2 + c3 + ... + cn
 * </pre>
 * <p>The result is a single-element collection containing the sum of all input elements.</p>
 *
 * <h2>Implementation Details</h2>
 * <p>The sum is computed using an aggregation pattern:</p>
 * <ul>
 *   <li><strong>Initialization:</strong> Accumulator starts at 0.0</li>
 *   <li><strong>Accumulation:</strong> Each element is added to the running sum</li>
 *   <li><strong>Iteration:</strong> Fixed count equal to the input collection size</li>
 *   <li><strong>Loop Replacement:</strong> Enabled for optimization opportunities</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Summing a vector:</strong></p>
 * <pre>{@code
 * CollectionProducer<PackedCollection> vector = c(1.0, 2.0, 3.0, 4.0, 5.0);
 * CollectionSumComputation<PackedCollection> sum =
 *     new CollectionSumComputation<>(vector);
 *
 * PackedCollection result = sum.get().evaluate();
 * // Result: [15.0]  (1 + 2 + 3 + 4 + 5)
 * }</pre>
 *
 * <p><strong>Summing a matrix (all elements):</strong></p>
 * <pre>{@code
 * CollectionProducer<PackedCollection> matrix = c(shape(2, 3),
 *     1.0, 2.0, 3.0,
 *     4.0, 5.0, 6.0);
 *
 * CollectionSumComputation<PackedCollection> sum =
 *     new CollectionSumComputation<>(matrix);
 *
 * PackedCollection result = sum.get().evaluate();
 * // Result: [21.0]  (1 + 2 + 3 + 4 + 5 + 6)
 * }</pre>
 *
 * <p><strong>Using via CollectionFeatures:</strong></p>
 * <pre>{@code
 * // More commonly used through helper methods
 * CollectionProducer<PackedCollection> data = c(10.0, 20.0, 30.0);
 * CollectionProducer<PackedCollection> total = sum(data);
 * // Result: [60.0]
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the number of elements</li>
 *   <li><strong>Memory:</strong> Single-element output collection</li>
 *   <li><strong>Parallelization:</strong> Can use parallel reduction strategies</li>
 *   <li><strong>Loop Optimization:</strong> Replacement enabled for better performance</li>
 * </ul>
 *
 * <h2>Comparison with Related Operations</h2>
 * <ul>
 *   <li><strong>{@link CollectionAddComputation}:</strong> Element-wise addition of multiple collections</li>
 *   <li><strong>CollectionSumComputation:</strong> Reduction to single sum value (this class)</li>
 *   <li><strong>Mean:</strong> Sum divided by count (build on top of this)</li>
 * </ul>
 *
 * @see AggregatedProducerComputation
 * @see CollectionAddComputation
 * @see org.almostrealism.collect.CollectionFeatures#sum(io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 */
public class CollectionSumComputation extends AggregatedProducerComputation {
	/**
	 * Constructs a sum computation that reduces the input collection to a single sum value.
	 * The input shape is automatically determined from the producer.
	 *
	 * @param input The {@link Producer} providing the collection to be summed
	 */
	public CollectionSumComputation(Producer<PackedCollection> input) {
		this(CollectionFeatures.getInstance().shape(input), input);
	}

	/**
	 * Constructs a sum computation with an explicit shape specification.
	 * This constructor sets up the aggregation with:
	 * <ul>
	 *   <li>Output shape: single element (shape.replace(shape(1)))</li>
	 *   <li>Iteration count: total input size</li>
	 *   <li>Initial value: 0.0</li>
	 *   <li>Aggregation function: addition (out + arg)</li>
	 *   <li>Loop replacement: enabled</li>
	 * </ul>
	 *
	 * @param shape The {@link TraversalPolicy} of the input collection
	 * @param input The {@link Producer} providing the collection to be summed
	 */
	protected CollectionSumComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
		super("sum", shape.replace(new TraversalPolicy(1)), shape.getSize(),
				(args, index) -> new DoubleConstant(0.0),
				(out, arg) -> out.add(arg),
				input);
		setReplaceLoop(true);
	}

	/**
	 * Generates a new sum computation with the specified child processes.
	 * This method creates a new instance with the same configuration but using
	 * the child process at index 1 as the input source.
	 *
	 * @param children List of child {@link Process} instances where children.get(1) is the input
	 * @return A new {@link CollectionSumComputation} for the child input
	 */
	@Override
	public CollectionSumComputation generate(List<Process<?, ?>> children) {
		return new CollectionSumComputation((Producer<PackedCollection>) children.get(1));
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
