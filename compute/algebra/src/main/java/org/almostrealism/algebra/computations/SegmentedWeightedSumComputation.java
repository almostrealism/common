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

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.SplitIndexTraversalOrdering;
import org.almostrealism.collect.computations.RepeatedProducerComputation;

import java.util.List;

/**
 * A segmented weighted sum: for each output element, the sum of a data-dependent range of
 * weighted, gathered input values. This is the reduction behind a compressed-sparse-row
 * (CSR) sparse matrix product.
 *
 * <h2>Mathematical Operation</h2>
 * <p>For an input of {@code width} columns (a vector when {@code width} is 1):</p>
 * <pre>
 * output[i, n] = sum(k = offsets[i] .. offsets[i + 1] - 1) values[k] * input[columns[k], n]
 * </pre>
 *
 * <p>The operands are, in order: the gathered {@code input}, the per-entry {@code values},
 * the per-entry {@code columns} (gather indices into the rows of {@code input}) and the
 * segment {@code offsets}. Both index operands are stored split across two lanes in the
 * layout of {@link SplitIndexTraversalOrdering}, and decoded with
 * {@link SplitIndexTraversalOrdering#positionAt}, so that offsets and columns above
 * {@code 2^24} stay exact on FP32 backends. Every flat address into {@code values} and
 * {@code columns} is formed from the offsets on integer expressions inside the kernel, so it
 * is exact too.</p>
 *
 * <h2>Generated Code Structure</h2>
 * <p>One kernel thread computes one output element, writing it exactly once, with no
 * atomics. The loop bound is read from device memory, so each thread runs as many iterations
 * as its own segment holds:</p>
 * <pre>
 * out[g] = 0.0;
 * for (int k = 0; k &lt; offsets[i + 1] - offsets[i];) {
 *     out[g] = out[g] + values[offsets[i] + k] * input[columns[offsets[i] + k] * width + n];
 *     k = k + 1;
 * }
 * </pre>
 * <p>where {@code i = g / width} and {@code n = g % width}.</p>
 *
 * <p>Because the iteration count is not known when the computation is built, the value of an
 * element cannot be unrolled into the expression of a consumer. This computation is therefore
 * not a {@link TraversableExpression}: it is always compiled into a kernel of its own, and its
 * consumers read its output.</p>
 *
 * <p>The kernel depends only on the operand shapes, not on their contents, so products with
 * the same shapes share a compiled kernel.</p>
 *
 * @see LoopedWeightedSumComputation
 * @see SplitIndexTraversalOrdering
 * @see org.almostrealism.algebra.SparseMatrix
 *
 * @author Michael Murray
 */
public class SegmentedWeightedSumComputation extends RepeatedProducerComputation {
	/** Position of the gathered input among the traversable arguments. */
	private static final int INPUT = 1;

	/** Position of the per-entry values among the traversable arguments. */
	private static final int VALUES = 2;

	/** Position of the split per-entry gather indices among the traversable arguments. */
	private static final int COLUMNS = 3;

	/** Position of the split segment offsets among the traversable arguments. */
	private static final int OFFSETS = 4;

	/** The number of segments, which is the number of output rows. */
	private final int count;

	/** The number of input columns, each reduced independently into an output column. */
	private final int width;

	/**
	 * Creates a segmented weighted sum with one output row per segment.
	 *
	 * @param name    the computation name
	 * @param count   the number of segments, which is the number of output rows
	 * @param width   the number of input columns; 1 for a vector, whose output has shape
	 *                {@code (count)}, otherwise the output has shape {@code (count, width)}
	 * @param input   the rows gathered by {@code columns}, {@code width} values per row
	 * @param values  the weight of each entry
	 * @param columns the input row of each entry, as split lanes of shape {@code (m, 2)}
	 * @param offsets the {@code count + 1} segment boundaries, as split lanes of shape
	 *                {@code (count + 1, 2)}, both produced by {@link PackedCollection#ofSplitIndices(int...)}
	 * @throws IllegalArgumentException if {@code count} or {@code width} is not positive
	 */
	public SegmentedWeightedSumComputation(String name, int count, int width,
										   Producer<PackedCollection> input,
										   Producer<PackedCollection> values,
										   Producer<PackedCollection> columns,
										   Producer<PackedCollection> offsets) {
		super(name, outputShape(count, width), 1, input, values, columns, offsets);
		this.count = count;
		this.width = width;
	}

