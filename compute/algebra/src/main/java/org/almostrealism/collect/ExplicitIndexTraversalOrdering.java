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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.collect.IndexExpressionTraversalOrdering;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import org.almostrealism.collect.computations.CollectionProviderProducer;

import java.util.OptionalInt;

/**
 * A traversal ordering based on explicit index values stored in a {@link PackedCollection}.
 *
 * <p>
 * {@link ExplicitIndexTraversalOrdering} defines a custom traversal order by using explicit
 * index values from a collection. This enables non-standard access patterns where elements
 * are accessed in a specific, pre-defined order rather than sequentially. The collection is
 * a direct lookup table from logical index to physical position, and a negative entry marks
 * a logical index with no physical position (an absent element of a compact ordering), which
 * reads as zero.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a collection with custom index ordering: access element 2
 * // first, then 0, then 3, and finally element 1
 * PackedCollection indices = pack(2.0, 0.0, 3.0, 1.0);
 *
 * ExplicitIndexTraversalOrdering ordering = new ExplicitIndexTraversalOrdering(indices);
 * // Traversal will access elements in order: [2, 0, 3, 1]
 * }</pre>
 *
 * <h2>Generated code</h2>
 * <p>
 * Inside a kernel, the index collection is {@link #bind(ArgumentProvider) delivered as an
 * argument}, so the lookup renders as a read of device memory. The indices are stored as
 * floating point values, which a 32-bit backend represents exactly only up to 2^24.
 * </p>
 *
 * <h2>Length Calculation</h2>
 * <p>
 * The length of the traversal is the number of physical positions the indices address:
 * one more than the maximum index value.
 * </p>
 *
 * @author  Michael Murray
 * @see IndexMaskTraversalOrdering
 * @see io.almostrealism.collect.TraversalOrdering
 */
public class ExplicitIndexTraversalOrdering extends IndexExpressionTraversalOrdering {
	/** The collection of physical positions, one per logical index. */
	private final PackedCollection indices;

	/**
	 * Creates a new explicit index traversal ordering with the specified index collection.
	 *
	 * @param indices  collection containing the explicit index values defining the traversal order
	 */
	public ExplicitIndexTraversalOrdering(PackedCollection indices) {
		this(indices, indices);
	}

	/**
	 * Creates an ordering that looks logical indices up through the given expression.
	 *
	 * @param indices  the index collection
	 * @param lookup   the expression supplying the entries of the index collection, either
	 *                 the collection itself or the kernel argument it is delivered as
	 */
	private ExplicitIndexTraversalOrdering(PackedCollection indices, TraversableExpression<?> lookup) {
		super(lookup);
		this.indices = indices;
	}

	/**
	 * Returns the length of the traversal: the number of physical positions addressed,
	 * which is one more than the maximum index value.
	 *
	 * <p>
	 * For example, if indices are [2, 0, 3, 1], the length is 4, and if they are
	 * [0, -1, -1, 1], the length is 2. If no index addresses a physical position, the
	 * length is 0.
	 * </p>
	 *
	 * @return the number of physical positions as an OptionalInt
	 */
	@Override
	public OptionalInt getLength() {
		return OptionalInt.of(indices.doubleStream().mapToInt(i -> (int) i).max().orElse(-1) + 1);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns an ordering that looks each logical index up in the kernel argument the
	 * provider supplies for the index collection, whether or not this ordering was already
	 * bound to another kernel's arguments. The bound ordering reports the same
	 * {@link #getLength() length}.</p>
	 */
	@Override
	public TraversalOrdering bind(ArgumentProvider provider) {
		TraversableExpression<?> lookup = provider.traversableArgument(new CollectionProviderProducer<>(indices));
		return lookup == null ? this : new ExplicitIndexTraversalOrdering(indices, lookup);
	}
}
