package io.almostrealism.code;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class Expression<T> {
	private Class<T> type;
	private Supplier<String> expression;
	private Method<T> generator;
	private List<Variable> dependencies;
	private int arraySize = -1;

	public Expression(Class<T> type) {
		setType(type);
	}

	public Expression(Method<T> generator) {
		this(null, generator);
	}

	public Expression(Class<T> type, Method<T> generator) {
		setType(type);
		this.generator = generator;
	}

	public Expression(String expression) {
		this(null, expression);
	}

	public Expression(Class<T> type, String expression) {
		setType(type);
		this.expression = () -> expression;
	}

	public Expression(Class<T> type, String expression, int arraySize) {
		setType(type);
		this.expression = () -> expression;
	}

	public Expression(int arraySize) {
		this(null, (Supplier) null, arraySize);
	}

	public Expression(Supplier<String> expression) {
		this(null, expression);
	}

	public Expression(Class<T> type, Supplier<String> expression) {
		setType(type);
		this.expression = expression;
	}

	public Expression(Class<T> type, Supplier<String> expression, int arraySize) {
		setType(type);
		this.expression = expression;
		setArraySize(arraySize);
	}

	public void setType(Class<T> t) { this.type = t; }
	public Class<T> getType() { return this.type; }

	public void setGenerator(Method<T> generator) { this.generator = generator; }
	public Method<T> getGenerator() { return this.generator; }

	public String getExpression() { return expression == null ? null : expression.get(); }
	public void setExpression(String expression) { this.expression = () -> expression; }
	public void setExpression(Supplier<String> expression) { this.expression = expression; }

	public List<Variable> getDependencies() { return dependencies; }

	public int getArraySize() { return arraySize; }
	public void setArraySize(int arraySize) { this.arraySize = arraySize; }

	public T getValue() {
		if (expression != null) {
			return (T) expression.get();
		} else {
			throw new RuntimeException();
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Expression == false) return false;

		Expression v = (Expression) obj;
		if (!Objects.equals(type, v.getType())) return false;
		if (!Objects.equals(expression, v.expression)) return false;
		if (!Objects.equals(generator, v.getGenerator())) return false;
		if (!Objects.equals(dependencies, v.getDependencies())) return false;

		return true;
	}

	@Override
	public int hashCode() { return getValue().hashCode(); }
}
