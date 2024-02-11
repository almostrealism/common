/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.scope;

import io.almostrealism.code.PhysicalScope;
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Generated;
import io.almostrealism.uml.Nameable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import io.almostrealism.relation.Provider;
import io.almostrealism.relation.Sortable;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T, V extends Variable<T, ?>> implements Nameable, Sortable, Delegated<V>, ConsoleFeatures {
	private String name;
	private PhysicalScope physicalScope;
	private int sortHint;

	private Expression<T> expression;

	private Supplier<Evaluable<? extends T>> originalProducer;
	private Supplier<Evaluable<? extends T>> producer;

	private V delegate;

	public Variable(String name) {
		this(name, null, null, null);
	}

	public Variable(String name, PhysicalScope scope, Expression<T> expression, Supplier<Evaluable<? extends T>> producer) {
		setName(name);
		setPhysicalScope(scope);
		setExpression(expression);
		setProducer(producer);
	}

	@Override
	public void setName(String n) { this.name = n; }

	@Override
	public String getName() {
		return this.name;
	}

	public InstanceReference<?> ref() {
		return new InstanceReference<>(this);
	}

	public void setPhysicalScope(PhysicalScope physicalScope) { this.physicalScope = physicalScope; }
	public PhysicalScope getPhysicalScope() { return physicalScope; }

	@Override
	public V getDelegate() {
		if (delegate != null) {
			return delegate;
		}

		return null;
	}

	public void setDelegate(V delegate) { this.delegate = delegate; }

	@Deprecated
	public void setExpression(Expression<T> value) { this.expression = value; }

	@Deprecated
	public Expression<T> getExpression() { return expression; }

	@Deprecated
	public void setSortHint(int hint) { this.sortHint = hint; }

	@Override
	public int getSortHint() { return sortHint; }

	public Expression<Integer> getArraySize() {
		if (getExpression() == null) return null;
		if (getExpression().getArraySize() <= 0) return null;
		return new IntegerConstant(getExpression().getArraySize());
	}

	private void setProducer(Supplier<Evaluable<? extends T>> producer) {
		this.originalProducer = producer;

		w: while (producer instanceof ProducerWithRank || producer instanceof Generated) {
			if (producer instanceof ProducerWithRank) {
				if (((ProducerWithRank<T, ?>) producer).getProducer() == producer) {
					break w;
				}

				producer = ((ProducerWithRank) producer).getProducer();
			}

			if (producer instanceof Generated) {
				producer = (Producer) ((Generated) producer).getGenerated();
			}
		}

		if (producer instanceof Provider) {
			throw new IllegalArgumentException("Provider is Evaluable, it does not supply an Evaluable");
		}

		if (producer != originalProducer) {
			warn("Producer for " + getName() + " changed from " + originalProducer + " to " + producer);
		}

		this.producer = producer;
	}

	public Supplier<Evaluable<? extends T>> getProducer() { return producer; }

	@Deprecated
	public Supplier<Evaluable<? extends T>> getOriginalProducer() { return originalProducer; }

	public Class<T> getType() {
		if (getDelegate() != null && getDelegate().getType() != null) return getDelegate().getType();
		return getExpression() == null ? null : getExpression().getType();
	}

	public List<Variable<?, ?>> getDependencies() {
		List<Variable<?, ?>> deps = new ArrayList<>();
		if (delegate != null) deps.add(delegate);
		deps.addAll(getExpressionDependencies());
		return deps;
	}

	protected List<Variable<?, ?>> getExpressionDependencies() {
		return Optional.ofNullable(getExpression()).map(Expression::getDependencies).orElse(Collections.emptyList());
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Variable)) return false;

		Variable v = (Variable) obj;
		if (!Objects.equals(name, v.name)) return false;
		if (!Objects.equals(physicalScope, v.getPhysicalScope())) return false;
		if (!Objects.equals(expression, v.expression)) return false;
		if (!Objects.equals(producer, v.getProducer())) return false;
		if (!Objects.equals(delegate, v.getDelegate())) return false;

		return true;
	}

	@Override
	public int hashCode() { return name.hashCode(); }

	public static Variable<Integer, ?> integer(String name) {
		return new Variable<>(name, null, new Constant<>(Integer.class), null);
	}
}
