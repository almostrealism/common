package org.almostrealism.collect;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.function.Function;

public interface CollectionProducer<T extends PackedCollection> extends ProducerComputation<T>, KernelizedProducer<T>, CollectionFeatures {
	// This should be 0, but Scalar is actually a Pair so a set of scalars is 2D not 1D
	int SCALAR_AXIS = 1;

	TraversalPolicy getShape();

	@Override
	default KernelizedEvaluable<T> get() {
		AcceleratedComputationEvaluable ev = new DefaultCollectionEvaluable(getShape(), this);
		ev.compile();
		return ev;
	}

	default CollectionProducer<PackedCollection> scalarMap(Function<Producer<Scalar>, Producer<Scalar>> f) {
		Producer<Scalar> p = f.apply(Input.value(Scalar.class, 0));

		return new CollectionProducer<>() {
			@Override
			public TraversalPolicy getShape() {
				throw new UnsupportedOperationException();
			}

			@Override
			public Scope<PackedCollection> getScope() {
				throw new UnsupportedOperationException();
			}

			@Override
			public KernelizedEvaluable<PackedCollection> get() {
				return new KernelizedEvaluable<>() {
					@Override
					public MemoryBank<PackedCollection> createKernelDestination(int size) {
						throw new UnsupportedOperationException();
					}

					@Override
					public PackedCollection evaluate(Object... args) {
						PackedCollection c = get().evaluate();
						KernelizedEvaluable<Scalar> ev = (KernelizedEvaluable<Scalar>) p.get();
						MemoryBank<Scalar> bank = ev.createKernelDestination(c.getShape().length(SCALAR_AXIS));
						ev.kernelEvaluate(bank, new MemoryBank[] { c.traverse(SCALAR_AXIS) });
						return new PackedCollection(c.getShape(), c.getShape().getDimensions(), bank, 0);
					}
				};
			}
		};
	}

	default <T extends MemoryDataAdapter> T collect(Function<TraversalPolicy, T> factory) {
		PackedCollection c = get().evaluate();
		T data = factory.apply(c.getShape());
		data.setDelegate(c, 0);
		return data;
	}
}
