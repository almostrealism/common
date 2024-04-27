package org.almostrealism.model;

import io.almostrealism.code.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.ParallelProcess;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.OperationList;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class CompiledModel implements CodeFeatures {
	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;

	private Consumer<PackedCollection<?>> updateInput;
	private Supplier<PackedCollection<?>> retrieveOutput;
	private Runnable forward;

	private Consumer<PackedCollection<?>> updateGradient;
	private Supplier<PackedCollection<?>> retrieveGradient;
	private Runnable backward;

	protected CompiledModel(TraversalPolicy inputShape, TraversalPolicy outputShape,
							Consumer<PackedCollection<?>> updateInput,
							Supplier<PackedCollection<?>> retrieveOutput,
							Runnable forward,
							Consumer<PackedCollection<?>> updateGradient,
							Supplier<PackedCollection<?>> retrieveGradient,
							Runnable backward) {
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.updateInput = updateInput;
		this.retrieveOutput = retrieveOutput;
		this.forward = forward;
		this.updateGradient = updateGradient;
		this.retrieveGradient = retrieveGradient;
		this.backward = backward;
	}

	public TraversalPolicy getInputShape() { return inputShape; }

	public TraversalPolicy getOutputShape() { return outputShape; }

	public PackedCollection<?> forward(PackedCollection<?> input) {
		updateInput.accept(input);
		forward.run();
		return retrieveOutput.get();
	}

	public PackedCollection<?> backward(PackedCollection<?> gradient) {
		updateGradient.accept(gradient);
		backward.run();
		return retrieveGradient.get();
	}

	public static CompiledModel compile(Model model) {
		return compile(model, true, null);
	}

	public static CompiledModel compile(Model model, OperationProfile profile) {
		return compile(model, true, profile);
	}

	public static CompiledModel compile(Model model, boolean backprop, OperationProfile profile) {
		model.setup().get().run();

		InputManager in = new InputManager(model.firstBlock().getInputShape());
		InputManager grad = new InputManager(model.lastBlock().getOutputShape());

		PackedCollection<?> output = new PackedCollection<>(model.lastBlock().getOutputShape());
		model.lastBlock().getForward().setReceptor(out ->
				Ops.o().copy("Model Forward Output", out, Ops.o().p(output), output.getMemLength()));

		PackedCollection<?> gradOut = new PackedCollection<>(model.firstBlock().getInputShape());
		model.firstBlock().getBackward().setReceptor(out ->
				Ops.o().copy("Model Backward Output", out, Ops.o().p(gradOut), gradOut.getMemLength()));

		ParallelProcess<?, Runnable> p = (ParallelProcess<?, Runnable>) model.forward().push(in.get());
		if (p instanceof OperationList) p = ((OperationList) p).flatten();
		p = p.optimize();

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

		return new CompiledModel(in.getShape(), grad.getShape(), in, () -> output,
									p.get(), grad, () -> gradOut, q == null ? null : q.get());
	}

	protected static class InputManager implements Consumer<PackedCollection<?>>,
			Supplier<DynamicCollectionProducer<PackedCollection<?>>> {
		private TraversalPolicy shape;
		private PackedCollection<?> input;

		public InputManager(TraversalPolicy shape) {
			this.shape = shape;
		}

		public TraversalPolicy getShape() { return shape; }

		@Override
		public void accept(PackedCollection<?> input) {
			this.input = input;
		}

		public DynamicCollectionProducer<PackedCollection<?>> get() {
			return new DynamicCollectionProducer<>(shape, args -> input);
		}
	}
}
