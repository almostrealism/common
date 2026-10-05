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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;

/**
 * Test suite for {@link CollectionPermute} demonstrating various dimension reordering scenarios.
 * These tests validate that permutation operations correctly transform collection shapes and
 * preserve element values while reordering dimensional access patterns.
 *
 * @see CollectionPermute
 * @see org.almostrealism.collect.CollectionFeatures#permute(io.almostrealism.relation.Producer, int...)
 */
public class CollectionPermuteTests extends TestSuiteBase {
	/**
	 * Reordering complies with the {@code into(destination)} contract: evaluating the permutation
	 * into a destination whose shape is regular physically restructures that destination's memory,
	 * rather than producing a collection that views the input through a permuting
	 * {@link io.almostrealism.collect.TraversalPolicy}.
	 *
	 * <p>This is the distinction the other tests here cannot make. They read the result through
	 * {@code valueAt}, which applies the policy, so restructured memory and a view are
	 * indistinguishable to them. This reads the destination linearly instead — which is its
	 * physical order, because its shape is regular and the destination is the caller's own
	 * collection — and compares against the permutation computed independently. A result handed
	 * back as a view would leave the destination holding the input's order.</p>
	 */
	@Test(timeout = 30000)
	public void permuteIntoRegularDestinationRestructuresMemory() {
		try (PackedCollection input = pack(
				0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0,
				12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0)
				.reshape(2, 3, 4);
				PackedCollection destination = new PackedCollection(shape(4, 3, 2))) {
			assertTrue("the destination should start with a regular shape",
					destination.getShape().isRegular());

			cp(input).permute(2, 1, 0).get().into(destination).evaluate();

			assertTrue("the destination's shape was replaced with a view-adjusting policy",
					destination.getShape().isRegular());

			log("destination memory " + Arrays.toString(destination.toArray(0, 24)));

			for (int a = 0; a < 4; a++) {
				for (int b = 0; b < 3; b++) {
					for (int c = 0; c < 2; c++) {
						// The destination's own layout, and the input value that belongs at [a][b][c]
						int physical = (a * 3 + b) * 2 + c;
						double expected = (c * 3 + b) * 4 + a;

						assertEquals("destination[" + a + "][" + b + "][" + c + "] at " + physical,
								expected, destination.toDouble(physical));
					}
				}
			}
		}
	}

	/**
	 * A bulk read of a permuted result agrees with element-by-element access, whatever layout the
	 * result carries. Evaluating without a destination may hand back a collection that views its
	 * input through a permuting {@link io.almostrealism.collect.TraversalPolicy} rather than one
	 * whose memory has been restructured, and a bulk read that ignored that policy would return
	 * the input's order while {@code valueAt} returned the permutation — the two disagreeing is
	 * the defect this pins.
	 */
	@Test(timeout = 30000)
	public void bulkReadOfAPermutedResultAgreesWithElementAccess() {
		try (PackedCollection input = pack(
				0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0,
				12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0)
				.reshape(2, 3, 4);
				PackedCollection out = cp(input).permute(2, 1, 0).evaluate()) {
			log("result shape " + out.getShape() + " regular=" + out.getShape().isRegular());
			log("result bulk read " + Arrays.toString(out.toArray(0, 24)));

			double[] bulk = out.toArray(0, 24);
			double[] streamed = out.doubleStream(0, 24).toArray();

			for (int a = 0; a < 4; a++) {
				for (int b = 0; b < 3; b++) {
					for (int c = 0; c < 2; c++) {
						int index = (a * 3 + b) * 2 + c;

						assertEquals("bulk read at " + index, out.valueAt(a, b, c), bulk[index]);
						assertEquals("stream at " + index, out.valueAt(a, b, c), streamed[index]);
					}
				}
			}
		}
	}

