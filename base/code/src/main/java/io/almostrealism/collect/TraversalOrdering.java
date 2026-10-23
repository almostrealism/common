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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.uml.Signature;

import java.util.OptionalInt;

/**
 * Defines a reordering of element indices for traversal.
 *
 * <p>A {@code TraversalOrdering} maps each logical output index to the actual position
 * within the underlying element sequence. It is used by {@link TraversalPolicy} to
 * transform indices during collection traversal, enabling patterns such as repetition,
 * permutation, or arbitrary index remapping.</p>
 *
 * <p>A logical index that has no physical position (an absent element of a compact or
 * sparse ordering) maps to a negative position, and is not a member of the ordering in
 * the sense of {@link #containsIndex(Expression)}. Readers of an ordered collection treat
 * such an element as zero, both on the host and in generated code.</p>
 *
 * <h2>Generated code</h2>
 * <p>An ordering whose {@link #indexOf(Expression)} is a closed-form function of the index
 * (such as {@link RepeatTraversalOrdering}) can appear in generated code as it is. An
 * ordering that reads a collection to map an index (a lookup table or a mask) must have that
 * collection delivered to the kernel as an argument; {@link #bind(ArgumentProvider)} returns
 * the equivalent ordering that reads the kernel argument instead.</p>
 */
public interface TraversalOrdering extends IndexSet, Signature {
	/**
	 * Maps a logical traversal index to the physical position in the element sequence.
	 *
	 * @param idx the logical traversal index expression
	 * @return an expression representing the physical position, which is negative when the
	 *         logical index has no physical position
	 */
	Expression<Integer> indexOf(Expression<Integer> idx);

	/**
	 * {@inheritDoc}
	 *
	 * <p>For an ordering, membership means that the logical index has a physical position.
	 * Returns {@code indexOf(index) >= 0}.</p>
	 */
	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return indexOf(index).greaterThanOrEqual(0);
	}

	/**
	 * Returns an ordering equivalent to this one that can appear in the generated code of a
	 * kernel whose arguments are obtained from the given {@link ArgumentProvider}.
	 *
	 * <p>An ordering that reads a collection in order to map an index obtains an argument for
	 * that collection from the provider, so that the collection is delivered to the kernel and
	 * {@link #indexOf(Expression)} renders as a read of it. The default returns this ordering,
	 * which is correct for an ordering whose mapping is a closed-form function of the index.</p>
	 *
	 * @param provider the provider of the kernel's arguments
	 * @return the equivalent ordering to use in generated code
	 */
	default TraversalOrdering bind(ArgumentProvider provider) {
		return this;
	}

	/**
	 * Returns a signature that identifies the structure of this ordering, so that two
	 * computations reading inputs with the same structure may share a compiled kernel.
	 *
	 * <p>The default returns {@code null}, which prevents the reuse of a compiled kernel. That
	 * is required of any ordering that reads a collection: the collection is delivered to the
	 * kernel as an argument with no position in the computation's process tree, so a reused
	 * kernel would read the collection of the computation it was compiled for.</p>
	 *
	 * @return the signature, or {@code null} if kernels reading this ordering must not be reused
	 */
	@Override
	default String signature() {
		return null;
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
	 * Returns the number of physical elements this ordering addresses, or empty if unbounded.
	 * This is the length of the backing memory the ordering reads from: every non-negative
	 * position returned by {@link #indexOf(Expression)} is less than it. It is used to size
	 * and bound-check memory, and is not the number of logical indices the ordering maps.
	 *
	 * @return the optional physical element count
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
