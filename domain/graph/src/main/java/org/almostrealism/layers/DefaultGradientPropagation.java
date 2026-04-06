/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Nameable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.SystemUtils;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Default implementation of {@link BackPropagation} that uses automatic differentiation
 * to compute gradients and update learnable weights.
 *
 * <p>Given a forward operator (a {@link Factor} that maps input to output), this class
 * uses the operator's {@code grad} and {@code delta} methods to compute:</p>
 * <ul>
 *   <li>The gradient with respect to the layer input ({@code δOut/δIn}), which is
 *       propagated upstream via the provided {@link Receptor}.</li>
 *   <li>The gradient with respect to each weight tensor ({@code δOut/δWeight}), which
 *       is applied to the weights via the corresponding {@link ParameterUpdate}.</li>
 * </ul>
 *
 * <p>Diagnostic flags ({@link #enableDiagnosticGrad}, {@link #enableDiagnosticWeight})
 * can be enabled to wrap operations in {@code OperationWithInfo} for profiling.</p>
 *
 * @see BackPropagation
 * @see BackPropagationCell
 * @author Michael Murray
 */
public class DefaultGradientPropagation implements BackPropagation, Learning, Nameable, CodeFeatures {

	/** When true, logs a message when the upstream receptor is null and gradients are skipped. */
	public static boolean verbose = false;

	/**
	 * When true, uses {@link io.almostrealism.compute.Process#optimized(Producer)} for the
	 * input-gradient evaluable in diagnostic mode.
	 */
	public static boolean enableOptimizedDiagnostics = false;

	/**
	 * When true (or when the environment variable {@code AR_DIAGNOSTIC_GRADIENT} is set),
	 * wraps the input-gradient computation in {@code OperationWithInfo} for profiling.
	 */
	public static boolean enableDiagnosticGrad = SystemUtils.isEnabled("AR_DIAGNOSTIC_GRADIENT").orElse(false);

	/** When true, wraps each weight-gradient computation in {@code OperationWithInfo} for profiling. */
	public static boolean enableDiagnosticWeight = false;

	/** The differentiable forward operator whose gradients are computed during back-propagation. */
	private final Factor<PackedCollection> operator;

	/** One update strategy per weight tensor; must be the same length as {@link #weights}. */
	private final ParameterUpdate<PackedCollection>[] updates;

	/** The learnable weight producers whose values are updated after each backward pass. */
	private final Producer<PackedCollection>[] weights;

	/** The human-readable name used for logging and operation metadata. */
	private String name;

	/**
	 * Creates a new gradient propagation strategy.
	 *
	 * @param name     a human-readable name used in logging and profiling
	 * @param operator the differentiable forward operator
	 * @param updates  one {@link ParameterUpdate} per weight tensor
	 * @param weights  the weight producers whose values are adjusted during training
	 * @throws IllegalArgumentException if {@code updates} and {@code weights} have different lengths
	 */
	protected DefaultGradientPropagation(String name,
										 Factor<PackedCollection> operator,
									     ParameterUpdate<PackedCollection>[] updates,
									     Producer<PackedCollection>[] weights) {
		setName(name);
		this.operator = operator;
		this.updates = updates;
		this.weights = weights;

		if (updates.length != weights.length) {
			throw new IllegalArgumentException();
		}
	}

	/** {@inheritDoc} */
	@Override
	public String getName() { return name; }

	/** {@inheritDoc} */
	@Override
	public void setName(String name) { this.name = name; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Sets the same update strategy for every weight tensor managed by this propagation.</p>
	 */
	@Override
	public void setParameterUpdate(ParameterUpdate<PackedCollection> update) {
		Arrays.fill(updates, update);
	}

	/**
	 * Sets the update strategy for a single weight tensor by index.
	 *
	 * @param index  the zero-based index of the weight whose update strategy is being replaced
	 * @param update the new parameter update strategy
	 */
	public void setParameterUpdate(int index, ParameterUpdate<PackedCollection> update) {
		updates[index] = update;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computes the input gradient ({@code δOut/δIn}) and all weight gradients
	 * ({@code δOut/δWeight_i}), applies the weight updates, and pushes the input
	 * gradient to {@code next} (if non-null).</p>
	 *
	 * @throws IllegalArgumentException if any entry in the updates array is {@code null}
	 */
	@Override
	public Supplier<Runnable> propagate(Producer<PackedCollection> gradient,
										Producer<PackedCollection> input,
										Receptor<PackedCollection> next) {
		for (int i = 0; i < weights.length; i++) {
			if (updates[i] == null) {
				throw new IllegalArgumentException("No ParameterUpdate for weights");
			}
		}

		if (next == null && verbose) {
			log("Gradient will not be computed for " + getName() +
					" because there is no provided Receptor");
		}

		TraversalPolicy shape = shape(input);

		Supplier<CollectionProducer> function = () -> (CollectionProducer) operator.getResultant(input);
		PackedCollection gradIn = new PackedCollection(shape(gradient));
		PackedCollection gradOut = next == null ? null : new PackedCollection(shape);

		int inSize = shape.getTotalSize();
		int outSize = shape(gradient).getTotalSize();

		OperationList op = new OperationList("Gradient Propagation");

		if (next != null) {
			Producer<PackedCollection> deltaOutDeltaIn = function.get().grad(input, gradient);

			if (enableDiagnosticGrad) {
				PackedCollection deltaOut = new PackedCollection(shape(outSize, inSize)).traverse(1);
				Producer<PackedCollection> delta = function.get().delta(input).reshape(outSize, inSize).traverse(1);

				op.add(OperationWithInfo.of(new OperationMetadata(getName() + " delta", getName() + " (\u03B4Out/\u03B4In)"), () -> {
					Evaluable<PackedCollection> d = delta.get();
					Evaluable<PackedCollection> grad = enableOptimizedDiagnostics ?
							(Evaluable) Process.optimized(deltaOutDeltaIn).get() : deltaOutDeltaIn.get();
					Evaluable<PackedCollection> inputGrad = gradient.get();

					return () -> {
						d.into(deltaOut).evaluate();
						inputGrad.into(gradIn).evaluate();
						grad.into(gradOut).evaluate();
						// deltaOut.print(r -> log(name + " delta:\n" + r));
					};
				}));
			} else {
				op.add(a(getName() + " (\u03B4Out/\u03B4In)", traverseEach(p(gradOut)), deltaOutDeltaIn));
			}
		}

		for (int i = 0; i < weights.length; i++) {
			int weightSize = shape(weights[i]).getTotalSize();
			Producer<PackedCollection> weightFlat = reshape(shape(weightSize), weights[i]);

			Producer<PackedCollection> deltaOutDeltaWeight = function.get().delta(weights[i])
					.reshape(outSize, weightSize)
					.traverse(1)
					.multiply(c(gradient).reshape(outSize).traverse(1).repeat(weightSize))
					.traverse(0)
					.enumerate(1, 1)
					.sum(1)
					.reshape(shape(weightSize))
					.each();

			Supplier<Runnable> weightUpdateAssignment = updates[i].apply(getName(), weightFlat, deltaOutDeltaWeight);

			if (enableDiagnosticWeight) {
				op.add(OperationWithInfo.of(new OperationMetadata(getName() + " weights " + i, getName() + " (\u0394 weights)"),() -> {
					Runnable wua = weightUpdateAssignment.get();

					return () -> {
						wua.run();
					};
				}));
			} else {
				op.add(weightUpdateAssignment);
			}
		}

		if (next != null) op.add(next.push(p(gradOut)));
		return op;
	}

	/**
	 * Creates a gradient propagation strategy from a stream of weight producers.
	 *
	 * @param name     a human-readable name for logging and profiling
	 * @param operator the differentiable forward operator
	 * @param weights  a stream of weight producers to be updated during training
	 * @return a new {@code DefaultGradientPropagation} with no initial update strategy
	 */
	public static DefaultGradientPropagation create(String name,
													Factor<PackedCollection> operator,
												    Stream<Producer<PackedCollection>> weights) {
		return create(name, operator, weights.toArray(Producer[]::new));
	}

	/**
	 * Creates a gradient propagation strategy with no initial update strategy.
	 *
	 * <p>A {@link ParameterUpdate} must be assigned via {@link #setParameterUpdate} before
	 * the first backward pass.</p>
	 *
	 * @param name     a human-readable name for logging and profiling
	 * @param operator the differentiable forward operator
	 * @param weights  the weight producers to be updated during training
	 * @return a new {@code DefaultGradientPropagation}
	 */
	public static DefaultGradientPropagation create(String name,
													Factor<PackedCollection> operator,
													Producer<PackedCollection>... weights) {
		return create(name, operator, null, weights);
	}

	/**
	 * Creates a gradient propagation strategy with the given update strategy applied
	 * to all weights.
	 *
	 * @param name     a human-readable name for logging and profiling
	 * @param operator the differentiable forward operator
	 * @param update   the parameter update strategy to use for all weights, or {@code null}
	 *                 to defer assignment
	 * @param weights  the weight producers to be updated during training
	 * @return a new {@code DefaultGradientPropagation}
	 */
	public static DefaultGradientPropagation create(String name,
													Factor<PackedCollection> operator,
													ParameterUpdate<PackedCollection> update,
												    Producer<PackedCollection>... weights) {
		ParameterUpdate<PackedCollection>[] updates = new ParameterUpdate[weights.length];
		Arrays.fill(updates, update);

		return new DefaultGradientPropagation(name, operator, updates, weights);
	}
}
