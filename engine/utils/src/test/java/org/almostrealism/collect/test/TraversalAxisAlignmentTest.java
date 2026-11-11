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

package org.almostrealism.collect.test;

import io.almostrealism.collect.TraversalAxisAlignment;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionTraversalAxisAlignment;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link TraversalAxisAlignment}. Most are independent of any collection producer:
 * the aligned values are shapes themselves, so every traversal and repetition the alignment
 * chooses is visible in the result. The remaining tests cover the repetition rule of
 * {@link CollectionTraversalAxisAlignment}, which depends on whether a producer's shape and
 * count are fixed.
 */
public class TraversalAxisAlignmentTest extends TestSuiteBase {

	/**
	 * Aligns shapes by traversing them, and records every expansion so that a test can
	 * tell an alignment by traversal alone from one that repeated a value.
	 */
	private static class ShapeAlignment extends TraversalAxisAlignment<TraversalPolicy> {
		/** The repetition counts passed to {@link #expand}, in call order. */
		private final List<Integer> expansions = new ArrayList<>();

		/**
		 * Creates an alignment of the given shapes.
		 *
		 * @param shapes  the shapes to align
		 */
		ShapeAlignment(TraversalPolicy... shapes) {
			super(List.of(shapes));
		}

		@Override
		protected TraversalPolicy shapeOf(TraversalPolicy value) {
			return value;
		}

		@Override
		protected TraversalPolicy alignAxis(int axis, TraversalPolicy value) {
			return value.traverse(axis);
		}

		@Override
		protected TraversalPolicy expand(int repeat, TraversalPolicy value) {
			expansions.add(repeat);
			return value.prependDimension(repeat);
		}
	}

	/**
	 * Two shapes of equal total size whose items share a size at some axis are aligned by
	 * traversal alone, at the smallest such item size, without any repetition.
	 */
	@Test(timeout = 10000)
	public void alignsByTraversal() {
		ShapeAlignment alignment = new ShapeAlignment(shape(3, 5).traverse(1), shape(3, 5).traverse(0));
		List<TraversalPolicy> aligned = new ArrayList<>();

		TraversalPolicy result = alignment.align((shape, values) -> {
			aligned.addAll(values);
			return shape;
		});

		assertEquals(5, result.getSizeLong());
		assertEquals(15, result.getTotalSizeLong());
		assertEquals(2, aligned.size());
		for (TraversalPolicy value : aligned) {
			assertEquals(1, value.getTraversalAxis());
			assertEquals(5, value.getSizeLong());
		}
		assertTrue("No value should be repeated", alignment.expansions.isEmpty());
	}

	/**
	 * A smaller shape whose total size divides that of the largest one is repeated up to it,
	 * while the largest shape is traversed past its last axis and is not repeated.
	 */
	@Test(timeout = 10000)
	public void repeatsSmallerValue() {
		ShapeAlignment alignment = new ShapeAlignment(shape(4, 6), shape(6));
		List<TraversalPolicy> aligned = new ArrayList<>();

		TraversalPolicy result = alignment.align((shape, values) -> {
			aligned.addAll(values);
			return shape;
		});

		assertEquals(24, result.getTotalSizeLong());
		assertEquals(List.of(4), alignment.expansions);

		assertEquals(2, aligned.get(0).getTraversalAxis());
		assertEquals(24, aligned.get(0).getTotalSizeLong());
		assertEquals(4, aligned.get(1).length(0));
		assertEquals(6, aligned.get(1).length(1));
		assertEquals(24, aligned.get(1).getTotalSizeLong());
	}

	/**
	 * A smaller shape whose total size does not divide that of the largest one is not
	 * repeated, since {@link TraversalPolicy#repetitionsToFill} yields zero for it; the
	 * combination receives it at its original size to reconcile.
	 */
	@Test(timeout = 10000)
	public void doesNotRepeatIndivisibleValue() {
		ShapeAlignment alignment = new ShapeAlignment(shape(6), shape(4));
		List<TraversalPolicy> aligned = new ArrayList<>();

		TraversalPolicy result = alignment.align((shape, values) -> {
			aligned.addAll(values);
			return shape;
		});

		assertEquals(6, result.getTotalSizeLong());
		assertTrue("An indivisible value should not be repeated", alignment.expansions.isEmpty());
		assertEquals(6, aligned.get(0).getTotalSizeLong());
		assertEquals(4, aligned.get(1).getTotalSizeLong());
	}

	/**
	 * Without strict sizes, shapes whose items have the same size but whose total sizes
	 * differ are both kept as candidate targets, and the larger one is chosen so that the
	 * alignment covers every element of the larger value, in whichever order they are given.
	 */
	@Test(timeout = 10000)
	public void prefersLargestTotalAmongEqualSizes() {
		boolean strict = TraversalPolicy.enableStrictSizes;
		TraversalPolicy.enableStrictSizes = false;

		try {
			TraversalPolicy small = shape(2, 3).traverse(1);
			TraversalPolicy large = shape(4, 3).traverse(1);

			for (TraversalPolicy[] order : new TraversalPolicy[][] { { small, large }, { large, small } }) {
				ShapeAlignment alignment = new ShapeAlignment(order);
				List<TraversalPolicy> aligned = new ArrayList<>();

				TraversalPolicy result = alignment.align((shape, values) -> {
					aligned.addAll(values);
					return shape;
				});

				assertEquals(large, result);
				assertEquals(2, aligned.size());
				for (TraversalPolicy value : aligned) {
					assertEquals(1, value.getTraversalAxis());
					assertEquals(large.getSizeLong(), value.getSizeLong());
				}
				assertTrue("No value should be repeated", alignment.expansions.isEmpty());
			}
		} finally {
			TraversalPolicy.enableStrictSizes = strict;
		}
	}

