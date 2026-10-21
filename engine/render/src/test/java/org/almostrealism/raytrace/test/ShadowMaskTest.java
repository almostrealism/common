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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.AmbientLight;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Intersectable;
import org.almostrealism.raytrace.ShadowMask;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link ShadowMask}, verifying the lit/shadowed decision for the cases that do not
 * require evaluating scene geometry: a point that evaluates to {@code null} (treated as lit) and
 * an unsupported light type (no shadow computation).
 */
public class ShadowMaskTest extends TestSuiteBase {

	/** Asserts the given color is white (1, 1, 1). */
	private void assertWhite(RGB c) {
		assertEquals(1.0, c.getRed(), 1e-6);
		assertEquals(1.0, c.getGreen(), 1e-6);
		assertEquals(1.0, c.getBlue(), 1e-6);
	}

	/**
	 * Verifies a {@code null} evaluated point is treated as fully lit (white), short-circuiting
	 * before any shadow ray is constructed.
	 */
	@Test(timeout = 5000)
	public void testNullPointIsLit() {
		Evaluable<PackedCollection> point = args -> null;
		ShadowMask mask = new ShadowMask(new PointLight(new Vector(0.0, 0.0, 10.0)),
				new ArrayList<>(), point);
		assertWhite(mask.evaluate(new Object[0]));
	}

	/**
	 * Verifies an unsupported light type (neither point nor directional) performs no shadow
	 * computation and reports the point as fully lit (white).
	 */
	@Test(timeout = 5000)
	public void testUnsupportedLightIsLit() {
		List<Intersectable> surfaces = new ArrayList<>();
		Evaluable<PackedCollection> point = args -> new Vector(0.5, 0.0, 0.0);
		ShadowMask mask = new ShadowMask(new AmbientLight(), surfaces, point);
		assertWhite(mask.evaluate(new Object[0]));
	}

	/** Verifies {@link ShadowMask#get()} returns the mask itself (it is its own {@link Evaluable}). */
	@Test(timeout = 5000)
	public void testGetReturnsSelf() {
		ShadowMask mask = new ShadowMask(new AmbientLight(), new ArrayList<>(), args -> null);
		assertEquals(mask, mask.get());
	}
}
