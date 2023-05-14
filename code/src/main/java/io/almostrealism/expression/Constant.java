package io.almostrealism.expression;

import java.util.List;

public class Constant<T> extends Expression<T> {
	public Constant(Class<T> type) {
		super(type);
	}

	@Deprecated
	public Constant(Class<T> type, String value) {
		super(type, value);
	}

	public Constant<T> generate(List<Expression<?>> children) {
		if (children.size() > 0) {
			throw new UnsupportedOperationException();
		}

		return this;
	}
}
