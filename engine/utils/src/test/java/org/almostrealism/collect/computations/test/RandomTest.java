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
