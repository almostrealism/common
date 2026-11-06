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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Random}, focused on the contract of the {@link Evaluable} it
 * returns from {@link Random#get()}: {@code evaluate()} hands back the held value
 * cache directly rather than wrapping it in a fresh {@link PackedCollection} on
 * every call, while {@code into(destination)} copies those held values into the
 * caller-owned destination. These pin the behaviour of the no-argument
 * {@code evaluate()} path, which otherwise allocated and copied into a throwaway
 * buffer on each evaluation.
 */
public class RandomTest extends TestSuiteBase {
	/**
	 * {@link Random#get()}'s {@code evaluate()} returns the cached values collection
	 * directly, so the shape is preserved and repeated evaluations return the same
	 * instance holding the same values; values are generated once and held until
	 * {@link Random#refresh()}. The values are also checked to lie in the uniform
	 * {@code [0, 1)} range produced by a non-normal generator.
	 */
	@Test(timeout = 10000)
	public void evaluateReturnsHeldValues() {
		TraversalPolicy shape = new TraversalPolicy(3, 4);
		Random random = new Random(shape, false, 42L);
		Evaluable<PackedCollection> evaluable = random.get();

		PackedCollection first = evaluable.evaluate();
		PackedCollection second = evaluable.evaluate();

		Assert.assertEquals(shape, first.getShape());
		Assert.assertSame(first, second);

		for (int i = 0; i < shape.getTotalSize(); i++) {
			double value = first.toDouble(i);
			Assert.assertEquals(value, second.toDouble(i), 0.0);
			Assert.assertTrue(value >= 0.0 && value < 1.0);
		}
	}

	/**
	 * {@code into(destination)} fills the caller-owned destination with the held
	 * values. The returned collection is the destination, distinct from the held
	 * cache, and it carries exactly the same values {@code evaluate()} returns.
	 */
	@Test(timeout = 10000)
	public void intoCopiesHeldValuesIntoDestination() {
		TraversalPolicy shape = new TraversalPolicy(2, 5);
		Random random = new Random(shape, true, 7L);
		Evaluable<PackedCollection> evaluable = random.get();

		PackedCollection held = evaluable.evaluate();

		PackedCollection destination = new PackedCollection(shape);
		PackedCollection result = evaluable.into(destination).evaluate();

		Assert.assertSame(destination, result);
		Assert.assertNotSame(held, result);

		for (int i = 0; i < shape.getTotalSize(); i++) {
			Assert.assertEquals(held.toDouble(i), result.toDouble(i), 0.0);
		}
	}

	/**
	 * Two generators sharing a single {@link java.util.Random} source draw their
	 * values in construction order, not in the order they are later evaluated.
	 * The first-constructed generator holds the first slice of the seeded source
	 * and the second-constructed generator the next slice, even when the second
	 * is evaluated before the first. This pins the reproducibility contract that
	 * the drawing happens at construction: were it deferred to first evaluation,
	 * evaluating the second generator first would hand it the first slice, so the
	 * two generators' values would depend on evaluation (kernel scheduling) order
	 * rather than on the seed.
	 */
	@Test(timeout = 10000)
	public void sharedSourceDrawsInConstructionOrderNotEvaluationOrder() {
		TraversalPolicy shape = new TraversalPolicy(4);
		int size = shape.getTotalSize();
		long seed = 42L;

		// Draws the first and second constructed generators must each hold
		java.util.Random reference = new java.util.Random(seed);
		double[] expectedFirst = new double[size];
		double[] expectedSecond = new double[size];
		for (int i = 0; i < size; i++) expectedFirst[i] = reference.nextDouble();
		for (int i = 0; i < size; i++) expectedSecond[i] = reference.nextDouble();

		java.util.Random shared = new java.util.Random(seed);
		Random first = new Random(shape, false, shared);
		Random second = new Random(shape, false, shared);

		// Evaluate in the reverse of construction order
		PackedCollection secondValues = second.get().evaluate();
		PackedCollection firstValues = first.get().evaluate();

		// The collections hold the draws at the hardware precision (FP32 rounds them),
		// which is far finer than the gap between distinct draws of the seeded source
		double tolerance = Hardware.getLocalHardware().getPrecision().epsilon();

		for (int i = 0; i < size; i++) {
			Assert.assertEquals(expectedFirst[i], firstValues.toDouble(i), tolerance);
			Assert.assertEquals(expectedSecond[i], secondValues.toDouble(i), tolerance);
		}
	}

	/**
	 * {@link Random#refresh()} clears the cache so the next evaluation regenerates
	 * the values, producing a different series than the one held before the refresh.
	 */
	@Test(timeout = 10000)
	public void refreshRegeneratesValues() {
		TraversalPolicy shape = new TraversalPolicy(32);
		Random random = new Random(shape, false, 123L);
		Evaluable<PackedCollection> evaluable = random.get();

		int size = shape.getTotalSize();
		double[] before = new double[size];
		PackedCollection firstValues = evaluable.evaluate();
		for (int i = 0; i < size; i++) before[i] = firstValues.toDouble(i);

		random.refresh();
		PackedCollection after = evaluable.evaluate();

		boolean changed = false;
		for (int i = 0; i < size; i++) {
			if (before[i] != after.toDouble(i)) {
				changed = true;
				break;
			}
		}
		Assert.assertTrue("refresh() should regenerate the held values", changed);
	}
}
