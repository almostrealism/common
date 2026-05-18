/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.sequence;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * A named {@link Index} with an optional numeric limit (exclusive upper bound).
 *
 * <p>{@code DefaultIndex} is a concrete index variable that can represent any iteration
 * dimension in a kernel computation. It extends {@link StaticReference} so it can be used
 * directly as an {@link io.almostrealism.expression.Expression} in generated code.</p>
 *
 * <p>The limit (if set) defines the exclusive upper bound of valid index values.
 * The {@link #upperBound} is {@code limit - 1} when the limit is present.</p>
 *
 * @see Index
 * @see IndexChild
 * @see io.almostrealism.kernel.KernelIndex
 */
public class DefaultIndex extends StaticReference<Integer> implements Index {
	/** The exclusive upper bound of this index, or empty if unbounded. */
	private OptionalLong limit;

	/**
	 * Creates an unbounded index with the given name.
	 *
	 * @param name the index variable name
	 */
	public DefaultIndex(String name) {
		this(name, null);
	}

	/**
	 * Creates a bounded index with the given name and integer limit.
	 *
	 * @param name the index variable name
	 * @param limit the exclusive upper bound (number of valid values)
	 */
	public DefaultIndex(String name, int limit) {
		this(name, (long) limit);
	}

	/**
	 * Creates a bounded index with the given name and long limit, or an unbounded index if limit is {@code null}.
	 *
	 * @param name the index variable name
	 * @param limit the exclusive upper bound, or {@code null} for no limit
	 */
	public DefaultIndex(String name, Long limit) {
		super(Integer.class, name);
		this.limit = limit == null ? OptionalLong.empty() : OptionalLong.of(limit);
	}

	/**
	 * Sets the limit (exclusive upper bound) for this index.
	 *
	 * @param limit the new limit as an {@code int}
	 */
	public void setLimit(int limit) { this.limit = OptionalLong.of(limit); }

	/**
	 * Sets the limit (exclusive upper bound) for this index.
	 *
	 * @param limit the new limit as a {@code long}
	 */
	public void setLimit(long limit) { this.limit = OptionalLong.of(limit); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the configured limit if present; otherwise falls back to computing it from
	 * the upper bound.
	 */
	@Override
	public OptionalLong getLimit() {
		if (limit.isPresent()) {
			return limit;
		}

		OptionalLong upperBound = upperBound(null);
		if (upperBound.isPresent()) {
			return OptionalLong.of(upperBound.getAsLong() + 1);
		}

		return OptionalLong.empty();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return limit.stream().map(i -> i - 1).findFirst();
	}

	@Override
	public boolean isPossiblyNegative() { return false; }

	@Override
	public boolean isValue(IndexValues values) {
		return values.containsIndex(getName());
	}

	@Override
	public Number value(IndexValues indexValues) {
		return indexValues.getIndex(getName());
	}

	/**
	 * Returns a new {@code DefaultIndex} with the same name but the given limit.
	 *
	 * @param limit the new exclusive upper bound
	 * @return a new bounded index
	 */
	public DefaultIndex withLimit(long limit) {
		return new DefaultIndex(getName(), limit);
	}

	@Override
	public ExpressionAssignment<Integer> assign(Expression exp) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean compare(Expression e) {
		return e instanceof DefaultIndex && Objects.equals(((DefaultIndex) e).getName(), getName());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getName());
	}
}
