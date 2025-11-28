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
import org.almostrealism.collect.PackedCollection;

import io.almostrealism.collect.IndexExpressionTraversalOrdering;

import java.util.OptionalInt;

/**
 * A traversal ordering based on explicit index values stored in a {@link PackedCollection}.
 *
 * <p>
 * {@link ExplicitIndexTraversalOrdering} defines a custom traversal order by using explicit
 * index values from a collection. This enables non-standard access patterns where elements
 * are accessed in a specific, pre-defined order rather than sequentially.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a collection with custom index ordering: [2, 0, 3, 1]
 * PackedCollection indices = new PackedCollection(4);
 * indices.setMem(0, 2.0);  // First access element 2
 * indices.setMem(1, 0.0);  // Then element 0
 * indices.setMem(2, 3.0);  // Then element 3
 * indices.setMem(3, 1.0);  // Finally element 1
 *
 * ExplicitIndexTraversalOrdering ordering = new ExplicitIndexTraversalOrdering(indices);
 * // Traversal will access elements in order: [2, 0, 3, 1]
 * }</pre>
 *
 * <h2>Length Calculation</h2>
 * <p>
 * The length of the traversal is determined by finding the maximum index value in the
 * index collection, enabling automatic sizing based on the actual indices used.
 * </p>
 *
 * @author  Michael Murray
 * @see IndexMaskTraversalOrdering
 * @see io.almostrealism.collect.TraversalOrdering
 */
public class ExplicitIndexTraversalOrdering extends IndexExpressionTraversalOrdering {
	/**
	 * Creates a new explicit index traversal ordering with the specified index collection.
	 *
	 * @param indices  collection containing the explicit index values defining the traversal order
	 */
	public ExplicitIndexTraversalOrdering(PackedCollection indices) {
		super(indices);
	}

	/**
	 * Returns the length of the traversal, computed as the maximum index value.
	 *
	 * <p>
	 * This method finds the maximum value in the index collection, which determines
	 * the extent of the traversal range. For example, if indices are [2, 0, 3, 1],
	 * the length is 3 (the maximum index).
	 * </p>
	 *
	 * @return the maximum index value as an OptionalInt
	 */
	@Override
	public OptionalInt getLength() {
		return ((PackedCollection) getIndices())
					.doubleStream().mapToInt(i -> (int) i).max();
	}
}
