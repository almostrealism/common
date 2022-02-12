package org.almostrealism.collect;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.function.Function;

// TODO  The producer, not just the collection itself, needs to know the shape
public interface CollectionProducer extends Producer<PackedCollection>, CollectionFeatures {
	// This should be 0, but Scalar is actually a Pair so a set of scalars is 2D not 1D
	int SCALAR_AXIS = 1;

	default CollectionProducer scalarMap(Function<Producer<Scalar>, Producer<Scalar>> f) {
		Producer<Scalar> p = f.apply(Input.value(Scalar.class, 0));

		return () -> args -> {
			PackedCollection c = get().evaluate();
			KernelizedEvaluable<Scalar> ev = (KernelizedEvaluable<Scalar>) p.get();
			MemoryBank<Scalar> bank = ev.createKernelDestination(c.getShape().length(SCALAR_AXIS));
			ev.kernelEvaluate(bank, new MemoryBank[] { c.traverse(SCALAR_AXIS) });
			return new PackedCollection(c.getShape(), c.getShape().getDimensions(), bank, 0);
		};
	}

	default <T extends MemoryDataAdapter> T collect(Function<TraversalPolicy, T> factory) {
		PackedCollection c = get().evaluate();
		T data = factory.apply(c.getShape());
		data.setDelegate(c, 0);
		return data;
	}
}
