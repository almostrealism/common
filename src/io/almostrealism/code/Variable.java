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

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.util.Nameable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.util.ProducerWithRank;
import org.almostrealism.util.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static org.almostrealism.util.Ops.*;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T> implements Nameable {
	private String name, annotation;
	private boolean declaration;
	private int sortHint;

	private Expression<T> expression;

	private Supplier<Evaluable<? extends T>> producer;
	private Variable<?> dependsOn;

	public Variable(String name, Expression<T> expression) {
		this(name, true, expression, (Supplier<Evaluable<? extends T>>) null);
	}

	public Variable(String name, Expression<T> expression, Variable<?> dependsOn) {
		this(name, true, expression, dependsOn);
	}

	public Variable(String name, boolean declaration, Expression<T> expression) {
		this(name, declaration, expression, (Variable) null);
	}

	public Variable(String name, boolean declaration, Expression<T> expression, Variable<?> dependsOn) {
		this(name, expression);
		this.declaration = declaration;
		this.dependsOn = dependsOn;
	}

	public Variable(String name, boolean declaration, Expression<T> expression, Supplier<Evaluable<? extends T>> producer) {
		setName(name);
		setExpression(expression);
		setProducer(producer);
		this.declaration = declaration;
	}

	public Variable(String name, T value) {
		this(name, true, (Expression) null, ops().v(value));
	}

	public Variable(String name, boolean declaration, Variable dependsOn) {
		this(name, declaration, (Expression) null, dependsOn);
	}

	public Variable(String name, String annotation, Supplier<Evaluable<? extends T>> producer) {
		this(name, annotation, (Class) null, producer);
	}

	public Variable(String name, Class<T> type, T value) {
		this(name, type, ops().v(value));
	}

	public Variable(String name, Class<T> type, Supplier<Evaluable<? extends T>> producer) {
		this(name, true, new Expression(type), producer);
	}

	public Variable(String name, String annotation, Class<T> type, Supplier<Evaluable<? extends T>> producer) {
		this(name, type, producer);
		setAnnotation(annotation);
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

	public Variable(String name, Class<T> type, String expression, Supplier<Evaluable<? extends T>> producer, int arraySize) {
		this(name, true, new Expression(type, expression, arraySize), producer);
	}

	public Variable(String name, Supplier<Evaluable<? extends T>> producer, int arraySize, String annotation) {
		this(name, null, (Supplier) null, producer, arraySize);
		setAnnotation(annotation);
	}

	public Variable(String name, Class<T> type, Supplier<String> expression, Supplier<Evaluable<? extends T>> producer, int arraySize) {
		this(name, true, new Expression(type, expression, arraySize), producer);
	}

	public void setName(String n) { this.name = n; }
	public String getName() { return this.name; }

	public void setAnnotation(String a) { this.annotation = a; }
	public String getAnnotation() { return this.annotation; }

	public void setDeclaration(boolean declaration) { this.declaration = declaration; }
	public boolean isDeclaration() { return declaration; }

	public void setExpression(Expression<T> value) { this.expression = value; }
	public Expression<T> getExpression() { return expression; }

	public void setSortHint(int hint) { this.sortHint = hint; }
	public int getSortHint() { return sortHint; }

	public void setProducer(Supplier<Evaluable<? extends T>> producer) {
		w: while (producer instanceof ProducerWithRank || producer instanceof GeneratedColorProducer) {
			if (producer instanceof ProducerWithRank) {
				if (((ProducerWithRank<T>) producer).getProducer() == producer) {
					break w;
				}

				producer = ((ProducerWithRank)  producer).getProducer();
			}

			if (producer instanceof GeneratedColorProducer) {
				producer = ((GeneratedColorProducer) producer).getProducer();
			}
		}

		if (producer instanceof Provider) {
			throw new IllegalArgumentException("Provider is Evaluable, it does not supply an Evaluable");
		}

		this.producer = producer;
	}

	public Supplier<Evaluable<? extends T>> getProducer() { return producer; }

	public Class<T> getType() { return getExpression() == null ? null : getExpression().getType(); }
	public Method<T> getGenerator() { return getExpression().getGenerator(); }
	public List<Variable<?>> getDependencies() {
		List<Variable<?>> deps = new ArrayList<>();
		if (dependsOn != null) deps.add(dependsOn);
		if (getExpression() != null) {
			deps.addAll(getExpression().getDependencies());
		}

		return deps;
	}

	public int getArraySize() { return getExpression().getArraySize(); }

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Variable == false) return false;

		Variable v = (Variable) obj;
		if (!Objects.equals(name, v.name)) return false;
		if (!Objects.equals(annotation, v.getAnnotation())) return false;
		if (!Objects.equals(expression, v.getExpression())) return false;
		if (!Objects.equals(producer, v.getProducer())) return false;
		if (!Objects.equals(dependsOn, v.dependsOn)) return false;

		return true;
	}

	@Override
	public int hashCode() { return name.hashCode(); }
}
