/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.space;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;

/**
 * Default implementation of {@link Mesh.VertexData} that provides storage for
 * mesh vertex information using {@link PackedCollection} data structures.
 *
 * <p>This class stores mesh data in three packed collections:
 * <ul>
 *   <li><b>Vertices</b>: 3D position vectors for each vertex</li>
 *   <li><b>Colors</b>: RGB color values for per-vertex coloring</li>
 *   <li><b>Texture Coordinates</b>: UV pairs for texture mapping</li>
 * </ul>
 *
 * <p>Triangle connectivity is stored as an array of integer triplets, where each
 * triplet contains indices into the vertex arrays defining a single triangle.
 *
 * <p>This implementation is designed to work efficiently with hardware-accelerated
 * rendering pipelines by keeping data in a format suitable for GPU operations.
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * DefaultVertexData data = new DefaultVertexData(vertexCount, triangleCount);
 *
 * // Set vertex positions
 * data.getVertices().set(0, new Vector(0, 0, 0));
 * data.getVertices().set(1, new Vector(1, 0, 0));
 * data.getVertices().set(2, new Vector(0, 1, 0));
 *
 * // Define triangle connectivity
 * data.setTriangle(0, 0, 1, 2);
 *
 * // Create mesh from vertex data
 * Mesh mesh = new Mesh(data);
 * }</pre>
 *
 * @author Michael Murray
 * @see Mesh
 * @see Mesh.VertexData
 */
public class DefaultVertexData implements Mesh.VertexData, CodeFeatures {
	private final PackedCollection vertices;
	private final PackedCollection colors;
	private final PackedCollection texCoords;

	// TODO Convert to a vertex bank so that conversion to PackedCollection can be kernelized
	private final int[][] triangles;

	/**
	 * Constructs a new {@link DefaultVertexData} with the specified capacity for
	 * vertices and triangles.
	 *
	 * @param points    the number of vertices to allocate space for
	 * @param triangles the number of triangles to allocate space for
	 */
	public DefaultVertexData(int points, int triangles) {
		this.vertices = Vector.bank(points);
		this.colors = RGB.bank(points);
		this.texCoords = Pair.bank(points);
		this.triangles = new int[triangles][3];
	}

	/**
	 * Returns the packed collection containing all vertex positions.
	 *
	 * @return the vertex position collection
	 */
	public PackedCollection getVertices() { return vertices; }

	/**
	 * Returns the packed collection containing all vertex colors.
	 *
	 * @return the vertex color collection
	 */
	public PackedCollection getColors() { return colors; }

	/**
	 * Returns the packed collection containing all texture coordinates.
	 *
	 * @return the texture coordinate collection
	 */
	public PackedCollection getTextureCoordinates() { return texCoords; }

	/** {@inheritDoc} */
	@Override
	public RGB getColor(int index) {
		return new RGB(colors.toDouble(index * 3), colors.toDouble(index * 3 + 1), colors.toDouble(index * 3 + 2));
	}

	/** {@inheritDoc} */
	@Override
	public Vector getPosition(int index) {
		return new Vector(vertices.toDouble(index * 3), vertices.toDouble(index * 3 + 1), vertices.toDouble(index * 3 + 2));
	}

	/** {@inheritDoc} */
	@Override
	public Pair getTexturePosition(int index) {
		return new Pair(texCoords.toDouble(index * 2), texCoords.toDouble(index * 2 + 1));
	}

	/** {@inheritDoc} */
	@Override
	public double getX(int index) { return vertices.toDouble(index * 3); }

	/** {@inheritDoc} */
	@Override
	public double getY(int index) { return vertices.toDouble(index * 3 + 1); }

	/** {@inheritDoc} */
	@Override
	public double getZ(int index) { return vertices.toDouble(index * 3 + 2); }

	/** {@inheritDoc} */
	@Override
	public double getRed(int index) { return colors.toDouble(index * 3); }

	/** {@inheritDoc} */
	@Override
	public double getGreen(int index) { return colors.toDouble(index * 3 + 1); }

	/** {@inheritDoc} */
	@Override
	public double getBlue(int index) { return colors.toDouble(index * 3 + 2); }

	/** {@inheritDoc} */
	@Override
	public double getTextureU(int index) { return texCoords.toDouble(index * 2); }

	/** {@inheritDoc} */
	@Override
	public double getTextureV(int index) { return texCoords.toDouble(index * 2 + 1); }

	/**
	 * Sets the vertex indices for the triangle at the specified index.
	 *
	 * @param index the triangle index
	 * @param p1    the index of the first vertex
	 * @param p2    the index of the second vertex
	 * @param p3    the index of the third vertex
	 */
	public void setTriangle(int index, int p1, int p2, int p3) {
		this.triangles[index][0] = p1;
		this.triangles[index][1] = p2;
		this.triangles[index][2] = p3;
	}

	/** {@inheritDoc} */
	@Override
	public int[] getTriangle(int index) { return triangles[index]; }

	/** {@inheritDoc} */
	@Override
	public int getTriangleCount() { return triangles.length; }

	/** {@inheritDoc} */
	@Override
	public int getVertexCount() { return vertices.getCount(); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation iterates over all triangles and builds a packed collection
	 * of vertex position data suitable for hardware-accelerated processing.
	 *
	 * <p>Returns shape (N, 3, 3) where N is the triangle count - 3 vertices with 3 components each.
	 */
	// TODO Kernelize
	@Override
	public PackedCollection getMeshPointData() {
		PackedCollection points = Vector.table(3, getTriangleCount());

		Producer<PackedCollection> producer =
				points(
						func(shape(1, 3),
								args -> vertices.range(shape(3), ((int[]) args[0])[0] * 3)),
						func(shape(1, 3),
								args -> vertices.range(shape(3), ((int[]) args[0])[1] * 3)),
						func(shape(1, 3),
								args -> vertices.range(shape(3), ((int[]) args[0])[2] * 3)));
		Evaluable<PackedCollection> ev = producer.get();

		for (int i = 0; i < triangles.length; i++) {
			points.setMem(i * 9, ev.evaluate(triangles[i]), 0, 9);
		}

		return points;
	}
}
