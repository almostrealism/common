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
import io.almostrealism.relation.Sortable;
import io.almostrealism.uml.Nameable;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link Variable} wraps some data that can be included in a {@link Scope}.
 *
 * <p>Variables represent named storage locations within a computation scope,
 * holding data of a specific type. They support delegation to other variables,
 * enabling aliasing and reference tracking in code generation.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Named storage with optional type information</li>
 *   <li>Physical scope assignment for memory allocation strategies</li>
 *   <li>Delegation support for variable aliasing</li>
 *   <li>Producer functions for lazy value computation</li>
 *   <li>Sort hints for ordering during code generation</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Variable<Double, ?> x = new Variable<>("x", Double.class);
 * Variable<Integer, ?> count = Variable.integer("count");
 *
 * // Create an instance reference for code generation
 * InstanceReference<?, ?> ref = x.ref();
 * }</pre>
 *
 * @param <T> the type of the underlying data this variable represents
 * @param <V> the self-referential type for delegation (typically the same as the implementing class)
 *
 * @see Scope
 * @see PhysicalScope
 * @see InstanceReference
 *
 * @author Michael Murray
 */
public class Variable<T, V extends Variable<T, ?>>
		implements Nameable, Sortable, Delegated<V>, Describable, ConsoleFeatures {
	private String name;
	private PhysicalScope physicalScope;
	private int sortHint;

	private Class<?> type;
	private Supplier<Evaluable<? extends T>> producer;

	private V delegate;

	/**
	 * Creates a new variable with the specified name.
	 *
	 * @param name the name of the variable
	 */
	public Variable(String name) {
		this(name, null, null, null);
	}

	/**
	 * Creates a new variable with the specified name and type.
	 *
	 * @param name the name of the variable
	 * @param type the Java class representing the variable's data type
	 */
	public Variable(String name, Class<?> type) {
		this(name, null, type, null);
	}

	/**
	 * Creates a new variable with full specification.
	 *
	 * @param name     the name of the variable
	 * @param scope    the physical scope for memory allocation, or {@code null}
	 * @param type     the Java class representing the variable's data type, or {@code null}
	 * @param producer a supplier that can produce evaluable values for this variable, or {@code null}
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * @return the name of this variable
	 */
	@Override
	public String getName() {
		return this.name;
	}

	/**
	 * Creates an {@link InstanceReference} pointing to this variable.
	 *
	 * <p>If this variable has a delegate, the reference will point to
	 * the delegate instead, ensuring proper aliasing behavior.</p>
	 *
	 * @return an instance reference for use in expressions and code generation
	 */
	public InstanceReference<?, ?> ref() {
		if (getDelegate() == null) {
			return new InstanceReference<>(this);
		} else {
			return getDelegate().ref();
		}
	}

	/**
	 * Sets the physical scope for this variable.
	 *
	 * @param physicalScope the physical scope defining memory allocation strategy
	 */
	public void setPhysicalScope(PhysicalScope physicalScope) { this.physicalScope = physicalScope; }

	/**
	 * Returns the physical scope for this variable.
	 *
	 * @return the physical scope, or {@code null} if not set
	 */
	public PhysicalScope getPhysicalScope() { return physicalScope; }

	/**
	 * {@inheritDoc}
	 *
	 * @return the delegate variable, or {@code null} if this variable has no delegate
	 */
	@Override
	public V getDelegate() {
		if (delegate != null) {
			return delegate;
		}

		return null;
	}

	/**
	 * Sets the delegate variable for aliasing.
	 *
	 * @param delegate the variable to delegate to, or {@code null} to remove delegation
	 */
	public void setDelegate(V delegate) { this.delegate = delegate; }

	/**
	 * Sets a sort hint for ordering this variable during code generation.
	 *
	 * @param hint the sort hint value (lower values sort first)
	 * @deprecated Use explicit ordering mechanisms instead
	 */
	@Deprecated
	public void setSortHint(int hint) { this.sortHint = hint; }

	/**
	 * {@inheritDoc}
	 *
	 * @return the sort hint value
	 */
	@Override
	public int getSortHint() { return sortHint; }

	private void setProducer(Supplier<Evaluable<? extends T>> producer) {
		this.producer = producer;
	}

	/**
	 * Returns the producer function for this variable.
	 *
	 * @return the supplier that can produce evaluable values, or {@code null}
	 */
	public Supplier<Evaluable<? extends T>> getProducer() { return producer; }

	private void setType(Class<?> type) {
		this.type = type;
	}

	/**
	 * Returns the type of data this variable holds.
	 *
	 * <p>If this variable has a delegate with a defined type, the delegate's
	 * type is returned instead.</p>
	 *
	 * @return the Java class representing the data type, or {@code null} if unknown
	 */
	public Class<?> getType() {
		if (getDelegate() != null && getDelegate().getType() != null) return getDelegate().getType();
		return type;
	}

	/**
	 * Returns a list of variables that this variable depends on.
	 *
	 * <p>Currently, this only includes the delegate variable if one exists.</p>
	 *
	 * @return a mutable list of dependency variables
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>If the producer implements {@link Describable}, includes its description.
	 * Otherwise, returns a simple representation with the class name and variable name.</p>
	 *
	 * @return a human-readable description of this variable
	 */
	@Override
	public String describe() {
		Supplier<?> p = getProducer();

		if (p instanceof Describable) {
			return getName() + " " + ((Describable) p).describe();
		}

		return getClass().getSimpleName() + " " + getName();
	}

	/**
	 * Creates a new integer variable with the specified name.
	 *
	 * <p>This is a convenience factory method for creating variables
	 * with {@link Integer} type.</p>
	 *
	 * @param name the name of the variable
	 * @return a new integer variable
	 */
	public static Variable<Integer, ?> integer(String name) {
		return new Variable<>(name, null, Integer.class, null);
	}
}
