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
 * A {@link TraversalOrdering} that maps every index to itself (identity ordering).
 *
 * <p>Optionally records a fixed element count. When a length is provided, callers
 * can query it via {@link #getLength()}; otherwise the length is reported as empty
 * (unbounded).</p>
 */
public class DefaultTraversalOrdering implements TraversalOrdering {
	/** The optional fixed element count for this ordering. */
	private OptionalInt length;

	/** Creates an unbounded identity ordering with no fixed element count. */
	public DefaultTraversalOrdering() {
		this.length = OptionalInt.empty();
	}

	/**
	 * Creates an identity ordering with the given fixed element count.
	 *
	 * @param length the number of elements in this ordering
	 */
	public DefaultTraversalOrdering(int length) {
		this.length = OptionalInt.of(length);
	}

	/** {@inheritDoc} Returns {@code idx} unchanged (identity mapping). */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return idx;
	}

	/** {@inheritDoc} */
	@Override
	public OptionalInt getLength() {
		return length;
	}
}
