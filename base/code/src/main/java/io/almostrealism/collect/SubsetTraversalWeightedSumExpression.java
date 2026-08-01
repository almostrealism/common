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

package io.almostrealism.collect;

/**
 * A {@link WeightedSumExpression} that accumulates values from a sliding window (group)
 * over an input collection, multiplied by corresponding weights.
 *
 * <p>This implements the kernel/convolution pattern: for each output position, a group of
 * input elements (defined by the window shape and its anchor positions) are multiplied by
 * weight elements and summed. The index mapping for each group member is provided by
 * {@link SubsetTraversalIndexMapping} instances constructed from the geometry shapes.</p>
 *
 * <p>Multiple constructor overloads allow the weight window to be:
 * <ul>
 *   <li>Fixed to position {@code 0} (the weight is not traversed)</li>
 *   <li>Traversed with its own set of anchor positions</li>
 *   <li>Fully specified with separate shapes for input and weight windows</li>
 * </ul>
 * </p>
 */
public class SubsetTraversalWeightedSumExpression extends WeightedSumExpression {

	/**
	 * Creates a subset weighted sum where the input is traversed over all positions but the
	 * weight window is not traversed (it is always read from position 0).
	 *
	 * @param inputPositions the anchor positions for the input window
	 * @param inputShape     the shape of the input operand
	 * @param groupShape     the shape of one sliding window
	 * @param input          the input operand expression
	 * @param weights        the weight operand expression
	 */
	public SubsetTraversalWeightedSumExpression(TraversalPolicy inputPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		// Do not traverse the weights, only traverse the input
		this(inputPositions, TraversalPolicy.uniform(1, inputPositions.getDimensions()),
				inputShape, groupShape, input, weights);
	}

	/**
	 * Creates a subset weighted sum with separate anchor positions for the input and weight windows.
	 *
	 * @param inputPositions  the anchor positions for the input window
	 * @param weightPositions the anchor positions for the weight window
	 * @param inputShape      the shape of the input operand
	 * @param groupShape      the shared window shape for both input and weights
	 * @param input           the input operand expression
	 * @param weights         the weight operand expression
	 */
	public SubsetTraversalWeightedSumExpression(TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		this(new TraversalPolicy(inputPositions.extent()),
				inputPositions, weightPositions, inputShape, groupShape, input, weights);
	}

	/**
	 * Creates a subset weighted sum with an explicit output shape and separate anchor positions
	 * for the input and weight windows (using a common window shape for both).
	 *
	 * @param shape           the explicit output shape
	 * @param inputPositions  the anchor positions for the input window
	 * @param weightPositions the anchor positions for the weight window
	 * @param inputShape      the shape of the input operand
	 * @param groupShape      the shared window shape for both input and weights
	 * @param input           the input operand expression
	 * @param weights         the weight operand expression
	 */
	public SubsetTraversalWeightedSumExpression(TraversalPolicy shape,
												TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy groupShape,
												TraversableExpression input, TraversableExpression weights) {
		this(shape, inputPositions, weightPositions, inputShape, inputShape,
				groupShape, groupShape, input, weights);
	}

	/**
	 * Full constructor allowing independent window shapes for the input and weight operands.
	 *
	 * @param shape            the output shape
	 * @param inputPositions   the anchor positions for the input window
	 * @param weightPositions  the anchor positions for the weight window
	 * @param inputShape       the shape of the input operand
	 * @param weightShape      the shape of the weight operand
	 * @param inputGroupShape  the window shape used when traversing the input
	 * @param weightGroupShape the window shape used when traversing the weights
	 * @param input            the input operand expression
	 * @param weights          the weight operand expression
	 * @throws IllegalArgumentException if the input and weight group shapes have different total sizes
	 */
	public SubsetTraversalWeightedSumExpression(TraversalPolicy shape,
												TraversalPolicy inputPositions, TraversalPolicy weightPositions,
												TraversalPolicy inputShape, TraversalPolicy weightShape,
												TraversalPolicy inputGroupShape, TraversalPolicy weightGroupShape,
												TraversableExpression input, TraversableExpression weights) {
		super(shape, inputGroupShape.getTotalSize(), input, weights,
				indexGenerator(shape,
						inputPositions, weightPositions,
						inputShape, weightShape,
						inputGroupShape, weightGroupShape));

		if (weightGroupShape.getTotalSize() != inputGroupShape.getTotalSize()) {
			throw new IllegalArgumentException();
		}
	}

	/** {@inheritDoc} */
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return super.delta(target);
	}

	/**
	 * Builds a {@link MemberIndexGenerator} that creates one {@link SubsetTraversalIndexMapping}
	 * per group member, routing each (memberIndex, operandIndex) pair to the appropriate geometry.
	 *
	 * @param resultShape      the output shape
	 * @param inputPositions   anchor positions for the input
	 * @param weightPositions  anchor positions for the weights
	 * @param inputShape       shape of the input operand
	 * @param weightShape      shape of the weight operand
	 * @param inputGroupShape  window shape for the input
	 * @param weightGroupShape window shape for the weights
	 * @return the member index generator
	 * @throws IllegalArgumentException if the dimension counts of the shapes are inconsistent
	 */
	private static MemberIndexGenerator indexGenerator(
			TraversalPolicy resultShape,
			TraversalPolicy inputPositions, TraversalPolicy weightPositions,
			TraversalPolicy inputShape, TraversalPolicy weightShape,
			TraversalPolicy inputGroupShape, TraversalPolicy weightGroupShape) {
		if (inputPositions.getDimensions() != resultShape.getDimensions() ||
				weightPositions.getDimensions() != resultShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (inputPositions.getDimensions() != inputShape.getDimensions() ||
				inputGroupShape.getDimensions() != inputShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (weightPositions.getDimensions() != weightShape.getDimensions() ||
				weightGroupShape.getDimensions() != weightShape.getDimensions()) {
			throw new IllegalArgumentException();
		}

		return (groupIndex, operandIndex) -> {
			TraversalPolicy operandShape = operandIndex == 0 ? inputShape : weightShape;
			TraversalPolicy groupShape = operandIndex == 0 ? inputGroupShape : weightGroupShape;
			TraversalPolicy positions = operandIndex == 0 ? inputPositions : weightPositions;
			return new SubsetTraversalIndexMapping(resultShape, operandShape, groupShape, positions, groupIndex);
		};
	}
}
