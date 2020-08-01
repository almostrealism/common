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
		return String.valueOf(p.getA());
	}

	/**
	 * Returns true.
	 */
	@Override
	public boolean isStatic() { return true; }
}
