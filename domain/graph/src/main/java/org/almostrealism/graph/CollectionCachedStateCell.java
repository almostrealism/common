/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A {@link CachedStateCell} specialised for {@link PackedCollection} data.
 *
 * <p>{@code CollectionCachedStateCell} provides a concrete implementation of
 * {@link CachedStateCell} that uses standard assignment ({@code a(1, ...)}) for
 * copying values and resets values to zero. It is the base class for common
 * collection-based cells such as {@link SummationCell} and {@link RunningAverageCell}.</p>
 *
 * @see CachedStateCell
 * @see SummationCell
 * @author Michael Murray
 */
public class CollectionCachedStateCell extends CachedStateCell<PackedCollection> implements CodeFeatures {
	/**
	 * Creates a new CollectionCachedStateCell with a single-element blank collection
	 * for both the cached and output values.
	 */
	public CollectionCachedStateCell() {
		super(PackedCollection.blank(1).get());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Assigns {@code in} to {@code out} using a single-element assignment operation.</p>
	 *
	 * @param out the destination producer
	 * @param in  the source producer
	 * @return an operation that performs the assignment
	 */
	@Override
	protected Supplier<Runnable> assign(Producer<PackedCollection> out,
										Producer<PackedCollection> in) {
		return a(1, out, in);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets {@code out} to zero using a single-element assignment of {@code c(0)}.</p>
	 *
	 * @param out the producer to reset to zero
	 * @return an operation that performs the reset
	 */
	@Override
	public Supplier<Runnable> reset(Producer<PackedCollection> out) {
		return a(1, out, c(0));
	}
}
