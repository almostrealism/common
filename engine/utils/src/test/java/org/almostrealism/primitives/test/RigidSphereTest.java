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
import org.almostrealism.primitives.RigidSphere;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link RigidSphere}: its rigid-body state, radius, particle vertices, sphere-sphere
 * collision detection, and the optional spherical light it can carry.
 */
public class RigidSphereTest extends TestSuiteBase {
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

	/**
	 * Returns a rigid sphere spinning at the given angular velocity, with the given mass and radius.
	 *
	 * @param w       the angular velocity
	 * @param mass    the mass
	 * @param radius  the radius
	 * @return the sphere
	 */
	private RigidSphere spinningSphere(Vector w, double mass, double radius) {
		return new RigidSphere(ZERO, ZERO, ZERO, w, ZERO, ZERO, mass, 1.0, radius, 4);
	}

	/**
	 * Asserts that the sphere's angular momentum equals {@code I w} for a solid sphere,
	 * where the scalar moment of inertia is {@code (2/5) mass radius^2} on each axis.
	 *
	 * @param s       the sphere
	 * @param w       the angular velocity
	 * @param mass    the mass used to build the inertia tensor
	 * @param radius  the radius used to build the inertia tensor
	 */
	private void assertAngularMomentum(RigidSphere s, Vector w, double mass, double radius) {
		double a = (2.0 / 5.0) * mass * radius * radius;
		Vector l = s.getState().getAngularMomentum();
		assertEquals(a * w.getX(), l.getX(), 1e-6);
		assertEquals(a * w.getY(), l.getY(), 1e-6);
		assertEquals(a * w.getZ(), l.getZ(), 1e-6);
	}

	/** A default rigid sphere has unit radius and one vertex per radial sample pair. */
	@Test(timeout = 30000)
	public void defaultRadiusAndVertices() {
		RigidSphere s = new RigidSphere();
		assertEquals(1.0, s.getRadius(), TOLERANCE);
		// The default radial sample count is four, giving a four-by-four vertex grid.
		assertEquals(16, s.getParticleVertices().length);

		// The state is centred at the origin with unit mass.
		assertEquals(0.0, s.getState().getLocation().length(), TOLERANCE);
		assertEquals(1.0, s.getState().getMass(), TOLERANCE);
	}

	/** Changing the radius moves every vertex onto the new surface. */
	@Test(timeout = 30000)
	public void settingRadiusMovesVertices() {
		RigidSphere s = new RigidSphere();
		s.setRadius(3.0);
		assertEquals(3.0, s.getRadius(), TOLERANCE);

		// Each vertex sits on the sphere of the new radius about the (origin) centre.
		for (double[] v : s.getParticleVertices()) {
			double r = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
			assertEquals(3.0, r, 1e-6);
		}
	}

	/**
	 * Changing the radius rebuilds the inertia tensor and refreshes angular momentum so that
	 * {@code L = I w} holds for the new tensor. A stale momentum (left over from the old radius)
	 * would fail this assertion.
	 */
	@Test(timeout = 30000)
	public void settingRadiusRefreshesAngularMomentum() {
		Vector w = new Vector(1.0, 2.0, 3.0);
		RigidSphere s = spinningSphere(w, 2.0, 1.0);

		// Initially the inertia tensor reflects the construction radius.
		assertAngularMomentum(s, w, 2.0, 1.0);

		s.setRadius(2.0);

		// After the radius change the inertia tensor, and therefore the angular momentum, must grow.
		assertAngularMomentum(s, w, 2.0, 2.0);
	}

	/**
	 * Changing the mass and then triggering a model update (here via {@link RigidSphere#setRadius(double)})
	 * must recompute angular momentum from the rebuilt, mass-dependent inertia tensor. This pins the
	 * {@code updateModel()} refresh against a regression where the rebuilt tensor leaves {@code l} stale.
	 */
	@Test(timeout = 30000)
	public void massChangeRefreshesAngularMomentumOnUpdate() {
		Vector w = new Vector(1.0, 2.0, 3.0);
		RigidSphere s = spinningSphere(w, 2.0, 1.0);

		s.getState().setMass(5.0);
		s.setRadius(1.0);

		// The inertia tensor rebuilt in updateModel reflects the new mass, and so must the momentum.
		assertAngularMomentum(s, w, 5.0, 1.0);
	}

	/** Two overlapping spheres report a contact point on the surface and an outward normal. */
	@Test(timeout = 30000)
	public void overlappingSpheresCollide() {
		RigidSphere a = sphereAt(ZERO);
		RigidSphere b = sphereAt(new Vector(1.5, 0.0, 0.0));

		Vector[] contact = a.intersect(b);
		assertEquals(2, contact.length);

		// The normal points from a towards b, and the contact sits a radius along it.
		assertEquals(1.0, contact[1].getX(), TOLERANCE);
		assertEquals(0.0, contact[1].getY(), TOLERANCE);
		assertEquals(1.0, contact[0].getX(), TOLERANCE);
	}

	/** Two spheres further apart than the sum of their radii do not collide. */
	@Test(timeout = 30000)
	public void separatedSpheresDoNotCollide() {
		RigidSphere a = sphereAt(ZERO);
		RigidSphere b = sphereAt(new Vector(3.0, 0.0, 0.0));
		assertEquals(0, a.intersect(b).length);
	}

	/** Drawing a rigid sphere is not yet supported. */
	@Test(timeout = 30000, expected = UnsupportedOperationException.class)
	public void drawIsUnsupported() {
		new RigidSphere().draw(null, null, 0.0, 0.0, 1.0);
	}

	/** Without lighting enabled the sphere emits no samples and reports zero intensity. */
	@Test(timeout = 30000)
	public void noLightingMeansNoSamples() {
		RigidSphere s = new RigidSphere();
		assertEquals(0, s.getSamples().length);
		assertEquals(0, s.getSamples(5).length);
		assertEquals(0.0, s.getIntensity(), TOLERANCE);
		assertTrue(s.getLight() == null);
	}

	/** Enabling lighting attaches a spherical light placed just outside the surface. */
	@Test(timeout = 30000)
	public void enablingLightingAttachesLight() {
		RigidSphere s = new RigidSphere();
		s.setLighting(true);
		assertTrue(s.getLight() != null);

		s.setIntensity(4.0);
		assertEquals(4.0, s.getIntensity(), TOLERANCE);

		Light[] samples = s.getSamples(2);
		assertEquals(2, samples.length);

		s.setLighting(false);
		assertTrue(s.getLight() == null);
	}
}
