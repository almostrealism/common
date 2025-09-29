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

import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Nameable;
import io.almostrealism.relation.Sortable;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * @param <T>  Type of the underlying data.
 */
public class Variable<T, V extends Variable<T, ?>>
		implements Nameable, Sortable, Delegated<V>, Describable, ConsoleFeatures {
	private String name;
	private PhysicalScope physicalScope;
	private int sortHint;

	private Class<?> type;
	private Supplier<Evaluable<? extends T>> producer;

	private V delegate;

	public Variable(String name) {
		this(name, null, null, null);
	}

	public Variable(String name, Class<?> type) {
		this(name, null, type, null);
	}

	public Variable(String name, PhysicalScope scope,
					Class<?> type,
					Supplier<Evaluable<? extends T>> producer) {
		setName(name);
		setPhysicalScope(scope);
		setType(type);
		setProducer(producer);
	}

	@Override
	public void setName(String n) { this.name = n; }

	@Override
	public String getName() {
		return this.name;
	}

	public InstanceReference<?, ?> ref() {
		if (getDelegate() == null) {
			return new InstanceReference<>(this);
		} else {
			return getDelegate().ref();
		}
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
	public void setSortHint(int hint) { this.sortHint = hint; }

	@Override
	public int getSortHint() { return sortHint; }

	private void setProducer(Supplier<Evaluable<? extends T>> producer) {
		this.producer = producer;
	}

	public Supplier<Evaluable<? extends T>> getProducer() { return producer; }

	private void setType(Class<?> type) {
		this.type = type;
	}

	public Class<?> getType() {
		if (getDelegate() != null && getDelegate().getType() != null) return getDelegate().getType();
		return type;
	}

	public List<Variable<?, ?>> getDependencies() {
		List<Variable<?, ?>> deps = new ArrayList<>();
		if (delegate != null) deps.add(delegate);
		return deps;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Variable)) return false;

		Variable v = (Variable) obj;
		if (!Objects.equals(name, v.name)) return false;
		if (!Objects.equals(physicalScope, v.getPhysicalScope())) return false;
		if (!Objects.equals(type, v.type)) return false;
		if (!Objects.equals(producer, v.getProducer())) return false;
		if (!Objects.equals(delegate, v.getDelegate())) return false;

		return true;
	}

	@Override
	public int hashCode() { return delegate == null ? name.hashCode() : delegate.hashCode(); }

	@Override
	public String describe() {
		Supplier<?> p = getProducer();

		if (p instanceof Describable) {
			return getName() + " " + ((Describable) p).describe();
		}

		return getClass().getSimpleName() + " " + getName();
	}

	public static Variable<Integer, ?> integer(String name) {
		return new Variable<>(name, null, Integer.class, null);
	}
}
