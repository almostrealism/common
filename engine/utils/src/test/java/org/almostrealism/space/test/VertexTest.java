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

package org.almostrealism.space.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.space.Vertex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link Vertex}, covering position inheritance, color storage,
 * and accumulated normal arithmetic.
 */
public class VertexTest extends TestSuiteBase {

	/** A default vertex sits at the origin with a black color and a zero normal. */
	@Test(timeout = 10000)
	public void defaultVertexIsOriginBlack() {
		Vertex v = new Vertex();

		Assert.assertEquals(0.0, v.getX(), 1e-12);
		Assert.assertEquals(0.0, v.getY(), 1e-12);
		Assert.assertEquals(0.0, v.getZ(), 1e-12);

		RGB c = v.getColor();
		Assert.assertEquals(0.0, c.getRed(), 1e-12);
		Assert.assertEquals(0.0, c.getGreen(), 1e-12);
		Assert.assertEquals(0.0, c.getBlue(), 1e-12);

		Vector n = v.getNormal();
		Assert.assertEquals(0.0, n.getX(), 1e-12);
		Assert.assertEquals(0.0, n.getY(), 1e-12);
		Assert.assertEquals(0.0, n.getZ(), 1e-12);
	}

	/** Constructing from a position vector copies the coordinates. */
	@Test(timeout = 10000)
	public void positionConstructorCopiesCoordinates() {
		Vertex v = new Vertex(new Vector(1.5, -2.0, 3.25));

		Assert.assertEquals(1.5, v.getX(), 1e-12);
		Assert.assertEquals(-2.0, v.getY(), 1e-12);
		Assert.assertEquals(3.25, v.getZ(), 1e-12);
	}

	/** The color set on a vertex is returned unchanged, and scales linearly. */
	@Test(timeout = 10000)
	public void colorStoreAndScale() {
		Vertex v = new Vertex();
		v.setColor(new RGB(0.2, 0.4, 0.6));

		RGB c = v.getColor();
		Assert.assertEquals(0.2, c.getRed(), 1e-12);
		Assert.assertEquals(0.4, c.getGreen(), 1e-12);
		Assert.assertEquals(0.6, c.getBlue(), 1e-12);

		RGB scaled = v.getColor(0.5);
		Assert.assertEquals(0.1, scaled.getRed(), 1e-12);
		Assert.assertEquals(0.2, scaled.getGreen(), 1e-12);
		Assert.assertEquals(0.3, scaled.getBlue(), 1e-12);
	}

	/** Setting a normal replaces the stored value and the scaled accessor multiplies it. */
	@Test(timeout = 10000)
	public void normalStoreAndScale() {
		Vertex v = new Vertex();
		v.setNormal(new Vector(0.0, 2.0, -4.0));

		Vector n = v.getNormal();
		Assert.assertEquals(0.0, n.getX(), 1e-12);
		Assert.assertEquals(2.0, n.getY(), 1e-12);
		Assert.assertEquals(-4.0, n.getZ(), 1e-12);

		Vector scaled = v.getNormal(0.5);
		Assert.assertEquals(0.0, scaled.getX(), 1e-12);
		Assert.assertEquals(1.0, scaled.getY(), 1e-12);
		Assert.assertEquals(-2.0, scaled.getZ(), 1e-12);

		// The scaled accessor must not mutate the stored normal.
		Assert.assertEquals(2.0, v.getNormal().getY(), 1e-12);
	}

	/** Adding and removing normals accumulates and reverses component-wise. */
	@Test(timeout = 10000)
	public void addAndRemoveNormalAccumulate() {
		Vertex v = new Vertex();
		v.addNormal(new Vector(1.0, 1.0, 1.0));
		v.addNormal(new Vector(2.0, 0.0, -1.0));

		Vector sum = v.getNormal();
		Assert.assertEquals(3.0, sum.getX(), 1e-12);
		Assert.assertEquals(1.0, sum.getY(), 1e-12);
		Assert.assertEquals(0.0, sum.getZ(), 1e-12);

		v.removeNormal(new Vector(2.0, 0.0, -1.0));
		Vector back = v.getNormal();
		Assert.assertEquals(1.0, back.getX(), 1e-12);
		Assert.assertEquals(1.0, back.getY(), 1e-12);
		Assert.assertEquals(1.0, back.getZ(), 1e-12);
	}
}
