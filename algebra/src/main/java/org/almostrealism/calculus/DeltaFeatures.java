/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.calculus;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputationBase;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Advanced automatic differentiation utilities using the chain rule for gradient computation.
 *
 * <p>
 * {@link DeltaFeatures} extends {@link MatrixFeatures} to provide sophisticated gradient
 * computation capabilities for complex computational graphs. It implements the multivariate
 * chain rule to compute derivatives of composite functions:
 * </p>
 *
 * <p>
 * For a composite function h(x) = f(g(x)), the chain rule states:<br>
 * dh/dx = (df/dg) . (dg/dx)
 * </p>
 *
 * <h2>Key Capabilities</h2>
 * <ul>
 *   <li><b>Chain Rule Implementation:</b> Automatic computation of gradients through intermediate layers</li>
 *   <li><b>Input Isolation:</b> Creates isolated computational graphs using {@link InputStub} for partial derivatives</li>
 *   <li><b>Multi-Term Delta Strategies:</b> Handles gradients for operations with multiple terms (sum, product)</li>
 *   <li><b>Input Replacement:</b> Substitutes producers in computational graphs for delta computation</li>
 * </ul>
 *
 * <h2>Chain Rule Example</h2>
 * <pre>{@code
 * // Compute gradient of h(x) = ReLU(Wx + b) with respect to W
 * CollectionProducer<PackedCollection<?>> W = v(weights);
 * CollectionProducer<PackedCollection<?>> x = v(input);
 * CollectionProducer<PackedCollection<?>> b = v(bias);
 *
 * // Forward pass: linear = Wx + b, h = ReLU(linear)
 * CollectionProducer<PackedCollection<?>> linear = matmul(W, x).add(b);
 * CollectionProducer<PackedCollection<?>> h = relu(linear);
 *
 * // Gradient computation using chain rule
 * // dh/dW = dReLU/dlinear . dlinear/dW
 * CollectionProducer<PackedCollection<?>> gradient = h.delta(W);
 * }</pre>
 *
 * <h2>Isolated Delta Computation</h2>
 * <p>
 * The {@link #generateIsolatedDelta} method creates an isolated computational graph by replacing
 * inputs with {@link InputStub} instances, enabling clean partial derivative computation:
 * </p>
 * <pre>{@code
 * // For f(g(x), h(x)), compute df/dg in isolation
 * Producer<T> g = ...;
 * Producer<T> h = ...;
 * ComputationBase<T, T, Evaluable<T>> f = computation(g, h);
 *
 * // Create isolated graph with stub for g
 * CollectionProducer<T> dfdg = generateIsolatedDelta(f, g);
 * // dfdg now represents df/dg, treating h as constant
 * }</pre>
 *
 * <h2>Multi-Term Delta Strategies</h2>
 * <p>
 * The {@link MultiTermDeltaStrategy} enum controls how gradients are computed for operations
 * with multiple terms:
 * </p>
 * <ul>
 *   <li><b>NONE:</b> No special handling, fails if multiple matching terms exist</li>
 *   <li><b>IGNORE:</b> For additive operations - returns identity matrix</li>
 *   <li><b>COMBINE:</b> For multiplicative operations - returns diagonal of non-target terms</li>
 * </ul>
 *
 * <h2>Configuration Flags</h2>
 * <ul>
 *   <li><b>enableTotalIsolation:</b> When true, replaces all inputs during isolation (default: false)</li>
 *   <li><b>enableRestoreReplacements:</b> When true, restores original producers after delta computation (default: false)</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see MatrixFeatures
 * @see InputStub
 * @see DeltaAlternate
 * @see org.almostrealism.collect.CollectionProducer#delta
 */
public interface DeltaFeatures extends MatrixFeatures {
	boolean enableTotalIsolation = false;
	boolean enableRestoreReplacements = false;

	/**
	 * Indicates whether chain rule automatic differentiation is supported.
	 *
	 * <p>
	 * Override this method and return true to enable chain rule gradient computation
	 * in {@link #attemptDelta}. When enabled, the system will automatically compute
	 * gradients through intermediate computations using the multivariate chain rule.
	 * </p>
	 *
	 * @return true if chain rule is supported, false otherwise (default: false)
	 */
	default boolean isChainRuleSupported() {
		return false;
	}

	/**
	 * Generates an isolated delta computation by replacing inputs with stubs.
	 *
	 * <p>
	 * This method creates a modified version of the producer where specified inputs are
	 * replaced with {@link InputStub} instances, enabling computation of partial derivatives
	 * in isolation from the rest of the computational graph.
	 * </p>
	 *
	 * <p>
	 * Process:
	 * <ol>
	 *   <li>Identify inputs to replace (all if enableTotalIsolation, otherwise just the specified input)</li>
	 *   <li>Create InputStub replacements for each input</li>
	 *   <li>Generate new producer with stubbed inputs</li>
	 *   <li>Compute delta with respect to the stub</li>
	 *   <li>Optionally restore original producers if enableRestoreReplacements is true</li>
	 * </ol>
	 * </p>
	 *
	 * @param producer  the computation to isolate
	 * @param input  the input to compute the delta with respect to
	 * @param <T>  the shape type
	 * @return the isolated delta computation
	 */
	default <T extends Shape<?>> CollectionProducer<T> generateIsolatedDelta(ComputationBase<T, T, Evaluable<T>> producer,
																			 Producer<?> input) {
		Map<Producer<?>, Producer<?>> replacements = new HashMap<>();
		List toReplace = enableTotalIsolation ? producer.getInputs() : Collections.singletonList(input);

		CollectionProducer isolated = (CollectionProducer) replaceInput(producer, toReplace, replacements);
		CollectionProducer delta = isolated.delta(replacements.get(input));

		if (enableRestoreReplacements) {
			List<Supplier> restore = new ArrayList();
			Map<Producer<?>, Producer<?>> originals = new HashMap<>();
			replacements.forEach((k, v) -> {
				originals.put(v, k);
				restore.add(v);
			});

			delta = replaceInput(delta, restore, originals);
		}

		return (CollectionProducer) delta;
	}

	/**
	 * Attempts to compute the delta (gradient) of a producer with respect to a target using the chain rule.
	 *
	 * <p>
	 * This method implements the multivariate chain rule for automatic differentiation:
	 * </p>
	 * <pre>
	 * For h(x) = f(g(x)), compute: dh/dx = (df/dg) . (dg/dx)
	 * </pre>
	 *
	 * <p>
	 * Algorithm:
	 * <ol>
	 *   <li>First tries MatrixFeatures.attemptDelta for simple cases</li>
	 *   <li>If chain rule is supported ({@link #isChainRuleSupported()}):
	 *     <ol>
	 *       <li>Match the target in the producer's inputs using {@link AlgebraFeatures#matchInput}</li>
	 *       <li>If no match found -> return null</li>
	 *       <li>If match is empty (independent) -> return zeros</li>
	 *       <li>If direct match -> delegate to {@link #applyDeltaStrategy}</li>
	 *       <li>Otherwise, apply chain rule:
	 *         <ul>
	 *           <li>Compute df/dg using {@link #generateIsolatedDelta}</li>
	 *           <li>Compute dg/dx using recursive delta call</li>
	 *           <li>Matrix multiply: df/dx = (df/dg) . (dg/dx)</li>
	 *         </ul>
	 *       </li>
	 *     </ol>
	 *   </li>
	 * </ol>
	 * </p>
	 *
	 * @param producer  the producer to differentiate
	 * @param target  the variable to differentiate with respect to
	 * @param <T>  the shape type
	 * @return the gradient computation, or null if unable to compute
	 */
	default <T extends Shape<?>> CollectionProducer<T> attemptDelta(CollectionProducer<T> producer, Producer<?> target) {
		CollectionProducer<T> result = MatrixFeatures.super.attemptDelta(producer, target);
		if (result != null) return result;

		TraversalPolicy shape = shape(producer);
		TraversalPolicy targetShape = shape(target);

		if (isChainRuleSupported()) {
			if (!producer.isFixedCount()) {
				Computation.console.features(DeltaFeatures.class)
						.warn("Cannot compute partial delta for variable Producer");
				return null;
			}

			Optional<Producer<T>> match = AlgebraFeatures.matchInput(producer, target);

			if (match == null) {
				return null;
			} else if (match.isEmpty()) {
				return (CollectionProducer<T>) zeros(shape.append(targetShape));
			}

			Producer<T> in = match.get();

			if (AlgebraFeatures.match(in, target)) {
				return applyDeltaStrategy(producer, target);
			}

			if (!(in instanceof CollectionProducer)) return null;

			Producer f = generateIsolatedDelta((ComputationBase) producer, in);
			if (f == null) return null;

			Producer g = ((CollectionProducer<T>) in).delta(target);

			int finalLength = shape.getTotalSize();
			int outLength = shape(in).getTotalSize();
			int inLength = shape(target).getTotalSize();

			f = reshape(shape(finalLength, outLength), f);
			g = reshape(shape(outLength, inLength), g);
			return (CollectionProducer) matmul(f, g).reshape(shape.append(targetShape));
		}

		return null;
	}

	/**
	 * Applies a custom delta computation strategy for specific operations.
	 *
	 * <p>
	 * This hook allows custom delta computation for operations that require special handling.
	 * Override this method to provide operation-specific gradient logic.
	 * </p>
	 *
	 * @param producer  the producer to differentiate
	 * @param target  the variable to differentiate with respect to
	 * @param <T>  the shape type
	 * @return the gradient computation, or null to use default behavior
	 */
	default <T extends Shape<?>> CollectionProducer<T> applyDeltaStrategy(CollectionProducer<T> producer,
																		  Producer<?> target) {
		return null;
	}

	/**
	 * Creates a delta strategy processor for operations with multiple terms.
	 *
	 * <p>
	 * This method generates a function that processes collections of producer terms and
	 * computes gradients according to the specified strategy. Used for operations like
	 * addition and multiplication that have multiple input terms.
	 * </p>
	 *
	 * <h3>Strategies</h3>
	 * <ul>
	 *   <li><b>NONE:</b> Returns null if target appears in multiple terms</li>
	 *   <li><b>IGNORE:</b> Returns identity matrix (for additive terms: d(a+b)/da = I)</li>
	 *   <li><b>COMBINE:</b> Returns diagonal of non-target terms (for multiplicative: d(a.b)/da = diag(b))</li>
	 * </ul>
	 *
	 * @param strategy  the delta strategy to apply
	 * @param producerFactory  factory function to recreate the producer from filtered terms
	 * @param producerShape  the shape of the output producer
	 * @param target  the variable to differentiate with respect to
	 * @param <T>  the shape type
	 * @return a function that processes term collections and returns gradients
	 */
	default <T extends Shape<?>> Function<Collection<Producer<?>>, CollectionProducer<T>>
			deltaStrategyProcessor(MultiTermDeltaStrategy strategy,
					  Function<List<Producer<?>>, CollectionProducer<T>> producerFactory,
					  TraversalPolicy producerShape, Producer<?> target) {
		return terms -> {
			if (strategy == MultiTermDeltaStrategy.NONE) {
				return null;
			}

			long matches = terms.stream()
					.filter(t -> AlgebraFeatures.match(t, target))
					.count();

			if (matches == 0) {
				return (CollectionProducer) CollectionFeatures.getInstance().c(0);
			} else if (matches > 1) {
				return null;
			}

			int pSize = producerShape.getTotalSize();
			int tSize = shape(target).getTotalSize();
			TraversalPolicy finalShape = producerShape.append(shape(target));

			switch (strategy) {
				case IGNORE:
					return (CollectionProducer)
							MatrixFeatures.getInstance().identity(shape(pSize, tSize))
									.reshape(finalShape);
				case COMBINE:
					CollectionProducer result = producerFactory.apply(terms.stream()
							.filter(t -> !AlgebraFeatures.match(t, target))
							.collect(Collectors.toList())).flatten();
					return (CollectionProducer) diagonal(result).reshape(finalShape);
				default:
					throw new IllegalArgumentException();
			}
		};
	}

	/**
	 * Replaces specified inputs in a producer with stub producers.
	 *
	 * <p>
	 * This method is used during isolated delta computation to substitute inputs with
	 * {@link InputStub} instances. Handles special cases like {@link ReshapeProducer}.
	 * </p>
	 *
	 * @param producer  the producer whose inputs should be replaced
	 * @param toReplace  list of inputs to replace
	 * @param replacements  map to store the original->stub mappings
	 * @param <T>  the shape type
	 * @return a new producer with inputs replaced by stubs
	 */
	default <T extends Shape<?>> CollectionProducer<T> replaceInput(
			Producer<T> producer,
			List<Supplier> toReplace,
			Map<Producer<?>, Producer<?>> replacements) {
		if (producer instanceof ReshapeProducer) {
			return ((ReshapeProducer) producer).generate(List.of(
						replaceInput((Producer) ((ReshapeProducer) producer).getChildren().iterator().next(),
					toReplace, replacements)));
		} else {
			return (CollectionProducer) replaceInput((ComputationBase) producer, toReplace, replacements);
		}
	}

	default <T extends Shape<?>> ComputationBase<T, T, Evaluable<T>> replaceInput(
			ComputationBase<T, T, Evaluable<T>> producer,
			List<Supplier> toReplace,
			Map<Producer<?>, Producer<?>> replacements) {
		List<Supplier<Evaluable<? extends T>>> inputs = ((ComputationBase) producer).getInputs();
		List<Process<?, ?>> newInputs = new ArrayList<>();
		newInputs.add(null);

		for (int i = 1; i < inputs.size(); i++) {
			Supplier<Evaluable<? extends T>> input = inputs.get(i);

			if (toReplace.contains(input)) {
				Producer<?> inputStub = replacements.getOrDefault(input, new InputStub((Producer) input));
				newInputs.add((Process) inputStub);
				replacements.put((Producer) input, inputStub);
			} else {
				newInputs.add((Process) input);
			}
		}

		return (ComputationBase<T, T, Evaluable<T>>) producer.generate(newInputs);
	}

	/**
	 * Expands a vector and performs element-wise multiplication with a matrix.
	 *
	 * <p>
	 * This utility method is used in certain gradient computations. It expands a 1D vector
	 * to match the matrix dimensions and then multiplies element-wise.
	 * </p>
	 *
	 * <p>
	 * Special case: If the matrix is an identity matrix scaled by the vector, returns
	 * a diagonal matrix instead of performing the full expansion.
	 * </p>
	 *
	 * @param vector  the vector to expand (must be 1-dimensional)
	 * @param matrix  the matrix to multiply with
	 * @param <V>  the packed collection type
	 * @return the result of element-wise multiplication
	 * @throws IllegalArgumentException if vector is not 1-dimensional
	 */
	// TODO  It seems like this should be something that is just
	// TODO  part of MatrixFeatures, or even an option for matmul
	default <V extends PackedCollection<?>> CollectionProducer<V> expandAndMultiply(
			CollectionProducer<V> vector, CollectionProducer<V> matrix) {
		if (vector.getShape().getDimensions() != 1) {
			throw new IllegalArgumentException();
		} else if (Algebraic.isIdentity(shape(vector).length(0), matrix)) {
			return diagonal(vector);
		} else {
			CollectionProducer<V> expanded = vector.traverse(1).repeat(matrix.getShape().length(1));
			return multiply(expanded, matrix);
		}
	}

	/**
	 * Strategy for computing gradients of operations with multiple input terms.
	 *
	 * <p>
	 * Different mathematical operations require different approaches when computing
	 * gradients with respect to one of multiple terms:
	 * </p>
	 * <ul>
	 *   <li><b>NONE:</b> No multi-term handling. Returns null if the target appears in multiple terms,
	 *       preventing potentially incorrect gradient computation.</li>
	 *   <li><b>IGNORE:</b> For additive operations like (a + b + c). The gradient d(a+b)/da = I
	 *       (identity matrix), since the derivative of a sum is the sum of derivatives.</li>
	 *   <li><b>COMBINE:</b> For multiplicative operations like (a . b . c). The gradient d(a.b.c)/da
	 *       is the product of all other terms: b.c. Implemented as diagonal(b.c).</li>
	 * </ul>
	 *
	 * <h3>Examples</h3>
	 * <pre>{@code
	 * // IGNORE strategy for addition
	 * // d(x + y + z)/dx = I (identity)
	 *
	 * // COMBINE strategy for multiplication
	 * // d(x . y . z)/dx = diag(y . z)
	 * }</pre>
	 */
	enum MultiTermDeltaStrategy {
		/** No special handling - fails if target appears in multiple terms */
		NONE,
		/** Additive operations - returns identity matrix */
		IGNORE,
		/** Multiplicative operations - returns diagonal of non-target terms product */
		COMBINE;
	}
}
