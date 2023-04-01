package org.almostrealism.layers;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class KernelLayerCell implements Cell<PackedCollection<?>>, CodeFeatures {
	private final Supplier<Runnable> setup;
	private final PackedCollection<?> output;
	private final KernelExpression kernel;
	private final List<PackedCollection<?>> weights;

	private Receptor<PackedCollection<?>> next;

	public KernelLayerCell(TraversableKernelExpression kernel, List<PackedCollection<?>> weights) {
		this(kernel.getShape(), kernel, weights);
	}

	public KernelLayerCell(TraversalPolicy outputShape,
						   KernelExpression kernel, List<PackedCollection<?>> weights) {
		this(outputShape, kernel, weights, new OperationList());
	}

	public KernelLayerCell(TraversalPolicy outputShape,
						   KernelExpression kernel, List<PackedCollection<?>> weights,
						   Supplier<Runnable> setup) {
		this.output = new PackedCollection<>(outputShape);
		this.kernel = kernel;
		this.weights = weights;
		this.setup = setup;
	}

	public PackedCollection<?> getOutput() { return output; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> input) {
		List<Producer> arguments = new ArrayList<>();
		arguments.add(input);
		weights.stream().map(this::p).forEach(arguments::add);

		CollectionProducerComputation<PackedCollection<?>> computation =
				kernel(output.getShape(), kernel, arguments.toArray(Producer[]::new));

		OperationList push = new OperationList();
		push.add(() -> {
			KernelizedEvaluable<PackedCollection<?>> k = computation.get();
			return () -> k.kernelEvaluate(output.traverseEach());
		});
		if (next != null) push.add(next.push(p(output)));
		return push;
	}

	@Override
	public void setReceptor(Receptor<PackedCollection<?>> r) {
		next = r;
	}
}
