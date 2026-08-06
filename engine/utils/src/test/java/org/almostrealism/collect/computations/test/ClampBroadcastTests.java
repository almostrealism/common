/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@code min}, {@code max} and {@code bound} broadcast a smaller operand
 * across a larger one, the way {@code multiply} and the other element-wise operations do.
 *
 * <p>Clamping a vector against scalar limits is the common case, and it is one where a
 * result that silently takes the shape of the smaller operand is indistinguishable from a
 * correct one whenever the vector happens to be uniform. Every vector here therefore varies
 * per element, and the expected values differ per element too.</p>
 */
public class ClampBroadcastTests extends TestSuiteBase {

	/** Length of the vectors under test. */
	private static final int SIZE = 8;

	/** The ramp {@code 0, 1, ... SIZE-1} used as the operand being clamped. */
	private PackedCollection ramp() {
		return integers(0, SIZE).evaluate().reshape(SIZE);
	}

	/** A scalar upper limit must apply to every element, not just the first. */
	@Test(timeout = 30000)
	public void minAgainstScalarBroadcasts() {
		PackedCollection out = min(cp(ramp()), c(3.0)).evaluate();

		Assert.assertEquals("the result must keep the shape of the larger operand",
				SIZE, out.getShape().getTotalSize());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(Math.min(i, 3.0), out.valueAt(i));
		}
	}

	/** A scalar lower limit must apply to every element, not just the first. */
	@Test(timeout = 30000)
	public void maxAgainstScalarBroadcasts() {
		PackedCollection out = max(cp(ramp()), c(4.0)).evaluate();

		Assert.assertEquals("the result must keep the shape of the larger operand",
				SIZE, out.getShape().getTotalSize());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(Math.max(i, 4.0), out.valueAt(i));
		}
	}

	/** Clamping a vector to scalar limits must clamp each element by those limits. */
	@Test(timeout = 30000)
	public void boundClampsEachElement() {
		PackedCollection out = bound(cp(ramp()), 2.0, 5.0).evaluate();

		Assert.assertEquals("the result must keep the shape of the operand being clamped",
				SIZE, out.getShape().getTotalSize());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(Math.min(Math.max(i, 2.0), 5.0), out.valueAt(i));
		}
	}

	/** The scalar may be either operand, so the smaller side is broadcast in both positions. */
	@Test(timeout = 30000)
	public void broadcastAppliesToEitherOperand() {
		PackedCollection out = max(c(4.0), cp(ramp())).evaluate();

		Assert.assertEquals("the result must keep the shape of the larger operand",
				SIZE, out.getShape().getTotalSize());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(Math.max(i, 4.0), out.valueAt(i));
		}
	}

	/**
	 * A per-row ramp clamped against scalar limits — the shape of expression the batched
	 * DSP paths build when a value is interpolated across a block and then bounded.
	 *
	 * <p>The rows deliberately differ: one rises past the upper limit, one falls below the
	 * lower limit, and one is flat. A clamp that collapsed to a single value would flatten
	 * the ramp, which is precisely what a uniform row could not reveal.</p>
	 */
	@Test(timeout = 30000)
	public void rampAcrossRowsSurvivesClamping() {
		int rows = 3;
		int cols = 8;
		double lower = 8.0;
		double upper = 40.0;

		PackedCollection previous = pack(10.0, 20.0, 30.0);
		PackedCollection current = pack(50.0, 4.0, 30.0);

		// Interpolate from previous to current across each row, as the block-parallel
		// delay networks do, then bound the result to the permitted range.
		CollectionProducer column = integers(0, cols).repeat(0, rows);
		CollectionProducer rising = column.add(c(1.0)).multiply(c(1.0 / cols));
		CollectionProducer falling = column.multiply(c(-1.0))
				.add(c(cols - 1.0)).multiply(c(1.0 / cols));
		CollectionProducer ramp = cp(previous).reshape(shape(rows)).repeat(1, cols).multiply(falling)
				.add(cp(current).reshape(shape(rows)).repeat(1, cols).multiply(rising));

		PackedCollection out = bound(ramp, lower, upper).evaluate();

		Assert.assertEquals("the clamped ramp must keep the shape of the ramp",
				rows * cols, out.getShape().getTotalSize());

		for (int r = 0; r < rows; r++) {
			for (int i = 0; i < cols; i++) {
				double up = (i + 1.0) / cols;
				double down = (cols - 1.0 - i) / cols;
				double expected = Math.min(Math.max(
						previous.toDouble(r) * down + current.toDouble(r) * up, lower), upper);
				assertEquals(expected, out.toDouble(r * cols + i));
			}
		}

		// The first row must genuinely vary, or the assertions above would hold
		// even for a result that had collapsed to one value.
		Assert.assertNotEquals("the ramp must vary across the row",
				out.toDouble(0), out.toDouble(cols - 1), 1.0);
	}

	/** Equally sized operands must still be compared element by element. */
	@Test(timeout = 30000)
	public void equalShapesArePairedElementwise() {
		PackedCollection descending =
				integers(0, SIZE).multiply(c(-1.0)).add(c(SIZE - 1.0)).evaluate().reshape(SIZE);
		PackedCollection out = min(cp(ramp()), cp(descending)).evaluate();

		Assert.assertEquals(SIZE, out.getShape().getTotalSize());
		for (int i = 0; i < SIZE; i++) {
			assertEquals(Math.min(i, SIZE - 1.0 - i), out.valueAt(i));
		}
	}
}
