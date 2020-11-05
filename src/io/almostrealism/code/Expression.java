/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.code;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class Expression<T> {
	private Class<T> type;
	private Supplier<String> expression;
	private Method<T> generator;
	private List<Variable<?>> dependencies = new ArrayList<>();
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

	public Expression(Class<T> type, String expression) {
		this(type, expression, new Variable[0]);
	}

	public Expression(Class<T> type, String expression, Expression<?>... dependencies) {
		this(type, expression, dependencies(dependencies));
	}

	public Expression(Class<T> type, String expression, Variable<?>... dependencies) {
		setType(type);
		this.expression = () -> expression;
		this.dependencies = new ArrayList<>();
		Stream.of(dependencies).forEach(this.dependencies::add);
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

	public void setGenerator(Method<T> generator) { this.generator = generator; }
	public Method<T> getGenerator() { return this.generator; }

	public String getExpression() { return expression == null ? null : expression.get(); }
	public void setExpression(String expression) { this.expression = () -> expression; }
	public void setExpression(Supplier<String> expression) { this.expression = expression; }

	public List<Variable<?>> getDependencies() { return dependencies; }

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

	private static Variable[] dependencies(Expression expressions[]) {
		Set<Variable<?>> dependencies = new HashSet<>();
		for (Expression e : expressions) dependencies.addAll(e.getDependencies());
		return dependencies.toArray(new Variable[0]);
	}
}
