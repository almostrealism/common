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
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A computation that concatenates multiple {@link PackedCollection}s along a specified axis.
 *
 * <p>This class provides an efficient implementation of concatenation with Producer-level
 * differentiation. Unlike the default implementation using {@code add(pad(...), pad(...))},
 * this class directly computes concatenation and provides isolated gradient computation,
 * preventing exponential expression tree growth during backpropagation.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For inputs A (shape [..., n, ...]) and B (shape [..., m, ...]) along axis d:</p>
 * <pre>
 * output[..., i, ...] = A[..., i, ...]     if i &lt; n
 *                     = B[..., i-n, ...]   if i &gt;= n
 * </pre>
 *
 * <h2>Chain Rule Implementation</h2>
 * <p>The gradient of concatenation with respect to each input is a subset operation:</p>
 * <ul>
 *   <li>d(concat)/dA = subset of output gradient for positions 0 to n-1</li>
 *   <li>d(concat)/dB = subset of output gradient for positions n to n+m-1</li>
 * </ul>
 *
 * <p>This class implements delta() at the Producer level, creating isolated computation
 * boundaries for each input's gradient, which prevents the expression tree explosion
 * that occurs with the add+pad implementation.</p>
 *
 * @see PackedCollectionSubset
 * @see PackedCollectionPad
 * @see TraversableExpressionComputation
 *
 * @author Michael Murray
 */
public class CollectionConcatenateComputation extends TransitiveDeltaExpressionComputation
		implements CollectionFeatures {

	private final int axis;
	private final TraversalPolicy[] inputShapes;
	private final int[] offsets;

	/**
	 * Constructs a concatenation computation along the specified axis.
	 *
	 * @param shape The output shape after concatenation
	 * @param axis The axis along which to concatenate (0-indexed)
	 * @param inputs The input producers to concatenate
	 */
	@SafeVarargs
	public CollectionConcatenateComputation(TraversalPolicy shape, int axis,
											Producer<PackedCollection>... inputs) {
		super("concat", shape, inputs);
		this.axis = axis;

		this.inputShapes = Stream.of(inputs)
				.map(this::shape)
				.toArray(TraversalPolicy[]::new);

		this.offsets = new int[inputShapes.length];
		int offset = 0;
		for (int i = 0; i < inputShapes.length; i++) {
			offsets[i] = offset;
			offset += inputShapes[i].length(axis);
		}

		init();
	}

	/**
	 * Returns the axis along which concatenation is performed.
	 *
	 * @return The concatenation axis
	 */
	public int getAxis() {
		return axis;
	}

	/**
	 * Returns the shapes of all input collections.
	 *
	 * @return Array of input shapes
	 */
	public TraversalPolicy[] getInputShapes() {
		return inputShapes;
	}

	/**
	 * Generates the expression that computes the concatenation.
	 *
	 * <p>For each output position, this determines which input to read from
	 * based on the position along the concatenation axis, then reads the
	 * appropriate value from that input.</p>
	 *
	 * @param args Array of TraversableExpressions where args[1..n] are the inputs
	 * @return A CollectionExpression that computes the concatenated output
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		TraversalPolicy outputShape = getShape();

		return DefaultCollectionExpression.create(outputShape, idx -> {
			// Separate batch and local index
			long blockSize = outputShape.getTotalSizeLong();
			Expression<?> batchIdx = idx.divide(blockSize);
			Expression<?> localIdx = idx.imod(blockSize);

			// Get position along each dimension
			Expression<?>[] pos = outputShape.position(localIdx);

			// Build nested conditionals to select the right input
			Expression<?> result = e(0.0);

			for (int i = inputShapes.length - 1; i >= 0; i--) {
				TraversalPolicy inputShape = inputShapes[i];
				int inputOffset = offsets[i];

				// Compute position in this input
				Expression<?>[] inputPos = new Expression[pos.length];
				for (int d = 0; d < pos.length; d++) {
					if (d == axis) {
						inputPos[d] = pos[d].subtract(inputOffset);
					} else {
						inputPos[d] = pos[d];
					}
				}

				// Compute input index
				Expression<?> inputIdx = inputShape.index(inputPos)
						.add(batchIdx.multiply(inputShape.getTotalSizeLong()));

				// Get value from this input (args[i+1] because args[0] is destination)
				Expression<?> value = args[i + 1].getValueAt(inputIdx);

				if (i == inputShapes.length - 1) {
					// Last input - no condition needed
					result = value;
				} else {
					// Check if position is in range for this input
					int nextOffset = offsets[i + 1];
					result = Conditional.of(pos[axis].lessThan(nextOffset), value, result);
				}
			}

			return result;
		});
	}

	/**
	 * Computes the derivative of the concatenation with respect to the target using
	 * pad+add at the Producer level.
	 *
	 * <p>Instead of creating a new {@link CollectionConcatenateComputation} with delta inputs
	 * (which produces deeply nested {@link Conditional} expressions that cause expression tree
	 * explosion during simplification), this method computes each input's delta independently
	 * and combines them using pad+add. Each pad and add becomes a separate compiled kernel,
	 * avoiding the exponential expression tree growth.</p>
	 *
	 * <p>For concat(A1, A2, ..., An) along axis d, the chain rule gives:</p>
	 * <pre>
	 * d(concat)/dtarget = pad(dA1/dtarget, offset=0) + pad(dA2/dtarget, offset=n1) + ...
	 * </pre>
	 *
	 * @param target The {@link Producer} with respect to which the derivative is computed
	 * @return A {@link CollectionProducer} that computes the derivative
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		CollectionProducer delta = attemptDelta(target);
		if (delta != null) return delta;

		TraversalPolicy targetShape = shape(target);
		TraversalPolicy fullShape = getShape().append(targetShape);

		List<Producer<?>> paddedDeltas = new ArrayList<>();

		for (int i = 0; i < inputShapes.length; i++) {
			CollectionProducer input = (CollectionProducer) getInputs().get(i + 1);
			CollectionProducer inputDelta = input.delta(target);

			int[] padPos = new int[fullShape.getDimensions()];
			padPos[axis] = offsets[i];

			paddedDeltas.add(pad(fullShape, new TraversalPolicy(true, padPos), inputDelta));
		}

		return add(paddedDeltas);
	}

	/**
	 * Generates a new concatenation computation with the specified child processes.
	 *
	 * <p>This method computes the output shape dynamically from the input shapes,
	 * which is essential for correct delta propagation where input shapes change.</p>
	 *
	 * @param children The child processes to use as inputs
	 * @return A new CollectionConcatenateComputation with the updated children
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		Producer<PackedCollection>[] inputs = children.stream()
				.skip(1)
				.map(p -> (Producer<PackedCollection>) p)
				.toArray(Producer[]::new);

		// Compute output shape from input shapes (needed for delta propagation
		// where input shapes have target dimensions appended)
		TraversalPolicy firstShape = shape(inputs[0]);
		long[] dims = new long[firstShape.getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			if (i == axis) {
				// Sum sizes along the concatenation axis
				long axisSum = 0;
				for (Producer<PackedCollection> input : inputs) {
					axisSum += shape(input).length(axis);
				}
				dims[i] = axisSum;
			} else {
				dims[i] = firstShape.length(i);
			}
		}
		TraversalPolicy outputShape = new TraversalPolicy(dims);

		return new CollectionConcatenateComputation(outputShape, axis, inputs)
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) {
			return null;
		}

		return signature + "{axis=" + axis + "}";
	}
}
