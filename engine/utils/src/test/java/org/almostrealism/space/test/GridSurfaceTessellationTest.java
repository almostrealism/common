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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.mesh.TriangleFeatures;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.space.format.GtsResource;
import org.almostrealism.space.format.MeshResource;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests the regular grid height-field tessellation on {@link Mesh}, {@link MeshData}, and
 * {@link TriangleFeatures}: node positions, triangle count, gradient normals, and a GTS
 * round trip.
 */
public class GridSurfaceTessellationTest extends TestSuiteBase implements GridIndexFeatures {
	/** The origin of node (0, 0) used across the tests. */
	private static final Vector ORIGIN = new Vector(1.0, 2.0, 3.0);

	/** Node spacing along X. */
	private static final double SPACING_X = 0.5;

	/** Node spacing along Z. */
	private static final double SPACING_Z = 0.25;

	/**
	 * Tolerance for vertex positions read back from a {@link Vector}, whose storage uses the
	 * hardware precision; single-precision backends round each coordinate to about 1e-7.
	 */
	private static final double POSITION_TOLERANCE = 1e-5;

	/**
	 * Builds a small bumpy height field, shared with other tests in this package that need a
	 * non-planar grid surface fixture.
	 *
	 * @param nx the number of nodes along X
	 * @param ny the number of nodes along Z
	 * @return the height field indexed {@code [i][j]}
	 */
	static double[][] bumpy(int nx, int ny) {
		double[][] h = new double[nx][ny];
		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < ny; j++) {
				h[i][j] = 0.3 * Math.sin(0.7 * i) * Math.cos(0.5 * j) + 0.1 * Math.sin(0.2 * i * j);
			}
		}
		return h;
	}

	/**
	 * Builds a tilted plane height field {@code h = a*x + b*z + c} as a device Producer graph,
	 * indexing the grid with {@code integers()} so no host-computed values are staged.
	 *
	 * @param nx the number of nodes along X
	 * @param ny the number of nodes along Z
	 * @param a  the X slope
	 * @param b  the Z slope
	 * @param c  the constant offset
	 * @return a producer for the {@code (nx*ny)} height field in row-major order
	 */
	private CollectionProducer planeHeights(int nx, int ny, double a, double b, double c) {
		CollectionProducer[] ij = gridIndices(nx, ny);
		return ij[0].multiply(a * SPACING_X).add(ij[1].multiply(b * SPACING_Z)).add(c);
	}


	/** Every grid node's vertex position equals the height field within tolerance. */
	@Test(timeout = 60000)
	public void vertexPositionsMatchHeights() {
		int nx = 5;
		int ny = 7;
		double[][] h = bumpy(nx, ny);
		Mesh mesh = Mesh.gridSurface(h, ORIGIN, SPACING_X, SPACING_Z);

		Vector[] v = mesh.getVectors();
		Assert.assertEquals(nx * ny, v.length);

		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < ny; j++) {
				Vector node = v[i * ny + j];
				Assert.assertEquals(ORIGIN.getX() + i * SPACING_X, node.getX(), POSITION_TOLERANCE);
				Assert.assertEquals(ORIGIN.getY() + h[i][j], node.getY(), POSITION_TOLERANCE);
				Assert.assertEquals(ORIGIN.getZ() + j * SPACING_Z, node.getZ(), POSITION_TOLERANCE);
			}
		}
	}

	/** The surface has exactly two triangles per cell, on both the host and device paths. */
	@Test(timeout = 60000)
	public void triangleCountIsTwoPerCell() {
		int nx = 5;
		int ny = 7;
		double[][] h = bumpy(nx, ny);
		int expected = 2 * (nx - 1) * (ny - 1);

		Mesh mesh = Mesh.gridSurface(h, ORIGIN, SPACING_X, SPACING_Z);
		Assert.assertEquals(expected, mesh.getTriangleData().length);

		// The triangle count is independent of the height values, so a flat field suffices.
		MeshData md = MeshData.gridSurface(cp(new PackedCollection(shape(nx, ny))),
				nx, ny, ORIGIN, SPACING_X, SPACING_Z);
		Assert.assertEquals(expected, md.getCount());
	}

	/** On a tilted plane the per-vertex gradient normals are exact at every node. */
	@Test(timeout = 60000)
	public void tiltedPlaneNormalsAreExact() {
		int nx = 6;
		int ny = 5;
		double a = 0.3;
		double b = -0.2;

		PackedCollection normals = TriangleFeatures.getInstance()
				.regularGridSurfaceNormals(planeHeights(nx, ny, a, b, 0.5), nx, ny, SPACING_X, SPACING_Z)
				.evaluate();

		double len = Math.sqrt(a * a + 1.0 + b * b);
		double ex = -a / len;
		double ey = 1.0 / len;
		double ez = -b / len;

		for (int node = 0; node < nx * ny; node++) {
			Assert.assertEquals("nx@" + node, ex, normals.toDouble(node * 3), 1e-5);
			Assert.assertEquals("ny@" + node, ey, normals.toDouble(node * 3 + 1), 1e-5);
			Assert.assertEquals("nz@" + node, ez, normals.toDouble(node * 3 + 2), 1e-5);
		}
	}

	/** Writing the grid surface to GTS and reading it back preserves vertices and triangles. */
	@Test(timeout = 60000)
	public void gtsRoundTripPreservesMesh() throws IOException {
		int nx = 4;
		int ny = 4;
		double[][] h = bumpy(nx, ny);
		Mesh mesh = Mesh.gridSurface(h, ORIGIN, SPACING_X, SPACING_Z);

		GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(mesh));
		Mesh back = new GtsResource.MeshReader().transcode(gts).getMesh();

		Vector[] original = mesh.getVectors();
		Vector[] restored = back.getVectors();
		Assert.assertEquals(original.length, restored.length);
		for (int i = 0; i < original.length; i++) {
			Assert.assertEquals("x@" + i, original[i].getX(), restored[i].getX(), 1e-6);
			Assert.assertEquals("y@" + i, original[i].getY(), restored[i].getY(), 1e-6);
			Assert.assertEquals("z@" + i, original[i].getZ(), restored[i].getZ(), 1e-6);
		}

		int[][] originalTriangles = mesh.getTriangleData();
		int[][] restoredTriangles = back.getTriangleData();
		Assert.assertEquals(originalTriangles.length, restoredTriangles.length);
		for (int i = 0; i < originalTriangles.length; i++) {
			Assert.assertEquals("triangle " + i, corners(originalTriangles[i]), corners(restoredTriangles[i]));
		}
	}
}
