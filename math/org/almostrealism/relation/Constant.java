package org.almostrealism.relation;

import org.almostrealism.util.Producer;

public class Constant<T> implements Operator<T> {
	private T v;

	public Constant(T v) { this.v = v; }

	public T evaluate(Object args[]) {
		return v;
	}

	public void compact() {
		if (v instanceof Producer) ((Producer) v).compact();
	}
}
