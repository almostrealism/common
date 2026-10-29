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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

/**
 * The members of a {@link TraversalOrdering}, enumerated segment by segment: the logical
 * indices are divided into consecutive segments of equal length (the rows of a matrix, when the
 * segment length is its number of columns), and the members of each segment occupy a contiguous
 * run of physical positions.
 *
 * <p>A computation that needs only the members of an ordered collection, such as a reduction
 * of each row of a sparse matrix, visits them through this enumeration instead of reading every
 * logical index through {@link TraversalOrdering#indexOf(Expression)}. For segment {@code s} it
 * visits the positions {@code start(s) .. start(s) + length(s) - 1}; the member at position
 * {@code p} has logical index {@code s * segmentLength + offset(p)} and its value is element
 * {@code p} of the backing memory.</p>
 *
 * <p>The expressions are those of the ordering that produced the enumeration, so an enumeration
 * obtained from an ordering {@link TraversalOrdering#bind(io.almostrealism.code.ArgumentProvider)
 * bound} to the arguments of a kernel reads those arguments.</p>
 *
 * @see TraversalOrdering#segments(int)
 */
public interface OrderingSegments {
	/**
	 * Returns the physical position of the first member of the given segment.
	 *
	 * @param segment the segment
	 * @return the position of its first member
	 */
	Expression<Integer> start(Expression<?> segment);

	/**
	 * Returns the number of members of the given segment.
	 *
	 * @param segment the segment
	 * @return the number of members, which may be zero
	 */
	Expression<Integer> length(Expression<?> segment);

	/**
	 * Returns the logical offset, within its segment, of the member at the given physical
	 * position.
	 *
	 * @param position the physical position of a member
	 * @return the member's offset within its segment
	 */
	Expression<Integer> offset(Expression<?> position);
}
