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

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
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

public class CompiledModel implements CodeFeatures {
	private List<TraversalPolicy> inputShapes;
	private TraversalPolicy outputShape;

	private Runnable setup;

	private List<? extends Consumer<PackedCollection<?>>> updateInput;
	private Supplier<PackedCollection<?>> retrieveOutput;
	private Runnable forward;

	private Consumer<PackedCollection<?>> updateGradient;
	private Supplier<PackedCollection<?>> retrieveGradient;
	private Runnable backward;

	protected CompiledModel(List<TraversalPolicy> inputShapes, TraversalPolicy outputShape,
							Runnable setup, List<? extends Consumer<PackedCollection<?>>> updateInput,
							Supplier<PackedCollection<?>> retrieveOutput,
							Runnable forward,
							Consumer<PackedCollection<?>> updateGradient,
							Supplier<PackedCollection<?>> retrieveGradient,
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

	public TraversalPolicy getInputShape() { return inputShapes.get(0); }

	public TraversalPolicy getOutputShape() { return outputShape; }

	public PackedCollection<?> forward(PackedCollection<?> input, PackedCollection<?>... args) {
		updateInput.get(0).accept(input);
		for (int i = 1; i < updateInput.size(); i++) {
			int a = i - 1;
			updateInput.get(i).accept(a < args.length ? args[a] : null);
		}

		forward.run();
		return retrieveOutput.get();
	}

	public PackedCollection<?> backward(PackedCollection<?> gradient) {
		updateGradient.accept(gradient);
		backward.run();
		return retrieveGradient == null ? null : retrieveGradient.get();
	}

	public void reset() {
		setup.run();
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

		PackedCollection<?> output = new PackedCollection<>(model.lastBlock().getOutputShape());
		model.lastBlock().getForward().setReceptor(out ->
				Ops.o().copy("Model Forward Output", out, Ops.o().p(output), output.getMemLength()));

		PackedCollection<?> gradOut;

		if (returnGradient) {
			gradOut = new PackedCollection<>(model.firstBlock().getInputShape());
			model.firstBlock().getBackward().setReceptor(out ->
					Ops.o().copy("Model Backward Output", out, Ops.o().p(gradOut), gradOut.getMemLength()));
		} else {
			gradOut = null;
		}

		List<Cell<PackedCollection<?>>> cells = model.forward();
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

	protected static class InputManager implements Consumer<PackedCollection<?>>,
			Supplier<DynamicCollectionProducer<PackedCollection<?>>>, ConsoleFeatures {
		private TraversalPolicy shape;
		private PackedCollection<?> input;

		public InputManager(TraversalPolicy shape) {
			this.shape = shape;
		}

		public TraversalPolicy getShape() { return shape; }

		@Override
		public void accept(PackedCollection<?> input) {
			if (input == null) {
				warn("null input");
			}

			this.input = input;
		}

		public DynamicCollectionProducer<PackedCollection<?>> get() {
			return new DynamicCollectionProducer<>(shape, args -> input);
		}

		@Override
		public Console console() {
			return LayerFeatures.console;
		}
	}
}
