/*
 * Copyright 2018 Michael Murray
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

import org.almostrealism.util.Nameable;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T> implements Nameable {
	private String name, annotation;
	private Expression<T> expression;
	private Producer<T> producer;

	public Variable(String name, Expression<T> expression) {
		this(name, expression, null);
	}

	public Variable(String name, Expression<T> expression, Producer<T> producer) {
		setName(name);
		setExpression(expression);
		setProducer(producer);
	}

	public Variable(String name, T expression) {
		this(name, (Expression) null, StaticProducer.of(expression));
	}

	public Variable(String name, Producer<T> producer) {
		this(name, (Class) null, producer);
	}

	public Variable(String name, Class<T> type, T expression) {
		this(name, type, StaticProducer.of(expression));
	}

	public Variable(String name, Class<T> type, Producer<T> producer) {
		this(name, new Expression(type), producer);
	}

	public Variable(String name, Method<T> generator) {
		this(name, null, generator);
	}
	
	public Variable(String name, Class<T> type, Method<T> generator) {
		this(name, new Expression(type, generator));
	}

	public Variable(String name, String expression) {
		this(name, null, expression);
	}

	public Variable(String name, Class<T> type, String expression) {
		this(name, new Expression(type, expression));
	}

	public Variable(String name, String expression, Producer<T> producer) {
		this(name, null, () -> expression, producer);
	}

	public Variable(String name, Class<T> type, String expression, Producer<T> producer, int arraySize) {
		this(name, new Expression(type, expression, arraySize), producer);
	}

	public Variable(String name, int arraySize) {
		this(name, null, (Supplier) null, null, arraySize);
	}

	public Variable(String name, Supplier<String> expression) {
		this(name, null, expression);
	}

	public Variable(String name, Class<T> type, Supplier<String> expression) {
		this(name, new Expression(type, expression));
	}

	public Variable(String name, Supplier<String> expression, Producer<T> producer) {
		this(name, null, expression, producer);
	}

	public Variable(String name, Class<T> type, Supplier<String> expression, Producer<T> producer) {
		this(name, type, expression, producer, -1);
	}

	public Variable(String name, Producer<T> producer, int arraySize) {
		this(name, null, (Supplier) null, producer, arraySize);
	}

	public Variable(String name, Producer<T> producer, int arraySize, String annotation) {
		this(name, null, (Supplier) null, producer, arraySize);
		setAnnotation(annotation);
	}

	public Variable(String name, Class<T> type, Supplier<String> expression, Producer<T> producer, int arraySize) {
		this(name, new Expression(type, expression, arraySize), producer);
	}

	public void setName(String n) { this.name = n; }
	public String getName() { return this.name; }

	public void setAnnotation(String a) { this.annotation = a; }
	public String getAnnotation() { return this.annotation; }

	public void setExpression(Expression<T> value) { this.expression = value; }
	public Expression<T> getExpression() { return expression; }

	public void setProducer(Producer<T> producer) { this.producer = producer; }
	public Producer<T> getProducer() { return producer; }

	public Class<T> getType() { return getExpression().getType(); }
	public Method<T> getGenerator() { return getExpression().getGenerator(); }
	public List<Variable> getDependencies() { return getExpression().getDependencies(); }

	public int getArraySize() { return getExpression().getArraySize(); }

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Variable == false) return false;

		Variable v = (Variable) obj;
		if (!Objects.equals(name, v.getName())) return false;
		if (!Objects.equals(annotation, v.getAnnotation())) return false;
		if (!Objects.equals(expression, v.getExpression())) return false;
		if (!Objects.equals(producer, v.getProducer())) return false;

		return true;
	}

	@Override
	public int hashCode() { return name.hashCode(); }
}
