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

package org.almostrealism.geometry.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.UniformSphericalRandom;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@link UniformSphericalRandom} actually produces unit vectors that are
 * uniformly distributed over the surface of the sphere, as its name and javadoc promise.
 *
 * <p>For a uniform distribution on the unit sphere the three coordinates are symmetric,
 * so each squared coordinate averages to exactly {@code 1/3} (since
 * {@code x^2 + y^2 + z^2 == 1} for every unit vector). A sampler that draws the polar
 * angle uniformly in angle instead of uniformly in {@code cos(theta)} bunches points at
 * the poles, which drives the mean of {@code z^2} toward {@code 1/2} and the mean of the
 * other two squared coordinates toward {@code 1/4}. The large, deterministic gap between
 * {@code 1/3} and those values makes this a stable check despite the use of random
 * sampling.</p>
 */
public class UniformSphericalRandomTest extends TestSuiteBase {

	/** Number of samples drawn; large enough that the sample means are tightly concentrated. */
	private static final int SAMPLES = 500000;

	/**
	 * Confirms that the sampled unit vectors are uniformly distributed over the sphere:
	 * every squared coordinate averages to {@code 1/3}.
	 */
	@Test(timeout = 30000)
	public void sphereSurfaceIsUniformlyDistributed() {
		double sumX2 = 0.0;
		double sumY2 = 0.0;
		double sumZ2 = 0.0;

		for (int i = 0; i < SAMPLES; i++) {
			// Each sample is a native-memory-backed Vector; close it as soon as its
			// coordinates have been read so the hardware allocator is not exhausted by
			// half a million live samples before the statistical assertions run.
			try (Vector v = UniformSphericalRandom.getInstance().evaluate(null)) {
				double x = v.getX();
				double y = v.getY();
				double z = v.getZ();

				// Every output must lie on the unit sphere regardless of distribution.
				double lengthSquared = x * x + y * y + z * z;
				Assert.assertEquals(1.0, lengthSquared, 0.0001);

				sumX2 += x * x;
				sumY2 += y * y;
				sumZ2 += z * z;
			}
		}

		double meanX2 = sumX2 / SAMPLES;
		double meanY2 = sumY2 / SAMPLES;
		double meanZ2 = sumZ2 / SAMPLES;

		log("mean(x^2) = " + meanX2);
		log("mean(y^2) = " + meanY2);
		log("mean(z^2) = " + meanZ2);

		double expected = 1.0 / 3.0;
		Assert.assertEquals(expected, meanX2, 0.02);
		Assert.assertEquals(expected, meanY2, 0.02);
		Assert.assertEquals(expected, meanZ2, 0.02);
	}
}
