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

package org.almostrealism.collect;

import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;

import java.util.OptionalInt;

/**
 * A traversal ordering that maps logical indices to physical indices using a mask collection.
 *
 * <p>
 * {@link IndexMaskTraversalOrdering} provides indirect access to collection elements by using
 * a mask that defines which elements should be accessed. The mask contains physical indices,
 * and when a logical index is requested, the mask is searched to find the corresponding position.
 * </p>
 *
 * <h2>Purpose</h2>
 * <p>
 * This is useful for:
 * <ul>
 *   <li>Sparse data access - accessing only selected elements of a larger collection</li>
 *   <li>Filtered iteration - iterating over elements that match certain criteria</li>
 *   <li>Index remapping - translating between different index spaces</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Mask indicating we want elements at positions 5, 2, 8, 1
 * PackedCollection<?> mask = new PackedCollection<>(4);
 * mask.setMem(0, 5.0);
 * mask.setMem(1, 2.0);
 * mask.setMem(2, 8.0);
 * mask.setMem(3, 1.0);
 *
 * IndexMaskTraversalOrdering ordering = new IndexMaskTraversalOrdering(mask);
 *
 * // indexOf(5) returns 0 (element 5 is at position 0 in mask)
 * // indexOf(2) returns 1 (element 2 is at position 1 in mask)
 * // indexOf(7) returns -1 (element 7 not in mask)
 * }</pre>
 *
 * @author  Michael Murray
 * @see ExplicitIndexTraversalOrdering
 * @see io.almostrealism.collect.TraversalOrdering
 */
public class IndexMaskTraversalOrdering implements TraversalOrdering {
	private PackedCollection<?> mask;

	/**
	 * Creates a new index mask traversal ordering with the specified mask.
	 *
	 * @param mask  collection containing the physical indices to access
	 */
	public IndexMaskTraversalOrdering(PackedCollection<?> mask) {
		this.mask = mask;
	}

	/**
	 * Finds the position in the mask where the specified logical index appears.
	 *
	 * <p>
	 * This method searches through the mask to find which position contains the
	 * requested logical index. If the index is found, its position in the mask
	 * is returned. If not found, -1 is returned.
	 * </p>
	 *
	 * @param idx  the logical index to find
	 * @return the position in the mask where idx appears, or -1 if not found
	 */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		int i = idx.intValue().orElseThrow();
		double m[] = mask.toArray();

		for (int k = 0; k < m.length; k++) {
			if (((int) m[k]) == i) {
				return new IntegerConstant(k);
			}
		}

		return new IntegerConstant(-1);
	}

	/**
	 * Returns the length of the traversal, which equals the size of the mask.
	 *
	 * @return the mask length as an OptionalInt
	 */
	@Override
	public OptionalInt getLength() {
		return OptionalInt.of(mask.getMemLength());
	}
}
