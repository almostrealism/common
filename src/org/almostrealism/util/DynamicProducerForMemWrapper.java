package org.almostrealism.util;

import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.relation.Evaluable;

import java.util.function.Function;
import java.util.function.IntFunction;

public class DynamicProducerForMemWrapper<T extends MemWrapper> extends DynamicProducer<T> {

	private IntFunction<MemoryBank<T>> kernelDestination;

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
