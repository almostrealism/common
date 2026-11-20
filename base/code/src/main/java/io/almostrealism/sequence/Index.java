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
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.uml.Named;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
	 * Returns the kernel index implied by this index taking the given value, if
	 * assigning this index determines the kernel index at all.
	 *
	 * <p>A {@link KernelIndex} implies itself. A {@link KernelIndexChild} implies the
	 * kernel index that owns the given child position. Any other index implies nothing,
	 * which is the default.</p>
	 *
	 * @param value the value assigned to this index
	 * @return the implied kernel index, or empty if this index does not determine it
	 */
	default OptionalLong impliedKernelIndex(long value) {
		return OptionalLong.empty();
	}

	/**
	 * Returns the number of consecutive values of this index that imply the same kernel
	 * index, if assigning this index determines the kernel index at all.
	 *
	 * <p>A {@link KernelIndex} implies a new kernel index at every value, so its
	 * granularity is 1. A {@link KernelIndexChild} implies one kernel index per run of
	 * child positions, so its granularity is the child index limit. Any other index
	 * implies nothing, which is the default.</p>
	 *
	 * @return the granularity, or empty if this index does not determine the kernel index
	 */
	default OptionalLong kernelIndexGranularity() {
		return OptionalLong.empty();
	}

	/**
	 * Returns the values of the given index required to produce every value of this index.
	 *
	 * <p>An index is opaque to this analysis: producing every value of it requires every
	 * one of its own values, from zero up to its limit, and no value of any other index
	 * (including the indices a composite {@link IndexChild} is built from). The result is
	 * empty when this index has no limit, or a limit above
	 * {@link ScopeSettings#indexOptionLimit}.</p>
	 *
	 * <p>The index expressions ({@link DefaultIndex}, {@link IndexChild}) override
	 * {@link io.almostrealism.expression.Expression#getIndexOptions(Index)} to answer with
	 * this method, since the class implementation would otherwise take precedence.</p>
	 *
	 * @param index the index whose required values are requested
	 * @return the required values of {@code index}, or empty if they cannot be enumerated
	 */
	default Optional<Set<Integer>> getIndexOptions(Index index) {
		if (!Objects.equals(this, index)) {
			return Optional.of(Collections.emptySet());
		}

		OptionalLong limit = getLimit();
		if (limit.isEmpty() || limit.getAsLong() > ScopeSettings.indexOptionLimit) {
			return Optional.empty();
		}

		return Optional.of(
				IntStream.range(0, Math.toIntExact(limit.getAsLong()))
					.boxed().collect(Collectors.toSet()));
	}

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
