/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.LayerFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
	private List<TraversalPolicy> inputShapes;
	private TraversalPolicy outputShape;

	private Runnable setup;

	private List<? extends Consumer<PackedCollection>> updateInput;
	private Supplier<PackedCollection> retrieveOutput;
	private Runnable forward;

	private Consumer<PackedCollection> updateGradient;
	private Supplier<PackedCollection> retrieveGradient;
	private Runnable backward;

	/**
	 * Creates a new compiled model with all required components.
	 * This constructor is typically called by {@link #compile(Model, boolean, boolean, OperationProfile)}.
	 *
	 * @param inputShapes the shapes of all inputs (primary + auxiliary)
	 * @param outputShape the output shape
	 * @param setup the setup/reset operation
	 * @param updateInput functions to update input values before forward pass
	 * @param retrieveOutput function to retrieve output after forward pass
	 * @param forward the compiled forward pass operation
	 * @param updateGradient function to update gradient before backward pass
	 * @param retrieveGradient function to retrieve input gradient after backward pass
	 * @param backward the compiled backward pass operation
	 */
	protected CompiledModel(List<TraversalPolicy> inputShapes, TraversalPolicy outputShape,
							Runnable setup, List<? extends Consumer<PackedCollection>> updateInput,
							Supplier<PackedCollection> retrieveOutput,
							Runnable forward,
							Consumer<PackedCollection> updateGradient,
							Supplier<PackedCollection> retrieveGradient,
							Runnable backward) {
		this.inputShapes = inputShapes;
		this.outputShape = outputShape;
		this.setup = setup;
		this.updateInput = updateInput;
		this.retrieveOutput = retrieveOutput;
		this.forward = forward;
		this.updateGradient = updateGradient;
		this.retrieveGradient = retrieveGradient;
		this.backward = backward;
	}

	/**
	 * Returns the primary input shape.
	 *
	 * @return the shape expected for the first input
	 */
	public TraversalPolicy getInputShape() { return inputShapes.get(0); }

	/**
	 * Returns the output shape.
	 *
	 * @return the shape of the model output
	 */
	public TraversalPolicy getOutputShape() { return outputShape; }

	/**
	 * Executes the forward pass with the given inputs.
	 *
	 * @param input the primary input data
	 * @param args additional inputs for multi-input models
	 * @return the model output
	 */
	public PackedCollection forward(PackedCollection input, PackedCollection... args) {
		updateInput.get(0).accept(input);
		for (int i = 1; i < updateInput.size(); i++) {
			int a = i - 1;
			updateInput.get(i).accept(a < args.length ? args[a] : null);
		}

		forward.run();
		return retrieveOutput.get();
	}

	/**
	 * Executes the backward pass with the given gradient.
	 * This computes gradients and updates model parameters according to the
	 * parameter update strategy set during model construction.
	 *
	 * @param gradient the gradient of the loss with respect to model output
	 * @return the gradient with respect to input, or null if not configured to return gradients
	 */
	public PackedCollection backward(PackedCollection gradient) {
		updateGradient.accept(gradient);
		backward.run();
		return retrieveGradient == null ? null : retrieveGradient.get();
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
	 */
	@Override
	public void destroy() {
		Destroyable.destroy(forward);
		Destroyable.destroy(backward);
	}

	public static CompiledModel compile(Model model) {
		return compile(model, true, false, null);
	}

	public static CompiledModel compile(Model model, OperationProfile profile) {
		return compile(model, true, false, profile);
	}

	public static CompiledModel compile(Model model,
										boolean backprop, boolean returnGradient,
										OperationProfile profile) {
		Runnable setup = Process.optimized(model.setup()).get();

		List<InputManager> in = new ArrayList<>();
		in.add(new InputManager(model.firstBlock().getInputShape()));
		model.getInputs().forEach(p -> in.add(new InputManager(p.getInputShape())));

		InputManager grad = new InputManager(model.lastBlock().getOutputShape());

		PackedCollection output = new PackedCollection(model.lastBlock().getOutputShape());
		model.lastBlock().getForward().setReceptor(out ->
				Ops.o().copy("Model Forward Output", out, Ops.o().p(output), output.getMemLength()));

		PackedCollection gradOut;

		if (returnGradient) {
			gradOut = new PackedCollection(model.firstBlock().getInputShape());
			model.firstBlock().getBackward().setReceptor(out ->
					Ops.o().copy("Model Backward Output", out, Ops.o().p(gradOut), gradOut.getMemLength()));
		} else {
			gradOut = null;
		}

		List<Cell<PackedCollection>> cells = model.forward();
		OperationList forward = new OperationList("CompiledModel Forward");
		for (int i = cells.size() - 1; i >= 0; i--) {
			forward.add(cells.get(i).push(in.get(i).get()));
		}

		ParallelProcess<?, Runnable> p = forward.flatten().optimize();

		ParallelProcess<?, Runnable> q;

		if (backprop) {
			q = (ParallelProcess<?, Runnable>) model.backward().push(grad.get());
			if (q instanceof OperationList) q = ((OperationList) q).flatten();
			q = q.optimize();
		} else {
			q = null;
		}

		if (p instanceof OperationList) ((OperationList) p).setProfile(profile);
		if (q instanceof OperationList) ((OperationList) q).setProfile(profile);

		CompiledModel compiled = new CompiledModel(in.stream().map(InputManager::getShape).collect(Collectors.toList()),
				grad.getShape(),
				setup, in,
				() -> output, p.get(), grad,
				gradOut == null ? null : () -> gradOut,
				q == null ? null : q.get());
		compiled.reset();
		return compiled;
	}

	protected static class InputManager implements Consumer<PackedCollection>,
			Supplier<DynamicCollectionProducer<PackedCollection>>, ConsoleFeatures {
		private TraversalPolicy shape;
		private PackedCollection input;

		public InputManager(TraversalPolicy shape) {
			this.shape = shape;
		}

		public TraversalPolicy getShape() { return shape; }

		@Override
		public void accept(PackedCollection input) {
			if (input == null) {
				warn("null input");
			} else if (input.getShape().getTotalSizeLong() != shape.getTotalSizeLong()) {
				throw new IllegalArgumentException("Provided " + input.getShape() +
						" input when " + shape + " was expected");
			}

			this.input = input;
		}

		public DynamicCollectionProducer<PackedCollection> get() {
			return new DynamicCollectionProducer<>(shape, args -> input);
		}

		@Override
		public Console console() {
			return LayerFeatures.console;
		}
	}
}
