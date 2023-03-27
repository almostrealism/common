/*
 * Copyright 2021 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.expression;

import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class Expression<T> {
	private Class<T> type;
	private Supplier<String> expression;
	private List<Variable<?, ?>> dependencies = new ArrayList<>();
	private int arraySize = -1;

	public Expression(Class<T> type) {
		setType(type);
	}

	public Expression(Class<T> type, String expression) {
		this(type, expression, new Variable[0]);
	}

	public Expression(Class<T> type, String expression, Expression<?>... dependencies) {
		this(type, expression, dependencies(dependencies));
	}

	public Expression(Class<T> type, String expression, Variable<?, ?>... dependencies) {
		if (type == null) {
			throw new IllegalArgumentException("Type is required");
		}

		setType(type);
		this.expression = () -> expression;
		this.dependencies = new ArrayList<>();
		this.dependencies.addAll(Arrays.asList(dependencies));
	}

	public Expression(Class<T> type, String expression, int arraySize) {
		setType(type);
		this.expression = () -> expression;
		setArraySize(arraySize);
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

	public String getExpression() {
		if (expression != null) return expression.get();
		return null;
	}
	public void setExpression(String expression) { this.expression = () -> expression; }
	public void setExpression(Supplier<String> expression) { this.expression = expression; }

	public List<Variable<?, ?>> getDependencies() { return dependencies; }

	public int getArraySize() { return arraySize; }
	public void setArraySize(int arraySize) { this.arraySize = arraySize; }

	public T getValue() {
		if (expression != null) {
			return (T) expression.get();
		} else {
			throw new RuntimeException();
		}
	}

	public Sum add(Expression<Double> operand) { return new Sum((Expression) this, operand); }

	public Product multiply(int operand) { return new Product((Expression) this, new Expression(Integer.class, String.valueOf(operand))); }
	public Product multiply(Expression<Double> operand) { return new Product((Expression) this, operand); }

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Expression)) return false;

		Expression v = (Expression) obj;
		if (type != v.getType()) return false;
		if (!Objects.equals(expression, v.expression)) return false;
		if (!Objects.equals(dependencies, v.getDependencies())) return false;

		return true;
	}

	@Override
	public int hashCode() { return getValue().hashCode(); }

	private static Variable[] dependencies(Expression expressions[]) {
		Set<Variable<?, ?>> dependencies = new HashSet<>();
		for (Expression e : expressions) dependencies.addAll(e.getDependencies());
		return dependencies.toArray(new Variable[0]);
	}
}
