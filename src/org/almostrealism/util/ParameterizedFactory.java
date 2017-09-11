package org.almostrealism.util;

public interface ParameterizedFactory<T> extends Factory<T> {
	void setParameter(Class<T> param, T value);
}
