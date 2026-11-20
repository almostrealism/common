/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.expression.Expression;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * An immutable {@link Set} of the integers {@code start + k * step} for every {@code k} from
 * zero to {@code count - 1}, in which the values of an {@link Index} that an expression
 * requires are described ({@link Expression#getIndexOptions(Index)}).
 *
 * <p>Every value of an index below its limit, every value below a modulus and every multiple
 * of a divisor below a limit is a progression of this kind. Only its three parameters are
 * stored, so {@link #size()}, {@link #contains(Object)} and, against another progression,
 * {@link #containsAll(Collection)} take constant time however many values the progression
 * has: the domain of an index with thousands of values is described without creating
 * thousands of boxed integers, and an expression whose children require the same values
 * shares their set instead of copying it. The values are iterated in ascending order.</p>
 *
 * @see Expression#getIndexOptions(Index)
 * @see Index#getIndexOptions(Index)
 */
public final class ArithmeticIndexSet extends AbstractSet<Integer> {
	/** The smallest value of the progression. */
	private final int start;

	/** The positive difference between consecutive values. */
	private final int step;

	/** The number of values, which is zero for the empty progression. */
	private final int count;

	/**
	 * Creates the ascending progression of {@code count} values beginning at {@code start},
	 * whose last value does not exceed {@link Integer#MAX_VALUE}.
	 *
	 * @param start the smallest value
	 * @param step  the positive difference between consecutive values
	 * @param count the number of values, zero or more
	 */
	private ArithmeticIndexSet(int start, int step, int count) {
		this.start = start;
		this.step = step;
		this.count = count;
	}

	/**
	 * Returns the set of every value from zero to {@code count - 1}.
	 *
	 * @param count the number of values; zero or less yields the empty set
	 * @return the values {@code 0} to {@code count - 1}
	 */
	public static ArithmeticIndexSet range(int count) {
		return of(0, 1, count);
	}

	/**
	 * Returns the set of the values {@code start + k * step} for every {@code k} from zero to
	 * {@code count - 1}.
	 *
	 * <p>A set has no order, so a descending progression is the ascending one that ends at
	 * {@code start}, and a progression whose step is zero, or that has a single value, holds
	 * only {@code start}.</p>
	 *
	 * @param start the first value
	 * @param step  the difference between consecutive values
	 * @param count the number of values; zero or less yields the empty set
	 * @return the values of the progression
	 * @throws ArithmeticException if a value of the progression, or the difference between
	 *                             consecutive values, is not an {@code int}
	 */
	public static ArithmeticIndexSet of(int start, int step, int count) {
		if (count <= 0) return new ArithmeticIndexSet(0, 1, 0);
		if (count == 1 || step == 0) return new ArithmeticIndexSet(start, 1, 1);

		long end = start + (long) (count - 1) * step;
		long first = Math.min(start, end);
		long last = Math.max(start, end);

		if (first < Integer.MIN_VALUE || last > Integer.MAX_VALUE) {
			throw new ArithmeticException("The progression from " + start + " by " + step +
					" to " + end + " has values that are not int values");
		}

		return new ArithmeticIndexSet((int) first, Math.absExact(step), count);
	}

	/** Returns the largest value of the progression, which must not be empty. */
	private int last() {
		return start + (count - 1) * step;
	}

	/**
	 * Returns whether every value of this progression is a value of the given one.
	 *
	 * <p>That is so exactly when its first, second and last values are: the given progression
	 * holds the first two only if the difference between them is a multiple of its own step,
	 * and then every value from the first to the last lies in it as long as the last does.</p>
	 *
	 * @param progression the progression that may hold this one
	 * @return {@code true} if every value of this progression is in {@code progression}
	 */
	private boolean isWithin(ArithmeticIndexSet progression) {
		if (count == 0) return true;
		if (!progression.contains(start)) return false;
		return count == 1 || (progression.contains(start + step) && progression.contains(last()));
	}

	@Override
	public int size() {
		return count;
	}

	@Override
	public boolean contains(Object o) {
		if (!(o instanceof Integer)) return false;

		long offset = (long) (Integer) o - start;
		return offset >= 0 && offset % step == 0 && offset / step < count;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The {@link Set} interface cannot describe a progression, so a progression given here is
	 * recognised as one and asked whether it lies within this one, which takes constant time.
	 * Any other collection is checked element by element.</p>
	 */
	@Override
	public boolean containsAll(Collection<?> c) {
		if (c instanceof ArithmeticIndexSet progression) return progression.isWithin(this);
		return super.containsAll(c);
	}

	@Override
	public Iterator<Integer> iterator() {
		return new Iterator<>() {
			/** The position of the next value in the progression. */
			private int k;

			@Override
			public boolean hasNext() {
				return k < count;
			}

			@Override
			public Integer next() {
				if (k >= count) throw new NoSuchElementException();
				return start + k++ * step;
			}
		};
	}
}
