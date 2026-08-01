/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware;

/**
 * Implemented by computations and operations whose small input arguments may be folded into a single
 * aggregate kernel argument (keeping the kernel's argument count under the compute context's limit).
 *
 * <p>Argument aggregation is supported by default; an implementor whose arguments must not be
 * aggregated &mdash; for example a pure memory-to-memory copy, which has only two arguments and would
 * otherwise fold its own operands into a nested aggregate &mdash; overrides
 * {@link #isArgumentAggregationSupported()} to return {@code false}. This is a capability interface in
 * the same style as {@link io.almostrealism.relation.Countable}: the machinery that prepares kernel
 * arguments asks any object whether it supports aggregation via {@link #isArgumentAggregationSupported(Object)}
 * rather than testing for a specific concrete type.</p>
 *
 * @see org.almostrealism.hardware.mem.MemoryDataArgumentMap
 */
public interface Aggregatable {
	/**
	 * Returns whether this object's arguments may be folded into an aggregate kernel argument.
	 *
	 * <p>The default implementation returns {@code true}.</p>
	 *
	 * @return {@code true} if argument aggregation is permitted
	 */
	default boolean isArgumentAggregationSupported() {
		return true;
	}

	/**
	 * Returns whether the given object supports argument aggregation.
	 *
	 * <p>If the object implements {@link Aggregatable}, delegates to its
	 * {@link #isArgumentAggregationSupported()} method. Otherwise returns {@code true} (objects that
	 * express no opinion are aggregated normally).</p>
	 *
	 * @param o the object to check
	 * @return {@code true} if the object supports argument aggregation
	 */
	static boolean isArgumentAggregationSupported(Object o) {
		if (o instanceof Aggregatable) {
			return ((Aggregatable) o).isArgumentAggregationSupported();
		}

		return true;
	}
}
