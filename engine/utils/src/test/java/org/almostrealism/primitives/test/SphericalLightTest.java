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

package org.almostrealism.primitives.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.primitives.SphericalLight;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link SphericalLight}: its default state, the number of samples it produces, how it divides
 * its intensity across them, the attenuation it stamps on each, and that the samples sit on its
 * surface.
 */
public class SphericalLightTest extends TestSuiteBase {
	/** The tolerance for comparing intensities, coefficients, and radii. */
	private static final double TOLERANCE = 1e-6;

	/** A new spherical light has unit intensity, one sample, and inverse-square attenuation. */
	@Test(timeout = 30000)
	public void defaultsAreUnitIntensityAndInverseSquare() {
		SphericalLight light = new SphericalLight();

		assertEquals(1.0, light.getIntensity(), TOLERANCE);
		assertEquals(1, light.getSampleCount());

		double[] att = light.getAttenuationCoefficients();
		assertEquals(0.0, att[0], TOLERANCE);
		assertEquals(0.0, att[1], TOLERANCE);
		assertEquals(1.0, att[2], TOLERANCE);

		assertEquals("Spherical Light", light.toString());
		assertEquals(0, light.getParticleVertices().length);
	}

	/** Requesting a number of samples yields that many lights, splitting the intensity evenly. */
	@Test(timeout = 30000)
	public void samplesSplitIntensity() {
		// A radius of one leaves the surface transform at unit scale, so each sample sits
		// exactly on the unit sphere regardless of the random angles used to place it.
		SphericalLight light = new SphericalLight(new Vector(0.0, 0.0, 0.0), 1.0);
		light.setIntensity(6.0);
		light.setAttenuationCoefficients(0.1, 0.2, 0.3);

		Light[] samples = light.getSamples(3);
		assertEquals(3, samples.length);

		for (Light l : samples) {
			PointLight p = (PointLight) l;
			assertEquals(2.0, p.getIntensity(), TOLERANCE);

			double[] att = p.getAttenuationCoefficients();
			assertEquals(0.1, att[0], TOLERANCE);
			assertEquals(0.2, att[1], TOLERANCE);
			assertEquals(0.3, att[2], TOLERANCE);

			// The sample lies on the surface of the unit sphere.
			assertEquals(1.0, p.getLocation().length(), TOLERANCE);
		}
	}

	/** The default sample count drives how many lights the no-argument sampler returns. */
	@Test(timeout = 30000)
	public void defaultSampleCountDrivesSampleArray() {
		SphericalLight light = new SphericalLight(new Vector(0.0, 0.0, 0.0), 1.0);
		light.setSampleCount(4);
		assertEquals(4, light.getSampleCount());
		assertEquals(4, light.getSamples().length);
	}
}
