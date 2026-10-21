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

package org.almostrealism.raytrace.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShaderSet;
import org.almostrealism.raytrace.DistanceEstimationLightingEngine;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Tests for {@link DistanceEstimationLightingEngine.Locus}, the ray-marched intersection point.
 * The enclosing engine's main constructor is uninstantiable (it passes a {@code null} intersection
 * field to the {@code LightingEngine} super-constructor, which dereferences it), so these tests
 * exercise the inner {@link DistanceEstimationLightingEngine.Locus} class directly.
 */
public class DistanceEstimationLightingEngineTest extends TestSuiteBase {

	/** Verifies the ray marching step budget constant is exposed with its documented value. */
	@Test(timeout = 5000)
	public void testMaxRayStepsConstant() {
		assertEquals(30, DistanceEstimationLightingEngine.MAX_RAY_STEPS);
	}

	/**
	 * Verifies {@link DistanceEstimationLightingEngine.Locus#getNormalAt} returns the normal the
	 * locus was constructed with (stored as the direction of its single ray).
	 */
	@Test(timeout = 30000)
	public void testLocusNormal() {
		DistanceEstimationLightingEngine.Locus locus =
				new DistanceEstimationLightingEngine.Locus(
						new Vector(1.0, 2.0, 3.0), new Vector(0.0, 0.0, 1.0), null, null);

		Producer<PackedCollection> normal = locus.getNormalAt(vector(0.0, 0.0, 0.0));
		double[] n = normal.get().evaluate().toArray();
		assertEquals(0.0, n[0], 1e-6);
		assertEquals(0.0, n[1], 1e-6);
		assertEquals(1.0, n[2], 1e-6);
	}

	/** Verifies {@link DistanceEstimationLightingEngine.Locus#getShaders} returns the shader set it was given. */
	@Test(timeout = 5000)
	public void testLocusShaders() {
		ShaderSet shaders = new ShaderSet();
		DistanceEstimationLightingEngine.Locus locus =
				new DistanceEstimationLightingEngine.Locus(
						new Vector(0.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0), shaders, null);
		assertSame(shaders, locus.getShaders());
	}

	/**
	 * Verifies {@link DistanceEstimationLightingEngine.Locus#shade} returns {@code null} when the
	 * locus has no shaders, and that {@link DistanceEstimationLightingEngine.Locus#call} delegates
	 * to {@code shade} (also {@code null} here).
	 */
	@Test(timeout = 5000)
	public void testLocusShadeWithoutShaders() {
		DistanceEstimationLightingEngine.Locus locus =
				new DistanceEstimationLightingEngine.Locus(
						new Vector(0.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0), null, null);
		assertNull(locus.shade(null));
		assertNull(locus.call());
	}

	/** Verifies {@link DistanceEstimationLightingEngine.Locus#toString} reflects its stored ray, not the literal "null". */
	@Test(timeout = 5000)
	public void testLocusToString() {
		DistanceEstimationLightingEngine.Locus locus =
				new DistanceEstimationLightingEngine.Locus(
						new Vector(4.0, 5.0, 6.0), new Vector(1.0, 0.0, 0.0), null, null);
		assertNotEquals("null", locus.toString());
	}
}
