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
import io.almostrealism.compute.ProcessContext;
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
 * <h2>Process Isolation Threshold</h2>
 * <p>
 * This computation implements {@link #isIsolationTarget(ProcessContext)} to control when
 * it should be isolated into a separate kernel with native loops vs embedded into the
 * parent expression tree. The threshold is set to {@value #ISOLATION_GROUP_SIZE_THRESHOLD}
 * elements in the input group.
 * </p>
 *
 * <p><b>Important:</b> Isolation involves a tradeoff between compilation time and runtime performance:</p>
 * <ul>
 *   <li><b>Compilation time:</b> Isolation dramatically reduces compilation time (e.g., 10x faster
 *       at group size 64) by avoiding construction of large expression trees</li>
 *   <li><b>Runtime overhead:</b> Isolation adds 22-45% runtime overhead per iteration due to
 *       kernel dispatch overhead</li>
 * </ul>
 *
 * <p>
 * Empirical testing (see {@code WeightedSumIsolationRuntimeTest}) measured both compilation
 * and execution time across group sizes 8-2048 with 100-1000 warmup iterations and 1000
 * measurement iterations. Key findings:
 * </p>
 * <table border="1">
 *   <caption>Break-even analysis for isolation</caption>
 *   <tr><th>Group Size</th><th>Break-even Iterations</th><th>Winner at 10K</th><th>Winner at 100K</th></tr>
 *   <tr><td>64</td><td>~2,200</td><td>No Isolation</td><td>No Isolation</td></tr>
 *   <tr><td>256</td><td>~6,100</td><td>No Isolation</td><td>No Isolation</td></tr>
 *   <tr><td>1024</td><td>~13,000</td><td>Isolation</td><td>No Isolation</td></tr>
 *   <tr><td>2048</td><td>~32,800</td><td>Isolation</td><td>No Isolation</td></tr>
 * </table>
 *
 * <p>
 * For typical training workloads (10K-100K iterations over the lifetime of an application),
 * the data shows that <b>no isolation is better for all group sizes up to 2048</b>. The
 * threshold of 4096 ensures isolation only triggers for exceptionally large reductions where
 * compilation time would otherwise be prohibitive, while avoiding the runtime penalty for
 * common use cases.
 * </p>
 *
 * <p>
 * <b>Do not lower this threshold</b> without re-running the empirical analysis. The intuition
 * that "faster compilation = better" is incorrect for long-running applications where
 * compilation happens once but execution happens many times.
 * </p>
 *
 * @author  Michael Murray
 * @see org.almostrealism.algebra.AlgebraFeatures#weightedSum
 * @see org.almostrealism.algebra.MatrixFeatures#matmul
 */
public class WeightedSumComputation
		extends TraversableExpressionComputation {

	/**
	 * Minimum input group size (total elements) before isolation is triggered.
	 * See class javadoc for empirical justification of this threshold.
	 */
	public static final int ISOLATION_GROUP_SIZE_THRESHOLD = 4096;

	private final TraversalPolicy resultShape;
	private final TraversalPolicy inputPositions;
	private final TraversalPolicy weightPositions;
	private final TraversalPolicy inputGroupShape;
	private final TraversalPolicy weightGroupShape;

	private final TraversalPolicy inShape;
	private final TraversalPolicy weightShape;

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
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new WeightedSumComputation(resultShape,
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
	public CollectionProducer delta(Producer<?> target) {
		if (AlgebraFeatures.match(getInputs().get(1), target) && AlgebraFeatures.cannotMatch(getInputs().get(2), target)) {
			return new DefaultTraversableExpressionComputation("weightedSumDelta",
					getShape().append(shape(target)),
					args ->
							new WeightedSumDeltaExpression(getShape(), shape(target), getInputTraversal(), getWeightsTraversal(), args[1]),
					getInputs().get(2));
		} else if (AlgebraFeatures.match(getInputs().get(2), target) && AlgebraFeatures.cannotMatch(getInputs().get(1), target)) {
			return new DefaultTraversableExpressionComputation("weightedSumDelta",
					getShape().append(shape(target)),
					args ->
							new WeightedSumDeltaExpression(getShape(), shape(target), getWeightsTraversal(), getInputTraversal(), args[1]),
					getInputs().get(1));
		}

		return super.delta(target);
	}

	/**
	 * Determines whether this computation should be isolated into a separate kernel.
	 *
	 * <p>
	 * Isolation is triggered when the input group size exceeds {@link #ISOLATION_GROUP_SIZE_THRESHOLD}.
	 * See the class javadoc for detailed empirical analysis of the compilation vs runtime tradeoff.
	 * </p>
	 *
	 * @param context  the process context
	 * @return {@code true} if the input group size exceeds the threshold
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return inputGroupShape.getTotalSize() >= ISOLATION_GROUP_SIZE_THRESHOLD;
	}
}
