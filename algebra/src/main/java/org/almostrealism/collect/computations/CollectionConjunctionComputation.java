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
 * A computation that performs element-wise logical AND operation on two {@link PackedCollection}s,
 * returning conditional values based on the truth values of both operands.
 *
 * <p>This class extends {@link CollectionComparisonComputation} to implement logical conjunction
 * (AND) with a truth value convention where non-zero values are considered true and zero values
 * are considered false. Unlike bitwise AND, this operation evaluates the logical truth of the
 * operands and selects between alternative result values.</p>
 *
 * <h2>Truth Value Convention</h2>
 * <p>This computation follows the C-style boolean convention:</p>
 * <ul>
 *   <li><strong>True:</strong> Any non-zero value (positive or negative)</li>
 *   <li><strong>False:</strong> Zero (0.0)</li>
 * </ul>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For input collections A (left) and B (right), the computation produces:</p>
 * <pre>
 * result[i] = ((A[i] != 0) AND (B[i] != 0)) ? trueValue[i] : falseValue[i]
 * </pre>
 * <p>This implements logical conjunction: the result is true only when both operands are true.</p>
 *
 * <h2>Truth Table</h2>
 * <pre>
 * A (left)  | B (right) | Result
 * ----------+-----------+-------------
 * non-zero  | non-zero  | trueValue
 * non-zero  | 0.0       | falseValue
 * 0.0       | non-zero  | falseValue
 * 0.0       | 0.0       | falseValue
 * </pre>
 *
 * <h2>Comparison with Related Operations</h2>
 * <ul>
 *   <li><strong>Logical AND (this class):</strong> Evaluates truth values, selects between result values</li>
 *   <li><strong>Bitwise AND:</strong> Performs bit-level AND operation on integer representations</li>
 *   <li><strong>Element-wise Multiply:</strong> Multiplies values directly without boolean interpretation</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic logical AND (returning 1.0 or 0.0):</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(4);
 * CollectionProducer<PackedCollection> a = c(0.0, 1.0, 0.0, 1.0);
 * CollectionProducer<PackedCollection> b = c(0.0, 0.0, 1.0, 1.0);
 *
 * CollectionConjunctionComputation<PackedCollection> andOp =
 *     new CollectionConjunctionComputation<>(shape, a, b,
 *         c(1.0), c(0.0));  // true -> 1.0, false -> 0.0
 *
 * PackedCollection result = andOp.get().evaluate();
 * // Result: [0.0, 0.0, 0.0, 1.0]  (only both non-zero yields true)
 * }</pre>
 *
 * <p><strong>Using via CollectionFeatures:</strong></p>
 * <pre>{@code
 * // More common usage through helper methods
 * CollectionProducer<PackedCollection> conditionA = greaterThan(x, threshold1, c(1.0), c(0.0));
 * CollectionProducer<PackedCollection> conditionB = lessThan(x, threshold2, c(1.0), c(0.0));
 * CollectionProducer<PackedCollection> both = and(conditionA, conditionB, c(1.0), c(0.0));
 * // Result: 1.0 where both conditions are true
 * }</pre>
 *
 * <p><strong>Masking with custom values:</strong></p>
 * <pre>{@code
 * // Apply mask only where both conditions are true
 * CollectionProducer<PackedCollection> mask1 = c(1.0, 0.0, 1.0, 1.0);
 * CollectionProducer<PackedCollection> mask2 = c(1.0, 1.0, 0.0, 1.0);
 * CollectionProducer<PackedCollection> data = c(10.0, 20.0, 30.0, 40.0);
 *
 * CollectionProducer<PackedCollection> masked =
 *     and(mask1, mask2,
 *         data,     // if both masks active, use data
 *         c(0.0));  // else use 0.0
 * // Result: [10.0, 0.0, 0.0, 40.0]
 * }</pre>
 *
 * <p><strong>Combining multiple conditions:</strong></p>
 * <pre>{@code
 * // Check if value is in range [min, max]
 * CollectionProducer<PackedCollection> x = c(5.0, 15.0, 25.0);
 * CollectionProducer<PackedCollection> min = c(10.0);
 * CollectionProducer<PackedCollection> max = c(20.0);
 *
 * CollectionProducer<PackedCollection> aboveMin = greaterThanOrEqual(x, min, c(1.0), c(0.0));
 * CollectionProducer<PackedCollection> belowMax = lessThanOrEqual(x, max, c(1.0), c(0.0));
 * CollectionProducer<PackedCollection> inRange = and(aboveMin, belowMax, c(1.0), c(0.0));
 * // Result: [0.0, 1.0, 0.0]  (only 15.0 is in [10, 20])
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the number of elements</li>
 *   <li><strong>Memory:</strong> Output collection matching input shape</li>
 *   <li><strong>Parallelization:</strong> Fully parallelizable element-wise operation</li>
 *   <li><strong>Branching:</strong> Uses conditional expressions in generated code</li>
 *   <li><strong>Short-circuit:</strong> No short-circuit evaluation (both operands always evaluated)</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see CollectionComparisonComputation
 * @see org.almostrealism.collect.CollectionFeatures#and
 *
 * @author Michael Murray
 */
public class CollectionConjunctionComputation<T extends PackedCollection> extends CollectionComparisonComputation<T> {

	/**
	 * Constructs a logical AND computation that evaluates conjunction of two boolean-valued
	 * collections (using the non-zero = true convention).
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param leftOperand The {@link Producer} providing the left-hand side boolean values
	 * @param rightOperand The {@link Producer} providing the right-hand side boolean values
	 * @param trueValue The {@link Producer} providing values to use when both operands are true
	 * @param falseValue The {@link Producer} providing values to use when either operand is false
	 */
	public CollectionConjunctionComputation(
			TraversalPolicy shape,
			Producer leftOperand,
			Producer rightOperand,
			Producer trueValue,
			Producer falseValue) {
		super("and", shape, leftOperand, rightOperand, trueValue, falseValue);
	}

	/**
	 * Generates the expression that applies element-wise logical AND with conditional
	 * value selection.
	 *
	 * <p>This method creates a {@link CollectionExpression} that evaluates whether both
	 * operands are non-zero (true) and selects the appropriate result value. The implementation
	 * checks {@code (left != 0) AND (right != 0)} using equality checks with negation.</p>
	 *
	 * <p>The logical expression structure is:</p>
	 * <pre>
	 * (left != 0.0) AND (right != 0.0) ? trueValue : falseValue
	 * </pre>
	 * <p>which is implemented as:</p>
	 * <pre>
	 * NOT(left == 0.0) AND NOT(right == 0.0) ? trueValue : falseValue
	 * </pre>
	 *
	 * @param args Array of {@link TraversableExpression}s where:
	 *             <ul>
	 *               <li>args[1] - left operand (boolean value as non-zero/zero)</li>
	 *               <li>args[2] - right operand (boolean value as non-zero/zero)</li>
	 *               <li>args[3] - value to use when both operands are true (trueValue)</li>
	 *               <li>args[4] - value to use when either operand is false (falseValue)</li>
	 *             </ul>
	 * @return A {@link CollectionExpression} that computes element-wise logical AND
	 *         with conditional value selection
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		// args[1] = left operand (a), args[2] = right operand (b)
		// args[3] = trueValue, args[4] = falseValue
		// Returns trueValue if both a != 0.0 AND b != 0.0, otherwise returns falseValue
		// Check if a != 0 and b != 0 by using eq().not()
		return CollectionExpression.create(getShape(), index ->
				conditional(
						args[1].getValueAt(index).eq(e(0.0)).not()
								.and(args[2].getValueAt(index).eq(e(0.0)).not()),
						args[3].getValueAt(index),
						args[4].getValueAt(index)));
	}

	/**
	 * Generates a new logical AND computation with the specified child processes.
	 * This method creates a new instance using the {@code and} factory method from
	 * {@link org.almostrealism.collect.CollectionFeatures} to ensure consistent
	 * construction and configuration of the logical operation.
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
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess)
				and((Producer) children.get(1), (Producer) children.get(2),
						(Producer) children.get(3), (Producer) children.get(4));
	}
}
