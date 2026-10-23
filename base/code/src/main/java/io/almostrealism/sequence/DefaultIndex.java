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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
	/** Counter shared across all temporary indices to keep their names unique. */
	private static final AtomicLong temporaryCount = new AtomicLong();

	/**
	 * The exclusive upper bound of this index, or empty if unbounded. It is fixed at
	 * construction because a composite {@link Expression} memoizes its bounds (see
	 * {@link Expression#upperBound(KernelStructureContext)}): a limit that changed after an
	 * expression containing this index had been bounded would leave those bounds stale.
	 */
	private final OptionalLong limit;

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
	 * Creates a new unbounded temporary index with a unique name of the form "ci_N",
	 * for constructing intermediate expressions that require a fresh index variable.
	 *
	 * @return a new {@link DefaultIndex} with a unique name
	 */
	public static DefaultIndex temporary() {
		return new DefaultIndex("ci_" + temporaryCount.getAndIncrement());
	}

	/**
	 * Creates a new bounded temporary index with a unique name of the form "ci_N".
	 *
	 * @param limit the exclusive upper bound for valid index values
	 * @return a new {@link DefaultIndex} with a unique name and the specified limit
	 */
	public static DefaultIndex temporary(int limit) {
		return new DefaultIndex("ci_" + temporaryCount.getAndIncrement(), limit);
	}

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
	protected OptionalLong computeUpperBound(KernelStructureContext context) {
		return limit.stream().map(i -> i - 1).findFirst();
	}

	@Override
	protected boolean computePossiblyNegative() { return false; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>An index contains the given index when the two have the same name.</p>
	 */
	@Override
	public boolean containsIndex(Index idx) {
		return Objects.equals(getName(), idx.getName()) || super.containsIndex(idx);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An index is replaced by the given expression when the index being replaced
	 * has its name.</p>
	 */
	@Override
	public Expression<Integer> withIndex(Index index, Expression<?> e) {
		if (Objects.equals(getName(), index.getName())) return (Expression) e;
		return super.withIndex(index, e);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An index references itself.</p>
	 */
	@Override
	public Set<Index> getIndices() {
		return Set.of(this);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An index answers for itself, as described by {@link Index#getIndexOptions(Index)}.</p>
	 */
	@Override
	public Optional<Set<Integer>> getIndexOptions(Index index) {
		return Index.super.getIndexOptions(index);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An index is already the series of its own values, so it is never a target.</p>
	 */
	@Override
	public boolean isSeriesSimplificationTarget(int depth) {
		return false;
	}

	@Override
	public boolean isValue(IndexValues values) {
		return values.containsIndex(getName());
	}

	@Override
	public Number computeValue(IndexValues indexValues) {
		return indexValues.getIndex(getName());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An index takes the positions of a range that varies it, and has no value
	 * over a range that varies any other index.</p>
	 */
	@Override
	protected double[] computeValues(IndexRange range) {
		if (range.isRangeIndex(this)) return range.positions();

		throw new IllegalStateException(getName() + " is not a value over " + range.getIndex().getName());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The index varied by the range is the identity progression; any other index
	 * has no progression over it.</p>
	 */
	@Override
	public ArithmeticIndexSequence arithmeticSequence(Index index, long len) {
		return index.getName().equals(getName()) ? new ArithmeticIndexSequence(1, 1, len) : null;
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
