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
import io.almostrealism.collect.OrderingSegments;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.RepeatedProducerComputation;

import java.util.List;

/**
 * The product of a matrix whose {@link TraversalOrdering} {@link TraversalOrdering#segments(int)
 * enumerates its rows}, such as a sparse matrix read through a
 * {@link org.almostrealism.collect.CompressedRowTraversalOrdering}, with a vector or a matrix:
 * for each output element, the sum over the stored entries of one row of the entry times the
 * input element it selects.
 *
 * <h2>Mathematical Operation</h2>
 * <p>For a matrix of shape {@code (count, n)} and an input of shape {@code (n)} or
 * {@code (n, width)}:</p>
 * <pre>
 * output[i, w] = sum(p = start(i) .. start(i) + length(i) - 1) matrix[p] * input[offset(p), w]
 * </pre>
 * <p>where {@code start}, {@code length} and {@code offset} are the
 * {@link OrderingSegments enumeration} of the rows of the matrix, and {@code matrix[p]} is the
 * entry at physical position {@code p}. This is the same value as the dense product of the
 * matrix read through its ordering, but visits each stored entry once and never forms a logical
 * index of the matrix, so it applies to matrices whose logical size exceeds the range of an
 * {@code int}.</p>
 *
 * <h2>Generated Code Structure</h2>
 * <p>One kernel thread computes one output element, writing it exactly once, with no atomics.
 * The loop bound is read from device memory, so each thread runs as many iterations as its row
 * has entries:</p>
 * <pre>
 * out[g] = 0.0;
 * for (int k = 0; k &lt; length(i);) {
 *     out[g] = out[g] + matrix[start(i) + k] * input[offset(start(i) + k) * width + w];
 *     k = k + 1;
 * }
 * </pre>
 * <p>where {@code i = g / width} and {@code w = g % width}. The ordering is
 * {@link TraversalOrdering#bind(ArgumentProvider) bound} to the arguments of the kernel when its
 * scope is prepared, so any collection it reads (for a compressed-sparse-row ordering, the row
 * offsets and columns) is delivered to the kernel.</p>
 *
 * <p>Because the iteration count is not known when the computation is built, the value of an
 * element cannot be unrolled into the expression of a consumer. This computation is therefore
 * not a {@link TraversableExpression}: it is always compiled into a kernel of its own, and its
 * consumers read its output.</p>
 *
 * @see LoopedWeightedSumComputation
 * @see org.almostrealism.algebra.MatrixFeatures#matmul(Producer, Producer)
 *
 * @author Michael Murray
 */
public class SegmentedWeightedSumComputation extends RepeatedProducerComputation {
	/** Position of the matrix among the traversable arguments. */
	private static final int MATRIX = 1;

	/** Position of the gathered input among the traversable arguments. */
	private static final int INPUT = 2;

	/** The number of input columns, each reduced independently into an output column. */
	private final int width;

	/** The number of columns of the matrix, which is the length of each segment. */
	private final int segmentLength;

	/** The ordering of the matrix, which enumerates its rows. */
	private final TraversalOrdering order;

	/** The enumeration of the rows, bound to the arguments of the kernel being compiled. */
	private OrderingSegments segments;

	/**
	 * Creates the product of the given matrix with the given input.
	 *
	 * @param name   the computation name
	 * @param width  the number of input columns; 1 for a vector, whose output has shape
	 *               {@code (rows)}, otherwise the output has shape {@code (rows, width)}
	 * @param matrix the matrix, of shape {@code (rows, n)}, whose ordering enumerates segments of
	 *               length {@code n}
	 * @param input  the input, {@code width} values for each of the {@code n} columns of the matrix
	 * @throws IllegalArgumentException if the matrix is not two-dimensional, its ordering does not
	 *         enumerate its rows, or {@code width} is not positive
	 */
	public SegmentedWeightedSumComputation(String name, int width,
										   Producer<PackedCollection> matrix,
										   Producer<PackedCollection> input) {
		this(name, CollectionFeatures.getInstance().shape(matrix), width, matrix, input);
	}

	/**
	 * Creates the product of a matrix of the given shape with the given input.
	 *
	 * @param name        the computation name
	 * @param matrixShape the shape of the matrix, carrying its ordering
	 * @param width       the number of input columns
	 * @param matrix      the matrix
	 * @param input       the input
	 */
	private SegmentedWeightedSumComputation(String name, TraversalPolicy matrixShape, int width,
											Producer<PackedCollection> matrix,
											Producer<PackedCollection> input) {
		super(name, outputShape(matrixShape, width), 1, matrix, input);
		this.width = width;
		this.segmentLength = matrixShape.length(1);
		this.order = matrixShape.getOrder();
	}

