package org.almostrealism.util;

import org.almostrealism.algebra.Pair;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.math.MemWrapper;

public class AcceleratedStaticProducer<T extends MemWrapper> extends DynamicAcceleratedProducerAdapter<T> {
	private T value;

	public AcceleratedStaticProducer(T value, Producer<T> output) {
		super(value.getMemLength(), output);
		this.value = value;
	}

	@Override
	public String getValue(int pos) {
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
