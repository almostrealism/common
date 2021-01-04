package io.almostrealism.code;

public class Constant<T> implements Operator<T> {
	private T v;

	public Constant(T v) { this.v = v; }

	@Override
	public T evaluate(Object args[]) {
		return v;
	}

	@Override
	public Scope<T> getScope() {
		Scope s = new Scope();
		s.getVariables().add(new Variable(v.getClass().getSimpleName(), v));
		return s;
	}
}
