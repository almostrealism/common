/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.OrderingSegments;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.RepeatedProducerComputation;

import java.util.List;
import java.util.Set;

/**
 * The outer product of two dense operands sampled at the stored entries of a matrix whose
 * {@link TraversalOrdering} {@link TraversalOrdering#segments(int) enumerates its rows}, such as
 * a sparse matrix read through a {@link org.almostrealism.collect.CompressedRowTraversalOrdering}:
 * for each stored entry, the dot product of the row of the left operand selected by the entry's
 * row with the row of the right operand selected by the entry's column.
 *
 * <h2>Mathematical Operation</h2>
 * <p>For a matrix of shape {@code (rows, n)} with {@code m} stored entries, a left operand of
 * shape {@code (rows, width)} and a right operand of shape {@code (n, width)}:</p>
 * <pre>
 * output[k] = sum(w = 0 .. width - 1) left[row(k), w] * right[column(k), w]
 * </pre>
 * <p>for each physical position {@code k} of a stored entry, where {@code row(k)} is the segment
 * of the entry ({@link TraversalOrdering#memberSegments(int)}) and {@code column(k)} its offset
 * within it ({@link OrderingSegments#offset(Expression)}). The output has one element per stored
 * entry, in their physical order. This is the gradient of the product {@code A·x} with respect to
 * the stored entries of {@code A}, for an upstream gradient {@code left} and the input
 * {@code right}: the outer product of the two restricted to the positions the matrix stores. It
 * never forms a logical index of the matrix, so it applies to matrices whose logical size exceeds
 * the range of an {@code int}.</p>
 *
 * <h2>Generated Code Structure</h2>
 * <p>One kernel thread computes the element of one stored entry, writing it exactly once:</p>
 * <pre>
 * out[k] = 0.0;
 * for (int w = 0; w &lt; width;) {
 *     out[k] = out[k] + left[row(k) * width + w] * right[column(k) * width + w];
 *     w = w + 1;
 * }
 * </pre>
 * <p>The ordering of the matrix and the ordering of the row of each entry are
 * {@link TraversalOrdering#bind(ArgumentProvider) bound} to the arguments of the kernel when its
 * scope is prepared, so the index collections they read are delivered to the kernel. Because
 * those bindings belong to this kernel, the computation is always compiled into a kernel of its
 * own.</p>
 *
 * @see SegmentedWeightedSumComputation
 *
 * @author Michael Murray
 */
public class SegmentedOuterProductComputation extends RepeatedProducerComputation {
	/** Position of the left operand, read by the row of each entry. */
	private static final int LEFT = 1;

	/** Position of the right operand, read by the column of each entry. */
	private static final int RIGHT = 2;

	/** The number of columns of each operand, summed over for each entry. */
	private final int width;

	/** The number of columns of the matrix, which is the length of each segment. */
	private final int segmentLength;

	/**
	 * The number of stored entries of the matrix. With none, the output is a single unused
	 * element that the kernel leaves at zero rather than reading an entry that does not exist.
	 */
	private final int entries;

	/** The shape of the matrix, carrying its ordering. */
	private final TraversalPolicy matrixShape;

	/** The ordering of the matrix, which enumerates its rows. */
	private final TraversalOrdering order;

	/** The ordering giving the row of the entry at each physical position. */
	private final TraversalOrdering rows;

	/** The enumeration of the rows, bound to the arguments of the kernel being compiled. */
	private OrderingSegments segments;

	/** The row of each entry, bound to the arguments of the kernel being compiled. */
	private TraversalOrdering boundRows;

	/**
	 * Creates the outer product of the given operands sampled at the stored entries of a matrix
	 * of the given shape.
	 *
	 * @param name        the computation name
	 * @param matrixShape the shape of the matrix, {@code (rows, n)}, whose ordering enumerates
	 *                    segments of length {@code n} and can give the row of each entry
	 * @param width       the number of columns of each operand, 1 for vectors
	 * @param left        the left operand, {@code width} values for each row of the matrix
	 * @param right       the right operand, {@code width} values for each column of the matrix
	 * @throws IllegalArgumentException if the matrix is not two-dimensional, {@code width} is not
	 *         positive, or its ordering cannot enumerate its rows, give the row of each entry, or
	 *         count its entries
	 */
	public SegmentedOuterProductComputation(String name, TraversalPolicy matrixShape, int width,
											Producer<PackedCollection> left,
											Producer<PackedCollection> right) {
		super(name, outputShape(matrixShape, width), 1, left, right);
		this.width = width;
		this.segmentLength = matrixShape.length(1);
		this.matrixShape = matrixShape;
		this.order = matrixShape.getOrder();
		this.rows = order.memberSegments(segmentLength).orElseThrow();
		this.entries = order.getLength().orElseThrow();
	}

