package org.almostrealism.util;

import java.util.function.Function;

public class DynamicProducer<T> implements Producer<T> {
	private Function<Object[], T> function;

	public DynamicProducer(Function<Object[], T> function) {
		this.function = function;
	}

	/**
	 * Applies the {@link Function}.
	 */
	@Override
	public T evaluate(Object[] args) { return function.apply(args); }

	/**
	 * Does nothing.
	 */
	@Override
	public void compact() { }
}
