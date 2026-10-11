/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.TraversalAxisAlignment;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;

import java.util.List;

/**
 * The {@link TraversalAxisAlignment} of collection {@link Producer}s, which traverses each
 * producer to its aligned axis and repeats a smaller producer up to the aligned size.
 * A producer whose count is not fixed is only repeated when {@link #enableVariableRepeat}
 * is set.
 *
 * @author  Michael Murray
 * @see CollectionTraversalFeatures#alignTraversalAxes
 */
public class CollectionTraversalAxisAlignment extends TraversalAxisAlignment<Producer<PackedCollection>>
		implements CollectionFeatures {
	/** When true, producers with variable (non-fixed) counts are repeated like any other producer. */
	public static boolean enableVariableRepeat = false;

	/**
	 * Creates an alignment of the given producers.
	 *
	 * @param producers  the collection producers to align
	 */
	public CollectionTraversalAxisAlignment(List<Producer<PackedCollection>> producers) {
		super(producers);
	}

	@Override
	protected TraversalPolicy shapeOf(Producer<PackedCollection> producer) {
		return shape(producer);
	}

	@Override
	protected Producer<PackedCollection> alignAxis(int axis, Producer<PackedCollection> producer) {
		return traverse(axis, producer);
	}

	/**
	 * Repeats the given producer, unless its count is not fixed and
	 * {@link #enableVariableRepeat} is not set, in which case the producer is returned
	 * unchanged.
	 *
	 * @param repeat    the number of repetitions
	 * @param producer  the producer to expand
	 * @return the repeated producer, or {@code producer} itself if it cannot be repeated
	 */
	@Override
	protected Producer<PackedCollection> expand(int repeat, Producer<PackedCollection> producer) {
		if (enableVariableRepeat || Countable.isFixedCount(producer)) {
			return repeat(repeat, producer);
		}

		return producer;
	}
}
