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
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.primitives.SpecularAbsorber;
import org.almostrealism.primitives.SphericalVolume;
import org.almostrealism.space.distribution.ReflectiveProbabilityDistribution;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link SpecularAbsorber}: which photons it absorbs, the order and timing in which it
 * re-emits them, the direction it re-emits them in, and wavelength filtering.
 */
public class SpecularAbsorberTest extends TestSuiteBase {
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

	/**
	 * Returns the energy of a photon of the given wavelength.
	 *
	 * @param nanometres the wavelength, in nanometres
	 * @return the energy, in electron volts
	 */
	private double energy(double nanometres) {
		return PhysicalConstants.HC / (nanometres / 1000.0);
	}

	/** Only photons inside the volume are absorbed, and nothing is absorbed without a volume. */
	@Test(timeout = 30000)
	public void absorbsOnlyInsideVolume() {
		SpecularAbsorber a = absorber();
		assertTrue(a.absorb(new Vector(0.0, 0.0, 0.5), DOWN, 2.0));
		assertFalse(a.absorb(new Vector(0.0, 0.0, 2.0), DOWN, 2.0));
		assertFalse(new SpecularAbsorber().absorb(new Vector(0.0, 0.0, 0.0), DOWN, 2.0));
	}

	/**
	 * Photons are re-emitted earliest first, each after the delay in effect when it arrived, and
	 * photons due at the same time are all kept.
	 */
	@Test(timeout = 30000)
	public void reemitsInTimeOrder() {
		SpecularAbsorber a = absorber();

		a.setAbsorbDelay(5.0);
		a.absorb(new Vector(0.0, 0.0, 0.1), DOWN, 3.0);
		a.setAbsorbDelay(1.0);
		a.absorb(new Vector(0.0, 0.0, 0.2), DOWN, 2.0);
		a.absorb(new Vector(0.0, 0.0, 0.3), DOWN, 2.5);

		assertEquals(1.0, a.getNextEmit());
		double first = a.getEmitEnergy();
		a.emit();
		double second = a.getEmitEnergy();
		a.emit();
		assertEquals(4.5, first + second);

		assertEquals(5.0, a.getNextEmit());
		assertEquals(3.0, a.getEmitEnergy());
		assertEquals(0.1, new Vector(a.getEmitPosition().get().evaluate(), 0).getZ());
		a.emit();

		assertEquals(Double.MAX_VALUE, a.getNextEmit());
	}

	/** With a mirror BRDF, a photon striking the top of the ball bounces off it. */
	@Test(timeout = 30000)
	public void mirrorBrdfReflectsOffSurface() {
		SpecularAbsorber a = absorber();
		a.setBRDF(new ReflectiveProbabilityDistribution());
		a.absorb(new Vector(0.0, 0.0, 1.0), new Vector(0.6, 0.0, -0.8), 2.0);

		Vector out = new Vector(a.emit().get().evaluate(), 0);
		assertEquals(0.6, out.getX(), 1e-6);
		assertEquals(0.8, out.getZ(), 1e-6);
	}

	/** The colour range is in nanometres: photons outside it are absorbed but never re-emitted. */
	@Test(timeout = 30000)
	public void colorRangeIsInNanometres() {
		SpecularAbsorber a = absorber();
		a.setColorRange(400.0, 100.0);

		assertTrue(a.absorb(new Vector(0.0, 0.0, 0.0), DOWN, energy(700.0)));
		assertEquals(Double.MAX_VALUE, a.getNextEmit());

		assertTrue(a.absorb(new Vector(0.0, 0.0, 0.0), DOWN, energy(450.0)));
		assertEquals(energy(450.0), a.getEmitEnergy());
	}

	/** Emitting with no photon waiting is an error. */
	@Test(timeout = 30000, expected = IllegalStateException.class)
	public void emitWithNothingWaitingThrows() {
		absorber().emit();
	}
}
