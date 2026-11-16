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
import org.almostrealism.primitives.Pinhole;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link Pinhole}: its radius, the photons it passes or absorbs, and its behaviour as a
 * non-emitting absorber.
 */
public class PinholeTest extends TestSuiteBase {
	/** The tolerance for comparing the radius. */
	private static final double TOLERANCE = 1e-9;

	/**
	 * Returns a pinhole whose up axis is {@code (0, 1, 0)} and whose surface normal is
	 * {@code (0, 0, 1)}, so its across axis is {@code (1, 0, 0)}, with the given hole radius.
	 *
	 * @param radius the hole radius
	 * @return the pinhole
	 */
	private Pinhole pinhole(double radius) {
		Pinhole pinhole = new Pinhole();
		pinhole.setOrientation(new double[] { 0.0, 1.0, 0.0 });
		pinhole.setSurfaceNormal(vector(0.0, 0.0, 1.0));
		pinhole.setRadius(radius);
		return pinhole;
	}

	/** The radius is stored and returned. */
	@Test(timeout = 30000)
	public void radiusRoundTrips() {
		Pinhole pinhole = new Pinhole();
		pinhole.setRadius(2.5);
		assertEquals(2.5, pinhole.getRadius(), TOLERANCE);
	}

	/** A photon inside the hole passes through; one outside the hole but within the slab is absorbed. */
	@Test(timeout = 30000)
	public void holeLetsPhotonsThrough() {
		Pinhole pinhole = pinhole(1.0);

		// Inside the hole (0.5 from the axis): passes through.
		assertFalse(pinhole.absorb(new Vector(0.5, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
		// Outside the hole (2.0 from the axis) but within the slab: absorbed.
		assertTrue(pinhole.absorb(new Vector(2.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0), 1.0));
	}

	/** A photon beyond the slab thickness is ignored regardless of the hole. */
	@Test(timeout = 30000)
	public void photonBeyondSlabIsIgnored() {
		Pinhole pinhole = pinhole(1.0);
		assertFalse(pinhole.absorb(new Vector(2.0, 0.0, 1.0), new Vector(0.0, 0.0, 1.0), 1.0));
	}

	/** A pinhole never emits, and records the clock it is given. */
	@Test(timeout = 30000)
	public void pinholeDoesNotEmit() {
		Pinhole pinhole = pinhole(1.0);

		Clock clock = new Clock();
		pinhole.setClock(clock);
		assertTrue(pinhole.getClock() == clock);

		assertTrue(pinhole.emit() == null);
		assertTrue(pinhole.getEmitPosition() == null);
		assertEquals(0.0, pinhole.getEmitEnergy(), TOLERANCE);
		assertEquals((double) Integer.MAX_VALUE, pinhole.getNextEmit(), TOLERANCE);
	}
}