	/**
	 * Returns the output shape for the given segment count and width, rejecting a
	 * non-positive dimension before it reaches the index arithmetic of the kernel
	 * (a zero width would divide by zero when locating the segment of an element).
	 *
	 * @param count the number of segments
	 * @param width the number of input columns
	 * @return {@code (count)} when {@code width} is 1, otherwise {@code (count, width)}
	 * @throws IllegalArgumentException if {@code count} or {@code width} is not positive
	 */
	private static TraversalPolicy outputShape(int count, int width) {
		if (count <= 0 || width <= 0) {
			throw new IllegalArgumentException("Segment count " + count + " and width " + width
					+ " must be positive");
		}

		return (width == 1 ? new TraversalPolicy(count) : new TraversalPolicy(count, width)).traverseEach();
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
	 * Continues while the loop index is within the segment of the current kernel thread,
	 * whose length is read from device memory.
	 *
	 * @param args  the traversable arguments
	 * @param index the position within the segment
	 * @return {@code index < offsets[i + 1] - offsets[i]}
	 */
	@Override
	protected Expression<Boolean> getCondition(TraversableExpression[] args, Expression index) {
		return index.lessThan(segmentLength(args, segmentOf(new KernelIndex())));
	}

	/**
	 * Returns the segment reduced by the kernel thread computing the given output element.
	 *
	 * @param outputIndex the flat index of the output element
	 * @return the segment, {@code outputIndex / width}
	 */
	private Expression<?> segmentOf(Expression<?> outputIndex) {
		return outputIndex.divide(width);
	}

	/**
	 * Returns the integer-exact offset of the first entry of the given segment.
	 *
	 * @param args    the traversable arguments
	 * @param segment the segment index
	 * @return the offset of the segment's first entry
	 */
	private Expression<Integer> segmentStart(TraversableExpression[] args, Expression<?> segment) {
		return SplitIndexTraversalOrdering.positionAt(args[OFFSETS], segment);
	}

	/**
	 * Returns the number of entries in the given segment.
	 *
	 * @param args    the traversable arguments
	 * @param segment the segment index
	 * @return the number of entries in the segment, read from device memory
	 */
	private Expression<?> segmentLength(TraversableExpression[] args, Expression<?> segment) {
		return SplitIndexTraversalOrdering.positionAt(args[OFFSETS], segment.add(1))
				.subtract(segmentStart(args, segment));
	}

	/**
	 * Returns the accumulated value after one more entry of the segment handled by the
	 * current kernel thread.
	 *
	 * @param args        the traversable arguments, where {@code args[0]} is the output
	 * @param globalIndex the index of the output element
	 * @param localIndex  the position of the entry within its segment
	 * @return the output element plus {@code values[k] * input[columns[k], n]}
	 */
	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		ArrayVariable<?> out = getOutputVariable();
		Expression currentValue = out.reference(globalIndex.multiply(out.length()));

		Expression<?> entry = segmentStart(args, segmentOf(globalIndex)).add(localIndex);
		Expression<?> row = SplitIndexTraversalOrdering.positionAt(args[COLUMNS], entry);
		Expression<?> gathered = args[INPUT].getValueAt(row.multiply(width).add(globalIndex.imod(width)));
		return currentValue.add(args[VALUES].getValueAt(entry).multiply(gathered));
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
	 * Creates a segmented weighted sum with the same segment count and width over new
	 * operands, in the order input, values, columns, offsets.
	 *
	 * @param children the output followed by the four operands
	 * @return the new computation
	 */
	@Override
	public SegmentedWeightedSumComputation generate(List<Process<?, ?>> children) {
		return new SegmentedWeightedSumComputation(getName(), count, width,
				(Producer<PackedCollection>) children.get(INPUT),
				(Producer<PackedCollection>) children.get(VALUES),
				(Producer<PackedCollection>) children.get(COLUMNS),
				(Producer<PackedCollection>) children.get(OFFSETS));
	}
}
