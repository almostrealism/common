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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.SubsetTraversalExpression;
import io.almostrealism.collect.SubsetTraversalWeightedSumExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumDeltaExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

import java.util.List;

/**
 * A computation that performs a weighted sum operation, the fundamental building block for many linear algebra operations.
 *
 * <p>
 * {@link WeightedSumComputation} implements a generalized weighted sum where:
 * <ul>
 *   <li>Input and weight arrays are traversed according to specified position policies</li>
 *   <li>Elements are multiplied together</li>
 *   <li>Products are summed over specified group shapes</li>
 * </ul>
 * This operation is used to implement:
 * <ul>
 *   <li>Matrix multiplication</li>
 *   <li>Convolution</li>
 *   <li>Attention mechanisms in transformers</li>
 *   <li>Custom tensor contractions</li>
 * </ul>
 *
 * <h2>Matrix Multiplication Example</h2>
 * <pre>{@code
 * // Matrix-vector multiplication: y = Ax
 * // A: (3, 4) matrix, x: (4) vector, y: (3) result
 * TraversalPolicy resultShape = shape(3);
 * TraversalPolicy inputPos = shape(3, 4);   // Position policy for A
 * TraversalPolicy weightPos = shape(3, 4);  // Position policy for x
 * TraversalPolicy groupShape = shape(1, 4); // Sum over dimension 1
 *
 * WeightedSumComputation<PackedCollection> matmul =
 *     new WeightedSumComputation<>(
 *         resultShape, inputPos, weightPos,
 *         groupShape, groupShape,
 *         matrixA, vectorX
 *     );
 * }</pre>
 *
 * @param <T>  the packed collection type
 * @author  Michael Murray
 * @see org.almostrealism.algebra.AlgebraFeatures#weightedSum
 * @see org.almostrealism.algebra.MatrixFeatures#matmul
 */
public class WeightedSumComputation <T extends PackedCollection>
		extends TraversableExpressionComputation<T> {
	private TraversalPolicy resultShape;
	private TraversalPolicy inputPositions, weightPositions;
	private TraversalPolicy inputGroupShape, weightGroupShape;

	private TraversalPolicy inShape, weightShape;

	/**
	 * Creates a new weighted sum computation.
	 *
	 * @param resultShape  the shape of the result
	 * @param inputPositions  traversal policy defining how to position input elements
	 * @param weightPositions  traversal policy defining how to position weight elements
	 * @param inputGroupShape  group shape for input (dimensions to sum over)
	 * @param weightGroupShape  group shape for weights (dimensions to sum over)
	 * @param input  producer for input values
	 * @param weights  producer for weight values
	 * @throws IllegalArgumentException if the traversal policies have incompatible dimensions
	 */
	public WeightedSumComputation(TraversalPolicy resultShape,
								  TraversalPolicy inputPositions,
								  TraversalPolicy weightPositions,
								  TraversalPolicy inputGroupShape,
								  TraversalPolicy weightGroupShape,
								  Producer<PackedCollection> input,
								  Producer<PackedCollection> weights) {
		super("weightedSum", resultShape.traverseEach(), input, weights);
		this.resultShape = resultShape;
		this.inputPositions = inputPositions;
		this.weightPositions = weightPositions;
		this.inputGroupShape = inputGroupShape;
		this.weightGroupShape = weightGroupShape;
		this.inShape = shape(input);
		this.weightShape = shape(weights);

		if (inputPositions.getDimensions() != resultShape.getDimensions() ||
				weightPositions.getDimensions() != resultShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (inputPositions.getDimensions() != inShape.getDimensions() ||
				inputGroupShape.getDimensions() != inShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (weightPositions.getDimensions() != weightShape.getDimensions() ||
				weightGroupShape.getDimensions() != weightShape.getDimensions()) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Generates the expression that performs the weighted sum.
	 *
	 * @param args  traversable expressions [this, input, weights]
	 * @return the weighted sum expression
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new SubsetTraversalWeightedSumExpression(
				resultShape,
				inputPositions, weightPositions,
				inShape, weightShape,
				inputGroupShape, weightGroupShape,
				args[1], args[2]);
	}

	/**
	 * Returns the subset traversal expression for the input.
	 *
	 * @return the input traversal expression
	 */
	public SubsetTraversalExpression getInputTraversal() {
		return new SubsetTraversalExpression(resultShape, inShape, inputGroupShape, inputPositions);
	}

	/**
	 * Returns the subset traversal expression for the weights.
	 *
	 * @return the weights traversal expression
	 */
	public SubsetTraversalExpression getWeightsTraversal() {
		return new SubsetTraversalExpression(resultShape, weightShape, weightGroupShape, weightPositions);
	}

	/**
	 * Generates the parallel process for this weighted sum computation.
	 *
	 * @param children  child processes
	 * @return a new weighted sum computation with the child producers
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new WeightedSumComputation<>(resultShape,
				inputPositions, weightPositions,
				inputGroupShape, weightGroupShape,
				(Producer) children.get(1),
				(Producer) children.get(2));
	}

	/**
	 * Computes the delta (gradient) of this weighted sum with respect to a target.
	 *
	 * <p>
	 * This method implements automatic differentiation for weighted sums:
	 * <ul>
	 *   <li>If target matches the input and not weights: returns gradient w.r.t. input</li>
	 *   <li>If target matches the weights and not input: returns gradient w.r.t. weights</li>
	 *   <li>Otherwise: delegates to superclass</li>
	 * </ul>
	 * </p>
	 *
	 * @param target  the target producer to differentiate with respect to
	 * @return the delta computation
	 */
	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (AlgebraFeatures.match(getInputs().get(1), target) && AlgebraFeatures.cannotMatch(getInputs().get(2), target)) {
			return new DefaultTraversableExpressionComputation<>("weightedSumDelta",
					getShape().append(shape(target)),
					args ->
							new WeightedSumDeltaExpression(getShape(), shape(target), getInputTraversal(), getWeightsTraversal(), args[1]),
					(Producer) getInputs().get(2));
		} else if (AlgebraFeatures.match(getInputs().get(2), target) && AlgebraFeatures.cannotMatch(getInputs().get(1), target)) {
			return new DefaultTraversableExpressionComputation<>("weightedSumDelta",
					getShape().append(shape(target)),
					args ->
							new WeightedSumDeltaExpression(getShape(), shape(target), getWeightsTraversal(), getInputTraversal(), args[1]),
					(Producer) getInputs().get(1));
		}

		return super.delta(target);
	}
}
