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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.computations.CollectionProviderProducer;

import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * A traversal ordering based on explicit physical positions stored <em>split</em> across two
 * floating-point lanes, so that positions above {@code 2^24} stay exact in single precision
 * memory.
 *
 * <p>This is the integer-exact counterpart of {@link ExplicitIndexTraversalOrdering}. That
 * ordering stores one position per element as a floating-point value, which a single-precision
 * backend represents exactly only up to {@code 2^24}; a larger position is rounded the moment it
 * is stored, so the ordering addresses the wrong element. This ordering stores each position
 * {@code v} as</p>
 *
 * <pre>
 * hi = floorDiv(v, BASE)
 * lo = floorMod(v, BASE)
 * </pre>
 *
 * <p>in a lane collection of shape {@code (n, 2)}, with the high lane of logical index {@code i}
 * at element {@code 2i} and the low lane at {@code 2i + 1}. With {@link #BASE} {@code = 2^12},
 * every {@code int} position yields a high lane whose magnitude is at most {@code 2^19} and a low
 * lane in {@code [0, 2^12)}, so each lane is exactly representable in FP32.
 * {@link #indexOf(Expression)} reconstructs {@code hi * BASE + lo} on integer expressions inside
 * the kernel, so the position never passes back through a floating-point value. A negative
 * position (an absent element) is encoded with a negative high lane and reads as zero, exactly as
 * for {@link ExplicitIndexTraversalOrdering}.</p>
 *
 * <h2>Usage</h2>
 * <p>A gather is a collection read through this ordering: every computation that reads the
 * collection respects the ordering, so no special-purpose gather operation is needed.</p>
 * <pre>{@code
 * // gathered[i] = data[positions[i]], exact for every int position
 * SplitIndexTraversalOrdering order = SplitIndexTraversalOrdering.of(16777216, 16777217, 16777221);
 * PackedCollection gathered = new PackedCollection(shape(3), 0, data, 0, order);
 * CollectionProducer doubled = cp(gathered).multiply(2.0);
 * }</pre>
 *
 * <h2>Generated code</h2>
 * <p>Inside a kernel, the lane collection is {@link #bind(ArgumentProvider) delivered as an
 * argument}, so the reconstruction renders as two reads of device memory followed by integer
 * arithmetic. Because the ordering reads a collection, kernels reading it are not reused
 * (see {@link TraversalOrdering#signature()}).</p>
 *
 * @see ExplicitIndexTraversalOrdering
 */
public class SplitIndexTraversalOrdering implements TraversalOrdering {
	/**
	 * The radix that separates the high and low lanes. Any power of two in
	 * {@code [2^12, 2^24]} keeps both lanes exact in FP32 for {@code int} positions;
	 * {@code 2^12} leaves the widest margin on the low lane.
	 */
	public static final int BASE = 1 << 12;

	/** The number of stored elements that represent one position. */
	public static final int LANES = 2;

	/** The collection of split positions, of shape {@code (n, LANES)}. */
	private final PackedCollection lanes;

	/** The expression supplying the lanes: the collection itself or its kernel argument. */
	private final TraversableExpression<?> lookup;

	/**
	 * Creates an ordering that maps each logical index to the position stored, split, in the
	 * given lane collection.
	 *
	 * @param lanes the split positions, of shape {@code (n, 2)}, as produced by
	 *              {@link #encode(int...)}
	 * @throws IllegalArgumentException if the last dimension of the lane collection is not
	 *         {@link #LANES}, which indicates an ordinary single-lane index table that would
	 *         otherwise be misread as split lanes
	 */
	public SplitIndexTraversalOrdering(PackedCollection lanes) {
		this(lanes, lanes);

		TraversalPolicy shape = lanes.getShape();
		if (shape.length(shape.getDimensions() - 1) != LANES) {
			throw new IllegalArgumentException("Split index shape " + shape
					+ " must have a last dimension of " + LANES);
		}
	}

	/**
	 * Creates an ordering that reads the lanes of the given collection through the given
	 * expression.
	 *
	 * @param lanes  the lane collection
	 * @param lookup the expression supplying the lanes, either the collection itself or the
	 *               kernel argument it is delivered as
	 */
	private SplitIndexTraversalOrdering(PackedCollection lanes, TraversableExpression<?> lookup) {
		this.lanes = lanes;
		this.lookup = lookup;
	}

	/**
	 * Returns the collection of split positions.
	 *
	 * @return the lane collection, of shape {@code (n, 2)}
	 */
	public PackedCollection getLanes() {
		return lanes;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Reads the high lane at {@code 2 * idx} and the low lane at {@code 2 * idx + 1}, converts
	 * each to an integer, and returns {@code hi * BASE + lo}.</p>
	 */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return positionAt(lookup, idx);
	}

	/**
	 * Decodes, on integer expressions, the position stored split at logical index {@code idx}
	 * of a lane collection in the layout produced by {@link #encode(int...)}. This is the
	 * reconstruction {@link #indexOf(Expression)} performs, exposed for computations that hold
	 * split integers as an operand of their own (for example the row offsets of a sparse matrix)
	 * rather than reading another collection through this ordering.
	 *
	 * @param lanes the lane collection, or the kernel argument it is delivered as
	 * @param idx   the logical index of the position to read
	 * @return an integer expression for {@code hi * BASE + lo}, exact for every {@code int}
	 */
	public static Expression<Integer> positionAt(TraversableExpression<?> lanes, Expression<?> idx) {
		Expression<?> position = idx.multiply(LANES);
		Expression<Integer> hi = lanes.getValueAt(position).toInt();
		Expression<Integer> lo = lanes.getValueAt(position.add(1)).toInt();
		return hi.multiply(BASE).add(lo).toInt();
	}

	/**
	 * Returns the number of physical positions addressed: one more than the largest stored
	 * position, or 0 if every position is negative.
	 *
	 * @return the number of physical positions
	 * @throws ArithmeticException if a stored position is {@link Integer#MAX_VALUE}, whose length
	 *         of {@code 2^31} positions is not representable as an {@code int}
	 */
	@Override
	public OptionalInt getLength() {
		long max = IntStream.of(decode(lanes.toArray())).max().orElse(-1);
		return OptionalInt.of(Math.toIntExact(Math.max(max, -1) + 1));
	}

	/**
	 * Decodes, on the host, lane values in the layout produced by {@link #encode(int...)} back
	 * into the integer positions they represent. This is the inverse of {@link #encode(int...)}.
	 *
	 * @param lanes the lane values, {@link #LANES} per position
	 * @return the positions, {@code hi * BASE + lo} for each pair of lanes
	 */
	public static int[] decode(double... lanes) {
		return IntStream.range(0, lanes.length / LANES)
				.map(i -> (int) lanes[LANES * i] * BASE + (int) lanes[LANES * i + 1])
				.toArray();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns an ordering that reads the lanes from the kernel argument the provider supplies
	 * for the lane collection.</p>
	 */
	@Override
	public TraversalOrdering bind(ArgumentProvider provider) {
		TraversableExpression<?> bound = provider.<PackedCollection>argumentForInput()
				.apply(new CollectionProviderProducer<>(lanes)).getTraversableExpression();
		return bound == null ? this : new SplitIndexTraversalOrdering(lanes, bound);
	}

	/**
	 * Creates an ordering over the given physical positions, storing them in a new lane
	 * collection with {@link PackedCollection#ofSplitIndices(int...)}.
	 *
	 * @param positions the physical position of each logical index, negative for an absent
	 *                  element
	 * @return the ordering
	 */
	public static SplitIndexTraversalOrdering of(int... positions) {
		return new SplitIndexTraversalOrdering(PackedCollection.ofSplitIndices(positions));
	}

	/**
	 * Encodes integer positions into the split layout that {@link #indexOf(Expression)} decodes:
	 * for each position {@code v}, the high lane {@code floorDiv(v, BASE)} followed by the low
	 * lane {@code floorMod(v, BASE)}. Negative positions are encoded with a negative high lane and
	 * a low lane in {@code [0, BASE)}, so the reconstruction {@code hi * BASE + lo} is exact for
	 * every {@code int}.
	 *
	 * @param positions the integer positions to encode
	 * @return the {@code positions.length * LANES} lane values, each exactly representable in FP32
	 */
	public static double[] encode(int... positions) {
		return IntStream.range(0, positions.length * LANES)
				.mapToDouble(i -> i % LANES == 0 ? Math.floorDiv(positions[i / LANES], BASE)
						: Math.floorMod(positions[i / LANES], BASE))
				.toArray();
	}
}
