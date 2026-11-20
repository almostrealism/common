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
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The alignment of a list of values to a common traversal, so that they can be combined
 * element by element.
 *
 * <p>An alignment first looks for a shape whose {@link TraversalPolicy#getSizeLong() size}
 * every value's {@link #shapeOf shape} exposes at some
 * {@link TraversalPolicy#compatibleAxis compatible axis} at equal total size. When the values
 * already have equal total size this combines them positionally without repeating any of them.</p>
 *
 * <p>Failing that, the values have differing total sizes and are reconciled by trailing-axis
 * broadcasting: the largest shape (of those, the one with the most axes) is the result, and
 * each other value must
 * {@link TraversalPolicy#broadcastsInto broadcast into} it — comparing shapes axis by axis from
 * the trailing end, each length must be equal or 1. A value that broadcasts is
 * {@link #expand repeated} along its length-1 (and missing leading) axes up to the result; a pair
 * that is not broadcast compatible is rejected when the alignment is built. No value is ever
 * tiled along an axis that is not length 1, nor read modulo its own length.</p>
 *
 * <p>The alignment derives each value's shape and adjusts the values itself, through the
 * {@link #shapeOf}, {@link #alignAxis} and {@link #expand} steps that a subclass for a
 * particular value type implements.</p>
 *
 * @param <V>  the type of the values being aligned
 * @author  Michael Murray
 * @see TraversalPolicy#isBroadcastCompatible
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
	 *
	 * <p>Values of equal total size are combined positionally when each exposes the common size
	 * at some {@link TraversalPolicy#compatibleAxis compatible axis}, without repeating any of
	 * them. Values of differing total size are reconciled by trailing-axis broadcasting onto the
	 * largest shape (see {@link TraversalPolicy#broadcastsInto}); a smaller value broadcasts by
	 * being repeated along its length-1 and missing leading axes.</p>
	 *
	 * @param <T>      the result type produced by {@code combine}
	 * @param combine  a function that produces the final result from the aligned shape and values
	 * @return the aligned result
	 * @throws IllegalArgumentException if the shapes are not broadcast compatible
	 */
	public <T> T align(BiFunction<TraversalPolicy, List<V>, T> combine) {
		List<TraversalPolicy> shapes = values.stream().map(this::shapeOf).collect(Collectors.toList());

		List<TraversalPolicy> sortedShapes = shapes.stream().distinct()
				.sorted(Comparator.comparing(TraversalPolicy::getSizeLong)
						.thenComparing(Comparator.comparing(TraversalPolicy::getTotalSizeLong).reversed()))
				.collect(Collectors.toList());

		s: for (TraversalPolicy shape : sortedShapes) {
			int[] compatibleAxes =
					IntStream.range(0, values.size())
							.map(i -> shapes.get(i).compatibleAxis(shape, true))
							.filter(i -> i >= 0).toArray();
			if (compatibleAxes.length != values.size()) continue s;

			List<V> vals = new ArrayList<>();
			for (int i = 0; i < values.size(); i++) {
				vals.add(alignAxis(compatibleAxes[i], values.get(i)));
			}

			return combine.apply(shape, vals);
		}

		TraversalPolicy target = shapes.stream()
				.max(Comparator.comparing(TraversalPolicy::getTotalSizeLong)
						.thenComparing(TraversalPolicy::getDimensions))
				.orElseThrow();

		for (TraversalPolicy shape : shapes) {
			if (!shape.broadcastsInto(target)) {
				throw new IllegalArgumentException("Cannot combine shapes " + shapes +
						" element-wise: " + shape + " is not broadcast compatible with " + target +
						" (axes must be equal or 1 from the trailing end)");
			}
		}

		List<V> vals = new ArrayList<>();
		for (int i = 0; i < values.size(); i++) {
			TraversalPolicy shape = shapes.get(i);
			V v = alignAxis(broadcastDepth(shape, target), values.get(i));

			long repeat = target.getTotalSizeLong() / shape.getTotalSizeLong();
			if (repeat > 1) v = expand(Math.toIntExact(repeat), v);
			vals.add(v);
		}

		return combine.apply(target, vals);
	}

	/**
	 * Returns the axis of {@code shape} to traverse to before repeating it up to {@code target}:
	 * the item from this axis onward is the unit that broadcasting repeats. The broadcast axes of
	 * {@code shape} against {@code target} (its length-1 axes, and the leading axes it lacks, where
	 * {@code target} is longer) must form a single contiguous block, which this construction
	 * repeats; a shape that broadcasts along non-adjacent axes cannot be reconciled this way and
	 * must be given an explicit {@code repeat}. An axis that is length 1 in {@code target} holds
	 * no repetition and does not separate the axes on either side of it, so a single-element
	 * value broadcasts into every shape.
	 *
	 * @param shape  the shape being broadcast into {@code target}
	 * @param target the broadcast result shape
	 * @return the traversal axis of {@code shape} whose item is repeated to fill {@code target}
	 * @throws IllegalArgumentException if the broadcast axes are not contiguous
	 */
	private static int broadcastDepth(TraversalPolicy shape, TraversalPolicy target) {
		int off = target.getDimensions() - shape.getDimensions();
		int first = target.getDimensions();
		boolean open = false, closed = false;

		for (int i = 0; i < target.getDimensions(); i++) {
			if (target.lengthLong(i) == 1) continue;

			long length = i < off ? 1 : shape.lengthLong(i - off);

			if (length == 1) {
				if (closed) {
					throw new IllegalArgumentException("Cannot broadcast " + shape + " into " +
							target + " along non-adjacent axes; add an explicit repeat");
				}
				if (!open) first = i;
				open = true;
			} else if (open) {
				closed = true;
			}
		}

		return Math.max(0, first - off);
	}
}
