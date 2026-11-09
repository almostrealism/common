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
import org.almostrealism.space.distribution.OverlayBRDF;
import org.almostrealism.space.distribution.ReflectiveProbabilityDistribution;
import org.almostrealism.space.distribution.RefractiveProbabilityDistribution;
import org.almostrealism.space.distribution.UniformHemisphericalDistribution;
import org.almostrealism.stats.SphericalProbabilityDistribution;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests the outgoing directions chosen by the distributions in
 * {@link org.almostrealism.space.distribution}, with the surface normal along {@code +z}.
 */
public class DirectionDistributionTest extends TestSuiteBase {
	/** The surface normal used throughout. */
	private static final Vector NORMAL = new Vector(0.0, 0.0, 1.0);

	/** The tolerance for comparing directions. */
	private static final double TOLERANCE = 1e-9;

	/**
	 * Returns one sample from the given distribution.
	 *
	 * @param d  the distribution
	 * @param in the unit vector towards the photon's origin
	 * @return the sampled direction
	 */
	private Vector sample(SphericalProbabilityDistribution d, Vector in) {
		return new Vector(d.getSample(in.toArray(), NORMAL.toArray()).get().evaluate(), 0);
	}

	/**
	 * Asserts that two directions are equal component by component.
	 *
	 * @param expected the expected direction
	 * @param actual   the actual direction
	 */
	private void assertDirection(Vector expected, Vector actual) {
		assertEquals("x", expected.getX(), actual.getX(), TOLERANCE);
		assertEquals("y", expected.getY(), actual.getY(), TOLERANCE);
		assertEquals("z", expected.getZ(), actual.getZ(), TOLERANCE);
	}

	/** A mirror sends the photon back out on the far side of the normal. */
	@Test(timeout = 30000)
	public void reflectionMirrorsAboutNormal() {
		assertDirection(new Vector(-0.6, 0.0, 0.8),
				sample(new ReflectiveProbabilityDistribution(), new Vector(0.6, 0.0, 0.8)));
	}

	/** The multiplier scales the sampled direction. */
	@Test(timeout = 30000)
	public void multiplierScalesSample() {
		ReflectiveProbabilityDistribution d = new ReflectiveProbabilityDistribution();
		d.setMultiplier(2.0);
		assertDirection(new Vector(-1.2, 0.0, 1.6), sample(d, new Vector(0.6, 0.0, 0.8)));
	}

	/**
	 * At 30 degrees into an index of 1.5, the photon continues into the surface, bent towards the
	 * normal so that the sine of its angle is a third.
	 */
	@Test(timeout = 30000)
	public void refractionObeysSnellsLaw() {
		RefractiveProbabilityDistribution d = new RefractiveProbabilityDistribution();
		d.setRefractiveIndex(1.5);

		Vector in = new Vector(0.5, 0.0, Math.sqrt(0.75));
		assertDirection(new Vector(-1.0 / 3.0, 0.0, -Math.sqrt(8.0) / 3.0), sample(d, in));
	}

	/** A photon arriving along the normal passes straight through. */
	@Test(timeout = 30000)
	public void refractionAtNormalIncidenceGoesStraightThrough() {
		RefractiveProbabilityDistribution d = new RefractiveProbabilityDistribution();
		d.setRefractiveIndex(1.5);
		assertDirection(new Vector(0.0, 0.0, -1.0), sample(d, NORMAL));
	}

	/** Beyond the critical angle of an index below one, the photon is reflected instead. */
	@Test(timeout = 30000)
	public void refractionBeyondCriticalAngleReflects() {
		RefractiveProbabilityDistribution d = new RefractiveProbabilityDistribution();
		d.setRefractiveIndex(0.5);

		Vector in = new Vector(Math.sqrt(0.75), 0.0, 0.5);
		assertDirection(new Vector(-Math.sqrt(0.75), 0.0, 0.5), sample(d, in));
	}

	/** Hemispherical samples are unit vectors on the side of the normal. */
	@Test(timeout = 30000)
	public void hemisphericalStaysOnNormalSide() {
		UniformHemisphericalDistribution d = new UniformHemisphericalDistribution();

		for (int i = 0; i < 100; i++) {
			Vector s = sample(d, new Vector(0.6, 0.0, 0.8));
			assertTrue("Sample below the surface: " + s, s.getZ() >= 0.0);
			assertEquals(1.0, s.length(), 1e-6);
		}
	}

	/** An overlay sums its children's samples and, by default, normalizes the sum. */
	@Test(timeout = 30000)
	public void overlaySumsChildren() {
		Vector in = new Vector(0.6, 0.0, 0.8);
		OverlayBRDF overlay = new OverlayBRDF(new SphericalProbabilityDistribution[] {
				new ReflectiveProbabilityDistribution(), new ReflectiveProbabilityDistribution() });

		assertDirection(new Vector(-0.6, 0.0, 0.8), sample(overlay, in));

		overlay.setNormalizeResult(false);
		assertDirection(new Vector(-1.2, 0.0, 1.6), sample(overlay, in));
	}
}
