/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.collect;

import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;

/**
 * A {@link CollectionExpression} that generates values following an arithmetic sequence
 * (linear progression) based on the index position.
 *
 * <p>An arithmetic sequence is defined by the formula:
 * <pre>{@code value(index) = initial + rate * index}</pre>
 *
 * <p>This expression type is useful for generating:
 * <ul>
 *   <li>Index sequences (e.g., [0, 1, 2, 3, ...] with initial=0, rate=1)</li>
 *   <li>Scaled indices (e.g., [0, 2, 4, 6, ...] with initial=0, rate=2)</li>
 *   <li>Offset sequences (e.g., [5, 6, 7, 8, ...] with initial=5, rate=1)</li>
 *   <li>Descending sequences (e.g., [10, 9, 8, ...] with initial=10, rate=-1)</li>
 *   <li>Constant values (e.g., [5, 5, 5, ...] with rate=0)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <pre>{@code
 * TraversalPolicy shape = new TraversalPolicy(10);
 *
 * // Simple index sequence [0, 1, 2, ..., 9]
 * ArithmeticSequenceExpression indices = new ArithmeticSequenceExpression(shape);
 *
 * // Scaled sequence [0, 0.5, 1.0, ..., 4.5]
 * ArithmeticSequenceExpression scaled = new ArithmeticSequenceExpression(shape, 0.5);
 *
 * // Offset and scaled [10, 12, 14, ..., 28]
 * ArithmeticSequenceExpression offset = new ArithmeticSequenceExpression(shape, 10, 2);
 * }</pre>
 *
 * <h2>Index Containment</h2>
 *
 * <p>The {@link #containsIndex(Expression)} method provides special handling:
 * <ul>
 *   <li>For zero sequences (initial=0, rate=0): Always returns false since all values are zero</li>
 *   <li>For sequences with a zero crossing at an integer index: Returns true only at that index</li>
 *   <li>For all other sequences: Returns true (assumes the sequence contains non-zero values)</li>
 * </ul>
 *
 * @see CollectionExpressionAdapter
 * @see CollectionExpression
 * @author Michael Murray
 */
public class ArithmeticSequenceExpression extends CollectionExpressionAdapter {
	/**
	 * The initial value of the sequence at index 0.
	 */
	private double initial;

	/**
	 * The rate of change (step size) between consecutive elements in the sequence.
	 * A positive rate creates an ascending sequence, negative creates descending,
	 * and zero creates a constant sequence.
	 */
	private double rate;

	/**
	 * Constructs an arithmetic sequence with default values (initial=0, rate=1).
	 *
	 * <p>This creates a simple index sequence where the value at each position
	 * equals the index: [0, 1, 2, 3, ...].
	 *
	 * @param shape the {@link TraversalPolicy} defining the dimensions of the sequence
	 */
	public ArithmeticSequenceExpression(TraversalPolicy shape) {
		this(shape, 0, 1);
	}

	/**
	 * Constructs an arithmetic sequence with the specified rate and initial value of 0.
	 *
	 * <p>This creates a scaled index sequence: [0, rate, 2*rate, 3*rate, ...].
	 *
	 * @param shape the {@link TraversalPolicy} defining the dimensions of the sequence
	 * @param rate the rate of change (step size) between consecutive elements
	 */
	public ArithmeticSequenceExpression(TraversalPolicy shape, double rate) {
		this(shape, 0, rate);
	}

	/**
	 * Constructs an arithmetic sequence with the specified initial value and rate.
	 *
	 * <p>This creates a fully customized arithmetic sequence:
	 * [initial, initial+rate, initial+2*rate, ...].
	 *
	 * @param shape the {@link TraversalPolicy} defining the dimensions of the sequence
	 * @param initial the starting value at index 0
	 * @param rate the rate of change (step size) between consecutive elements
	 */
	public ArithmeticSequenceExpression(TraversalPolicy shape, double initial, double rate) {
		super(null, shape);
		this.initial = initial;
		this.rate = rate;
	}

	/**
	 * Computes the value at the specified index in the arithmetic sequence.
	 *
	 * <p>The value is calculated as: {@code initial + rate * index}
	 *
	 * @param index an {@link Expression} representing the index position
	 * @return an {@link Expression} representing the computed value at the index
	 */
	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		return (Expression) e(initial).add(e(rate).multiply(index));
	}

	/**
	 * Returns the unique non-zero offset for this expression.
	 *
	 * <p>Arithmetic sequences do not support unique non-zero offset computation,
	 * so this method always returns null.
	 *
	 * @param globalIndex the global index context
	 * @param localIndex the local index context
	 * @param targetIndex the target index expression
	 * @return always null for arithmetic sequences
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
	}

	/**
	 * Determines if the specified index is contained in this sequence's non-zero value set.
	 *
	 * <p>This method provides special handling for arithmetic sequences:
	 * <ul>
	 *   <li>If both initial and rate are zero, all values are zero, so returns false</li>
	 *   <li>If the sequence crosses zero at an integer index n (where n = -initial/rate),
	 *       returns true only when index equals n</li>
	 *   <li>Otherwise, assumes all indices contain non-zero values and returns true</li>
	 * </ul>
	 *
	 * @param index an {@link Expression} representing the index to test
	 * @return an {@link Expression} evaluating to true if the index contains a non-zero value
	 */
	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		if (initial == 0 && rate == 0)
			return new BooleanConstant(false);

		double n = -initial / rate;
		if (n == Math.floor(n)) {
			return index.eq(new IntegerConstant((int) n));
		}

		return new BooleanConstant(true);
	}
}
