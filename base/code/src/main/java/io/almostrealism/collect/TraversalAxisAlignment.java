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

package io.almostrealism.collect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The alignment of a list of values to a common traversal, so that they can be combined
 * element by element.
 *
 * <p>An alignment first looks for a shape whose {@link TraversalPolicy#getSizeLong() size}
 * every value's {@link #shapeOf shape} exposes at some
 * {@link TraversalPolicy#compatibleAxis compatible axis}. Failing that, it chooses the largest
 * shape and {@link TraversalPolicy#repetitionsToFill repeats} each smaller value up to it,
 * along the depth at which their leading dimensions {@link TraversalPolicy#matchDepth match}.</p>
 *
 * <p>The alignment derives each value's shape and adjusts the values itself, through the
 * {@link #shapeOf}, {@link #alignAxis} and {@link #expand} steps that a subclass for a
 * particular value type implements.</p>
 *
 * @param <V>  the type of the values being aligned
 * @author  Michael Murray
 * @see TraversalPolicy#enableStrictSizes
 * @see TraversalPolicy#enableDivisibleSizes
 */
public abstract class TraversalAxisAlignment<V> {
	/** The values to align. */
	private final List<V> values;

	/**
	 * Creates an alignment of the given values.
	 *
	 * @param values  the values to align
	 */
	protected TraversalAxisAlignment(List<V> values) {
		this.values = values;
	}

	/**
	 * Returns the shape of the given value.
	 *
	 * @param value  one of the values of this alignment
	 * @return the shape of {@code value}
	 */
	protected abstract TraversalPolicy shapeOf(V value);

	/**
	 * Returns the given value traversed along the given axis.
	 *
	 * @param axis   the traversal axis
	 * @param value  the value to adjust
	 * @return {@code value} traversed along {@code axis}
	 */
	protected abstract V alignAxis(int axis, V value);

	/**
	 * Returns the given value repeated the given number of times. A value type whose values
	 * cannot always be repeated may return {@code value} unchanged for a value it cannot
	 * repeat, leaving the combination to reconcile the sizes.
	 *
	 * @param repeat  the number of repetitions, always greater than one
	 * @param value   the value to expand
	 * @return {@code value} repeated {@code repeat} times, or {@code value} itself if it
	 *         cannot be repeated
	 */
	protected abstract V expand(int repeat, V value);

	/**
	 * Aligns the traversal axes of the values of this alignment and combines the aligned values.
	 * Identical total sizes are required when {@link TraversalPolicy#enableStrictSizes} is set
	 * or no shape has more than one dimension.
	 *
	 * @param <T>      the result type produced by {@code combine}
	 * @param combine  a function that produces the final result from the aligned shape and values
	 * @return the aligned result
	 * @throws IllegalArgumentException if no compatible traversal axes exist
	 */
	public <T> T align(BiFunction<TraversalPolicy, List<V>, T> combine) {
		List<TraversalPolicy> shapes = values.stream().map(this::shapeOf).collect(Collectors.toList());
		boolean requireIdenticalTotalSize = TraversalPolicy.enableStrictSizes ||
				shapes.stream().noneMatch(s -> s.getDimensions() > 1);

		TreeSet<TraversalPolicy> sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getSizeLong));
		sortedShapes.addAll(shapes);

		s: for (TraversalPolicy shape : sortedShapes) {
			int[] compatibleAxes =
					IntStream.range(0, values.size())
							.map(i -> shapes.get(i).compatibleAxis(shape, requireIdenticalTotalSize))
							.filter(i -> i >= 0).toArray();
			if (compatibleAxes.length != values.size()) continue s;

			List<V> vals = new ArrayList<>();
			for (int i = 0; i < values.size(); i++) {
				vals.add(alignAxis(compatibleAxes[i], values.get(i)));
			}

			return combine.apply(shape, vals);
		}

		sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getTotalSizeLong).reversed());
		sortedShapes.addAll(shapes);

		long largest = sortedShapes.iterator().next().getTotalSizeLong();
		int depth = sortedShapes.iterator().next().getDimensions();

		s: for (TraversalPolicy shape : sortedShapes) {
			if (shape.getTotalSizeLong() < largest) {
				break s;
			}

			int minDepth = (TraversalPolicy.enableStrictSizes || depth <= 1) ? -1 : 0;

			int[] matchDepths =
					IntStream.range(0, values.size())
							.map(i -> shapes.get(i).matchDepth(shape))
							.filter(i -> i > minDepth).toArray();
			if (matchDepths.length != values.size()) continue s;

			List<V> vals = new ArrayList<>();
			for (int i = 0; i < values.size(); i++) {
				int repeat = shapes.get(i).repetitionsToFill(shape);
				V v = alignAxis(matchDepths[i], values.get(i));
				if (repeat > 1) v = expand(repeat, v);
				vals.add(v);
			}

			return combine.apply(shape, vals);
		}

		throw new IllegalArgumentException("No compatible traversal axes");
	}
}
