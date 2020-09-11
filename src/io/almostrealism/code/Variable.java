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

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T> implements Nameable {
	private String name, annotation;
	private Class<T> type;
	private Producer<T> producer;
	private String expression;
	private Method<T> generator;

	public Variable(String name, T value) {
		this(name, StaticProducer.of(value));
	}

	public Variable(String name, Producer<T> producer) {
		this(name, null, producer);
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
		this.expression = expression;
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

	public String getExpression() { return expression; }
	public void setExpression(String expression) { this.expression = expression; }

	public T getValue() {
		if (expression != null) {
			return (T) expression;
		} else if (producer != null) {
			return producer.evaluate();
		} else {
			throw new RuntimeException();
		}
	}
}
