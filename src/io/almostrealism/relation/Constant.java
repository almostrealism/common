package io.almostrealism.relation;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;

public class Constant<T> implements Operator<T> {
	private T v;

	public Constant(T v) { this.v = v; }

	@Override
	public T evaluate(Object args[]) {
		return v;
	}

	@Override
	public Scope<T> getScope(NameProvider p) {
		Scope s = new Scope();
		s.getVariables().add(new Variable(p.getFunctionName() + v.getClass().getSimpleName(), v));
		return s;
	}
}