	/**
	 * Returns the output shape for a matrix of the given shape and the given width, rejecting a
	 * matrix whose rows cannot be enumerated and a non-positive width before either reaches the
	 * index arithmetic of the kernel.
	 *
	 * @param matrixShape the shape of the matrix, carrying its ordering
	 * @param width       the number of input columns
	 * @return {@code (rows)} when {@code width} is 1, otherwise {@code (rows, width)}
	 * @throws IllegalArgumentException if the matrix is not two-dimensional, its ordering does not
	 *         enumerate its rows, or {@code width} is not positive
	 */
	private static TraversalPolicy outputShape(TraversalPolicy matrixShape, int width) {
		if (matrixShape.getDimensions() != 2 || width <= 0) {
			throw new IllegalArgumentException("Cannot multiply " + matrixShape + " by " + width + " columns");
		}

		TraversalOrdering order = matrixShape.getOrder();
		if (order == null || order.segments(matrixShape.length(1)).isEmpty()) {
			throw new IllegalArgumentException("The ordering of " + matrixShape + " does not enumerate its rows");
		}

		int rows = matrixShape.length(0);
		return (width == 1 ? new TraversalPolicy(rows) : new TraversalPolicy(rows, width)).traverseEach();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also binds the ordering of the matrix to the arguments of the kernel, and obtains the
	 * enumeration of its rows from the bound ordering.</p>
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		segments = order.bind(manager).segments(segmentLength).orElseThrow();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also discards the enumeration bound by
	 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)}.</p>
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();
		segments = null;
	}

	/**
	 * Returns zero: every output element starts the reduction empty.
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
	 * Continues while the loop index is within the row of the current kernel thread, whose
	 * length is read from device memory.
	 *
	 * @param args  the traversable arguments
	 * @param index the position within the row
	 * @return {@code index < length(i)}
	 */
	@Override
	protected Expression<Boolean> getCondition(TraversableExpression[] args, Expression index) {
		return index.lessThan(segments.length(rowOf(new KernelIndex())));
	}

	/**
	 * Returns the row reduced by the kernel thread computing the given output element.
	 *
	 * @param outputIndex the flat index of the output element
	 * @return the row, {@code outputIndex / width}
	 */
	private Expression<?> rowOf(Expression<?> outputIndex) {
		return outputIndex.divide(width);
	}

	/**
	 * Returns the accumulated value after one more entry of the row handled by the current
	 * kernel thread. The entry is read from the memory of the matrix at its physical position,
	 * not through the ordering.
	 *
	 * @param args        the traversable arguments, where {@code args[0]} is the output
	 * @param globalIndex the index of the output element
	 * @param localIndex  the position of the entry within its row
	 * @return the output element plus {@code matrix[p] * input[offset(p), w]}
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		ArrayVariable<?> out = getOutputVariable();
		Expression currentValue = out.reference(globalIndex.multiply(out.length()));

		Expression<?> position = segments.start(rowOf(globalIndex)).add(localIndex);
		Expression<?> column = segments.offset(position);
		Expression<?> gathered = args[INPUT].getValueAt(column.multiply(width).add(globalIndex.imod(width)));
		// TODO(review): physical-position read assumes the matrix argument's memory is entry-packed; verify for a computed matrix producer whose shape carries the ordering
		Expression<?> entry = getArgumentForInput(getInputs().get(MATRIX)).reference(position);
		return currentValue.add(entry.multiply(gathered));
	}

	/**
	 * Always returns true: the iteration count is read from device memory, so the
	 * computation can only run as a native loop in a kernel of its own.
	 *
	 * @param context the process context
	 * @return always true
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return true;
	}

	/**
	 * Creates the product of new operands with the same width, in the order matrix, input.
	 *
	 * @param children the output followed by the two operands
	 * @return the new computation
	 */
	@Override
	public SegmentedWeightedSumComputation generate(List<Process<?, ?>> children) {
		return new SegmentedWeightedSumComputation(getName(), width,
				(Producer<PackedCollection>) children.get(MATRIX),
				(Producer<PackedCollection>) children.get(INPUT));
	}
}
