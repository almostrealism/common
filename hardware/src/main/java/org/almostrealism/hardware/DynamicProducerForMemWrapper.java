package org.almostrealism.hardware;

import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class DynamicProducerForMemWrapper<T extends MemWrapper> extends DynamicProducer<T> {

	private IntFunction<MemoryBank<T>> kernelDestination;

	public DynamicProducerForMemWrapper(Supplier<T> supplier) {
		this(args -> supplier.get());
	}

	public DynamicProducerForMemWrapper(Function<Object[], T> function) {
		this(function, null);
	}

	public DynamicProducerForMemWrapper(Function<Object[], T> function, IntFunction<MemoryBank<T>> kernelDestination) {
		super(function);
		this.kernelDestination = kernelDestination;
	}

	@Override
	public KernelizedEvaluable<T> get() {
		Evaluable<T> e = super.get();

		return new KernelizedEvaluable<T>() {
			@Override
			public MemoryBank<T> createKernelDestination(int size) {
				if (kernelDestination == null) {
					throw new UnsupportedOperationException();
				} else {
					return kernelDestination.apply(size);
				}
			}

			@Override
			public T evaluate(Object... args) { return e.evaluate(args); }
		};
	}
}
