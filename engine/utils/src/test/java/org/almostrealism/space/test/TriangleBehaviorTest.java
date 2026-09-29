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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Triangle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link Triangle}, covering vertex storage, flag accessors,
 * texture coordinates, particle vertices, and the vertex-data backed constructor.
 */
public class TriangleBehaviorTest extends TestSuiteBase {

	/** The vertices supplied to the constructor are returned in order. */
	@Test(timeout = 30000)
	public void verticesRoundTrip() {
		Vector a = new Vector(0.0, 0.0, 0.0);
		Vector b = new Vector(1.0, 0.0, 0.0);
		Vector c = new Vector(0.0, 1.0, 0.0);
		Triangle t = new Triangle(a, b, c);

		Vector[] v = t.getVertices();
		Assert.assertEquals(3, v.length);
		Assert.assertSame(a, v[0]);
		Assert.assertSame(b, v[1]);
		Assert.assertSame(c, v[2]);
	}

	/** getParticleVertices reports each vertex as an x/y/z triple. */
	@Test(timeout = 30000)
	public void particleVertices() {
		Triangle t = new Triangle(new Vector(1.0, 2.0, 3.0),
				new Vector(4.0, 5.0, 6.0), new Vector(7.0, 8.0, 9.0));

		double[][] pv = t.getParticleVertices();
		Assert.assertArrayEquals(new double[] {1.0, 2.0, 3.0}, pv[0], 1e-12);
		Assert.assertArrayEquals(new double[] {4.0, 5.0, 6.0}, pv[1], 1e-12);
		Assert.assertArrayEquals(new double[] {7.0, 8.0, 9.0}, pv[2], 1e-12);
	}

	/** Without a vertex-data provider, texture coordinates default to zero. */
	@Test(timeout = 30000)
	public void textureCoordinatesDefaultZero() {
		Triangle t = new Triangle(new Vector(0.0, 0.0, 0.0),
				new Vector(1.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0));

		float[][] tc = t.getTextureCoordinates();
		Assert.assertEquals(3, tc.length);
		for (float[] uv : tc) {
			Assert.assertEquals(0f, uv[0], 1e-6f);
			Assert.assertEquals(0f, uv[1], 1e-6f);
		}
	}

	/** The smooth, interpolate-color, and use-transform flags round-trip. */
	@Test(timeout = 30000)
	public void flagAccessors() {
		Triangle t = new Triangle();

		Assert.assertTrue(t.getUseTransform());
		t.setUseTransform(false);
		Assert.assertFalse(t.getUseTransform());

		Assert.assertFalse(t.getSmooth());
		t.setSmooth(true);
		Assert.assertTrue(t.getSmooth());

		Assert.assertFalse(t.getInterpolateVertexColor());
		t.setInterpolateVertexColor(true);
		Assert.assertTrue(t.getInterpolateVertexColor());
	}

	/** The point data bank contains the three vertex positions. */
	@Test(timeout = 30000)
	public void pointDataContainsVertices() {
		Triangle t = new Triangle(new Vector(0.0, 0.0, 0.0),
				new Vector(2.0, 0.0, 0.0), new Vector(0.0, 3.0, 0.0));

		PackedCollection points = t.getPointData();
		Assert.assertEquals(9, points.getMemLength());
		Assert.assertEquals(2.0, points.toDouble(3), 1e-9);
		Assert.assertEquals(3.0, points.toDouble(7), 1e-9);
	}

	/** setVertices recomputes the precomputed 4x3 intersection data. */
	@Test(timeout = 30000)
	public void setVerticesUpdatesData() {
		Triangle t = new Triangle();
		PackedCollection before = t.getData();
		Assert.assertNotNull(before);

		t.setVertices(new Vector(0.0, 0.0, 0.0), new Vector(1.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0));
		PackedCollection after = t.getData();
		Assert.assertNotNull(after);
		// setVertices recomputes the data, producing a distinct backing collection.
		Assert.assertNotSame(before, after);
		// 4 rows x 3 columns of precomputed triangle data.
		Assert.assertEquals(12, after.getMemLength());
	}

	/** toString reports the three vertices. */
	@Test(timeout = 30000)
	public void toStringReportsVertices() {
		Triangle t = new Triangle(new Vector(0.0, 0.0, 0.0),
				new Vector(1.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0));
		String s = t.toString();
		Assert.assertTrue(s.startsWith("Triangle:"));
	}

	/** A triangle backed by vertex data reads positions from the provider. */
	@Test(timeout = 30000)
	public void vertexDataConstructor() {
		DefaultVertexData data = new DefaultVertexData(3, 1);
		data.getVertices().set(0, new Vector(0.0, 0.0, 0.0));
		data.getVertices().set(1, new Vector(5.0, 0.0, 0.0));
		data.getVertices().set(2, new Vector(0.0, 7.0, 0.0));
		data.setTriangle(0, 0, 1, 2);

		Triangle t = new Triangle(0, 1, 2, new RGB(1.0, 1.0, 1.0), data);
		Vector[] v = t.getVertices();
		Assert.assertEquals(5.0, v[1].getX(), 1e-9);
		Assert.assertEquals(7.0, v[2].getY(), 1e-9);

		double[][] pv = t.getParticleVertices();
		Assert.assertEquals(5.0, pv[1][0], 1e-9);
		Assert.assertEquals(7.0, pv[2][1], 1e-9);
	}

	/** getValueAt returns the triangle's flat color when vertex-color interpolation is off. */
	@Test(timeout = 30000)
	public void valueAtReturnsFlatColor() {
		Triangle t = new Triangle(new Vector(0.0, 0.0, 0.0),
				new Vector(1.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0), new RGB(0.2, 0.4, 0.6));
		t.setUseTransform(false);

		PackedCollection c = t.getValueAt(vector(0.25, 0.25, 0.0)).get().evaluate();
		Assert.assertEquals(0.2, c.toDouble(0), 1e-6);
		Assert.assertEquals(0.4, c.toDouble(1), 1e-6);
		Assert.assertEquals(0.6, c.toDouble(2), 1e-6);
	}

	/** calculateBoundingSolid is not yet implemented for triangles. */
	@Test(timeout = 30000, expected = UnsupportedOperationException.class)
	public void boundingSolidUnsupported() {
		Triangle t = new Triangle();
		t.calculateBoundingSolid();
	}
}
