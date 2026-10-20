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
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.Triangle;
import org.almostrealism.space.Vertex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link Mesh}, covering vertex/triangle management, caching,
 * flag accessors, unsupported operations, and vertex-data backed iteration.
 */
public class MeshTest extends TestSuiteBase {

	/**
	 * Builds a single-triangle mesh from three vertices.
	 *
	 * @return the populated mesh
	 */
	private Mesh singleTriangle() {
		Mesh m = new Mesh();
		int a = m.addVector(new Vector(0.0, 0.0, 0.0));
		int b = m.addVector(new Vector(1.0, 0.0, 0.0));
		int c = m.addVector(new Vector(0.0, 1.0, 0.0));
		m.addTriangle(a, b, c);
		return m;
	}

	/** Adding vectors returns sequential indices and increases the node count. */
	@Test(timeout = 30000)
	public void addVectorReturnsSequentialIndices() {
		Mesh m = new Mesh();
		Assert.assertEquals(0, m.addVector(new Vector(0.0, 0.0, 0.0)));
		Assert.assertEquals(1, m.addVector(new Vector(1.0, 0.0, 0.0)));
		Assert.assertEquals(2, m.addVector(new Vector(0.0, 1.0, 0.0)));
		Assert.assertEquals(3, m.countNodes());
	}

	/** A valid triangle is added at index 0; a degenerate one (repeated index) is rejected. */
	@Test(timeout = 30000)
	public void addTriangleRejectsDegenerate() {
		Mesh m = new Mesh();
		m.addVector(new Vector(0.0, 0.0, 0.0));
		m.addVector(new Vector(1.0, 0.0, 0.0));
		m.addVector(new Vector(0.0, 1.0, 0.0));

		Assert.assertEquals(0, m.addTriangle(0, 1, 2));
		Assert.assertEquals(-1, m.addTriangle(1, 1, 2));
		Assert.assertEquals(-1, m.addTriangle(0, 2, 2));
		Assert.assertEquals(-1, m.addTriangle(2, 1, 2));
	}

	/** Triangle connectivity is retrievable as index triples. */
	@Test(timeout = 30000)
	public void triangleDataRoundTrip() {
		Mesh m = singleTriangle();
		int[][] data = m.getTriangleData();
		Assert.assertEquals(1, data.length);
		Assert.assertArrayEquals(new int[] {0, 1, 2}, data[0]);
	}

	/** Vertices are stored as Vertex objects and located by value. */
	@Test(timeout = 30000)
	public void indexOfAndGetIndex() {
		Mesh m = singleTriangle();
		Vertex[] v = m.getVectors();
		Assert.assertEquals(3, v.length);

		Vector query = new Vector(1.0, 0.0, 0.0);
		Assert.assertEquals(1, m.indexOf(query));
		Assert.assertEquals(-1, m.indexOf(new Vector(9.0, 9.0, 9.0)));
	}

	/** getTriangles builds one Triangle per connectivity entry. */
	@Test(timeout = 30000)
	public void getTrianglesReflectsConnectivity() {
		Mesh m = singleTriangle();
		Triangle[] t = m.getTriangles();
		Assert.assertEquals(1, t.length);

		// getTriangle caches: repeated retrieval yields the same instance.
		Triangle first = m.getTriangle(0);
		Assert.assertSame(first, m.getTriangle(0));
	}

	/** The smooth, back-face, and interpolate-color flags round-trip. */
	@Test(timeout = 30000)
	public void flagAccessors() {
		Mesh m = new Mesh();

		Assert.assertFalse(m.getSmooth());
		m.setSmooth(true);
		Assert.assertTrue(m.getSmooth());

		Assert.assertFalse(m.getRemoveBackFaces());
		m.setRemoveBackFaces(true);
		Assert.assertTrue(m.getRemoveBackFaces());

		Assert.assertFalse(m.getInterpolateColor());
		m.setInterpolateColor(true);
		Assert.assertTrue(m.getInterpolateColor());
	}

	/** triangulate returns the mesh itself. */
	@Test(timeout = 30000)
	public void triangulateReturnsSelf() {
		Mesh m = new Mesh();
		Assert.assertSame(m, m.triangulate());
	}

	/** encode returns the mesh itself when no source is set. */
	@Test(timeout = 30000)
	public void encodeReturnsSelfWithoutSource() {
		Mesh m = new Mesh();
		Assert.assertSame(m, m.encode());
	}

	/** getNormalAt(Vector) is intentionally not implemented and returns null. */
	@Test(timeout = 30000)
	public void normalAtVectorIsNull() {
		Mesh m = new Mesh();
		Assert.assertNull(m.getNormalAt(new Vector(0.0, 0.0, 0.0)));
	}

	/** A fresh mesh has no spatial vertex tree until one is built. */
	@Test(timeout = 30000)
	public void spatialVertexTreeInitiallyNull() {
		Mesh m = new Mesh();
		Assert.assertNull(m.getSpatialVertexTree());
	}

