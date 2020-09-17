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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T> implements Nameable {
	private String name, annotation;
	private Class<T> type;
	private Producer<T> producer;
	private Supplier<String> expression;
	private Method<T> generator;
	private int arraySize = -1;

	public Variable(String name, T value) {
		this(name, StaticProducer.of(value));
	}

	public Variable(String name, Producer<T> producer) {
		this(name, (Class) null, producer);
	}

	public Variable(String name, Class<T> type, T value) {
		this(name, type, StaticProducer.of(value));
	}

	public Variable(String name, Class<T> type, Producer<T> producer) {
		setName(name);
		setType(type);
		this.producer = producer;
	}

	public Variable(String name, Method<T> generator) {
		this(name, null, generator);
	}
	
	public Variable(String name, Class<T> type, Method<T> generator) {
		setName(name);
		setType(type);
		this.generator = generator;
	}

	public Variable(String name, String expression) {
		this(name, null, expression);
	}

	public Variable(String name, Class<T> type, String expression) {
		setName(name);
		setType(type);
		this.expression = () -> expression;
	}

	public Variable(String name, String expression, Producer<T> producer) {
		this(name, null, () -> expression, producer);
	}

	public Variable(String name, Class<T> type, String expression, Producer<T> producer, int arraySize) {
		setName(name);
		setType(type);
		this.expression = () -> expression;
		this.producer = producer;
	}

	public Variable(String name, int arraySize) {
		this(name, null, (Supplier) null, null, arraySize);
	}

	public Variable(String name, Supplier<String> expression) {
		this(name, null, expression);
	}

	public Variable(String name, Class<T> type, Supplier<String> expression) {
		setName(name);
		setType(type);
		this.expression = expression;
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
		setName(name);
		setType(type);
		this.expression = expression;
		this.producer = producer;
		setArraySize(arraySize);
	}

	public void setName(String n) { this.name = n; }
	public String getName() { return this.name; }

	public void setType(Class<T> t) { this.type = t; }
	public Class<T> getType() { return this.type; }

	public void setAnnotation(String a) { this.annotation = a; }
	public String getAnnotation() { return this.annotation; }

	public void setProducer(Producer<T> producer) { this.producer = producer; }
	public Producer<T> getProducer() { return producer; }
	
	public void setGenerator(Method<T> generator) { this.generator = generator; }
	public Method<T> getGenerator() { return this.generator; }

	public String getExpression() { return expression == null ? null : expression.get(); }
	public void setExpression(String expression) { this.expression = () -> expression; }
	public void setExpression(Supplier<String> expression) { this.expression = expression; }

	public int getArraySize() { return arraySize; }
	public void setArraySize(int arraySize) { this.arraySize = arraySize; }

	public T getValue() {
		if (producer != null) {
			return producer.evaluate();
		} else if (producer != null) {
			return (T) expression.get();
		} else {
			throw new RuntimeException();
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Variable == false) return false;

		Variable v = (Variable) obj;
		if (!Objects.equals(name, v.getName())) return false;
		if (!Objects.equals(annotation, v.getAnnotation())) return false;
		if (!Objects.equals(type, v.getType())) return false;
		if (!Objects.equals(producer, v.getProducer())) return false;
		if (!Objects.equals(expression, v.expression)) return false;
		if (!Objects.equals(generator, v.getGenerator())) return false;

		return true;
	}

	@Override
	public int hashCode() { return name.hashCode(); }
}
