package io.almostrealism.persist;

import java.util.Collection;

/**
 * Abstract filter that reduces or transforms a collection of values of type {@code V}.
 *
 * @param <V> The type of values to filter
 */
public abstract class ValueFilter<V> {
	/**
	 * Filters the given collection and returns the subset (or transformed version) that
	 * passes the filter's criteria.
	 *
	 * @param values The input collection of values
	 * @return The filtered collection of values
	 * @throws CloneNotSupportedException If cloning a value during filtering fails
	 */
	public abstract Collection<V> filter(Collection<V> values) throws CloneNotSupportedException;
}
