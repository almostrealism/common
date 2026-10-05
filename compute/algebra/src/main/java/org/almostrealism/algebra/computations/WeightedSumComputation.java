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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.SubsetTraversalExpression;
import io.almostrealism.collect.SubsetTraversalWeightedSumExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumDeltaExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.expression.Expression;
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
 * @author  Michael Murray
 * @see org.almostrealism.algebra.AlgebraFeatures#weightedSum
 * @see org.almostrealism.algebra.MatrixFeatures#matmul
 */
public class WeightedSumComputation
		extends TraversableExpressionComputation {

	// TODO(review): public static mutable config is global state (tests mutate it); consider a settings/property-backed source
	/**
	 * Whether a large weighted sum may be compiled as a native loop at all; when false every
	 * weighted sum is compiled as a single expression regardless of {@link #loopThreshold}.
	 *
	 * <p>The loop form is correct on the native (CPU) backend and on the GPU backends for the
	 * matmul and convolution kernels exercised by the ML tests, but it regressed the studio
	 * audio render to silence on the GPU backends (the {@code test-media-mac} and
	 * {@code test-media-cl} lanes), where the failure could not be reproduced or pinned from a
	 * CPU-only host. Until the loop kernel is verified against those backends for the audio
	 * render's weighted sums, it is opt-in: the default single-expression form is the one
	 * master shipped and is correct on every backend. Callers and tests that have verified the
	 * loop for their own kernels enable it explicitly. The setting is read when a weighted sum
	 * is constructed and, through {@link #isLooped()}, is part of its {@link #signature()}.</p>
	 */
	public static boolean enableLoopGeneration = false;

	/**
	 * Group size at or above which the kernel for a weighted sum is generated as a native
	 * loop over the group, rather than as one expression that sums every member.
	 *
	 * <p>The single-expression form builds, simplifies and renders one product per group
	 * member, so the cost of compiling it grows with the size of the group: a convolution
	 * over 1024 channels with a kernel of 7 is a single expression of 7168 products, nearly a
	 * megabyte of kernel source that takes tens of seconds to generate. The loop form compiles
	 * a body of at most {@link #maxUnrolledMembers} products whatever the size of the group.
	 * The setting is read when a weighted sum is constructed and is part of its
	 * {@link #signature() signature}.</p>
	 */
	public static int loopThreshold = 256;

	/**
	 * Maximum number of group members summed by each iteration of the loop that replaces a
	 * group of at least {@link #loopThreshold} members.
	 */
	public static int maxUnrolledMembers = 16;

	/** The shape of the output collection produced by this computation. */
	private final TraversalPolicy resultShape;

	/** The traversal policy defining which positions in the input to use for each output element. */
	private final TraversalPolicy inputPositions;

	/** The traversal policy defining which positions in the weight tensor to use for each output element. */
	private final TraversalPolicy weightPositions;

	/** The group shape that defines the sub-dimensions of the input that are summed over. */
	private final TraversalPolicy inputGroupShape;

	/** The group shape that defines the sub-dimensions of the weights that are summed over. */
	private final TraversalPolicy weightGroupShape;

	/** The full shape of the input collection. */
	private final TraversalPolicy inShape;

	/** The full shape of the weight collection. */
	private final TraversalPolicy weightShape;

	/**
	 * Number of group members summed by each iteration of the loop the kernel is generated
	 * as, or zero when the group is summed by a single expression.
	 */
	private final int loopMembers;

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
		this(resultShape, inputPositions, weightPositions,
				inputGroupShape, weightGroupShape, input, weights,
				unrolledLoopMembers(inputGroupShape));
	}

	/**
	 * Creates a new weighted sum computation with an explicit loop decision. This is the form
	 * {@link #generate(List)} uses, so that optimization preserves the number of members each
	 * loop iteration sums - and hence the kernel form and {@link #signature() signature} - of
	 * the sum it regenerates, rather than rereading the mutable {@link #loopThreshold}, which a
	 * concurrently constructed sum may have changed.
	 *
	 * @param resultShape  the shape of the result
	 * @param inputPositions  traversal policy defining how to position input elements
	 * @param weightPositions  traversal policy defining how to position weight elements
	 * @param inputGroupShape  group shape for input (dimensions to sum over)
	 * @param weightGroupShape  group shape for weights (dimensions to sum over)
	 * @param input  producer for input values
	 * @param weights  producer for weight values
	 * @param loopMembers  the number of group members summed by each loop iteration, or zero
	 *                     when the group is summed by a single expression
	 * @throws IllegalArgumentException if the traversal policies have incompatible dimensions,
	 *                                  or the input and weight groups have different total sizes
	 */
	private WeightedSumComputation(TraversalPolicy resultShape,
								   TraversalPolicy inputPositions,
								   TraversalPolicy weightPositions,
								   TraversalPolicy inputGroupShape,
								   TraversalPolicy weightGroupShape,
								   Producer<PackedCollection> input,
								   Producer<PackedCollection> weights,
								   int loopMembers) {
		super("weightedSum", resultShape.traverseEach(), input, weights);
		this.resultShape = resultShape;
		this.inputPositions = inputPositions;
		this.weightPositions = weightPositions;
		this.inputGroupShape = inputGroupShape;
		this.weightGroupShape = weightGroupShape;
		this.inShape = shape(input);
		this.weightShape = shape(weights);
		this.loopMembers = loopMembers;

		if (inputPositions.getDimensions() != resultShape.getDimensions() ||
				weightPositions.getDimensions() != resultShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (inputPositions.getDimensions() != inShape.getDimensions() ||
				inputGroupShape.getDimensions() != inShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (weightPositions.getDimensions() != weightShape.getDimensions() ||
				weightGroupShape.getDimensions() != weightShape.getDimensions()) {
			throw new IllegalArgumentException();
		} else if (weightGroupShape.getTotalSizeLong() != inputGroupShape.getTotalSizeLong()) {
			// The loop form sums the input group against the weight traversal without
			// constructing a SubsetTraversalWeightedSumExpression, so the equal-group-size
			// contract that expression enforces is applied here for both kernel forms.
			throw new IllegalArgumentException();
		}

		// Refresh the signature captured before the traversal policies were assigned
		init();
	}

	/**
	 * Extends the standard computation signature with the traversal policies
	 * that determine the generated kernel. The position and group policies are
	 * not derivable from the input and output shapes alone, so two weighted
	 * sums over identically shaped operands would otherwise share a signature
	 * while generating different kernels. Position policies are constructed
	 * with per-axis rates, which their standard descriptions omit, so the
	 * rates are rendered explicitly here, as is the number of members summed
	 * by each iteration when the kernel is generated as a loop.
	 *
	 * @return The signature string, or null when the base signature is unavailable
	 */
	@Override
	public String signature() {
		// Superclass construction requests the signature before the traversal
		// policies are assigned; the constructor refreshes it once they are
		if (inputPositions == null) return null;

		String signature = super.signature();
		if (signature == null) return null;

		StringBuilder detail = new StringBuilder(signature);

		TraversalPolicy policies[] = {
				inputPositions, inputGroupShape,
				weightPositions, weightGroupShape
		};

		for (TraversalPolicy policy : policies) {
			detail.append("{").append(policy.toStringDetail());

			for (int i = 0; i < policy.getDimensions(); i++) {
				detail.append("|").append(policy.rateNumeratorLong(i))
						.append(":").append(policy.rateDenominatorLong(i));
			}

			detail.append("}");
		}

		if (isLooped()) {
			detail.append("{loop:").append(loopMembers).append("}");
		}

		return detail.toString();
	}

	/**
	 * Returns true when the kernel for this weighted sum is generated as a native loop over
	 * its group rather than as a single expression; see {@link #loopThreshold}.
	 *
	 * @return true if the group is summed by a loop
	 */
	public boolean isLooped() { return loopMembers > 0; }

	/**
	 * Returns the number of iterations of the loop that sums the group, when the group has at
	 * least {@link #loopThreshold} members, each iteration summing the members that span the
	 * trailing dimensions of the group (at most {@link #maxUnrolledMembers} of them).
	 *
	 * @return the number of loop iterations, or zero when the group is summed by a single expression
	 */
	@Override
	protected int getAccumulationCount() {
		return isLooped() ? Math.toIntExact(inputGroupShape.getTotalSizeLong() / loopMembers) : 0;
	}

	/**
	 * Returns the sum of the products of the group members that one iteration of the loop
	 * contributes to an output element: members {@code iteration * loopMembers} through
	 * {@code iteration * loopMembers + loopMembers - 1}, each located in the input and the
	 * weights by the same {@link SubsetTraversalExpression traversals} that locate it in the
	 * single-expression form.
	 *
	 * @param index     the index of the output element
	 * @param iteration the index of the loop iteration
	 * @return the partial weighted sum contributed by the iteration
	 */
	@Override
	protected Expression<?> getAccumulationTerm(Expression<?> index, Expression<?> iteration) {
		TraversableExpression[] args = getTraversableArguments(index);
		SubsetTraversalExpression input = getInputTraversal();
		SubsetTraversalExpression weights = getWeightsTraversal();

		Expression<?> sum = e(0.0);
		for (int m = 0; m < loopMembers; m++) {
			Expression<?> member = iteration.multiply(loopMembers).add(m);
			sum = sum.add(args[1].getValueAt(input.getInputIndex(member, index))
					.multiply(args[2].getValueAt(weights.getInputIndex(member, index))));
		}

		return sum;
	}

	/**
	 * Returns the number of group members each iteration of the loop sums, or zero when the
	 * group is smaller than {@link #loopThreshold} and is summed by a single expression. The
	 * members of one iteration span the trailing dimensions of the group, taken from the last
	 * dimension backwards for as long as their product does not exceed
	 * {@link #maxUnrolledMembers}, so the position of each member within the group is a
	 * constant offset from the position of the iteration.
	 *
	 * @param inputGroupShape  the shape of one group (the dimensions summed over)
	 * @return the members summed by each iteration, or zero for a single expression
	 */
	private static int unrolledLoopMembers(TraversalPolicy inputGroupShape) {
		if (!enableLoopGeneration) return 0;

		long size = inputGroupShape.getTotalSizeLong();
		if (size < loopThreshold) return 0;

		long members = 1;
		for (int axis = inputGroupShape.getDimensions() - 1; axis >= 0; axis--) {
			long extended = members * inputGroupShape.lengthLong(axis);
			if (extended > maxUnrolledMembers) break;
			members = extended;
		}

		return members < size ? Math.toIntExact(members) : 0;
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
	 * Determines whether this weighted sum should be compiled as an isolated kernel.
	 *
	 * <p>Large weighted sums (e.g., convolutions with many channels) produce expression
	 * trees that are too complex for the simplifier when inlined. Isolating them forces
	 * independent compilation, preventing expression explosion in the parent kernel.</p>
	 *
	 * @param context the process context
	 * @return {@code true} if the group size exceeds the isolation threshold
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		long groupSize = inputGroupShape.getTotalSizeLong();
		return groupSize >= 64;
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
				(Producer) children.get(2),
				loopMembers);
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

}
