package org.almostrealism.util;

public interface ParameterizedFactory<T, V> extends Factory<V> {
	<A extends T> void setParameter(Class<A> param, A value);
}
