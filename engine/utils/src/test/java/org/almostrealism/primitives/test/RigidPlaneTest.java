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
import org.almostrealism.primitives.RigidPlane;
import org.almostrealism.primitives.RigidSphere;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link RigidPlane}: its rigid-body state and the collision it reports against a
 * {@link RigidSphere} approaching from either side, as well as the bodies it ignores.
 */
public class RigidPlaneTest extends TestSuiteBase {
	/** The tolerance for comparing contact points and normals. */
	private static final double TOLERANCE = 1e-9;

	/** The zero vector, used for the many unused state components. */
	private static final Vector ZERO = new Vector(0.0, 0.0, 0.0);

	/**
	 * Returns a unit-radius rigid sphere centred at the given point.
	 *
	 * @param location the centre of the sphere
	 * @return the sphere
	 */
	private RigidSphere sphereAt(Vector location) {
		return new RigidSphere(location, ZERO, ZERO, ZERO, ZERO, ZERO, 1.0, 1.0, 1.0, 4);
	}

	/** A default rigid plane starts at the origin with unit mass. */
	@Test(timeout = 30000)
	public void defaultStateIsAtOrigin() {
		RigidPlane plane = new RigidPlane();
		assertEquals(0.0, plane.getState().getLocation().length(), TOLERANCE);
		assertEquals(1.0, plane.getState().getMass(), TOLERANCE);
	}

	/** A sphere whose centre sits on or below the plane is contacted with a downward normal. */
	@Test(timeout = 30000)
	public void sphereOnPlaneContactsFromAbove() {
		RigidPlane plane = new RigidPlane();
		Vector[] contact = plane.intersect(sphereAt(ZERO));

		assertEquals(2, contact.length);
		assertEquals(0.0, contact[1].getX(), TOLERANCE);
		assertEquals(-1.0, contact[1].getY(), TOLERANCE);
		assertEquals(0.0, contact[1].getZ(), TOLERANCE);
	}

	/** A sphere whose centre is just above the plane is contacted with an upward normal. */
	@Test(timeout = 30000)
	public void sphereAbovePlaneContactsFromBelow() {
		RigidPlane plane = new RigidPlane();
		Vector[] contact = plane.intersect(sphereAt(new Vector(0.0, 0.5, 0.0)));

		assertEquals(2, contact.length);
		assertEquals(1.0, contact[1].getY(), TOLERANCE);
		// The contact point is projected onto the plane.
		assertEquals(0.0, contact[0].getY(), TOLERANCE);
	}

	/** A sphere whose centre is further than its radius from the plane does not collide. */
	@Test(timeout = 30000)
	public void distantSphereDoesNotCollide() {
		RigidPlane plane = new RigidPlane();
		assertEquals(0, plane.intersect(sphereAt(new Vector(0.0, 5.0, 0.0))).length);
	}

	/** A body that is not a sphere is ignored. */
	@Test(timeout = 30000)
	public void nonSphereIsIgnored() {
		RigidPlane plane = new RigidPlane();
		assertEquals(0, plane.intersect(new RigidPlane()).length);
	}
}