	/** addSurface is unsupported; use addVector/addTriangle. */
	@Test(timeout = 30000, expected = RuntimeException.class)
	public void addSurfaceUnsupported() {
		new Mesh().addSurface(new Triangle());
	}

	/** removeSurface is unsupported. */
	@Test(timeout = 30000, expected = RuntimeException.class)
	public void removeSurfaceUnsupported() {
		new Mesh().removeSurface(0);
	}

	/** getSurfaces is unsupported; use getTriangles. */
	@Test(timeout = 30000, expected = RuntimeException.class)
	public void getSurfacesUnsupported() {
		new Mesh().getSurfaces();
	}

	/** buildSpatialVertexTree is not yet implemented. */
	@Test(timeout = 30000, expected = UnsupportedOperationException.class)
	public void buildSpatialVertexTreeUnsupported() {
		new Mesh().buildSpatialVertexTree();
	}

	/** neighbors is not yet implemented. */
	@Test(timeout = 30000, expected = UnsupportedOperationException.class)
	public void neighborsUnsupported() {
		new Mesh().neighbors(new Vector(0.0, 0.0, 0.0));
	}

	/** calculateBoundingSolid is not yet implemented for meshes. */
	@Test(timeout = 30000, expected = UnsupportedOperationException.class)
	public void boundingSolidUnsupported() {
		new Mesh().calculateBoundingSolid();
	}

	/** setSurfaces is a no-op and does not disturb existing state. */
	@Test(timeout = 30000)
	public void setSurfacesIsNoOp() {
		Mesh m = singleTriangle();
		m.setSurfaces(new Triangle[] {new Triangle()});
		// The connectivity is unchanged by the no-op setter.
		Assert.assertEquals(1, m.getTriangleData().length);
	}

	/** A mesh backed by VertexData iterates triangles from the provider. */
	@Test(timeout = 30000)
	public void vertexDataBackedIteration() {
		DefaultVertexData data = new DefaultVertexData(3, 1);
		data.getVertices().set(0, new Vector(0.0, 0.0, 0.0));
		data.getVertices().set(1, new Vector(1.0, 0.0, 0.0));
		data.getVertices().set(2, new Vector(0.0, 1.0, 0.0));
		data.setTriangle(0, 0, 1, 2);

		Mesh m = new Mesh(data);
		Assert.assertSame(data, m.getVertexData());

		int count = 0;
		for (Triangle t : m.triangles()) {
			Assert.assertNotNull(t);
			count++;
		}
		Assert.assertEquals(1, count);
	}

	/** loadTree builds the BSP acceleration structure and marks the mesh tree-loaded. */
	@Test(timeout = 30000)
	public void treeLoading() {
		Mesh m = singleTriangle();
		Assert.assertFalse(m.isTreeLoaded());
		m.loadTree();
		Assert.assertTrue(m.isTreeLoaded());
	}

	/** getSurface returns the Triangle at the requested index. */
	@Test(timeout = 30000)
	public void getSurfaceReturnsTriangle() {
		Mesh m = singleTriangle();
		Assert.assertTrue(m.getSurface(0) instanceof Triangle);
		Assert.assertEquals(0, m.getIndex(new Vector(0.0, 0.0, 0.0)));
	}

	/** setVectors and setTriangleData replace the mesh contents. */
	@Test(timeout = 30000)
	public void setVectorsAndTriangleData() {
		Mesh m = new Mesh();
		m.setVectors(new Vertex[] {
				new Vertex(new Vector(0.0, 0.0, 0.0)),
				new Vertex(new Vector(1.0, 0.0, 0.0)),
				new Vertex(new Vector(0.0, 1.0, 0.0))
		});
		Assert.assertEquals(3, m.countNodes());

		m.setTriangleData(new int[][] {{0, 1, 2}});
		Assert.assertEquals(1, m.getTriangleData().length);
		Assert.assertArrayEquals(new int[] {0, 1, 2}, m.getTriangleData()[0]);
	}

	/** downsample flags triangles below the perimeter threshold for the given probability. */
	@Test(timeout = 30000)
	public void downsampleFlagsSmallTriangles() {
		Mesh m = singleTriangle();
		// A huge threshold with probability 1.0 always eliminates the single small triangle.
		int removed = m.downsample(1.0e9, 1.0);
		Assert.assertEquals(1, removed);

		// A zero probability never eliminates anything.
		Mesh m2 = singleTriangle();
		Assert.assertEquals(0, m2.downsample(1.0e9, 0.0));
	}

	/** The Vector[]/int[][] constructor populates points and triangles. */
	@Test(timeout = 30000)
	public void arrayConstructor() {
		Vector[] points = {
				new Vector(0.0, 0.0, 0.0),
				new Vector(1.0, 0.0, 0.0),
				new Vector(0.0, 1.0, 0.0)
		};
		int[][] tris = {{0, 1, 2}};
		Mesh m = new Mesh(points, tris);

		Assert.assertEquals(3, m.countNodes());
		Assert.assertEquals(1, m.getTriangleData().length);
	}
}
