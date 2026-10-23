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

import org.almostrealism.algebra.Pair;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Function;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link RenderParameters}, verifying the default supersampling values, the
 * full-image and region constructors, and the pixel-to-screen coordinate mapping returned
 * by {@link RenderParameters#positionForIndices()}.
 */
public class RenderParametersTest extends TestSuiteBase {

	/** Verifies the no-argument constructor leaves the documented 2x2 supersampling default. */
	@Test(timeout = 5000)
	public void testDefaultSupersampling() {
		RenderParameters params = new RenderParameters();
		assertEquals(2, params.ssWidth);
		assertEquals(2, params.ssHeight);
		assertEquals(0, params.width);
		assertEquals(0, params.height);
	}

	/** Verifies the full-image constructor sets dimensions and a full-image render region. */
	@Test(timeout = 5000)
	public void testFullImageConstructor() {
		RenderParameters params = new RenderParameters(1920, 1080, 3, 4);
		assertEquals(1920, params.width);
		assertEquals(1080, params.height);
		assertEquals(3, params.ssWidth);
		assertEquals(4, params.ssHeight);
		assertEquals(0, params.x);
		assertEquals(0, params.y);
		assertEquals(1920, params.dx);
		assertEquals(1080, params.dy);
	}

	/** Verifies the region constructor stores the offset, region size, and full image size independently. */
	@Test(timeout = 5000)
	public void testRegionConstructor() {
		RenderParameters params = new RenderParameters(10, 20, 30, 40, 800, 600, 1, 1);
		assertEquals(10, params.x);
		assertEquals(20, params.y);
		assertEquals(30, params.dx);
		assertEquals(40, params.dy);
		assertEquals(800, params.width);
		assertEquals(600, params.height);
		assertEquals(1, params.ssWidth);
		assertEquals(1, params.ssHeight);
	}

	/**
	 * Verifies {@link RenderParameters#positionForIndices()} offsets by the region origin and flips
	 * the Y-axis using {@code height - 1 - j - y}.
	 */
	@Test(timeout = 5000)
	public void testPositionForIndices() {
		RenderParameters params = new RenderParameters(5, 7, 100, 100, 100, 100, 1, 1);
		Function<Pair, Pair> f = params.positionForIndices();

		Pair origin = f.apply(new Pair(0, 0));
		assertEquals(5.0, origin.getX(), 0.0);
		assertEquals(100 - 1 - 0 - 7, origin.getY(), 0.0);

		Pair offset = f.apply(new Pair(3, 4));
		assertEquals(8.0, offset.getX(), 0.0);
		assertEquals(100 - 1 - 4 - 7, offset.getY(), 0.0);
	}
}
