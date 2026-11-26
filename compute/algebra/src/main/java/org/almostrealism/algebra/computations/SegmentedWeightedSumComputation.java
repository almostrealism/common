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
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.RepeatedProducerComputation;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

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

	/**
	 * The shape through which the matrix is read, carrying its ordering. It is the shape of the
	 * matrix operand, except for the {@link #deltaDependent transposed product} that forms the
	 * gradient of the input, which reads the memory of the matrix as its transpose.
	 */
	private final TraversalPolicy matrixShape;

	/** The ordering of the matrix, which enumerates its rows. */
	private final TraversalOrdering order;

	/** The enumeration of the rows, bound to the arguments of the kernel being compiled. */
	private OrderingSegments segments;

	/**
	 * Creates the product of the given matrix with the given input.
	 *
	 * @param name   the computation name
	 * @param width  the number of input columns, 1 for a vector; the output has shape
	 *               {@code (rows, width)}, as the dense product does
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
		this.matrixShape = matrixShape;
		this.order = matrixShape.getOrder();
	}

	/**
	 * Returns the output shape for a matrix of the given shape and the given width, rejecting a
	 * matrix whose rows cannot be enumerated and a non-positive width before either reaches the
	 * index arithmetic of the kernel.
	 *
	 * @param matrixShape the shape of the matrix, carrying its ordering
	 * @param width       the number of input columns
	 * @return {@code (rows, width)}, which for a vector input ({@code width} 1) is
	 *         {@code (rows, 1)}, the shape of the dense product
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

		return new TraversalPolicy(matrixShape.length(0), width).traverseEach();
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
	 * <p>Also counts the {@link TraversalOrdering#getArgumentCount() arguments} binding the
	 * ordering of the matrix obtains.</p>
	 */
	@Override
	public int getScopeArgumentCount(int cap) {
		return (int) Math.min(Integer.MAX_VALUE, (long) super.getScopeArgumentCount(cap) + order.getArgumentCount());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Also discards the enumeration bound by
	 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)}.</p>
	 */
	@Override
	public void resetArguments(Set<ScopeLifecycle> visited) {
		super.resetArguments(visited);
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
	 * kernel thread. The entry is read from the memory of the matrix at its
	 * {@link OrderingSegments#position(Expression) physical position}, not through the ordering.
	 *
	 * @param args        the traversable arguments, where {@code args[0]} is the output
	 * @param globalIndex the index of the output element
	 * @param localIndex  the position of the entry within its row
	 * @return the output element plus {@code matrix[p] * input[offset(p), w]}
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		Expression<?> currentValue = getCurrentOutputValue(getOutputVariable(), globalIndex);

		Expression<?> position = segments.start(rowOf(globalIndex)).add(localIndex);
		Expression<?> column = segments.offset(position);
		Expression<?> gathered = args[INPUT].getValueAt(column.multiply(width).add(globalIndex.imod(width)));
		Expression<?> entry = getArgumentForInput(getInputs().get(MATRIX)).reference(segments.position(position));
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
	 * Creates the product of new operands with the same width, in the order matrix, input, reading
	 * the matrix through the same shape as this product.
	 *
	 * @param children the output followed by the two operands
	 * @return the new computation
	 */
	@Override
	public SegmentedWeightedSumComputation generate(List<Process<?, ?>> children) {
		return new SegmentedWeightedSumComputation(getName(), matrixShape, width,
				(Producer<PackedCollection>) children.get(MATRIX),
				(Producer<PackedCollection>) children.get(INPUT));
	}

	/**
	 * Differentiates this product, which is bilinear in the matrix and the input, without forming
	 * its Jacobian: each reduced delta is a computation over the stored entries alone, as the
	 * product itself is, and so applies to a matrix whose logical size exceeds the range of an
	 * {@code int}.
	 * <ul>
	 *   <li>{@link ReduceAxis#OUT}, input: the gradient {@code Aᵀ·g} of the upstream gradient
	 *       {@code g}, which is the product of the {@link TraversalOrdering#transposed(int)
	 *       transpose} of the matrix, read over the same memory, with {@code g}. It has the shape
	 *       of the input and is passed on to the input's own delta.</li>
	 *   <li>{@link ReduceAxis#OUT}, matrix: the gradient of each stored entry {@code k},
	 *       {@code Σ_w g[row(k), w] · input[column(k), w]}, which is the
	 *       {@link SegmentedOuterProductComputation outer product} of {@code g} and the input
	 *       restricted to the stored entries. It has one element per stored entry, in their
	 *       physical order, rather than the logical shape of the matrix: a sparse matrix is
	 *       parameterised by its entries alone.</li>
	 *   <li>{@link ReduceAxis#IN}, input: the product of the matrix with the input's own
	 *       input-reduced delta of the tangent.</li>
	 *   <li>{@link ReduceAxis#IN}, matrix: the product with the input of a matrix whose stored
	 *       entries are the tangent, which therefore has one element per stored entry.</li>
	 * </ul>
	 * <p>The full Jacobian ({@link ReduceAxis#NONE}) of a sparse product is the one form that
	 * cannot be represented once its logical size exceeds the range of an {@code int}, and is left
	 * to the general implementation, as is any case the rules above do not cover: a matrix that
	 * depends on the target without being it, a target both operands depend on, an ordering that
	 * cannot form the transpose or the row of each entry, and a tangent of another size.</p>
	 */
	@Override
	protected CollectionProducer deltaDependent(Producer<?> target, ReduceAxis axis, Producer<PackedCollection> weight) {
		Producer<PackedCollection> matrix = getInputs().get(MATRIX);
		Producer<PackedCollection> input = getInputs().get(INPUT);
		if (axis == ReduceAxis.NONE) return super.deltaDependent(target, axis, weight);

		if (matrix.cannotMatch(target)) {
			CollectionProducer delta = axis == ReduceAxis.OUT ? inputGradient(target, weight) : inputTangent(target, weight);
			if (delta != null) return delta;
		} else if (matrix.refersTo(target) && input.cannotMatch(target)) {
			// TODO(review): entry deltas use shape (entries), not ReduceAxis.resultShape/weightShape of the (rows, cols) target
			CollectionProducer delta = axis == ReduceAxis.OUT ? entryGradient(weight) : entryTangent(weight);
			if (delta != null) return delta;
		}

		return super.deltaDependent(target, axis, weight);
	}

	/**
	 * Returns the gradient with respect to the target of the input, {@code Aᵀ·g} passed on to the
	 * input's own output-reduced delta.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @param weight the upstream gradient, in the shape of this product, or null for ones
	 * @return the gradient, or null if the ordering cannot form the transpose
	 */
	private CollectionProducer inputGradient(Producer<?> target, Producer<PackedCollection> weight) {
		Optional<TraversalOrdering> transpose = order.transposed(segmentLength);
		if (transpose.isEmpty()) return null;

		TraversalPolicy transposeShape = new TraversalPolicy(transpose.get(), segmentLength, matrixShape.length(0));
		CollectionProducer gradient = new SegmentedWeightedSumComputation(getName() + "Transpose",
				transposeShape, width, getInputs().get(MATRIX), upstream(weight));
		return c(getInputs().get(INPUT)).delta(target, ReduceAxis.OUT,
				gradient.reshape(shape(getInputs().get(INPUT))));
	}

	/**
	 * Returns the Jacobian-vector product with respect to the target of the input: the product of
	 * the matrix with the input's own input-reduced delta of the tangent.
	 *
	 * @param target  the producer with respect to which to differentiate
	 * @param tangent the tangent, in the shape of the target
	 * @return the product, or null without a tangent
	 */
	private CollectionProducer inputTangent(Producer<?> target, Producer<PackedCollection> tangent) {
		if (tangent == null) return null;

		Producer<PackedCollection> input = getInputs().get(INPUT);
		return new SegmentedWeightedSumComputation(getName(), matrixShape, width, getInputs().get(MATRIX),
				c(input).delta(target, ReduceAxis.IN, tangent).reshape(shape(input)));
	}

	/**
	 * Returns the gradient of each stored entry of the matrix, the outer product of the upstream
	 * gradient and the input restricted to the stored entries.
	 *
	 * @param weight the upstream gradient, in the shape of this product, or null for ones
	 * @return the gradient, one element per stored entry, or null if the ordering cannot form the
	 *         row of each entry
	 */
	private CollectionProducer entryGradient(Producer<PackedCollection> weight) {
		if (order.memberSegments(segmentLength).isEmpty()) return null;
		return new SegmentedOuterProductComputation(getName() + "EntryGradient", matrixShape, width,
				upstream(weight), getInputs().get(INPUT));
	}

	/**
	 * Returns the Jacobian-vector product with respect to the matrix: the product with the input
	 * of the matrix whose stored entries are the tangent.
	 *
	 * @param tangent the tangent of each stored entry, in their physical order
	 * @return the product, or null if the tangent does not have one element per stored entry
	 */
	private CollectionProducer entryTangent(Producer<PackedCollection> tangent) {
		OptionalInt entries = order.getLength();
		if (tangent == null || entries.isEmpty() || shape(tangent).getTotalSizeLong() != entries.getAsInt()) {
			return null;
		}

		return new SegmentedWeightedSumComputation(getName(), matrixShape, width, tangent, getInputs().get(INPUT));
	}

	/**
	 * Returns the upstream gradient in the shape of this product.
	 *
	 * @param weight the upstream gradient, or null to weight every output by one
	 * @return the upstream gradient
	 */
	private CollectionProducer upstream(Producer<PackedCollection> weight) {
		return weight == null ? constant(getShape(), 1.0) : c(weight).reshape(getShape());
	}
}
