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
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShaderSet;
import org.almostrealism.geometry.Ray;
import org.almostrealism.raytrace.RayMarchingEngine;
import org.almostrealism.space.DistanceEstimator;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link RayMarchingEngine}, verifying the surface-property producers it exposes
 * (fixed gray base color, marched normal) and the currently-incomplete {@link RayMarchingEngine#trace}
 * / {@link RayMarchingEngine#shade} behavior.
 */
public class RayMarchingEngineTest extends TestSuiteBase {

	/**
	 * Builds a {@link RayMarchingEngine} with the given shaders and a trivial distance estimator.
	 *
	 * @param shaders The shader set the engine should apply at marched points
	 * @return A new ray marching engine
	 */
	private RayMarchingEngine engine(ShaderSet shaders) {
		Light l = new PointLight(new Vector(1.0, 1.0, 1.0));
		DistanceEstimator estimator = ray -> 0.0;
		return new RayMarchingEngine(new ArrayList<>(), new Light[] { l }, l, estimator, shaders);
	}

	/**
	 * Verifies {@link RayMarchingEngine#trace} currently returns {@code null} (ray marching
	 * integration is not yet implemented).
	 */
	@Test(timeout = 30000)
	public void testTraceNotImplemented() {
		RayMarchingEngine engine = engine(new ShaderSet());
		assertNull(engine.trace(null));
	}

	/** Verifies {@link RayMarchingEngine#getValueAt} returns the documented fixed gray base color. */
	@Test(timeout = 30000)
	public void testGetValueAtFixedGray() {
		RayMarchingEngine engine = engine(new ShaderSet());
		RGB color = new RGB(engine.getValueAt(vector(0.0, 0.0, 0.0)).get().evaluate(), 0);
		assertEquals(0.8, color.getRed(), 1e-6);
		assertEquals(0.8, color.getGreen(), 1e-6);
		assertEquals(0.8, color.getBlue(), 1e-6);
	}

	/**
	 * Verifies {@link RayMarchingEngine#getNormalAt} returns the direction of the first stored
	 * ray producer (the marched intersection ray).
	 */
	@Test(timeout = 30000)
	public void testGetNormalAtFromFirstRay() {
		RayMarchingEngine engine = engine(new ShaderSet());
		engine.add(p(new Ray(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0))));

		Producer<PackedCollection> normal = engine.getNormalAt(vector(0.0, 0.0, 0.0));
		double[] n = normal.get().evaluate().toArray();
		assertEquals(0.0, n[0], 1e-6);
		assertEquals(1.0, n[1], 1e-6);
		assertEquals(0.0, n[2], 1e-6);
	}

	/**
	 * Verifies {@link RayMarchingEngine#shade} returns {@code null} when the shader set is empty
	 * (there is no shader contribution to accumulate).
	 */
	@Test(timeout = 30000)
	public void testShadeWithNoShaders() {
		RayMarchingEngine engine = engine(new ShaderSet());
		assertNull(engine.shade(null));
	}
}
