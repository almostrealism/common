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

package org.almostrealism.util;

import org.almostrealism.collect.PackedCollection;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link GradientRecorder} and {@link TestFeatures#assertGradientMatches}: the recorder
 * copies each gradient it is wired with into a buffer of the weight's shape without touching the
 * weight, overwrites that buffer on every pass, and identifies a weight's gradient by its size only
 * when exactly one recorded gradient has that size; the assertion rejects a gradient that differs
 * from its estimate and one that is trivially zero.
 */
public class GradientRecorderTest extends TestSuiteBase implements ModelTestFeatures {

	/**
	 * Running the recorded update copies the gradient into the recorder, leaves the weight unchanged,
	 * and a second run with new gradient values replaces the first rather than accumulating.
	 */
	@Test(timeout = 60000)
	public void recordsTheMostRecentGradientWithoutUpdatingTheWeight() {
		PackedCollection weight = PackedCollection.of(1.0, 2.0, 3.0);
		PackedCollection gradient = PackedCollection.of(0.5, -0.25, 4.0);

		GradientRecorder recorder = new GradientRecorder();
		Runnable update = recorder.apply("w", p(weight), p(gradient)).get();

		update.run();
		Assert.assertArrayEquals(new double[] {0.5, -0.25, 4.0}, recorder.gradientOfSize(3), 1e-6);
		Assert.assertArrayEquals(new double[] {1.0, 2.0, 3.0}, weight.toArray(), 0.0);

		gradient.setMem(0, -7.0);
		gradient.setMem(1, 8.0);
		gradient.setMem(2, 9.0);
		update.run();
		Assert.assertArrayEquals(new double[] {-7.0, 8.0, 9.0}, recorder.gradientOfSize(3), 1e-6);
		Assert.assertArrayEquals(new double[] {1.0, 2.0, 3.0}, weight.toArray(), 0.0);
	}

	/**
	 * Each weight's gradient is found by its size, whatever order the weights were wired in, and the
	 * list-backed recorder from {@link ModelTestFeatures#gradientRecorder(List)} adds one buffer per
	 * weight to the caller's list in wiring order.
	 */
	@Test(timeout = 60000)
	public void identifiesEachGradientBySize() {
		PackedCollection small = new PackedCollection(shape(2));
		PackedCollection large = new PackedCollection(shape(2, 3));
		PackedCollection smallGradient = PackedCollection.of(1.0, -1.0);
		PackedCollection largeGradient = PackedCollection.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);

		List<PackedCollection> recorded = new ArrayList<>();
		GradientRecorder recorder = gradientRecorder(recorded);
		Runnable largeUpdate = recorder.apply("large", p(large), p(largeGradient)).get();
		Runnable smallUpdate = recorder.apply("small", p(small), p(smallGradient)).get();
		largeUpdate.run();
		smallUpdate.run();

		Assert.assertEquals(2, recorded.size());
		Assert.assertEquals(6, recorded.get(0).getShape().getTotalSize());
		Assert.assertEquals(2, recorded.get(1).getShape().getTotalSize());
		Assert.assertArrayEquals(new double[] {1.0, -1.0}, recorder.gradientOfSize(2), 1e-6);
		Assert.assertArrayEquals(new double[] {1.0, 2.0, 3.0, 4.0, 5.0, 6.0},
				recorder.gradientOfSize(6), 1e-6);
	}

	/** Asking for a size no recorded gradient has fails rather than returning an empty gradient. */
	@Test(timeout = 60000)
	public void rejectsASizeThatWasNotRecorded() {
		GradientRecorder recorder = new GradientRecorder();
		assertFails("no gradient was recorded", () -> recorder.gradientOfSize(3));

		recorder.apply("w", p(new PackedCollection(shape(4))), p(new PackedCollection(shape(4))));
		assertFails("no gradient was recorded", () -> recorder.gradientOfSize(3));
	}

	/** Two weights of the same size cannot be told apart, so asking for that size fails. */
	@Test(timeout = 60000)
	public void rejectsASizeThatIsAmbiguous() {
		GradientRecorder recorder = new GradientRecorder();
		recorder.apply("a", p(new PackedCollection(shape(4))), p(new PackedCollection(shape(4))));
		recorder.apply("b", p(new PackedCollection(shape(2, 2))), p(new PackedCollection(shape(2, 2))));

		assertFails("2 gradients were recorded", () -> recorder.gradientOfSize(4));
	}

	/** A gradient within the relative tolerance of a non-trivial estimate passes. */
	@Test(timeout = 60000)
	public void acceptsAMatchingGradient() {
		assertGradientMatches("close", new double[] {1.0, -2.0, 0.5},
				new double[] {1.001, -2.001, 0.5}, 1e-3, 1e-6);
	}

	/**
	 * A gradient further from the estimate than the tolerance allows fails, as does one whose
	 * estimate is no larger than the minimum (a trivially zero gradient agreeing with a zero
	 * estimate), and one whose length differs from the estimate's.
	 */
	@Test(timeout = 60000)
	public void rejectsAMismatchedOrTrivialGradient() {
		assertFails("differs from the finite differences", () -> assertGradientMatches("far",
				new double[] {1.0, -2.0}, new double[] {1.0, -1.9}, 1e-3, 1e-6));
		assertFails("trivially zero", () -> assertGradientMatches("zero",
				new double[] {0.0, 0.0}, new double[] {0.0, 0.0}, 1e-3, 1e-6));
		assertFails("gradient size", () -> assertGradientMatches("length",
				new double[] {1.0, 2.0}, new double[] {1.0}, 1e-3, 1e-6));
	}

	/**
	 * Asserts that the action fails with an {@link AssertionError} whose message contains the given
	 * text.
	 *
	 * @param expected text the failure message must contain
	 * @param action   the action expected to fail
	 */
	private static void assertFails(String expected, Runnable action) {
		try {
			action.run();
		} catch (AssertionError e) {
			Assert.assertTrue("unexpected message: " + e.getMessage(),
					String.valueOf(e.getMessage()).contains(expected));
			return;
		}

		Assert.fail("expected a failure containing \"" + expected + "\"");
	}
}
