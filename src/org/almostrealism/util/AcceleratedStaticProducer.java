package org.almostrealism.util;

import io.almostrealism.code.Argument;
import org.almostrealism.algebra.Pair;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;

public class AcceleratedStaticProducer<T extends MemWrapper> extends DynamicAcceleratedProducerAdapter<T> {
	private T value;

	public AcceleratedStaticProducer(T value, Producer<T> output) {
		super(value.getMemLength(), output);
		this.value = value;
	}

	/**
	 * Short circuit the evaluation of a CL program by simply returning
	 * the value.
	 */
	@Override
	public T evaluate(Object args[]) {
		return value;
	}

	/**
	 * Provided to support compact operations of other {@link DynamicAcceleratedProducerAdapter}s,
	 * this is not actually used by {@link #evaluate(Object[])}.
	 */
	@Override
	public String getValue(Argument arg, int pos) {
		Pair p = MemWrapper.fromMem(value.getMem(), value.getOffset() + pos, 1);

		String s = stringForDouble(p.getA());
		if (s.contains("Infinity")) {
			throw new IllegalArgumentException("Infinity is not supported");
		}

		return s;
	}

	/**
	 * Returns true.
	 */
	@Override
	public boolean isStatic() { return true; }
}
