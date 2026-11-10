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
 * A producer whose shape has a variable count is only repeated when
 * {@link #enableVariableRepeat} is set.
 *
 * @author  Michael Murray
 * @see CollectionTraversalFeatures#alignTraversalAxes
 */
public class CollectionTraversalAxisAlignment extends TraversalAxisAlignment<Producer<PackedCollection>>
		implements CollectionFeatures {
	/** When true, producers whose shapes have variable (non-fixed) counts are repeated like any other producer. */
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
	 * Repeats the given producer, unless its shape has a variable count and
	 * {@link #enableVariableRepeat} is not set, in which case the producer is returned
	 * unchanged.
	 *
	 * <p>A producer whose own shape is fixed is repeated even when its count is variable
	 * because it depends on a variable-count input. Each item of such a kernel is one
	 * evaluation of the producer, and the producer has the same fixed shape in every item,
	 * so repeating it up to the aligned size is well defined. Without the repetition, a
	 * single-element value computed from a variable-count input (a ray's hit distance, for
	 * example) would not broadcast against a larger one (that ray's direction) and the
	 * product would read it at every element position instead of at its only element.</p>
	 *
	 * @param repeat    the number of repetitions
	 * @param producer  the producer to expand
	 * @return the repeated producer, or {@code producer} itself if it cannot be repeated
	 */
	@Override
	protected Producer<PackedCollection> expand(int repeat, Producer<PackedCollection> producer) {
		// TODO(review): broadened repeat rule affects every caller of alignTraversalAxes; add a direct ar-algebra test for the fixed-shape variable-count case
		if (enableVariableRepeat || Countable.isFixedCount(producer) || shapeOf(producer).isFixedCount()) {
			return repeat(repeat, producer);
		}

		return producer;
	}
}
