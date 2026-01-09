package io.almostrealism.persist;

import java.util.Collection;

public abstract class ValueFilter<V> {
	public abstract Collection<V> filter(Collection<V> values) throws CloneNotSupportedException;
}
