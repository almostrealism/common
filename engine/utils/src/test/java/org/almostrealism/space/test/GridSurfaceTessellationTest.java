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

import io.almostrealism.relation.Producer;
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
import java.util.ArrayList;
import java.util.List;

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
	 * Evaluates a height-field producer into an {@code (nx, ny)} collection, the input
	 * {@link Mesh#gridSurface} expects.
	 *
	 * @param heights the {@code (nx*ny)} height field in row-major order
	 * @param nx      the number of nodes along X
	 * @param ny      the number of nodes along Z
	 * @return the evaluated height field
	 */
	private PackedCollection heightField(CollectionProducer heights, int nx, int ny) {
		return heights.evaluate().reshape(shape(nx, ny));
	}

	/**
	 * Returns the three corner positions of every triangle of a mesh, in triangle order, as
	 * the host reference against which the device tessellation is compared.
	 *
	 * @param mesh the mesh
	 * @return one three-element corner array per triangle
	 */
	private static List<Vector[]> triangleCorners(Mesh mesh) {
		Vector[] v = mesh.getVectors();
		List<Vector[]> triangles = new ArrayList<>();
		for (int[] t : mesh.getTriangleData()) {
			triangles.add(new Vector[] { v[t[0]], v[t[1]], v[t[2]] });
		}
		return triangles;
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
		PackedCollection h = heightField(bumpyHeights(nx, ny), nx, ny);
		Mesh mesh = Mesh.gridSurface(h, ORIGIN, SPACING_X, SPACING_Z);

		Vector[] v = mesh.getVectors();
		Assert.assertEquals(nx * ny, v.length);

		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < ny; j++) {
				Vector node = v[i * ny + j];
				Assert.assertEquals(ORIGIN.getX() + i * SPACING_X, node.getX(), POSITION_TOLERANCE);
				Assert.assertEquals(ORIGIN.getY() + h.toDouble(i * ny + j), node.getY(), POSITION_TOLERANCE);
				Assert.assertEquals(ORIGIN.getZ() + j * SPACING_Z, node.getZ(), POSITION_TOLERANCE);
			}
		}
	}

	/** The surface has exactly two triangles per cell, on both the host and device paths. */
	@Test(timeout = 60000)
	public void triangleCountIsTwoPerCell() {
		int nx = 5;
		int ny = 7;
		int expected = 2 * (nx - 1) * (ny - 1);

		Mesh mesh = Mesh.gridSurface(heightField(bumpyHeights(nx, ny), nx, ny), ORIGIN, SPACING_X, SPACING_Z);
		Assert.assertEquals(expected, mesh.getTriangleData().length);

		// The triangle count is independent of the height values, so a flat field suffices.
		MeshData md = MeshData.gridSurface(cp(new PackedCollection(shape(nx, ny))),
				nx, ny, ORIGIN, SPACING_X, SPACING_Z);
		Assert.assertEquals(expected, md.getCount());
	}

	/**
	 * Asserts that the device tessellation of a tilted plane produces, triangle by triangle
	 * and corner by corner, the same positions as the host tessellation of the same plane.
	 *
	 * @param heights the device height field of the plane {@code h = a*x + b*z + c}
	 * @param nx      the number of nodes along X
	 * @param ny      the number of nodes along Z
	 * @param a       the X slope
	 * @param b       the Z slope
	 * @param c       the constant offset
	 */
	private void assertMatchesHostTessellation(Producer<PackedCollection> heights, int nx, int ny,
											   double a, double b, double c) {
		PackedCollection points = TriangleFeatures.getInstance()
				.regularGridSurfacePoints(heights, nx, ny,
						ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ(), SPACING_X, SPACING_Z)
				.evaluate();
		List<Vector[]> expected = triangleCorners(Mesh.gridSurface(
				heightField(planeHeights(nx, ny, a, b, c), nx, ny), ORIGIN, SPACING_X, SPACING_Z));

		Assert.assertEquals(2 * (nx - 1) * (ny - 1), expected.size());
		Assert.assertEquals(expected.size() * 9, points.getMemLength());

		for (int t = 0; t < expected.size(); t++) {
			for (int k = 0; k < 3; k++) {
				Vector corner = expected.get(t)[k];
				int offset = t * 9 + k * 3;
				String label = "triangle " + t + " corner " + k;
				Assert.assertEquals(label + " x", corner.getX(), points.toDouble(offset), POSITION_TOLERANCE);
				Assert.assertEquals(label + " y", corner.getY(), points.toDouble(offset + 1), POSITION_TOLERANCE);
				Assert.assertEquals(label + " z", corner.getZ(), points.toDouble(offset + 2), POSITION_TOLERANCE);
			}
		}
	}

	/**
	 * The device tessellation of a height field computed by a Producer graph matches the host
	 * tessellation corner by corner, so the kernel's cell, sub-triangle, and corner index
	 * mapping (including the winding order) matches the documented layout. A non-square grid
	 * and distinct slopes on each axis make any transposition of the X and Z axes or any corner
	 * reordering visible.
	 */
	@Test(timeout = 60000)
	public void devicePointsMatchHostTessellation() {
		assertMatchesHostTessellation(planeHeights(5, 4, 0.3, -0.7, 0.5), 5, 4, 0.3, -0.7, 0.5);
	}

	/**
	 * The device tessellation of a height field held in a collection matches the host
	 * tessellation. Regression test: on this grid size a {@code conditional} in the corner
	 * offset index arithmetic became floating-point once the kernel compiler hoisted one of
	 * its branches, making the node index floating-point, and the kernel failed to compile.
	 */
	@Test(timeout = 60000)
	public void devicePointsFromCollectionMatchHostTessellation() {
		PackedCollection heights = planeHeights(5, 4, 0.3, -0.7, 0.5).evaluate();
		assertMatchesHostTessellation(cp(heights), 5, 4, 0.3, -0.7, 0.5);
	}

	/**
	 * Every face of the device-built {@link MeshData} on a tilted plane carries the plane's
	 * upward unit normal and has its first corner at the expected node, confirming the
	 * documented {@code +Y} winding of both triangles in each cell.
	 */
	@Test(timeout = 60000)
	public void deviceFaceNormalsPointUp() {
		int nx = 4;
		int ny = 6;
		double a = -0.4;
		double b = 0.25;
		double c = 0.1;

		MeshData md = MeshData.gridSurface(planeHeights(nx, ny, a, b, c), nx, ny,
				ORIGIN, SPACING_X, SPACING_Z);
		List<Vector[]> expected = triangleCorners(Mesh.gridSurface(
				heightField(planeHeights(nx, ny, a, b, c), nx, ny), ORIGIN, SPACING_X, SPACING_Z));
		Assert.assertEquals(expected.size(), md.getCount());

		double len = Math.sqrt(a * a + 1.0 + b * b);
		for (int t = 0; t < expected.size(); t++) {
			int row = t * 12;
			Vector first = expected.get(t)[0];
			Assert.assertEquals("jkl x@" + t, first.getX(), md.toDouble(row + 6), POSITION_TOLERANCE);
			Assert.assertEquals("jkl y@" + t, first.getY(), md.toDouble(row + 7), POSITION_TOLERANCE);
			Assert.assertEquals("jkl z@" + t, first.getZ(), md.toDouble(row + 8), POSITION_TOLERANCE);

			Assert.assertEquals("normal x@" + t, -a / len, md.toDouble(row + 9), 1e-5);
			Assert.assertEquals("normal y@" + t, 1.0 / len, md.toDouble(row + 10), 1e-5);
			Assert.assertEquals("normal z@" + t, -b / len, md.toDouble(row + 11), 1e-5);
		}
	}

	/** A grid with a single node along X has no cells, so tessellating it is rejected. */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void singleColumnGridPointsRejected() {
		TriangleFeatures.getInstance().regularGridSurfacePoints(cp(new PackedCollection(shape(4))),
				1, 4, 0.0, 0.0, 0.0, SPACING_X, SPACING_Z);
	}

	/** A grid with a single node along Z has no cells, so tessellating it is rejected. */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void singleRowGridPointsRejected() {
		TriangleFeatures.getInstance().regularGridSurfacePoints(cp(new PackedCollection(shape(4))),
				4, 1, 0.0, 0.0, 0.0, SPACING_X, SPACING_Z);
	}

	/** A grid with a single node along X has no gradient span, so its normals are rejected. */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void singleColumnGridNormalsRejected() {
		TriangleFeatures.getInstance().regularGridSurfaceNormals(cp(new PackedCollection(shape(4))),
				1, 4, SPACING_X, SPACING_Z);
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
		Mesh mesh = Mesh.gridSurface(heightField(bumpyHeights(nx, ny), nx, ny), ORIGIN, SPACING_X, SPACING_Z);

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
