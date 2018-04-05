package org.almostrealism.relation;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.util.Producer;

public class Constant<T> implements Operator<T> {
	private T v;

	public Constant(T v) { this.v = v; }

	@Override
	public T evaluate(Object args[]) {
		return v;
	}

	@Override
	public void compact() {
		if (v instanceof Producer) ((Producer) v).compact();
	}

	@Override
	public Scope<Variable<T>> getScope(String prefix) {
		// TODO  Not sure this is correct
		Scope s = new Scope();
		s.getVariables().add(new Variable(prefix + v.getClass().getSimpleName(), v));
		return s;
	}
}
