package org.almostrealism.layers;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.MemoryDataCopy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class KernelLayerCell implements Cell<PackedCollection<?>>, CodeFeatures {
	private final Supplier<Runnable> setup;
	private final PackedCollection<?> input;
	private final PackedCollection<?> output;
	private final KernelExpression kernel;
	private final List<PackedCollection<?>> weights;

	private Receptor<PackedCollection<?>> next;

	public KernelLayerCell(TraversalPolicy inputShape, TraversableKernelExpression kernel, List<PackedCollection<?>> weights) {
		this(inputShape, kernel.getShape(), kernel, weights);
	}

	public KernelLayerCell(TraversalPolicy inputShape, TraversalPolicy outputShape,
						   KernelExpression kernel, List<PackedCollection<?>> weights) {
		this(inputShape, outputShape, kernel, weights, new OperationList());
	}

	public KernelLayerCell(TraversalPolicy inputShape, TraversalPolicy outputShape,
						   KernelExpression kernel, List<PackedCollection<?>> weights,
						   Supplier<Runnable> setup) {
		this.input = new PackedCollection<>(inputShape);
		this.output = new PackedCollection<>(outputShape);
		this.kernel = kernel;
		this.weights = weights;
		this.setup = setup;
	}

	public PackedCollection<?> getInput() { return input; }

	public PackedCollection<?> getOutput() { return output; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> input) {
		List<Producer> arguments = new ArrayList<>();
		arguments.add(p(this.input));
		weights.stream().map(this::p).forEach(arguments::add);

		CollectionProducerComputation<PackedCollection<?>> computation =
				kernel(output.getShape(), kernel, arguments.toArray(Producer[]::new));

		Evaluable<PackedCollection<?>> in = input.get();

		OperationList push = new OperationList();
		push.add(new MemoryDataCopy("KernelLayerCell Input",
				() -> in.evaluate(), () -> this.input,
				this.input.getShape().getTotalSize()));
		push.add(() -> {
			KernelizedEvaluable<PackedCollection<?>> k = computation.get();
			return () -> k.into(output.traverseEach()).evaluate();
		});
		if (next != null) push.add(next.push(p(output)));
		return push;
	}

	@Override
	public void setReceptor(Receptor<PackedCollection<?>> r) {
		next = r;
	}
}
