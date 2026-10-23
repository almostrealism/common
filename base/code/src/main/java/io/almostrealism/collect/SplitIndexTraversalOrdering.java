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

import io.almostrealism.expression.Expression;

import java.util.stream.IntStream;

/**
 * A {@link TraversalOrdering} that reads integer positions stored <em>split</em> across two
 * floating-point lanes, so that index values above {@code 2^24} survive storage in single
 * precision memory.
 *
 * <p>A single-precision float represents integers exactly only up to {@code 2^24}. An index
 * collection read through {@link IndexExpressionTraversalOrdering} therefore loses any index above
 * that bound the moment it is stored on an FP32 backend, before any kernel runs. This ordering
 * owns an alternative representation for index data: each integer index {@code v} is stored as</p>
 *
 * <pre>
 * hi = floorDiv(v, BASE)
 * lo = floorMod(v, BASE)
 * </pre>
 *
 * <p>interleaved, so the index for logical position {@code i} occupies the two consecutive
 * elements {@code 2i} (high lane) and {@code 2i + 1} (low lane) of a collection of shape
 * {@code (n, 2)}. With {@link #BASE} {@code = 2^12}, every {@code int} index yields a high lane
 * whose magnitude is below {@code 2^19} and a low lane in {@code [0, 2^12)}, so each lane is
 * exactly representable in FP32. {@link #indexOf(Expression)} reconstructs
 * {@code hi * BASE + lo} on integer expressions inside the kernel, so the value never passes back
 * through a floating-point representation and stays exact for the full {@code int} range.</p>
 *
 * <p>The representation is opt-in: ordinary collections keep their precision, and only index
 * data deliberately stored in this layout (for example by
 * {@code PackedCollection.ofSplitIndices}) is read this way.</p>
 *
 * @see IndexExpressionTraversalOrdering
 */
public class SplitIndexTraversalOrdering implements TraversalOrdering {
	/**
	 * The radix that separates the high and low lanes. Any power of two in
	 * {@code [2^12, 2^24]} keeps both lanes exact in FP32 for {@code int} indices;
	 * {@code 2^12} leaves the widest margin on the high lane.
	 */
	public static final int BASE = 1 << 12;

	/** The number of stored elements that represent one index. */
	public static final int LANES = 2;

	/** The expression supplying the interleaved high and low lanes. */
	private final TraversableExpression<?> lanes;

	/**
	 * Creates an ordering backed by the given split-index expression.
	 *
	 * @param lanes the expression supplying the interleaved high and low lanes of each index
	 */
	public SplitIndexTraversalOrdering(TraversableExpression<?> lanes) {
		this.lanes = lanes;
	}

	/**
	 * Returns the backing split-index expression.
	 *
	 * @return the traversable expression supplying the interleaved lanes
	 */
	public TraversableExpression<?> getLanes() {
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
		Expression<?> position = idx.multiply(LANES);
		Expression<Integer> hi = lanes.getValueAt(position).toInt();
		Expression<Integer> lo = lanes.getValueAt(position.add(1)).toInt();
		return hi.multiply(BASE).add(lo).toInt();
	}

	/**
	 * Encodes integer indices into the interleaved split layout that {@link #indexOf(Expression)}
	 * decodes: for each index {@code v}, the high lane {@code floorDiv(v, BASE)} followed by the
	 * low lane {@code floorMod(v, BASE)}. Negative indices are encoded with a negative high lane
	 * and a low lane in {@code [0, BASE)}, so the reconstruction {@code hi * BASE + lo} is exact
	 * for every {@code int}.
	 *
	 * @param indices the integer indices to encode
	 * @return the {@code indices.length * LANES} lane values, each exactly representable in FP32
	 */
	public static double[] encode(int... indices) {
		return IntStream.range(0, indices.length * LANES)
				.mapToDouble(i -> i % LANES == 0 ? Math.floorDiv(indices[i / LANES], BASE)
						: Math.floorMod(indices[i / LANES], BASE))
				.toArray();
	}
}
