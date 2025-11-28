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
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;

/**
 * A computation that performs element-wise less-than comparison between two {@link PackedCollection}s,
 * returning conditional values based on the comparison result.
 *
 * <p>This class extends {@link CollectionComparisonComputation} to implement the less-than
 * relational operator with optional equality checking (&lt;=). It evaluates the comparison for each
 * corresponding element pair and selects between true and false values accordingly.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For input collections A (left) and B (right), the computation produces:</p>
 * <pre>
 * if includeEqual == false:
 *   result[i] = (A[i] &lt; B[i]) ? trueValue[i] : falseValue[i]
 * if includeEqual == true:
 *   result[i] = (A[i] &lt;= B[i]) ? trueValue[i] : falseValue[i]
 * </pre>
 *
 * <h2>Conditional Value Selection</h2>
 * <p>Unlike boolean comparison operations that return 0 or 1, this computation allows
 * specifying arbitrary values for true and false outcomes. This enables patterns like:</p>
 * <ul>
 *   <li>Clipping: return threshold if &lt; threshold, else return original value</li>
 *   <li>Masking: return value if condition met, else return 0</li>
 *   <li>Selection: choose between two different computations based on comparison</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic less-than comparison (returning 1.0 or 0.0):</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(5);
 * CollectionProducer<PackedCollection> a = c(3.0, 7.0, 2.0, 9.0, 5.0);
 * CollectionProducer<PackedCollection> b = c(5.0, 5.0, 5.0, 5.0, 5.0);
 *
 * LessThanCollection<PackedCollection> lt =
 *     new LessThanCollection<>(shape, a, b,
 *         c(1.0), c(0.0));  // true -> 1.0, false -> 0.0
 *
 * PackedCollection result = lt.get().evaluate();
 * // Result: [1.0, 0.0, 1.0, 0.0, 0.0]  (3<5 and 2<5 are true)
 * }</pre>
 *
 * <p><strong>Less-than-or-equal comparison:</strong></p>
 * <pre>{@code
 * LessThanCollection<PackedCollection> lte =
 *     new LessThanCollection<>(shape, a, b,
 *         c(1.0), c(0.0),
 *         true);  // includeEqual = true
 *
 * PackedCollection result = lte.get().evaluate();
 * // Result: [1.0, 0.0, 1.0, 0.0, 1.0]  (3<=5, 2<=5, 5<=5 are true)
 * }</pre>
 *
 * <p><strong>Using via CollectionFeatures:</strong></p>
 * <pre>{@code
 * // More common usage through helper methods
 * CollectionProducer<PackedCollection> x = c(1.0, 2.0, 3.0);
 * CollectionProducer<PackedCollection> threshold = c(2.0, 2.0, 2.0);
 * CollectionProducer<PackedCollection> result =
 *     lessThan(x, threshold, c(1.0), c(0.0));
 * // Result: [1.0, 0.0, 0.0]
 * }</pre>
 *
 * <p><strong>Value clipping example:</strong></p>
 * <pre>{@code
 * // Clip values to maximum threshold: min(value, threshold)
 * CollectionProducer<PackedCollection> values = c(1.0, 5.0, 3.0);
 * CollectionProducer<PackedCollection> threshold = c(4.0);
 * CollectionProducer<PackedCollection> clipped =
 *     lessThan(values, threshold,
 *         values,      // if value < threshold, keep value
 *         threshold);  // else use threshold
 * // Result: [1.0, 4.0, 3.0]  (5.0 clipped to 4.0)
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
 * @see CollectionComparisonComputation
 * @see GreaterThanCollection
 * @see org.almostrealism.collect.CollectionFeatures#lessThan
 *
 * @author Michael Murray
 */
public class LessThanCollection extends CollectionComparisonComputation {
	/**
	 * Flag controlling whether the comparison includes equality (<=) or is strict (<).
	 * When true, performs less-than-or-equal comparison (<=).
	 * When false, performs strict less-than comparison (<).
	 */
	private boolean includeEqual;

