package org.almostrealism.model;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class DefaultBlock implements Block {
	private TraversalPolicy inputShape;
	private TraversalPolicy outputShape;
	private Supplier<Runnable> setup;
	private Cell<PackedCollection<?>> forward;
	private Cell<PackedCollection<?>> backward;

	public DefaultBlock(TraversalPolicy inputShape, TraversalPolicy outputShape, Cell<PackedCollection<?>> forward,
			Cell<PackedCollection<?>> backward) {
		this(inputShape, outputShape, forward, backward, new OperationList());
	}

	public DefaultBlock(TraversalPolicy inputShape, TraversalPolicy outputShape,
						Cell<PackedCollection<?>> forward, Cell<PackedCollection<?>> backward,
						Supplier<Runnable> setup) {
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.setup = setup;
		this.forward = forward;
		this.backward = backward;
	}

	@Override
	public Supplier<Runnable> setup() {
		return setup;
	}

	@Override
	public TraversalPolicy getInputShape() {
		return inputShape;
	}

	@Override
	public TraversalPolicy getOutputShape() {
		return outputShape;
	}

	@Override
	public Cell<PackedCollection<?>> getForward() {
		return forward;
	}

	@Override
	public Cell<PackedCollection<?>> getBackward() {
		return backward;
	}
}
