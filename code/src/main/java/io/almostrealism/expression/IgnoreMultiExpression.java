package io.almostrealism.expression;

/**
 * This is a marker interface, designed to aid in migration away from {@link MultiExpression}.
 * Implementors of {@link MultiExpression} (usually inherited from a parent that has not been
 * migrated away from it yet) can also implement this interface to indicate that they should be
 * treated as though they were not a {@link MultiExpression}.
 */
public interface IgnoreMultiExpression<T> extends MultiExpression<T> {
	@Override
	default Expression<T> getValue(int pos) {
		throw new UnsupportedOperationException();
	}
}