	/**
	 * Constructs a less-than comparison computation with strict inequality (<).
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param leftOperand The {@link Producer} providing the left-hand side values
	 * @param rightOperand The {@link Producer} providing the right-hand side values
	 * @param trueValue The {@link Producer} providing values to use when comparison is true
	 * @param falseValue The {@link Producer} providing values to use when comparison is false
	 */
	public LessThanCollection(
			TraversalPolicy shape,
			Producer leftOperand,
			Producer rightOperand,
			Producer trueValue,
			Producer falseValue) {
		this(shape, leftOperand, rightOperand, trueValue, falseValue, false);
	}

	/**
	 * Constructs a less-than comparison computation with configurable equality inclusion.
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param left The {@link Producer} providing the left-hand side values
	 * @param right The {@link Producer} providing the right-hand side values
	 * @param trueValue The {@link Producer} providing values to use when comparison is true
	 * @param falseValue The {@link Producer} providing values to use when comparison is false
	 * @param includeEqual If true, performs <= comparison; if false, performs < comparison
	 */
	public LessThanCollection(
			TraversalPolicy shape,
			Producer<PackedCollection> left, Producer<PackedCollection> right,
			Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
			boolean includeEqual) {
		super("lessThan", shape,  left, right, trueValue, falseValue);
		this.includeEqual = includeEqual;
	}

	/**
	 * Generates the expression that applies element-wise less-than comparison
	 * with conditional value selection.
	 *
	 * <p>This method creates a {@link CollectionExpression} that evaluates the comparison
	 * at each index position and selects between true and false values using a ternary-like
	 * conditional expression. The comparison operator used depends on the {@link #includeEqual} flag.</p>
	 *
	 * @param args Array of {@link TraversableExpression}s where:
	 *             <ul>
	 *               <li>args[1] - left operand (compared value)</li>
	 *               <li>args[2] - right operand (comparison threshold)</li>
	 *               <li>args[3] - value to use when comparison is true</li>
	 *               <li>args[4] - value to use when comparison is false</li>
	 *             </ul>
	 * @return A {@link CollectionExpression} that computes element-wise
	 *         (left < right ? trueValue : falseValue) or
	 *         (left <= right ? trueValue : falseValue) if includeEqual is true
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		if (includeEqual) {
			return CollectionExpression.create(getShape(), index ->
					conditional(args[1].getValueAt(index).lessThanOrEqual(args[2].getValueAt(index)),
							args[3].getValueAt(index), args[4].getValueAt(index)));
		} else {
			return CollectionExpression.create(getShape(), index ->
					conditional(args[1].getValueAt(index).lessThan(args[2].getValueAt(index)),
							args[3].getValueAt(index), args[4].getValueAt(index)));
		}
	}

	/**
	 * Generates a new less-than comparison computation with the specified child processes.
	 * This method creates a new instance with the same configuration (including the
	 * {@link #includeEqual} flag) but using the provided child processes as operands.
	 *
	 * <p>The method delegates to the {@code lessThan} factory method from
	 * {@link org.almostrealism.collect.CollectionFeatures} to ensure consistent
	 * construction and configuration of the comparison operation.</p>
	 *
	 * @param children List of child {@link Process} instances where:
	 *                 <ul>
	 *                   <li>children.get(1) - left operand producer</li>
	 *                   <li>children.get(2) - right operand producer</li>
	 *                   <li>children.get(3) - true value producer</li>
	 *                   <li>children.get(4) - false value producer</li>
	 *                 </ul>
	 * @return A new {@link CollectionProducerParallelProcess} for parallel execution
	 */
	@Override
	public CollectionProducerParallelProcess<PackedCollection> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess)
				lessThan((Producer) children.get(1), (Producer) children.get(2),
						(Producer) children.get(3), (Producer) children.get(4), includeEqual);
	}
}
