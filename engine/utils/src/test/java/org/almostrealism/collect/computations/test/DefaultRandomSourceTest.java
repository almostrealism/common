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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultRandomSource;
import org.almostrealism.collect.computations.RandomProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Tests for {@link DefaultRandomSource}, the one process-wide source that {@code rand}/{@code randn}
 * draw from when given no explicit {@link Random}. These pin the three guarantees the source makes:
 * seeding it makes unseeded fills reproducible, clearing it restores the independent-per-generator
 * behaviour, and the per-test reset in {@link TestSuiteBase} makes a test's first draw identical on
 * every run. The counter implementation is covered as well as the default host implementation, and
 * an explicit source is shown to override the default.
 */
public class DefaultRandomSourceTest extends TestSuiteBase {
	/** Shape of the collections filled in these tests. */
	private static final TraversalPolicy SHAPE = new TraversalPolicy(64);

	/**
	 * Fills a fresh {@code shape(64)} collection with standard normal values from the default
	 * source and returns its host values.
	 *
	 * @return the filled values
	 */
	private double[] normalFill() {
		PackedCollection values = new PackedCollection(SHAPE).randnFill();
		double[] result = values.toArray();
		values.destroy();
		return result;
	}

	/**
	 * Generates standard normal values with the counter implementation, seeded from whatever the
	 * default source currently is.
	 *
	 * @return the generated values
	 */
	private double[] counterFill() {
		RandomProducer random = RandomProducer.Implementation.COUNTER
				.create(SHAPE, true, DefaultRandomSource.current());
		PackedCollection out = random.get().evaluate();
		double[] result = out.toArray();
		out.destroy();
		return result;
	}

	/**
	 * Seeding the default source with the same seed before each of two fills makes the two fills
	 * identical, while a different seed makes them differ. This is the reproducibility guarantee
	 * for the host ({@link org.almostrealism.collect.computations.Random}) implementation.
	 */
	@Test(timeout = 30000)
	public void sameSeedReproducesHostFill() {
		DefaultRandomSource.seed(7);
		double[] first = normalFill();

		DefaultRandomSource.seed(7);
		double[] second = normalFill();
		Assert.assertArrayEquals(first, second, 0.0);

		DefaultRandomSource.seed(8);
		double[] different = normalFill();
		Assert.assertFalse(equal(first, different));
	}

	/**
	 * Seeding the default source makes {@link DefaultRandomSource#getSeed()} report that exact seed,
	 * and {@link DefaultRandomSource#current()} is non-null. This pins the present branch of
	 * {@code getSeed()}, complementing the empty branch asserted in {@link #clearRestoresUnseededHostFill()}.
	 */
	@Test(timeout = 30000)
	public void getSeedReportsSeededValue() {
		DefaultRandomSource.seed(7);
		Assert.assertNotNull(DefaultRandomSource.current());
		Assert.assertTrue(DefaultRandomSource.getSeed().isPresent());
		Assert.assertEquals(7L, DefaultRandomSource.getSeed().getAsLong());

		DefaultRandomSource.seed(8);
		Assert.assertEquals(8L, DefaultRandomSource.getSeed().getAsLong());
	}

	/**
	 * After {@link DefaultRandomSource#clear()} the source is unseeded, so each generator draws
	 * from its own fresh {@link Random} and two fills differ. This is the production default, where
	 * behaviour is unchanged unless the source is configured.
	 */
	@Test(timeout = 30000)
	public void clearRestoresUnseededHostFill() {
		DefaultRandomSource.clear();
		Assert.assertNull(DefaultRandomSource.current());
		Assert.assertFalse(DefaultRandomSource.getSeed().isPresent());

		double[] first = normalFill();
		double[] second = normalFill();
		Assert.assertFalse(equal(first, second));
	}

	/**
	 * The same reproducibility holds for the counter implementation, whose seed is drawn from the
	 * default source when it is constructed: the same default seed yields identical kernel output
	 * and a different seed yields different output.
	 */
	@Test(timeout = 60000)
	public void sameSeedReproducesCounterFill() {
		DefaultRandomSource.seed(7);
		double[] first = counterFill();

		DefaultRandomSource.seed(7);
		double[] second = counterFill();
		Assert.assertArrayEquals(first, second, 0.0);

		DefaultRandomSource.seed(8);
		double[] different = counterFill();
		Assert.assertFalse(equal(first, different));
	}

	/**
	 * Calling the {@link TestSuiteBase#resetRandomSeed()} hook twice reseeds the default source to
	 * the same test seed each time, so the first {@code randn} draw after each reset is identical.
	 * This is the property that makes a test's unseeded inputs independent of the order tests run in.
	 */
	@Test(timeout = 30000)
	public void perTestResetReproducesFirstDraw() {
		resetRandomSeed();
		double[] first = normalFill();

		resetRandomSeed();
		double[] second = normalFill();
		Assert.assertArrayEquals(first, second, 0.0);
	}

	/**
	 * An explicit source passed to {@code randnFill} overrides the default source entirely: the
	 * same explicit seed reproduces the fill whatever the default source is set to.
	 */
	@Test(timeout = 30000)
	public void explicitSourceOverridesDefault() {
		DefaultRandomSource.seed(7);
		PackedCollection a = new PackedCollection(SHAPE).randnFill(new Random(99));
		double[] first = a.toArray();
		a.destroy();

		DefaultRandomSource.seed(8);
		PackedCollection b = new PackedCollection(SHAPE).randnFill(new Random(99));
		double[] second = b.toArray();
		b.destroy();

		Assert.assertArrayEquals(first, second, 0.0);
	}

	/**
	 * Returns whether two arrays of equal length hold identical values.
	 *
	 * @param a the first array
	 * @param b the second array
	 * @return {@code true} when every element is equal
	 */
	private static boolean equal(double[] a, double[] b) {
		if (a.length != b.length) return false;

		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) return false;
		}

		return true;
	}
}
