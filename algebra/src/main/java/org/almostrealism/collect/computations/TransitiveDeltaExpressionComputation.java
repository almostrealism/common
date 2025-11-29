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

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Abstract base class for computations that support transitive delta (derivative) propagation
 * through their arguments in automatic differentiation scenarios.
 *
 * <p>This class extends {@link TraversableExpressionComputation} to provide specialized behavior
 * for computations where gradient information needs to flow through multiple input arguments.
 * It implements transitive differentiation by automatically propagating delta computations
 * to selected input arguments based on the {@link #isTransitiveArgumentIndex(int)} predicate.</p>
 *
 * <h2>Transitive Differentiation</h2>
 * <p>When computing the derivative of a computation with respect to a target, transitive delta
 * computations apply the chain rule across all transitive argument indices. For each transitive
 * argument, the delta is computed and the overall result is the composition of these deltas.</p>
 *
 * <p>For example, if a computation {@code f(x, y, z)} has transitive arguments {@code x} and {@code y},
 * then {@code delta(f, target)} computes:</p>
 * <pre>
 * df/dtarget = f(dx/dtarget, dy/dtarget, z)
 * </pre>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Automatic Chain Rule:</strong> Automatically applies chain rule to transitive arguments</li>
 *   <li><strong>Algebraic Properties:</strong> Preserves algebraic properties (zero, diagonal, identity) from inputs</li>
 *   <li><strong>Kernel Optimization:</strong> Supports atomic kernel generation via {@link #enableAtomicKernel}</li>
 *   <li><strong>Diagonal Scalar Extraction:</strong> Can extract scalar values from diagonal matrix operations</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <p>Subclasses typically override {@link #isTransitiveArgumentIndex(int)} to specify which
 * arguments should have transitive delta propagation:</p>
 * <pre>{@code
 * public class MyDeltaComputation extends TransitiveDeltaExpressionComputation<PackedCollection> {
 *     @Override
 *     protected boolean isTransitiveArgumentIndex(int index) {
 *         return index == 1 || index == 2; // Apply delta to first two arguments
 *     }
 *
 *     @Override
 *     protected CollectionExpression getExpression(TraversableExpression... args) {
 *         // Define computation expression
 *         return ...;
 *     }
 * }
 * }</pre>
 *
 * @see TraversableExpressionComputation
 * @see org.almostrealism.collect.CollectionProducer#delta(Producer)
 * @see #delta(Producer)
 * @see #isTransitiveArgumentIndex(int)
 *
 * @author Michael Murray
 */
public abstract class TransitiveDeltaExpressionComputation
												extends TraversableExpressionComputation {
	/**
	 * Global flag enabling atomic kernel generation for transitive delta computations.
	 * When enabled, computations with fixed counts can generate single atomic kernels
	 * instead of relative output kernels, potentially improving performance through
	 * better optimization opportunities.
	 *
	 * @see #isOutputRelative()
	 */
	public static final boolean enableAtomicKernel = true;

	/**
	 * Constructs a new transitive delta expression computation with the specified parameters.
	 * This constructor initializes the computation with {@link MultiTermDeltaStrategy#NONE}
	 * as the delta strategy, since transitive delta computations handle multi-term differentiation
	 * through their own specialized logic.
	 *
	 * @param name The name of this computation, used for debugging and code generation
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer} arguments providing the data collections
	 */
	protected TransitiveDeltaExpressionComputation(String name, TraversalPolicy shape,
												   Producer<PackedCollection>... arguments) {
		super(name, shape, MultiTermDeltaStrategy.NONE, arguments);
	}

	/**
	 * Determines whether this computation uses relative output positioning.
	 * Relative output is disabled when atomic kernel generation is enabled AND
	 * the computation has a fixed count, allowing for more optimized single-kernel execution.
	 *
	 * @return {@code false} if atomic kernels are enabled and count is fixed,
	 *         {@code true} otherwise for relative output positioning
	 * @see #enableAtomicKernel
	 * @see CollectionProducerComputationAdapter#isOutputRelative()
	 */
	@Override
	public boolean isOutputRelative() {
		return !enableAtomicKernel || !isFixedCount();
	}

	/**
	 * Checks whether this computation always produces zero values.
	 * A transitive delta computation is zero if either the base implementation
	 * determines it's zero, or if all child arguments (excluding the destination)
	 * are algebraically zero.
	 *
	 * @return {@code true} if this computation produces only zero values, {@code false} otherwise
	 * @see io.almostrealism.collect.Algebraic#isZero()
	 */
	@Override
	public boolean isZero() {
		return super.isZero() || getChildren().stream().skip(1).allMatch(Algebraic::isZero);
	}

	/**
	 * Checks whether this computation represents a diagonal matrix operation of the specified width.
	 * A transitive delta computation is diagonal if it represents an identity matrix or if all
	 * child arguments (excluding the destination) are diagonal.
	 *
	 * @param width The width of the target diagonal matrix
	 * @return {@code true} if this computation represents a diagonal matrix, {@code false} otherwise
	 * @see io.almostrealism.collect.Algebraic#isDiagonal(int, io.almostrealism.relation.Producer)
	 */
	@Override
	public boolean isDiagonal(int width) {
		return isIdentity(width) || getChildren().stream().skip(1)
				.allMatch(p -> Algebraic.isDiagonal(width, p));
	}

	/**
	 * Attempts to extract a scalar computation that represents the diagonal elements of this
	 * computation when viewed as a diagonal matrix operation.
	 *
	 * <p>This method extracts diagonal scalars from all child arguments and combines them
	 * using the same computation structure. If all children successfully produce diagonal scalars,
	 * a new computation is generated that operates on those scalars. Otherwise, delegates to
	 * the parent implementation.</p>
	 *
	 * @param width The width of the target diagonal matrix
	 * @return An {@link Optional} containing the scalar computation if extraction is successful,
	 *         or empty if the computation is not diagonal or scalar extraction fails
	 * @see io.almostrealism.collect.Algebraic#getDiagonalScalar(int, io.almostrealism.relation.Producer)
	 */
	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		List<Process<?, ?>> scalars = isDiagonal(width) ? getChildren().stream().skip(1)
				.map(p -> Algebraic.getDiagonalScalar(width, p))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.filter(c -> c instanceof Process)
				.map(c -> (Process<?, ?>) c)
				.collect(Collectors.toList()) : Collections.emptyList();
		if (scalars.size() != getChildren().size() - 1) {
			return super.getDiagonalScalar(width);
		}

		List<Process<?, ?>> operands = new ArrayList<>();
		operands.add(null);
		operands.addAll(scalars);
		return Optional.of(generate(operands));
	}

	/**
	 * Determines whether the argument at the specified index should have transitive
	 * delta propagation applied during automatic differentiation.
	 *
	 * <p>By default, all arguments except the destination (index 0) are considered
	 * transitive, meaning gradients will be computed and propagated through them.
	 * Subclasses can override this method to customize which arguments participate
	 * in transitive differentiation.</p>
	 *
	 * <p>For example, some operations may have constant parameters that should not
	 * have gradients computed, and can return {@code false} for those indices.</p>
	 *
	 * @param index The argument index to check (0 is the destination, 1+ are inputs)
	 * @return {@code true} if gradients should be computed for this argument,
	 *         {@code false} otherwise
	 * @see #delta(Producer)
	 */
	protected boolean isTransitiveArgumentIndex(int index) {
		return index > 0;
	}

	/**
	 * Computes the derivative (delta) of this computation with respect to the specified target
	 * using transitive delta propagation through the arguments.
	 *
	 * <p>This method implements automatic differentiation by applying the chain rule across
	 * all transitive arguments. For each argument marked as transitive by
	 * {@link #isTransitiveArgumentIndex(int)}, the delta is computed with respect to the target,
	 * and the overall result is the composition of these deltas.</p>
	 *
	 * <h3>Computation Process</h3>
	 * <ol>
	 *   <li>First attempts optimized delta computation via {@link #attemptDelta(Producer)}</li>
	 *   <li>Validates that all operands are {@link CollectionProducer}s with fixed counts</li>
	 *   <li>For each operand:
	 *     <ul>
	 *       <li>If the operand is at a transitive index, computes {@code operand.delta(target)}</li>
	 *       <li>Otherwise, uses the operand unchanged</li>
	 *     </ul>
	 *   </li>
	 *   <li>Generates a new computation with the delta operands</li>
	 *   <li>Reshapes the result to append the target shape dimensions</li>
	 * </ol>
	 *
	 * <h3>Example</h3>
	 * <p>For a computation {@code f(x, y)} where both arguments are transitive:</p>
	 * <pre>
	 * df/dw = f(dx/dw, dy/dw)
	 * </pre>
	 *
	 * <p>The result shape is {@code [original_shape..., target_shape...]}, enabling
	 * proper gradient accumulation in backpropagation.</p>
	 *
	 * @param target The {@link Producer} with respect to which the derivative should be computed
	 * @return A {@link CollectionProducer} that computes the derivative of this computation
	 *         with respect to the target, reshaped to include the target dimensions
	 * @throws IllegalStateException if transitive delta is not supported for variable count operands
	 * @see #isTransitiveArgumentIndex(int)
	 * @see #attemptDelta(Producer)
	 * @see org.almostrealism.collect.CollectionProducer#reshape(TraversalPolicy)
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		CollectionProducer delta = attemptDelta(target);
		if (delta != null) return delta;

		TraversalPolicy targetShape = shape(target);

		List<CollectionProducer> operands = List.of(
				getChildren().stream().skip(1)
						.filter(p -> p instanceof CollectionProducer)
						.toArray(CollectionProducer[]::new));

		boolean supported = true;

		if (operands.size() != getChildren().size() - 1) {
			supported = false;
		} else if (operands.stream().anyMatch(o -> !o.isFixedCount())) {
			warn("Transitive delta not implemented for variable count operands");
			supported = false;
		}

		if (!supported) {
			return super.delta(target);
		}

		List<Process<?, ?>> deltas = new ArrayList<>();
		deltas.add(null);

		for (int i = 0; i < operands.size(); i++) {
			if (isTransitiveArgumentIndex(i + 1)) {
				deltas.add((Process) operands.get(i).delta(target));
			} else {
				deltas.add((Process) operands.get(i));
			}
		}

		return generate(deltas).reshape(getShape().append(targetShape));
	}
}
