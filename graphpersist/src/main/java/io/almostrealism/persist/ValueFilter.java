package io.almostrealism.persist;

import java.util.Collection;

/** The ValueFilter class. */
public abstract class ValueFilter<V> {
	/** Performs the filter operation. */
	public abstract Collection<V> filter(Collection<V> values) throws CloneNotSupportedException;
}
