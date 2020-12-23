package io.almostrealism.relation;

/**
 * A simple way to track the origin of some data.
 */
public interface Generated<T, V> {
	T getGenerator();

	default V getGenerated() {
		return (V) this;
	}
}