	/**
	 * Returns the output shape, one element per stored entry of the matrix, rejecting a matrix
	 * whose entries cannot be enumerated with their rows before it reaches the index arithmetic of
	 * the kernel.
	 *
	 * @param matrixShape the shape of the matrix, carrying its ordering
	 * @param width       the number of columns of each operand
	 * @return {@code (m)} for {@code m} stored entries, or {@code (1)} for a matrix storing none,
	 *         since a collection cannot be empty
	 * @throws IllegalArgumentException if the matrix cannot be sampled
	 */
	private static TraversalPolicy outputShape(TraversalPolicy matrixShape, int width) {
		TraversalOrdering order = matrixShape.getOrder();
		if (matrixShape.getDimensions() != 2 || width <= 0 || order == null
				|| order.segments(matrixShape.length(1)).isEmpty()
				|| order.memberSegments(matrixShape.length(1)).isEmpty()
				|| order.getLength().isEmpty()) {
			throw new IllegalArgumentException("Cannot sample " + width + " columns at the entries of " + matrixShape);
		}

		return new TraversalPolicy(Math.max(1, order.getLength().getAsInt())).traverseEach();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also binds the ordering of the matrix and the ordering of the row of each entry to the
	 * arguments of the kernel.</p>
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		segments = order.bind(manager).segments(segmentLength).orElseThrow();
		boundRows = rows.bind(manager);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also counts the arguments that binding the two orderings obtains.</p>
	 */
	@Override
	public int getScopeArgumentCount(int cap) {
		return (int) Math.min(Integer.MAX_VALUE, (long) super.getScopeArgumentCount(cap)
				+ order.getArgumentCount() + rows.getArgumentCount());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also discards the orderings bound by
	 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)}.</p>
	 */
	@Override
	public void resetArguments(Set<ScopeLifecycle> visited) {
		super.resetArguments(visited);
		segments = null;
		boundRows = null;
	}

	/**
	 * Returns zero: every element starts the sum over the columns empty.
	 *
	 * @param args  the traversable arguments
	 * @param index the position within the output block
	 * @return the constant {@code 0.0}
	 */
	@Override
	protected Expression<?> getInitialValue(TraversableExpression[] args, Expression index) {
		return new DoubleConstant(0.0);
	}

	/**
	 * Continues while the loop index is within the columns of the operands. For a matrix with no
	 * stored entries the loop performs no iterations, so the unused output element stays zero.
	 *
	 * @param args  the traversable arguments
	 * @param index the column
	 * @return {@code index < width}, or {@code index < 0} for a matrix with no stored entries
	 */
	@Override
	protected Expression<Boolean> getCondition(TraversableExpression[] args, Expression index) {
		return index.lessThan(entries == 0 ? 0 : width);
	}

	/**
	 * Returns the accumulated value after one more column, for the stored entry at the physical
	 * position of the output element.
	 *
	 * @param args        the traversable arguments, where {@code args[0]} is the output
	 * @param globalIndex the physical position of the entry
	 * @param localIndex  the column
	 * @return the output element plus {@code left[row(k), w] * right[column(k), w]}
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		Expression<?> currentValue = getCurrentOutputValue(getOutputVariable(), globalIndex);
		Expression<?> row = boundRows.indexOf(globalIndex.toInt());
		Expression<?> column = segments.offset(globalIndex);
		Expression<?> left = args[LEFT].getValueAt(row.multiply(width).add(localIndex));
		Expression<?> right = args[RIGHT].getValueAt(column.multiply(width).add(localIndex));
		return currentValue.add(left.multiply(right));
	}

	/**
	 * Always returns true: the orderings are bound to the arguments of this computation's own
	 * kernel, so it can only run as a kernel of its own.
	 *
	 * @param context the process context
	 * @return always true
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return true;
	}

	/**
	 * Creates the outer product of new operands sampled at the entries of the same matrix, in the
	 * order left, right.
	 *
	 * @param children the output followed by the two operands
	 * @return the new computation
	 */
	@Override
	public SegmentedOuterProductComputation generate(List<Process<?, ?>> children) {
		return new SegmentedOuterProductComputation(getName(), matrixShape, width,
				(Producer<PackedCollection>) children.get(LEFT),
				(Producer<PackedCollection>) children.get(RIGHT));
	}
}
