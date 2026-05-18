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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

import java.util.OptionalInt;

/**
 * A {@link TraversalOrdering} that maps indices cyclically within a fixed period.
 *
 * <p>{@link #indexOf(Expression)} returns {@code idx % length}, so elements of
 * the underlying sequence are repeated as the logical index grows beyond the period.</p>
 */
public class RepeatTraversalOrdering implements TraversalOrdering {
	/** The repeat period (number of elements in one cycle). */
	private int length;

	/**
	 * Creates a repeating ordering with the given period.
	 *
	 * @param length the number of elements in one repeat cycle
	 */
	public RepeatTraversalOrdering(int length) {
		this.length = length;
	}

	/** {@inheritDoc} Returns {@code idx % length}. */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return idx.imod(length);
	}

	/** {@inheritDoc} Returns the repeat period. */
	@Override
	public OptionalInt getLength() {
		return OptionalInt.of(length);
	}
}
