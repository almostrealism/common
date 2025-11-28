/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * Abstract base class for element-wise comparison operations on {@link PackedCollection}s
 * that perform conditional value selection based on comparison results.
 *
 * <p>This class extends {@link TransitiveDeltaExpressionComputation} to provide a foundation
 * for comparison operations that evaluate a relational operator (e.g., {@code <, >, <=, >=, ==})
 * between two input collections and select between alternative values based on the result.</p>
 *
 * <h2>Operation Pattern</h2>
 * <p>All comparison computations follow this general pattern:</p>
 * <pre>
 * result[i] = (left[i] O right[i]) ? positive[i] : negative[i]
 * </pre>
 * <p>where O is a relational operator implemented by subclasses (e.g., {@code <, >, <=, >=, ==}).</p>
 *
 * <h2>Four-Operand Structure</h2>
 * <p>Comparison computations take four producer inputs:</p>
 * <ul>
 *   <li><strong>left</strong> - Left-hand side of the comparison</li>
 *   <li><strong>right</strong> - Right-hand side of the comparison</li>
 *   <li><strong>positive</strong> - Value to use when comparison is true</li>
 *   <li><strong>negative</strong> - Value to use when comparison is false</li>
 * </ul>
 *
 * <h2>Gradient Propagation Strategy</h2>
 * <p>For automatic differentiation, this base class implements a transitive delta strategy where:</p>
 * <ul>
 *   <li><strong>Non-transitive arguments:</strong> left and right (indices 1-2)</li>
 *   <li><strong>Transitive arguments:</strong> positive and negative (indices 3-4)</li>
 * </ul>
 * <p>This means gradients flow through the selected value (positive or negative) but not
 * through the comparison operands themselves. This is appropriate because the comparison
 * result is typically a discrete decision, not differentiable with respect to small changes
 * in the comparison operands.</p>
 *
 * <h2>Subclass Implementation Pattern</h2>
 * <p>Subclasses override {@link #getExpression(TraversableExpression...)} to implement
 * specific comparison operators:</p>
 * <pre>{@code
 * // Example: Greater-than comparison
 * @Override
 * protected CollectionExpression getExpression(TraversableExpression... args) {
 *     return CollectionExpression.create(getShape(), index ->
 *         conditional(args[1].getValueAt(index).greaterThan(args[2].getValueAt(index)),
 *                     args[3].getValueAt(index), args[4].getValueAt(index)));
 * }
 * }</pre>
 *
 * <h2>Default Implementation</h2>
 * <p>This base class provides a default equality comparison implementation. While this makes
 * it a concrete class, it is typically subclassed for specific comparison operations:</p>
 * <ul>
 *   <li>{@link GreaterThanCollection} - Greater-than ({@code >}) and greater-or-equal ({@code >=})</li>
 *   <li>{@link LessThanCollection} - Less-than ({@code <}) and less-or-equal ({@code <=})</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Using the default equality comparison:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(3);
 * CollectionProducer<PackedCollection> a = c(1.0, 2.0, 3.0);
 * CollectionProducer<PackedCollection> b = c(1.0, 0.0, 3.0);
 *
 * CollectionComparisonComputation<PackedCollection> equals =
 *     new CollectionComparisonComputation<>("equals", shape, a, b,
 *         c(1.0), c(0.0));  // true -> 1.0, false -> 0.0
 *
 * PackedCollection result = equals.get().evaluate();
 * // Result: [1.0, 0.0, 1.0]  (a[0]==b[0], a[2]==b[2])
 * }</pre>
 *
 * <p><strong>Subclassing for custom comparisons:</strong></p>
 * <pre>{@code
 * public class NotEqualCollection<T extends PackedCollection>
 *         extends CollectionComparisonComputation<T> {
 *
 *     @Override
 *     protected CollectionExpression getExpression(TraversableExpression... args) {
 *         return CollectionExpression.create(getShape(), index ->
 *             conditional(args[1].getValueAt(index).notEqual(args[2].getValueAt(index)),
 *                         args[3].getValueAt(index), args[4].getValueAt(index)));
 *     }
 * }
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the number of elements</li>
 *   <li><strong>Memory:</strong> Output collection matching input shape</li>
 *   <li><strong>Parallelization:</strong> Fully parallelizable element-wise operation</li>
 *   <li><strong>Branching:</strong> Uses conditional expressions in generated code</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see TransitiveDeltaExpressionComputation
 * @see GreaterThanCollection
 * @see LessThanCollection
 * @see org.almostrealism.collect.CollectionFeatures#equals
 *
 * @author Michael Murray
 */
public class CollectionComparisonComputation<T extends PackedCollection> extends TransitiveDeltaExpressionComputation<T> {
	/**
	 * Constructs a comparison computation with four operands: two comparison values
	 * and two conditional result values.
	 *
	 * @param name The operation identifier (e.g., "lessThan", "greaterThan", "equals")
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param left The {@link Producer} providing the left-hand side comparison values
	 * @param right The {@link Producer} providing the right-hand side comparison values
	 * @param positive The {@link Producer} providing values to use when comparison is true
	 * @param negative The {@link Producer} providing values to use when comparison is false
	 */
	public CollectionComparisonComputation(String name, TraversalPolicy shape,
										   Producer<PackedCollection> left,
										   Producer<PackedCollection> right,
										   Producer<PackedCollection> positive,
										   Producer<PackedCollection> negative) {
		super(name, shape, left, right, positive, negative);
	}

	/**
	 * Determines which arguments should propagate gradients transitively during
	 * automatic differentiation.
	 *
	 * <p>For comparison operations, only the result values (positive and negative)
	 * propagate gradients transitively, not the comparison operands (left and right).
	 * This is because the comparison itself is a discrete decision that is not
	 * differentiable with respect to small changes in the operands.</p>
	 *
	 * <p>The argument indices are:</p>
	 * <ul>
	 *   <li>Index 1: left operand (non-transitive)</li>
	 *   <li>Index 2: right operand (non-transitive)</li>
	 *   <li>Index 3: positive value (transitive)</li>
	 *   <li>Index 4: negative value (transitive)</li>
	 * </ul>
	 *
	 * @param index The argument index to check (1-based indexing, excluding output at 0)
	 * @return {@code true} if the argument at the given index should propagate gradients
	 *         transitively (indices 3 and 4), {@code false} otherwise (indices 1 and 2)
	 */
	@Override
	protected boolean isTransitiveArgumentIndex(int index) {
		return index > 2;
	}

	/**
	 * Generates the expression that applies element-wise comparison with conditional
	 * value selection.
	 *
	 * <p>This default implementation performs an equality comparison ({@code ==}).
	 * Subclasses override this method to implement other comparison operators
	 * ({@code <, >, <=, >=, !=}).</p>
	 *
	 * @param args Array of {@link TraversableExpression}s where:
	 *             <ul>
	 *               <li>args[1] - left operand (compared value)</li>
	 *               <li>args[2] - right operand (comparison value)</li>
	 *               <li>args[3] - value to use when comparison is true (positive)</li>
	 *               <li>args[4] - value to use when comparison is false (negative)</li>
	 *             </ul>
	 * @return A {@link CollectionExpression} that computes element-wise
	 *         (left == right ? positive : negative)
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return CollectionExpression.create(getShape(), index ->
			conditional(args[1].getValueAt(index).eq(args[2].getValueAt(index)),
						args[3].getValueAt(index), args[4].getValueAt(index)));
	}

	/**
	 * Generates a new comparison computation with the specified child processes.
	 * This default implementation creates an equality comparison using the
	 * {@code equals} factory method from {@link org.almostrealism.collect.CollectionFeatures}.
	 *
	 * <p>Subclasses override this method to delegate to their specific comparison
	 * factory methods (e.g., {@code greaterThan}, {@code lessThan}).</p>
	 *
	 * @param children List of child {@link Process} instances where:
	 *                 <ul>
	 *                   <li>children.get(1) - left operand producer</li>
	 *                   <li>children.get(2) - right operand producer</li>
	 *                   <li>children.get(3) - positive value producer</li>
	 *                   <li>children.get(4) - negative value producer</li>
	 *                 </ul>
	 * @return A new {@link CollectionProducerParallelProcess} for parallel execution
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess)
				equals((Producer) children.get(1), (Producer) children.get(2),
						(Producer) children.get(3), (Producer) children.get(4));
	}
}