	/**
	 * With strict sizes, the same shapes have no common target by traversal, so the larger
	 * one is chosen and the smaller one is repeated up to it.
	 */
	@Test(timeout = 10000)
	public void strictSizesRepeatSmallerOfEqualSizes() {
		TraversalPolicy large = shape(4, 3).traverse(1);
		ShapeAlignment alignment = new ShapeAlignment(shape(2, 3).traverse(1), large);

		TraversalPolicy result = alignment.align((shape, values) -> shape);

		assertEquals(large, result);
		assertEquals(List.of(2), alignment.expansions);
	}

	/**
	 * A collection producer whose own shape is fixed, but whose count is variable because it
	 * is computed from a variable-count input, is repeated up to the aligned size by
	 * {@link CollectionTraversalAxisAlignment}, just as a fixed-count producer would be.
	 */
	@Test(timeout = 30000)
	public void repeatsFixedShapeOfVariableCountProducer() {
		Producer<PackedCollection> vectors = v(shape(-1, 3), 0);
		Producer<PackedCollection> first = x(vectors);

		assertTrue(shape(first).isFixedCount());
		assertFalse(Countable.isFixedCount(first));
		assertEquals(3, alignedTotalSize(first, vectors));
	}

	/**
	 * A collection producer whose shape itself has a variable count is left unchanged by
	 * {@link CollectionTraversalAxisAlignment} unless
	 * {@link CollectionTraversalAxisAlignment#enableVariableRepeat} is set, in which case it
	 * is repeated like any other producer.
	 */
	@Test(timeout = 30000)
	public void repeatsVariableShapeOnlyWhenEnabled() {
		Producer<PackedCollection> variable = v(shape(-1, 1), 0);
		Producer<PackedCollection> fixed = v(shape(3), 1);

		assertFalse(shape(variable).isFixedCount());
		assertEquals(1, alignedTotalSize(variable, fixed));

		boolean enabled = CollectionTraversalAxisAlignment.enableVariableRepeat;
		CollectionTraversalAxisAlignment.enableVariableRepeat = true;

		try {
			assertEquals(3, alignedTotalSize(variable, fixed));
		} finally {
			CollectionTraversalAxisAlignment.enableVariableRepeat = enabled;
		}
	}

	/**
	 * A collection producer whose shape is fixed but whose count is intrinsically variable,
	 * such as {@code integers()} whose value is the kernel index, is not repeated by
	 * {@link CollectionTraversalAxisAlignment}: each kernel position is a distinct element
	 * of it, so repeating it would read the same index at several positions.
	 */
	@Test(timeout = 30000)
	public void doesNotRepeatIntrinsicallyVariableCountProducer() {
		Producer<PackedCollection> indices = integers().add(c(2));
		Producer<PackedCollection> fixed = c(6, 18, 48);

		assertTrue(shape(indices).isFixedCount());
		assertFalse(Countable.isFixedCount(indices));
		assertEquals(1, alignedTotalSize(indices, fixed));
	}

	/**
	 * A per-item scalar computed from a variable-count input broadcasts against that input's
	 * vectors: each item's vector is scaled by its own first component, rather than each
	 * element being multiplied by a different element of the unrepeated scalar.
	 */
	@Test(timeout = 60000)
	public void fixedShapeOfVariableCountBroadcasts() {
		Producer<PackedCollection> vectors = v(shape(-1, 3), 0);
		Producer<PackedCollection> product = multiply(x(vectors), vectors);

		PackedCollection input = pack(2.0, 3.0, 4.0, 5.0, 6.0, 7.0).reshape(shape(2, 3).traverse(1));

		PackedCollection result = new PackedCollection(shape(2, 3).traverse(1));
		product.into(result.each()).evaluate(input);

		double[] expected = { 4.0, 6.0, 8.0, 25.0, 30.0, 35.0 };
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], result.toDouble(i), 1e-6);
		}
	}

	/**
	 * Aligns the two given producers with {@link CollectionTraversalAxisAlignment} and returns
	 * the total size the first one has after alignment.
	 *
	 * @param first   the producer whose aligned size is reported
	 * @param second  the producer it is aligned with
	 * @return the total size of {@code first} after alignment
	 */
	private long alignedTotalSize(Producer<PackedCollection> first, Producer<PackedCollection> second) {
		List<Producer<PackedCollection>> aligned = new ArrayList<>();
		new CollectionTraversalAxisAlignment(List.of(first, second)).align((shape, values) -> {
			aligned.addAll(values);
			return shape;
		});
		return shape(aligned.get(0)).getTotalSizeLong();
	}
}
