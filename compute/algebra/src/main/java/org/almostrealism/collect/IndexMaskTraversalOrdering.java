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
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import org.almostrealism.collect.computations.CollectionProviderProducer;

import java.util.OptionalInt;

/**
 * A traversal ordering that maps logical indices to physical indices using a mask collection.
 *
 * <p>
 * {@link IndexMaskTraversalOrdering} provides indirect access to collection elements by using
 * a mask that defines which elements should be accessed. The mask contains logical indices,
 * and when a logical index is requested, the mask is searched to find the corresponding
 * physical position.
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
 * PackedCollection mask = pack(5.0, 2.0, 8.0, 1.0);
 *
 * IndexMaskTraversalOrdering ordering = new IndexMaskTraversalOrdering(mask);
 *
 * // indexOf(5) returns 0 (element 5 is at position 0 in mask)
 * // indexOf(2) returns 1 (element 2 is at position 1 in mask)
 * // indexOf(7) returns -1 (element 7 not in mask)
 * }</pre>
 *
 * <h2>Generated code</h2>
 * <p>
 * The search is expressed symbolically, as a chain of comparisons against the entries of the
 * mask, so it can appear in generated code with the mask {@link #bind(ArgumentProvider)
 * delivered to the kernel as an argument}. The search has no closed form, so each lookup costs
 * one comparison per mask entry: this suits small masks, not a large sparse structure.
 * </p>
 *
 * @author  Michael Murray
 * @see ExplicitIndexTraversalOrdering
 * @see io.almostrealism.collect.TraversalOrdering
 */
public class IndexMaskTraversalOrdering implements TraversalOrdering {
	/** The collection of logical indices that defines this traversal ordering. */
	private final PackedCollection mask;

	/** The expression through which the entries of the mask are read. */
	private final TraversableExpression<?> entries;

	/**
	 * Creates a new index mask traversal ordering with the specified mask.
	 *
	 * @param mask  collection containing the logical indices that are present
	 */
	public IndexMaskTraversalOrdering(PackedCollection mask) {
		this(mask, mask);
	}

	/**
	 * Creates an ordering that reads the entries of a mask through the given expression.
	 *
	 * @param mask     the mask collection
	 * @param entries  the expression supplying the entries of the mask, either the mask
	 *                 itself or the kernel argument it is delivered as
	 */
	private IndexMaskTraversalOrdering(PackedCollection mask, TraversableExpression<?> entries) {
		this.mask = mask;
		this.entries = entries;
	}

	/**
	 * Finds the position in the mask where the specified logical index appears.
	 *
	 * <p>
	 * The search is a chain of conditionals, one per mask entry, that yields the position
	 * of the first entry equal to the index, or -1 if there is none. When the index and
	 * the entries are known constants, as they are on the host, the chain folds to the
	 * constant position.
	 * </p>
	 *
	 * @param idx  the logical index to find
	 * @return the position in the mask where idx appears, or -1 if not found
	 */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		Expression<Integer> position = new IntegerConstant(-1);

		for (int k = mask.getMemLength() - 1; k >= 0; k--) {
			Expression<Boolean> found = entries.getValueAt(new IntegerConstant(k)).toInt().eq(idx);
			position = Conditional.of(found, new IntegerConstant(k), position);
		}

		return position;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns an ordering that searches the kernel argument the provider supplies for the
	 * mask, whether or not this ordering was already bound to another kernel's arguments.</p>
	 */
	@Override
	public TraversalOrdering bind(ArgumentProvider provider) {
		TraversableExpression<?> bound = provider.traversableArgument(new CollectionProviderProducer<>(mask));
		return bound == null ? this : new IndexMaskTraversalOrdering(mask, bound);
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
