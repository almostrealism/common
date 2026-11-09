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
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.Scene;
import org.almostrealism.space.Triangle;
import org.almostrealism.space.format.GtsResource;
import org.almostrealism.space.format.MeshResource;
import org.almostrealism.space.format.ModelData;
import org.almostrealism.space.format.ObjResource;
import org.almostrealism.space.format.PlyResource;
import org.almostrealism.space.format.RawResource;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests reading, and for GTS writing, the mesh and scene formats in
 * {@code org.almostrealism.space.format}.
 */
public class MeshFormatTest extends TestSuiteBase {
	/** The corners of a unit square in the z = 0 plane. */
	private static final double[][] SQUARE = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};

	/** The two triangles that cover {@link #SQUARE}. */
	private static final int[][] SQUARE_TRIANGLES = {{0, 1, 2}, {0, 2, 3}};

	/**
	 * Returns the unit square as a list-based mesh.
	 *
	 * @return the mesh
	 */
	private static Mesh square() {
		Vector[] points = Arrays.stream(SQUARE).map(p -> new Vector(p[0], p[1], p[2])).toArray(Vector[]::new);
		return new Mesh(points, SQUARE_TRIANGLES);
	}

	/**
	 * Asserts that a list-based mesh is the unit square, with each triangle's corners in any
	 * order, since a format may change the winding.
	 *
	 * @param mesh the mesh to check
	 */
	private static void assertSquare(Mesh mesh) {
		Vector[] v = mesh.getVectors();
		Assert.assertEquals(SQUARE.length, v.length);

		for (int i = 0; i < SQUARE.length; i++) {
			Assert.assertArrayEquals(SQUARE[i], new double[] { v[i].getX(), v[i].getY(), v[i].getZ() }, 1e-9);
		}

		int[][] t = mesh.getTriangleData();
		Assert.assertEquals(SQUARE_TRIANGLES.length, t.length);

		for (int i = 0; i < t.length; i++) {
			Assert.assertEquals("triangle " + i, corners(SQUARE_TRIANGLES[i]), corners(t[i]));
		}
	}

	/**
	 * Returns a triangle's vertex indices as a set.
	 *
	 * @param triangle the vertex indices
	 * @return the set of indices
	 */
	private static Set<Integer> corners(int[] triangle) {
		return new HashSet<>(Arrays.asList(triangle[0], triangle[1], triangle[2]));
	}

	/**
	 * Returns a stream of the given text.
	 *
	 * @param text the text
	 * @return the stream
	 */
	private static ByteArrayInputStream stream(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	/** A mesh written as GTS reads back as the same mesh. */
	@Test(timeout = 60000)
	public void gtsRoundTrip() throws IOException {
		GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(square()));
		assertSquare(new GtsResource.MeshReader().transcode(gts).getMesh());
	}

	/** GTS indices start at 1, so the first edge of the written square joins vertices 1 and 2. */
	@Test(timeout = 60000)
	public void gtsIndicesStartAtOne() {
		GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(square()));
		String[] lines = new String(gts.getData(), StandardCharsets.UTF_8).split("\n");

		Assert.assertEquals("4 6 2", lines[1]);
		Assert.assertEquals("1 2", lines[2 + SQUARE.length]);
		Assert.assertEquals("1 2 3", lines[2 + SQUARE.length + 6]);
	}

	/**
	 * Reading GTS into a non-empty initial mesh appends the vertices and offsets the triangle
	 * indices past the existing ones, so the triangles reference the data just read.
	 */
	@Test(timeout = 60000)
	public void gtsInitialMeshOffset() throws IOException {
		GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(square()));

		Mesh initial = new Mesh();
		initial.addVector(new Vector(9, 9, 9));

		GtsResource.MeshReader reader = new GtsResource.MeshReader();
		reader.setInitialMesh(initial);
		Mesh mesh = reader.transcode(gts).getMesh();

		Vector[] v = mesh.getVectors();
		Assert.assertEquals(1 + SQUARE.length, v.length);

		int[][] t = mesh.getTriangleData();
		Assert.assertEquals(SQUARE_TRIANGLES.length, t.length);

		for (int i = 0; i < t.length; i++) {
			Set<Integer> expected = new HashSet<>();
			for (int corner : SQUARE_TRIANGLES[i]) expected.add(corner + 1);
			Assert.assertEquals("triangle " + i, expected, corners(t[i]));
		}
	}

	/**
	 * OBJ vertices and faces are read, comments and texture coordinates are ignored, and only
	 * the first three corners of a larger face are used.
	 */
	@Test(timeout = 60000)
	public void obj() throws IOException {
		String obj = "# square\n"
				+ "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\n"
				+ "vt 0 0\n"
				+ "f 1/1 2/1 3/1\n"
				+ "f 1 3 4 2\n";

		assertSquare(new ObjResource.MeshReader().transcode(new ObjResource(stream(obj))).getMesh());
	}

	/** An ASCII PLY header, vertex list and face list are read into vertex data. */
	@Test(timeout = 60000)
	public void ply() throws IOException {
		String ply = "ply\nformat ascii 1.0\n"
				+ "element vertex 4\nproperty float x\nproperty float y\nproperty float z\n"
				+ "element face 2\nproperty list uchar int vertex_indices\nend_header\n"
				+ "0 0 0\n1 0 0\n1 1 0\n0 1 0\n"
				+ "3 0 1 2\n3 0 2 3\n";

		PlyResource r = new PlyResource();
		r.load(ply.getBytes(StandardCharsets.UTF_8), 0, ply.length());

		DefaultVertexData data = (DefaultVertexData) new PlyResource.MeshReader().transcode(r).getMesh().getVertexData();
		Assert.assertEquals(4, data.getVertexCount());
		Assert.assertEquals(1.0, data.getX(2), 1e-6);
		Assert.assertEquals(1.0, data.getY(2), 1e-6);
		Assert.assertEquals(2, data.getTriangleCount());
		Assert.assertArrayEquals(SQUARE_TRIANGLES[1], data.getTriangle(1));
	}

	/** RAW lines of nine coordinates are triangles, and a malformed line is skipped. */
	@Test(timeout = 60000)
	public void rawTriangles() throws IOException {
		String raw = "# two triangles\n"
				+ "0 0 0 1 0 0 1 1 0\n"
				+ "not a triangle\n"
				+ "0 0 0 1 1 0 0 1 0\n";

		Assert.assertEquals(2, rawScene(raw).size());
	}

	/** The RAW point-and-polygon layout indexes its points from 0. */
	@Test(timeout = 60000)
	public void rawPolygons() throws IOException {
		String raw = "4 2\n0 0 0\n1 0 0\n1 1 0\n0 1 0\n0 1 2\n0 2 3\n";

		Scene<Triangle> scene = rawScene(raw);
		Assert.assertEquals(2, scene.size());

		Vector[] corners = scene.get(1).getVertices();
		Assert.assertEquals(1.0, corners[1].getX(), 1e-9);
		Assert.assertEquals(1.0, corners[2].getY(), 1e-9);
	}

	/**
	 * Reads RAW text into a scene.
	 *
	 * @param raw the RAW text
	 * @return the scene of triangles
	 * @throws IOException if the text cannot be read
	 */
	private static Scene<Triangle> rawScene(String raw) throws IOException {
		RawResource r = new RawResource();
		r.load(raw.getBytes(StandardCharsets.UTF_8), 0, raw.length());
		return new RawResource.SceneReader().transcode(r).getScene();
	}

	/** {@link ModelData} returns a mesh format as a scene holding the one mesh. */
	@Test(timeout = 60000)
	public void decodeMeshAsScene() throws IOException {
		GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(square()));

		Scene<?> scene = ModelData.decodeScene(new ByteArrayInputStream(gts.getData()),
				ModelData.GTSEncoding, false, null);

		Assert.assertEquals(1, scene.size());
		ShadableSurface surface = scene.get(0);
		assertSquare((Mesh) surface);
	}
}
