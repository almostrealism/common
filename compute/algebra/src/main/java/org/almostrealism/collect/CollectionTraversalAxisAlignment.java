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
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Producer;

import java.util.ArrayList;
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
	 * product would read it at every element position instead of at its only element.
	 * This only holds when the variable count comes from variable-shaped inputs; see
	 * {@link #isCountFromShapes(Producer)}.</p>
	 *
	 * @param repeat    the number of repetitions
	 * @param producer  the producer to expand
	 * @return the repeated producer, or {@code producer} itself if it cannot be repeated
	 */
	@Override
	protected Producer<PackedCollection> expand(int repeat, Producer<PackedCollection> producer) {
		if (enableVariableRepeat || Countable.isFixedCount(producer)
				|| (shapeOf(producer).isFixedCount() && isCountFromShapes(producer))) {
			return repeat(repeat, producer);
		}

		return producer;
	}

	/**
	 * Returns whether every source of variable count in the given producer's graph is a
	 * producer whose shape itself has a variable count (a variable-count input, for example).
	 * A producer whose count is variable even though its shape and all of its inputs are
	 * fixed (such as {@code integers()}, whose value is the kernel index) is intrinsically
	 * variable: each kernel position is a distinct element of it rather than one more
	 * evaluation of a fixed-shape value, so it must not be repeated.
	 *
	 * @param producer  the producer to inspect
	 * @return {@code true} if the producer's count is fixed or follows only variable shapes
	 */
	protected boolean isCountFromShapes(Producer<?> producer) {
		// TODO(review): unmemoized recursion revisits shared subgraphs; consider a visited set if graph construction slows
		if (Countable.isFixedCount(producer)) return true;

		List<Object> variable = new ArrayList<>();
		if (producer instanceof Parent) {
			for (Object child : ((Parent<?>) producer).getChildren()) {
				if (!Countable.isFixedCount(child)) variable.add(child);
			}
		}

		if (variable.isEmpty()) {
			return !shape(producer).isFixedCount();
		}

		return variable.stream().allMatch(c -> c instanceof Producer && isCountFromShapes((Producer<?>) c));
	}
}
