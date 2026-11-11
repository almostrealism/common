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

package org.almostrealism.geometry.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.geometry.Ray;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Checks the secondary-ray directions of {@link GeometryFeatures} against Snell's law and the
 * mirror law computed by hand: {@link GeometryFeatures#refract} entering a medium, leaving it,
 * under total internal reflection and with an index of 1, the glossy
 * {@link GeometryFeatures#reflect(Producer, Producer, double)}, and the offset
 * {@link org.almostrealism.geometry.RayFeatures#ray(Producer, Producer, double)}.
 *
 * <p>Every case uses the surface normal {@code (0, 0, 1)}; a viewer vector with a positive z
 * component is outside the medium, one with a negative z component is inside it.</p>
 */
public class GeometryFeaturesTest extends TestSuiteBase {
	/** The tolerance for comparing evaluated directions with their analytic values. */
	private static final double EPSILON = 1e-5;

	/**
	 * Evaluates a producer of a 3-component vector.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated vector
	 */
	private Vector evaluateVector(Producer<PackedCollection> producer) {
		return new Vector(producer.get().evaluate(), 0);
	}

	/**
	 * Asserts that an evaluated vector equals the expected one, component by component.
	 *
	 * @param label    describes the case, for the failure message
	 * @param expected the expected vector
	 * @param actual   the evaluated vector
	 */
	private void assertVector(String label, Vector expected, Vector actual) {
		Assert.assertEquals(label + " x", expected.getX(), actual.getX(), EPSILON);
		Assert.assertEquals(label + " y", expected.getY(), actual.getY(), EPSILON);
		Assert.assertEquals(label + " z", expected.getZ(), actual.getZ(), EPSILON);
	}

	/**
	 * A ray entering glass (index 1.5) at sin(theta) = 0.6 bends toward the normal to
	 * sin(theta) = 0.4, so its direction of travel is (-0.4, 0, -sqrt(0.84)).
	 */
	@Test(timeout = 60000)
	public void refractEnteringBendsTowardNormal() {
		Vector t = evaluateVector(refract(vector(0.6, 0.0, 0.8), vector(0.0, 0.0, 1.0), 1.5));
		assertVector("entering", new Vector(-0.4, 0.0, -Math.sqrt(0.84)), t);
	}

	/**
	 * A ray leaving glass at sin(theta) = 0.4 bends away from the normal back to
	 * sin(theta) = 0.6: the exit interface undoes the entry, giving (-0.6, 0, 0.8).
	 */
	@Test(timeout = 60000)
	public void refractLeavingBendsAwayFromNormal() {
		Vector t = evaluateVector(refract(vector(0.4, 0.0, -Math.sqrt(0.84)), vector(0.0, 0.0, 1.0), 1.5));
		assertVector("leaving", new Vector(-0.6, 0.0, 0.8), t);
	}

	/**
	 * Inside glass at sin(theta) = 0.8, beyond the critical angle (sin = 1 / 1.5), the ray is
	 * totally internally reflected: it stays inside, mirrored about the boundary, travelling
	 * (-0.8, 0, -0.6).
	 */
	@Test(timeout = 60000)
	public void refractBeyondCriticalAngleReflectsInternally() {
		Vector t = evaluateVector(refract(vector(0.8, 0.0, -0.6), vector(0.0, 0.0, 1.0), 1.5));
		assertVector("total internal reflection", new Vector(-0.8, 0.0, -0.6), t);
	}

	/**
	 * Entering at sin(theta) = 0.8 is not total internal reflection, since a ray entering a
	 * denser medium always refracts: it bends to sin(theta) = 0.8 / 1.5.
	 */
	@Test(timeout = 60000)
	public void refractEnteringAtGrazingAngleStillTransmits() {
		double sin = 0.8 / 1.5;
		Vector t = evaluateVector(refract(vector(0.8, 0.0, 0.6), vector(0.0, 0.0, 1.0), 1.5));
		assertVector("entering steeply", new Vector(-sin, 0.0, -Math.sqrt(1.0 - sin * sin)), t);
	}

	/**
	 * With an index of 1 the ray passes straight through, and the inputs need not be unit
	 * length: the result is the unit incident direction of travel.
	 */
	@Test(timeout = 60000)
	public void refractIndexOneIsUndeflectedAndNormalized() {
		Vector t = evaluateVector(refract(vector(1.8, 0.0, 2.4), vector(0.0, 0.0, 2.0), 1.0));
		assertVector("index 1", new Vector(-0.6, 0.0, -0.8), t);
	}

	/**
	 * With no blur the glossy reflection is the mirror reflection: viewer (0.6, 0, 0.8) about
	 * the normal (0, 0, 1) leaves travelling (-0.6, 0, 0.8).
	 */
	@Test(timeout = 60000)
	public void reflectWithoutBlurIsMirror() {
		Vector r = evaluateVector(reflect(vector(0.6, 0.0, 0.8), vector(0.0, 0.0, 1.0), 0.0));
		assertVector("mirror", new Vector(-0.6, 0.0, 0.8), r);
	}

	/**
	 * A blurred reflection is a unit vector within the angle the blur allows of the mirror
	 * direction. Each tangent offset is at most blur / 2, so the perturbation before
	 * renormalization is at most blur / sqrt(2). Both tangent-frame branches are exercised:
	 * a normal mostly along z and one along x, where the frame is built from a different
	 * helper axis.
	 */
	@Test(timeout = 60000)
	public void reflectWithBlurStaysNearMirror() {
		double blur = 0.2;
		double minCos = Math.cos(Math.atan(blur / Math.sqrt(2.0))) - EPSILON;

		Vector[] viewers = { new Vector(0.6, 0.0, 0.8), new Vector(0.8, 0.6, 0.0) };
		Vector[] normals = { new Vector(0.0, 0.0, 1.0), new Vector(1.0, 0.0, 0.0) };
		Vector[] mirrors = { new Vector(-0.6, 0.0, 0.8), new Vector(0.8, -0.6, 0.0) };

		for (int c = 0; c < viewers.length; c++) {
			Vector r = evaluateVector(reflect(v(viewers[c]), v(normals[c]), blur));

			Assert.assertEquals("case " + c + " length", 1.0, Math.sqrt(r.dotProduct(r)), EPSILON);
			double cos = r.dotProduct(mirrors[c]);
			Assert.assertTrue("case " + c + " deviates too far from the mirror: cos=" + cos, cos >= minCos);
		}
	}

	/**
	 * An offset ray moves its origin the given multiple of its direction and keeps the
	 * direction itself unchanged.
	 */
	@Test(timeout = 60000)
	public void rayOffsetMovesOriginAlongDirection() {
		Ray r = new Ray(ray(vector(1.0, 2.0, 3.0), vector(0.0, 2.0, -4.0), 0.5).get().evaluate(), 0);
		assertVector("origin", new Vector(1.0, 3.0, 1.0), r.getOrigin());
		assertVector("direction", new Vector(0.0, 2.0, -4.0), r.getDirection());
	}
}
