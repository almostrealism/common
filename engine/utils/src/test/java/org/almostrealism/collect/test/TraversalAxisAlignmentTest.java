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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link TraversalAxisAlignment}, independent of any collection producer: the
 * aligned values are shapes themselves, so every traversal and repetition the alignment
 * chooses is visible in the result.
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
}
