package org.almostrealism.algebra;

public interface TripleFunction<T extends Triple> {
	public T operate(Triple in);
}
