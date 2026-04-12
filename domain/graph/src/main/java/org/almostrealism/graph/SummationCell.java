/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A cell that accumulates incoming values by summing them into its cache.
 *
 * <p>{@code SummationCell} overrides the push operation to add each incoming
 * value to the cached value rather than replacing it. This makes it the standard
 * accumulator used at the receiving end of multiple concurrent push operations,
 * such as when multiple upstream cells contribute to a single downstream value.</p>
 *
 * <p>On each tick (via {@link CachedStateCell#tick()}), the accumulated sum is
 * forwarded downstream and the cache is reset to zero.</p>
 *
 * <p>Note: {@link CachedStateCell#tick()} contains an optimized path specifically
 * for SummationCell that avoids the intermediate outValue copy.</p>
 *
 * @see CollectionCachedStateCell
 * @see CachedStateCell
 * @author Michael Murray
 */
public class SummationCell extends CollectionCachedStateCell {
	/**
	 * {@inheritDoc}
	 *
	 * <p>Adds the incoming value to the cached sum using an accelerated assignment.</p>
	 *
	 * @param protein the value to add to the accumulated sum (must not be null)
	 * @return an operation that performs the accumulation
	 * @throws NullPointerException if protein is null
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		if (protein == null) throw new NullPointerException();
		return a(1, p(getCachedValue()), add(p(getCachedValue()), protein));
	}
}
