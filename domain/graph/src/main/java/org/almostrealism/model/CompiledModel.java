/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.model;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionContainer;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.LayerFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * An optimized, executable version of a {@link Model} that is ready for inference
 * and optionally training. CompiledModel encapsulates the compiled forward and
 * backward pass operations for efficient execution.
 *
 * <p>CompiledModel is produced by calling {@link Model#compile()} and provides:</p>
 * <ul>
 *   <li>Optimized forward pass execution via {@link #forward(PackedCollection, PackedCollection...)}</li>
 *   <li>Backward pass for gradient computation via {@link #backward(PackedCollection)}</li>
 *   <li>Model reset functionality via {@link #reset()}</li>
 * </ul>
 *
 * <h2>Inference Usage</h2>
 * <pre>{@code
 * Model model = new Model(shape(784));
 * // ... add layers ...
 * CompiledModel compiled = model.compile(false);  // Inference only
 *
 * PackedCollection output = compiled.forward(input);
 * }</pre>
 *
 * <h2>Training Usage</h2>
 * <pre>{@code
 * Model model = new Model(inputShape, learningRate);
 * // ... add layers ...
 * CompiledModel compiled = model.compile(true);  // Enable backprop
 *
 * // Training loop
 * for (int epoch = 0; epoch < epochs; epoch++) {
 *     for (batch : data) {
 *         PackedCollection output = compiled.forward(input);
 *         PackedCollection gradient = lossFunction.gradient(output, target);
 *         compiled.backward(gradient);
 *     }
 * }
 * }</pre>
 *
 * <h2>Multi-Input Models</h2>
 * <p>For models with auxiliary inputs:</p>
 * <pre>{@code
 * // Primary input + additional inputs as varargs
 * PackedCollection output = compiled.forward(query, key, value);
 * }</pre>
 *
 * <h2>Gradient Return</h2>
 * <p>When compiled with {@code returnGradient=true}, the backward pass returns
 * the gradient with respect to the input, useful for stacking models or computing
 * input sensitivities:</p>
 * <pre>{@code
 * CompiledModel compiled = model.compile(true, true);  // backprop + return gradient
 * PackedCollection inputGradient = compiled.backward(outputGradient);
 * }</pre>
 *
 * @see Model#compile()
 * @see Model#compile(boolean, boolean, OperationProfile)
 * @author Michael Murray
 */
public class CompiledModel implements Destroyable, CodeFeatures {
	/** Containers of the inputs the forward pass reads (primary followed by auxiliary). */
	private final List<InputContainer> inputs;
	/** The collection the forward pass writes the model output into. */
	private final PackedCollection output;

	/** One-time setup/reset operation run before the first forward pass. */
	private final Runnable setup;
	/** Compiled forward-pass operation. */
	private final Runnable forward;

	/** Container of the gradient with respect to the model output, which the backward pass reads. */
	private final InputContainer outputGradient;
	/** The collection the backward pass writes the gradient with respect to the input into; may be {@code null}. */
	private final PackedCollection inputGradient;
	/** Compiled backward-pass operation; {@code null} when backprop is disabled. */
	private final Runnable backward;

	/**
	 * Creates a new compiled model with all required components.
	 * This constructor is typically called by {@link #compile(Model, boolean, boolean, OperationProfile)}.
	 *
	 * @param inputs         containers of all inputs the forward pass reads (primary + auxiliary)
	 * @param output         the collection the forward pass writes the output into
	 * @param setup          the setup/reset operation
	 * @param forward        the compiled forward pass operation
	 * @param outputGradient container of the gradient with respect to the output that the backward pass reads
	 * @param inputGradient  the collection the backward pass writes the gradient with respect to the
	 *                       input into, or {@code null} if the model does not return it
	 * @param backward       the compiled backward pass operation, or {@code null} without backprop
	 */
	protected CompiledModel(List<InputContainer> inputs, PackedCollection output,
							Runnable setup, Runnable forward,
							InputContainer outputGradient, PackedCollection inputGradient,
							Runnable backward) {
		this.inputs = inputs;
		this.output = output;
		this.setup = setup;
		this.forward = forward;
		this.outputGradient = outputGradient;
		this.inputGradient = inputGradient;
		this.backward = backward;
	}

	/**
	 * Returns the primary input shape.
	 *
	 * @return the shape expected for the first input
	 */
	public TraversalPolicy getInputShape() { return getInputShape(0); }

	/**
	 * Returns the number of inputs this model expects.
	 *
	 * @return the number of inputs (primary + auxiliary)
	 */
	public int getInputCount() { return inputs.size(); }

	/**
	 * Returns the shape of a specific input.
	 *
	 * @param index the input index (0 = primary input)
	 * @return the shape expected for that input
	 */
	public TraversalPolicy getInputShape(int index) { return inputs.get(index).getShape(); }

	/**
	 * Returns the output shape.
	 *
	 * @return the shape of the model output
	 */
	public TraversalPolicy getOutputShape() { return output.getShape(); }

	/**
	 * Returns the collection the forward pass writes the model output into. It is the same
	 * collection for every pass, and holds a pass's result once that pass has completed.
	 *
	 * @return the output collection
	 */
	public PackedCollection getOutput() { return output; }

	/**
	 * Executes the forward pass with the given inputs: the pass is issued as by
	 * {@link #submitForward(Semaphore, PackedCollection, PackedCollection...)} and waited for.
	 *
	 * @param input the primary input data
	 * @param args additional inputs for multi-input models
	 * @return the model output
	 */
	public PackedCollection forward(PackedCollection input, PackedCollection... args) {
		Semaphore completion = submitForward(null, input, args);
		if (completion != null) completion.waitFor();
		return getOutput();
	}

	/**
	 * Issues the forward pass with the given inputs, ordered after {@code dependsOn}, and returns
	 * its completion without waiting for it, so that a caller running several passes in sequence
	 * waits only where it reads a result on the host.
	 *
	 * <p>The inputs are bound to the pass as it is issued, so the next pass may be given other
	 * inputs as soon as this returns; the contents of these inputs must not change, and the
	 * {@link #getOutput() output} must not be read, until the returned completion has fired.</p>
	 *
	 * @param dependsOn the completion the pass must be ordered after, or {@code null}
	 * @param input     the primary input data
	 * @param args      additional inputs for multi-input models
	 * @return the completion of the pass, or {@code null} if nothing it issued remains in flight
	 */
	public Semaphore submitForward(Semaphore dependsOn, PackedCollection input, PackedCollection... args) {
		inputs.get(0).set(input);
		for (int i = 1; i < inputs.size(); i++) {
			int a = i - 1;
			inputs.get(i).set(a < args.length ? args[a] : null);
		}

		return Submittable.of(forward).submit(dependsOn);
	}

	/**
	 * Executes the backward pass with the given gradient: the pass is issued as by
	 * {@link #submitBackward(Semaphore, PackedCollection)} and waited for.
	 * This computes gradients and updates model parameters according to the
	 * parameter update strategy set during model construction.
	 *
	 * @param gradient the gradient of the loss with respect to model output
	 * @return the gradient with respect to input, or null if not configured to return gradients
	 */
	public PackedCollection backward(PackedCollection gradient) {
		Semaphore completion = submitBackward(null, gradient);
		if (completion != null) completion.waitFor();
		return inputGradient;
	}

	/**
	 * Issues the backward pass with the given gradient, ordered after {@code dependsOn}, and
	 * returns its completion without waiting for it. The parameter updates of the pass are made
	 * by the time the completion fires, so a forward pass that must see them is ordered after it.
	 *
	 * <p>The gradient is bound to the pass as it is issued; its contents must not change, and the
	 * gradient with respect to the input (when the model returns one) must not be read, until the
	 * returned completion has fired.</p>
	 *
	 * @param dependsOn the completion the pass must be ordered after, or {@code null}
	 * @param gradient  the gradient of the loss with respect to model output
	 * @return the completion of the pass, or {@code null} if nothing it issued remains in flight
	 */
	public Semaphore submitBackward(Semaphore dependsOn, PackedCollection gradient) {
		outputGradient.set(gradient);
		return Submittable.of(backward).submit(dependsOn);
	}

	/**
	 * Resets the model to its initial state.
	 * This runs the setup operations to reinitialize all layers.
	 */
	public void reset() {
		setup.run();
	}

	/**
	 * Destroys this compiled model and releases all resources.
	 * After calling this method, the model should not be used.
	 *
	 * <p><strong>Warning:</strong> this also releases memory that is shared with the
	 * originating {@link Model}. The forward and backward operations capture buffers
	 * owned by the model's layers (for example, the activation buffer that each layer's
	 * {@code BackPropagationCell} holds via {@code setForwardInput}). Destroying this
	 * {@link CompiledModel} frees those buffers, so the source {@link Model} must not be
	 * reused or recompiled afterward — a later compilation would wire its backward pass
	 * to released memory and fail with a {@link NullPointerException}. Build a fresh
	 * {@link Model} if another compilation is needed.</p>
	 */
	@Override
	public void destroy() {
		Destroyable.destroy(forward);
		Destroyable.destroy(backward);
	}

	/**
	 * Compiles the given model with backpropagation enabled and no operation profile.
	 *
	 * @param model the model to compile
	 * @return a compiled, ready-to-run model
	 */
	public static CompiledModel compile(Model model) {
		return compile(model, true, false, null);
	}

	/**
	 * Compiles the given model with backpropagation enabled and the provided operation profile.
	 *
	 * @param model   the model to compile
	 * @param profile the operation profile for timing instrumentation; may be {@code null}
	 * @return a compiled, ready-to-run model
	 */
	public static CompiledModel compile(Model model, OperationProfile profile) {
		return compile(model, true, false, profile);
	}

	/**
	 * Compiles the given model, optionally enabling backpropagation and gradient retrieval.
	 *
	 * @param model          the model to compile
	 * @param backprop       {@code true} to compile the backward pass
	 * @param returnGradient {@code true} to allocate and return the input gradient after backward
	 * @param profile        operation profile for timing instrumentation; may be {@code null}
	 * @return a compiled, ready-to-run model
	 */
	public static CompiledModel compile(Model model,
										boolean backprop, boolean returnGradient,
										OperationProfile profile) {
		model.recordCompilation();

		Runnable setup = Process.optimized(model.setup()).get();

		List<InputContainer> inputs = new ArrayList<>();
		inputs.add(new InputContainer(model.firstBlock().getInputShape()));
		model.getInputs().forEach(p -> inputs.add(new InputContainer(p.getInputShape())));

		InputContainer outputGradient = new InputContainer(model.lastBlock().getOutputShape());

		PackedCollection output = new PackedCollection(model.lastBlock().getOutputShape());
		Receptor<PackedCollection> outputReceptor = out ->
				Ops.o().a("Model Forward Output", Ops.o().p(output), out);

		// Chain with existing receptor if one was set (e.g., via andThen() for cache writes)
		Cell<PackedCollection> lastForward = model.lastBlock().getForward();
		Receptor<PackedCollection> existingReceptor = lastForward.getReceptor();
		if (existingReceptor != null) {
			lastForward.setReceptor(Receptor.to(existingReceptor, outputReceptor));
		} else {
			lastForward.setReceptor(outputReceptor);
		}

		PackedCollection gradOut;

		if (returnGradient) {
			gradOut = new PackedCollection(model.firstBlock().getInputShape());
			model.firstBlock().getBackward().setReceptor(out ->
					Ops.o().a("Model Backward Output", Ops.o().p(gradOut), out));
		} else {
			gradOut = null;
		}

		// TODO(review): inference compile mutates the shared Model (disables tracking) without restoring it; a later compile(true) on the same instance could leave backward cells wired to a destroyed input buffer.
		if (!backprop) {
			model.setInputTracking(false);
		}

		List<Cell<PackedCollection>> cells = model.forward();
		OperationList forward = new OperationList("CompiledModel Forward");
		for (int i = cells.size() - 1; i >= 0; i--) {
			forward.add(cells.get(i).push(inputs.get(i)));
		}

		ParallelProcess<?, Runnable> p = forward.flatten().optimize();

		ParallelProcess<?, Runnable> q;

		if (backprop) {
			q = (ParallelProcess<?, Runnable>) model.backward().push(outputGradient);
			if (q instanceof OperationList) q = ((OperationList) q).flatten();
			q = q.optimize();
		} else {
			q = null;
		}

		if (p instanceof OperationList) ((OperationList) p).setProfile(profile);
		if (q instanceof OperationList) ((OperationList) q).setProfile(profile);

		CompiledModel compiled = new CompiledModel(inputs, output, setup, p.get(),
				outputGradient, gradOut, q == null ? null : q.get());
		compiled.reset();
		return compiled;
	}

	/**
	 * The {@link CollectionContainer} of one input of a compiled pass: an input of the forward pass, or
	 * the gradient the backward pass receives. A pass binds the collection set on it as the pass is
	 * issued, and a copy of that collection is ordered after the work the pass depends on by the
	 * backend, rather than waiting for that work on the host before the collection is resolved.
	 *
	 * <p>It refuses a collection whose shape differs from the one the model was compiled for.</p>
	 */
	protected static class InputContainer extends CollectionContainer implements ConsoleFeatures {
		/**
		 * Creates the container of an input of the given shape.
		 *
		 * @param shape the shape of the input the model was compiled for
		 */
		public InputContainer(TraversalPolicy shape) {
			super(shape);
		}

		/**
		 * Sets the collection the next pass reads for this input. A missing input is reported
		 * but accepted; an input of a different shape is rejected, as by any
		 * {@link CollectionContainer}.
		 *
		 * @param input the collection the next pass reads for this input
		 * @throws IllegalArgumentException if the shape of {@code input} differs from the shape
		 *                                  of this input, ignoring the traversal axis
		 */
		@Override
		public void set(PackedCollection input) {
			if (input == null) warn("null input");
			super.set(input);
		}

		@Override
		public Console console() {
			return LayerFeatures.console;
		}
	}
}
