package org.almostrealism.algebra;

public interface TripleFunction<T extends Triple> {
	T operate(Triple in);
}
