package org.almostrealism.graph.mesh.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.mesh.DefaultVertexData;
import org.almostrealism.graph.mesh.Mesh;
import org.almostrealism.graph.mesh.MeshData;
import org.junit.Test;

public class TriangleDataTest {
	protected Mesh mesh() {
		DefaultVertexData data = new DefaultVertexData(5, 3);
		data.getVertices().set(0, new Vector(0.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(-1.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(1.0, -1.0, 0.0));
		data.getVertices().set(3, new Vector(-1.0, 1.0, -1.0));
		data.getVertices().set(4, new Vector(1.0, 1.0, -1.0));
		data.setTriangle(0, 0, 1, 2);
		data.setTriangle(1, 3, 1, 0);
		data.setTriangle(2, 0, 2, 4);
		return new Mesh(data);
	}

	@Test
	public void fromMesh() {
		MeshData data = mesh().getMeshData();
		System.out.println(data.get(0).getNormal());
		System.out.println(data.get(1).getNormal());
		System.out.println(data.get(2).getNormal());
	}
}
