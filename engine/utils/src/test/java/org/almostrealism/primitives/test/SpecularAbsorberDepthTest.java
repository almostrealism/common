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
import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.physics.Clock;
import org.almostrealism.primitives.SpecularAbsorber;
import org.almostrealism.primitives.SphericalVolume;
import org.almostrealism.space.distribution.ReflectiveProbabilityDistribution;
import org.almostrealism.stats.SphericalProbabilityDistribution;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests the depth gates and configuration of {@link SpecularAbsorber}: the absorb-depth test that
 * refuses photons too deep inside the volume, the reflect-depth test that passes them straight
 * through on emission, and the round-tripping of its wavelength range, BRDF, spectra and recorded
 * arrival position.
 */
public class SpecularAbsorberDepthTest extends TestSuiteBase {
	/** The tolerance for comparing wavelengths, depths, and directions. */
	private static final double TOLERANCE = 1e-9;

	/** The direction of an arriving photon, travelling downwards. */
	private static final Vector DOWN = new Vector(0.0, 0.0, -1.0);

	/**
	 * Returns an absorber confined to a ball of radius one, with a clock at time zero.
	 *
	 * @return the absorber
	 */
	private SpecularAbsorber absorber() {
		SpecularAbsorber a = new SpecularAbsorber();
		a.setVolume(new SphericalVolume(1.0));
		a.setClock(new Clock());
		return a;
	}

	/** A positive absorb depth refuses a photon whose surface is farther than the depth on both sides. */
	@Test(timeout = 30000)
	public void absorbDepthRefusesDeepPhotons() {
		SpecularAbsorber shallow = absorber();
		shallow.setAbsorbDepth(0.5);
		// At the centre the ball surface is a full radius away on either side, exceeding the depth.
		assertFalse(shallow.absorb(new Vector(0.0, 0.0, 0.0), DOWN, 2.0));

		SpecularAbsorber deep = absorber();
		deep.setAbsorbDepth(2.0);
		// A depth larger than the ball never trips, so the photon is still absorbed.
		assertTrue(deep.absorb(new Vector(0.0, 0.0, 0.0), DOWN, 2.0));
	}

	/** A photon that fails the reflect-depth test passes through unchanged instead of scattering. */
	@Test(timeout = 30000)
	public void reflectDepthPassesPhotonThrough() {
		SpecularAbsorber a = absorber();
		a.setReflectDepth(0.5);
		a.absorb(new Vector(0.0, 0.0, 0.0), DOWN, 2.0);

		Vector out = new Vector(a.emit().get().evaluate(), 0);
		assertEquals(0.0, out.getX(), TOLERANCE);
		assertEquals(0.0, out.getY(), TOLERANCE);
		assertEquals(-1.0, out.getZ(), TOLERANCE);
	}

	/** The depth gates round-trip through their accessors. */
	@Test(timeout = 30000)
	public void depthsRoundTrip() {
		SpecularAbsorber a = absorber();
		a.setAbsorbDepth(1.5);
		a.setReflectDepth(-2.5);
		assertEquals(1.5, a.getAbsorbDepth(), TOLERANCE);
		assertEquals(-2.5, a.getReflectDepth(), TOLERANCE);
	}

	/** The wavelength range is stored as a start and a length and reported together. */
	@Test(timeout = 30000)
	public void colorRangeRoundTrips() {
		SpecularAbsorber a = absorber();
		a.setColorRangeStart(400.0);
		a.setColorRangeLength(150.0);

		assertEquals(400.0, a.getColorRangeStart(), TOLERANCE);
		assertEquals(150.0, a.getColorRangeLength(), TOLERANCE);

		double[] range = a.getColorRange();
		assertEquals(400.0, range[0], TOLERANCE);
		assertEquals(150.0, range[1], TOLERANCE);
	}

	/** The BRDF, spectra and recorded arrival position round-trip through their accessors. */
	@Test(timeout = 30000)
	public void configurationRoundTrips() {
		SpecularAbsorber a = absorber();

		SphericalProbabilityDistribution brdf = new ReflectiveProbabilityDistribution();
		a.setBRDF(brdf);
		assertTrue(a.getBRDF() == brdf);

		ProbabilityDistribution spectra = new ProbabilityDistribution();
		a.setSpectra(spectra);
		assertTrue(a.getSpectra() == spectra);

		a.setOrigPosition(new double[] { 1.0, 2.0, 3.0 });
		double[] orig = a.getOrigPosition();
		assertEquals(1.0, orig[0], TOLERANCE);
		assertEquals(2.0, orig[1], TOLERANCE);
		assertEquals(3.0, orig[2], TOLERANCE);
	}
}
