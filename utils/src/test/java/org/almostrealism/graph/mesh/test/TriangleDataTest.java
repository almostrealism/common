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

package org.almostrealism.graph.mesh.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

public class TriangleDataTest extends TestSuiteBase {
	protected Mesh.VertexData data() {
		DefaultVertexData data = new DefaultVertexData(5, 3);
		data.getVertices().set(0, new Vector(0.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(-1.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(1.0, -1.0, 0.0));
		data.getVertices().set(3, new Vector(-1.0, 1.0, -1.0));
		data.getVertices().set(4, new Vector(1.0, 1.0, -1.0));
		assertEquals(-1.0, data.getVertices().get(1).toDouble(0));

		data.setTriangle(0, 0, 1, 2);
		data.setTriangle(1, 3, 1, 0);
		data.setTriangle(2, 0, 2, 4);
		return data;
	}

	protected PackedCollection points() {return data().getMeshPointData();}

	@Test(timeout = 10000)
	public void edges() {
		PackedCollection points = points();

		Producer<PackedCollection> edge1 = vector(subtract(v(points.get(0).get(1)), v(points.get(0).get(0))));
		Vector value = new Vector(edge1.get().evaluate(), 0);
		log(value);
		Assert.assertEquals(-1, value.toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(-2, value.toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0, value.toDouble(2), Math.pow(10, -10));

		Producer<PackedCollection> edge2 = vector(subtract(v(points.get(0).get(2)), v(points.get(0).get(0))));
		value = new Vector(edge2.get().evaluate(), 0);
		log(value);
		Assert.assertEquals(1, value.toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(-2, value.toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0, value.toDouble(2), Math.pow(10, -10));

		value = new Vector(crossProduct(vector(0.0, 0.0, -1.0), edge2).get().evaluate(), 0);
		log(value);
		Assert.assertEquals(-2, value.toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(-1, value.toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0, value.toDouble(2), Math.pow(10, -10));
	}

	@Test(timeout = 10000)
	public void triangleData() {
		PackedCollection points = points();
		CollectionProducer td = triangle(
				v(points.get(0).get(0)),
				v(points.get(0).get(1)),
				v(points.get(0).get(2)));
		triangleDataAssertions(td.get().evaluate().reshape(shape(4, 3).traverse(1)));
	}

	@Test(timeout = 10000)
	public void triangleDataKernel() {
		PackedCollection points = points();
		Producer<PackedCollection> td = triangle(points(0));

		MeshData output = new MeshData(1);
		td.get().into(output).evaluate(points);
		triangleDataAssertions(output.get(0));
	}

	protected void triangleDataAssertions(PackedCollection value) {
		Assert.assertEquals(shape(4, 3).traverse(1), value.getShape());
		value.print();

		Assert.assertEquals(-1, value.get(0).toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(-2, value.get(0).toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0, value.get(0).toDouble(2), Math.pow(10, -10));

		Assert.assertEquals(1, value.get(1).toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(-2, value.get(1).toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0, value.get(1).toDouble(2), Math.pow(10, -10));

		Assert.assertEquals(0, value.get(2).toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(1, value.get(2).toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0, value.get(2).toDouble(2), Math.pow(10, -10));

		Assert.assertEquals(0, value.get(3).toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(0, value.get(3).toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(1, value.get(3).toDouble(2), Math.pow(10, -10));
	}

	protected Mesh mesh() {return new Mesh(data());}

	@Test(timeout = 10000)
	public void fromMesh() {
		Mesh m = mesh();
		PackedCollection points = m.getMeshPointData();
		log("getMeshPointData shape: " + points.getShape());
		log("Triangle count: " + m.getVertexData().getTriangleCount());

		log("Triangle 0 vertices:");
		points.get(0).print();

		MeshData data = m.getMeshData();
		log("MeshData shape: " + data.getShape());

		log("Triangle 0 normal: [" + data.get(0).get(3).toDouble(0) + ", " + data.get(0).get(3).toDouble(1) + ", " + data.get(0).get(3).toDouble(2) + "]");
		log("Triangle 1 normal: [" + data.get(1).get(3).toDouble(0) + ", " + data.get(1).get(3).toDouble(1) + ", " + data.get(1).get(3).toDouble(2) + "]");
		log("Triangle 2 normal: [" + data.get(2).get(3).toDouble(0) + ", " + data.get(2).get(3).toDouble(1) + ", " + data.get(2).get(3).toDouble(2) + "]");

		Assert.assertEquals(0, data.get(0).get(3).toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(0, data.get(0).get(3).toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(1, data.get(0).get(3).toDouble(2), Math.pow(10, -10));
		assertEquals(-2.0 / 3.0, data.get(1).get(3).toDouble(0));
		assertEquals(1.0 / 3.0, data.get(1).get(3).toDouble(1));
		assertEquals(2.0 / 3.0, data.get(1).get(3).toDouble(2));
		assertEquals(2.0 / 3.0, data.get(2).get(3).toDouble(0));
		assertEquals(1.0 / 3.0, data.get(2).get(3).toDouble(1));
		assertEquals(2.0 / 3.0, data.get(2).get(3).toDouble(2));
	}
}