	/**
	 * Referencing a view and evaluating it into a destination of another layout is refused, rather
	 * than copying the view's backing memory and silently losing the reordering.
	 *
	 * <p>A producer over a collection is a reference to that collection's memory, and writing it
	 * into a destination is a flat copy. For a view — a shape that maps indices onto another
	 * collection's buffer — that copy yields backing-memory order, which is not the view's values.
	 * The reordering belongs in a computation, which
	 * {@link #permuteIntoRegularDestinationRestructuresMemory} covers; this pins the refusal, so the
	 * difference cannot be discovered as wrong data.</p>
	 */
	@Test(timeout = 30000)
	public void referencingAViewIntoAnotherLayoutIsRefused() {
		try (PackedCollection frameMajor = pack(0.0, -0.1, 0.1, -0.2, 0.2, -0.3).reshape(3, 2);
				PackedCollection destination = new PackedCollection(shape(2, 3))) {
			PackedCollection view = frameMajor.reshape(frameMajor.getShape().permute(1, 0));

			assertFalse("the view should be irregular", view.getShape().isRegular());
			assertTrue("the destination should be regular", destination.getShape().isRegular());

			try {
				cp(view).get().into(destination).evaluate();
				throw new AssertionError("a view was copied into a destination of another layout");
			} catch (IllegalArgumentException expected) {
				log("refused: " + expected.getMessage());
				assertTrue(expected.getMessage().contains("views other memory"));
			}
		}
	}

	/**
	 * Tests basic 2D transpose operation (matrix transpose).
	 * Demonstrates swapping the two dimensions of a 2D collection using permute(1, 0).
	 *
	 * <p>Original shape: (2, 4) -> Permuted shape: (4, 2)</p>
	 * <p>Element at [i,j] in original -> Element at [j,i] in result</p>
	 */
	@Test(timeout = 30000)
	public void permute2() {
		PackedCollection input = new PackedCollection(shape(2, 4)).randFill();
		PackedCollection out = cp(input).permute(1, 0).evaluate();

		assertEquals(4, out.getShape().length(0));
		assertEquals(2, out.getShape().length(1));

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				assertEquals(input.valueAt(i, j), out.valueAt(j, i));
			}
		}
	}

	/**
	 * Verifies that permuting a collection and evaluating the result {@code into} a destination with a
	 * standard (non-reordered) {@link org.almostrealism.collect.PackedCollection#getShape() shape}
	 * physically restructures the destination's backing memory, rather than leaving it as a
	 * view whose {@link io.almostrealism.collect.TraversalPolicy} merely reorders logical access.
	 *
	 * <p>The destination is a plain {@code shape(4, 3)} collection, so its memory is row-major with no
	 * ordering adjustment. After {@code cp(input).permute(1, 0).into(destination).evaluate()} the raw
	 * memory of the destination — read with {@link org.almostrealism.collect.PackedCollection#toArray(int, int)},
	 * which returns physical order for a regular shape — must equal the transposed values laid out
	 * row-major, proving the permutation was applied to real memory.</p>
	 */
	@Test(timeout = 30000)
	public void permuteMaterializesIntoDestinationMemory() {
		try (PackedCollection input = pack(
				0.0, 1.0, 2.0, 3.0,
				10.0, 11.0, 12.0, 13.0,
				20.0, 21.0, 22.0, 23.0).reshape(3, 4);
				PackedCollection destination = new PackedCollection(shape(4, 3))) {
			cp(input).permute(1, 0).into(destination.traverseEach()).evaluate();

			// Transpose of input, laid out row-major for shape (4, 3)
			double[] expectedMemory = {
					0.0, 10.0, 20.0,
					1.0, 11.0, 21.0,
					2.0, 12.0, 22.0,
					3.0, 13.0, 23.0};

			double[] actualMemory = destination.toArray(0, 12);
			for (int i = 0; i < expectedMemory.length; i++) {
				assertEquals(expectedMemory[i], actualMemory[i]);
			}
		}
	}

	/**
	 * Tests 4D dimension reordering with partial permutation.
	 * Demonstrates swapping middle dimensions while keeping first and last in place using permute(0, 2, 1, 3).
	 *
	 * <p>Original shape: (2, 4, 3, 8) -> Permuted shape: (2, 3, 4, 8)</p>
	 * <p>Element at [i,j,k,l] in original -> Element at [i,k,j,l] in result</p>
	 * <p>This pattern is common in tensor operations where you need to reorder height/width or channel dimensions.</p>
	 */
	@Test(timeout = 30000)
	public void permute4() {
		PackedCollection input = new PackedCollection(shape(2, 4, 3, 8)).randFill();
		PackedCollection out = cp(input).permute(0, 2, 1, 3).evaluate();

		assertEquals(2, out.getShape().length(0));
		assertEquals(3, out.getShape().length(1));
		assertEquals(4, out.getShape().length(2));
		assertEquals(8, out.getShape().length(3));

		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 4; j++) {
				for (int k = 0; k < 3; k++) {
					for (int l = 0; l < 8; l++) {
						assertEquals(input.valueAt(i, j, k, l), out.valueAt(i, k, j, l));
					}
				}
			}
		}
	}
}
