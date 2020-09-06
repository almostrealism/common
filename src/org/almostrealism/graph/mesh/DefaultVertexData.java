/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBBank;

public class DefaultVertexData implements Mesh.VertexData {
	private VectorBank vertices;
	private RGBBank colors;
	private PairBank texCoords;

	private int triangles[][];

	public DefaultVertexData(int points, int triangles) {
		this.vertices = new VectorBank(points);
		this.colors = new RGBBank(points);
		this.texCoords = new PairBank(points);
		this.triangles = new int[triangles][3];
	}

	public VectorBank getVertices() { return vertices; }
	public RGBBank getColors() { return colors; }
	public PairBank getTextureCoordinates() { return texCoords; }

	@Override
	public RGB getColor(int index) { return getColors().get(index); }
	@Override
	public Vector getPosition(int index) { return getVertices().get(index); }
	@Override
	public Pair getTexturePosition(int index) { return getTextureCoordinates().get(index); }

	@Override
	public double getX(int index) { return vertices.get(index).getX(); }
	@Override
	public double getY(int index) { return vertices.get(index).getY(); }
	@Override
	public double getZ(int index) { return vertices.get(index).getZ(); }

	@Override
	public double getRed(int index) { return colors.get(index).getRed(); }
	@Override
	public double getGreen(int index) { return colors.get(index).getGreen(); }
	@Override
	public double getBlue(int index) { return colors.get(index).getBlue(); }

	@Override
	public double getTextureU(int index) { return texCoords.get(index).getX(); }
	@Override
	public double getTextureV(int index) { return texCoords.get(index).getY(); }

	public void setTriangle(int index, int p1, int p2, int p3) {
		this.triangles[index][0] = p1;
		this.triangles[index][1] = p2;
		this.triangles[index][2] = p3;
	}

	@Override
	public int[] getTriangle(int index) { return triangles[index]; }

	@Override
	public int getTriangleCount() { return triangles.length; }
	@Override
	public int getVertexCount() { return vertices.getCount(); }

	@Override
	public MeshPointData getMeshPointData() {
		MeshPointData points = new MeshPointData(getTriangleCount());

		for (int i = 0; i < triangles.length; i++) {
			points.set(i,
					getX(triangles[i][0]), getY(triangles[i][0]), getZ(triangles[i][0]),
					getX(triangles[i][1]), getY(triangles[i][1]), getZ(triangles[i][1]),
					getX(triangles[i][2]), getY(triangles[i][2]), getZ(triangles[i][2]));
		}

		return points;
	}
}
