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

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link DefaultVertexData}, covering vertex/color/texture storage,
 * triangle connectivity, and packed mesh-point extraction.
 */
public class DefaultVertexDataTest extends TestSuiteBase {

	/**
	 * Builds a single-triangle vertex data set with distinct positions, colors, and
	 * texture coordinates.
	 *
	 * @return the populated vertex data
	 */
	private DefaultVertexData triangleData() {
		DefaultVertexData data = new DefaultVertexData(3, 1);

		data.getVertices().set(0, new Vector(0.0, 0.0, 0.0));
		data.getVertices().set(1, new Vector(1.0, 0.0, 0.0));
		data.getVertices().set(2, new Vector(0.0, 1.0, 0.0));

		data.getColors().set(0, new RGB(0.1, 0.2, 0.3));
		data.getColors().set(1, new RGB(0.4, 0.5, 0.6));
		data.getColors().set(2, new RGB(0.7, 0.8, 0.9));

		data.getTextureCoordinates().set(0, new Pair(0.0, 0.0));
		data.getTextureCoordinates().set(1, new Pair(1.0, 0.0));
		data.getTextureCoordinates().set(2, new Pair(0.0, 1.0));

		data.setTriangle(0, 0, 1, 2);
		return data;
	}

	/** Vertex and triangle capacities are reported from the constructor arguments. */
	@Test(timeout = 10000)
	public void reportsCapacities() {
		DefaultVertexData data = new DefaultVertexData(3, 1);
		Assert.assertEquals(3, data.getVertexCount());
		Assert.assertEquals(1, data.getTriangleCount());
	}

	/** Positions round-trip through both the vector accessor and the component accessors. */
	@Test(timeout = 10000)
	public void positionAccessors() {
		DefaultVertexData data = triangleData();

		Vector p1 = data.getPosition(1);
		Assert.assertEquals(1.0, p1.getX(), 1e-9);
		Assert.assertEquals(0.0, p1.getY(), 1e-9);
		Assert.assertEquals(0.0, p1.getZ(), 1e-9);

		Assert.assertEquals(0.0, data.getX(2), 1e-9);
		Assert.assertEquals(1.0, data.getY(2), 1e-9);
		Assert.assertEquals(0.0, data.getZ(2), 1e-9);
	}

	/** Colors round-trip through both the RGB accessor and the component accessors. */
	@Test(timeout = 10000)
	public void colorAccessors() {
		DefaultVertexData data = triangleData();

		// Colors round-trip through backend-dependent precision (FP32 on some
		// hardware, FP64 on others), so compare at float precision.
		RGB c0 = data.getColor(0);
		Assert.assertEquals(0.1f, (float) c0.getRed(), 1e-9);
		Assert.assertEquals(0.2f, (float) c0.getGreen(), 1e-9);
		Assert.assertEquals(0.3f, (float) c0.getBlue(), 1e-9);

		Assert.assertEquals(0.4f, (float) data.getRed(1), 1e-9);
		Assert.assertEquals(0.5f, (float) data.getGreen(1), 1e-9);
		Assert.assertEquals(0.6f, (float) data.getBlue(1), 1e-9);
	}

	/** Texture coordinates round-trip through both the pair accessor and the component accessors. */
	@Test(timeout = 10000)
	public void textureAccessors() {
		DefaultVertexData data = triangleData();

		Pair t1 = data.getTexturePosition(1);
		Assert.assertEquals(1.0, t1.getA(), 1e-9);
		Assert.assertEquals(0.0, t1.getB(), 1e-9);

		Assert.assertEquals(0.0, data.getTextureU(2), 1e-9);
		Assert.assertEquals(1.0, data.getTextureV(2), 1e-9);
	}

	/** Triangle connectivity is stored and retrieved as a vertex-index triple. */
	@Test(timeout = 10000)
	public void triangleConnectivity() {
		DefaultVertexData data = triangleData();
		int[] t = data.getTriangle(0);
		Assert.assertArrayEquals(new int[] {0, 1, 2}, t);

		data.setTriangle(0, 2, 1, 0);
		Assert.assertArrayEquals(new int[] {2, 1, 0}, data.getTriangle(0));
	}

	/** getMeshPointData packs each triangle's three vertex positions contiguously. */
	@Test(timeout = 30000)
	public void meshPointDataLayout() {
		DefaultVertexData data = triangleData();
		PackedCollection points = data.getMeshPointData();

		// Shape (triangleCount, 3, 3) => 9 doubles for one triangle.
		Assert.assertEquals(9, points.getMemLength());

		// Vertex 0 = (0,0,0), vertex 1 = (1,0,0), vertex 2 = (0,1,0).
		Assert.assertEquals(0.0, points.toDouble(0), 1e-9);
		Assert.assertEquals(1.0, points.toDouble(3), 1e-9);
		Assert.assertEquals(0.0, points.toDouble(4), 1e-9);
		Assert.assertEquals(1.0, points.toDouble(7), 1e-9);
	}
}
