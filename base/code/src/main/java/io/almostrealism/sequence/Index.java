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

package io.almostrealism.sequence;

import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelIndexChild;
import io.almostrealism.uml.Named;

import java.util.OptionalLong;

/**
 * A named, bounded sequence generator that represents an index variable in a kernel computation.
 *
 * <p>An {@code Index} is the fundamental building block for describing iteration dimensions in
 * kernel computations. It combines {@link SequenceGenerator} (for producing {@link IndexSequence}
 * values) with {@link Named} (for identifier-based lookup). Concrete subtypes include
 * {@link io.almostrealism.kernel.KernelIndex} (the GPU thread ID) and {@link DefaultIndex}
 * (an arbitrary named index with an optional limit).</p>
 *
 * @see SequenceGenerator
 * @see DefaultIndex
 * @see IndexChild
 * @see io.almostrealism.kernel.KernelIndex
 */
public interface Index extends SequenceGenerator, Named {

	/**
	 * Creates a composite child index from the given parent and child indices.
	 *
	 * <p>The child index encodes a multi-dimensional position as a flat index using
	 * {@code parent * childLimit + child}.
	 *
	 * @param parent the parent (outer) index
	 * @param child the child (inner) index
	 * @return a new {@link IndexChild} combining the two indices
	 */
	static IndexChild child(Index parent, Index child) {
		return child(parent, child, null);
	}

	/**
	 * Creates a composite child index from the given parent and child indices,
	 * returning {@code null} if the resulting limit exceeds the given maximum.
	 *
	 * @param parent the parent (outer) index
	 * @param child the child (inner) index
	 * @param limitMax the maximum allowed limit (exclusive), or {@code null} for no limit check
	 * @return a new {@link IndexChild} if within the limit, or {@code null} if the limit is exceeded
	 */
	static IndexChild child(Index parent, Index child, Long limitMax) {
		IndexChild result;

		if (parent instanceof KernelIndex) {
			result = new KernelIndexChild(((KernelIndex) parent).getStructureContext(), child);
		} else {
			result = new IndexChild(parent, child);
		}

		if (limitMax != null) {
			OptionalLong limit = result.getLimit();

			if (limit.isEmpty() || limit.getAsLong() > limitMax) {
				return null;
			}
		}

		return result;
	}
}
