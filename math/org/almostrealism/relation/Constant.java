package org.almostrealism.relation;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.util.Evaluable;

public class Constant<T> implements Operator<T> {
	private T v;

	public Constant(T v) { this.v = v; }

	@Override
	public T evaluate(Object args[]) {
		return v;
	}

	@Override
	public void compact() {
		if (v instanceof Evaluable) ((Evaluable) v).compact();
	}

	@Override
	public Scope<T> getScope(NameProvider p) {
		Scope s = new Scope();
		s.getVariables().add(new Variable(p.getFunctionName() + v.getClass().getSimpleName(), v));
		return s;
	}
}
