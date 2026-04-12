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
import io.almostrealism.expression.IntegerConstant;

import java.util.OptionalInt;

/**
 * Defines a reordering of element indices for traversal.
 *
 * <p>A {@code TraversalOrdering} maps each logical output index to the actual position
 * within the underlying element sequence. It is used by {@link TraversalPolicy} to
 * transform indices during collection traversal, enabling patterns such as repetition,
 * permutation, or arbitrary index remapping.</p>
 */
public interface TraversalOrdering extends IndexSet {
	/**
	 * Maps a logical traversal index to the physical position in the element sequence.
	 *
	 * @param idx the logical traversal index expression
	 * @return an expression representing the physical position
	 */
	Expression<Integer> indexOf(Expression<Integer> idx);

	/** {@inheritDoc} Returns {@code indexOf(index) >= 0}. */
	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return indexOf(index).greaterThanOrEqual(0);
	}

	/**
	 * Maps a concrete integer logical index to its physical position.
	 *
	 * @param idx the logical traversal index
	 * @return the physical position
	 * @throws java.util.NoSuchElementException if the index cannot be evaluated at compile time
	 */
	default int indexOf(int idx) {
		return indexOf(new IntegerConstant(idx)).intValue().orElseThrow();
	}

	/**
	 * Returns the number of elements covered by this ordering, or empty if unbounded.
	 *
	 * @return the optional element count
	 */
	default OptionalInt getLength() {
		return OptionalInt.empty();
	}

	/**
	 * Returns a composed ordering that first applies this ordering, then the given ordering.
	 *
	 * <p>If {@code other} is {@code null}, returns {@code this} unchanged.</p>
	 *
	 * @param other the ordering to apply after this one, or {@code null}
	 * @return the composed ordering
	 */
	default TraversalOrdering compose(TraversalOrdering other) {
		if (other == null) {
			return this;
		} else {
			return new DelegatedTraversalOrdering(this, other);
		}
	}
}
