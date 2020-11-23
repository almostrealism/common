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

package org.almostrealism.graph.mesh.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.graph.mesh.DefaultVertexData;
import org.almostrealism.graph.mesh.Mesh;
import org.almostrealism.graph.mesh.MeshData;
import org.almostrealism.graph.mesh.MeshPointData;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleDataProducer;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.relation.Evaluable;
import org.junit.Assert;
import org.junit.Test;

public class TriangleDataTest implements CodeFeatures {
	protected Mesh.VertexData data() {
		DefaultVertexData data = new DefaultVertexData(5, 3);
		data.getVertices().set(0, new Vector(0.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(-1.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(1.0, -1.0, 0.0));
		data.getVertices().set(3, new Vector(-1.0, 1.0, -1.0));
		data.getVertices().set(4, new Vector(1.0, 1.0, -1.0));
		data.setTriangle(0, 0, 1, 2);
		data.setTriangle(1, 3, 1, 0);
		data.setTriangle(2, 0, 2, 4);
		return data;
	}

	protected MeshPointData points() { return data().getMeshPointData(); }

	@Test
	public void edges() {
		MeshPointData points = points();

		VectorProducer edge1 = subtract(v(points.get(0).getP2()), v(points.get(0).getP1()));
		Vector value = edge1.get().evaluate();
		System.out.println(value);
		Assert.assertEquals(-1, value.getX(), Math.pow(10, -10));
		Assert.assertEquals(-2, value.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getZ(), Math.pow(10, -10));

		VectorProducer edge2 = subtract(v(points.get(0).getP3()), v(points.get(0).getP1()));
		value = edge2.get().evaluate();
		System.out.println(value);
		Assert.assertEquals(1, value.getX(), Math.pow(10, -10));
		Assert.assertEquals(-2, value.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getZ(), Math.pow(10, -10));

		value = vector(0.0, 0.0, -1.0).crossProduct(edge2).get().evaluate();
		System.out.println(value);
		Assert.assertEquals(-2, value.getX(), Math.pow(10, -10));
		Assert.assertEquals(-1, value.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getZ(), Math.pow(10, -10));
	}

	@Test
	public void triangleData() {
		MeshPointData points = points();
		TriangleDataProducer td = triangle(v(points.get(0).getP1()),
											v(points.get(0).getP2()),
											v(points.get(0).getP3()));
		triangleDataAssertions(td.get().evaluate());
	}

	@Test
	public void triangleDataCompact() {
		MeshPointData points = points();
		Evaluable<? extends TriangleData> td = triangle(v(points.get(0).getP1()),
				v(points.get(0).getP2()),
				v(points.get(0).getP3())).get();
		td.compact();
		triangleDataAssertions(td.evaluate());
	}

	@Test
	public void triangleDataKernel() {
		MeshPointData points = points();
		Evaluable<? extends TriangleData> td = triangle(points(0)).get();
		td.compact();

		MeshData output = new MeshData(1);
		((KernelizedEvaluable) td).kernelEvaluate(output, new MemoryBank[] { points });
		triangleDataAssertions(output.get(0));
	}

	protected void triangleDataAssertions(TriangleData value) {
		System.out.println(value.getABC());
		System.out.println(value.getDEF());
		System.out.println(value.getJKL());
		System.out.println(value.getNormal());

		Assert.assertEquals(-1, value.getABC().getX(), Math.pow(10, -10));
		Assert.assertEquals(-2, value.getABC().getY(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getABC().getZ(), Math.pow(10, -10));
		Assert.assertEquals(1, value.getDEF().getX(), Math.pow(10, -10));
		Assert.assertEquals(-2, value.getDEF().getY(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getDEF().getZ(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getJKL().getX(), Math.pow(10, -10));
		Assert.assertEquals(1, value.getJKL().getY(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getJKL().getZ(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getNormal().getX(), Math.pow(10, -10));
		Assert.assertEquals(0, value.getNormal().getY(), Math.pow(10, -10));
		Assert.assertEquals(1, value.getNormal().getZ(), Math.pow(10, -10));
	}

	protected Mesh mesh() { return new Mesh(data()); }

	@Test
	public void fromMesh() {
		MeshData data = mesh().getMeshData();
		Assert.assertEquals(0, data.get(0).getNormal().getX(), Math.pow(10, -10));
		Assert.assertEquals(0, data.get(0).getNormal().getY(), Math.pow(10, -10));
		Assert.assertEquals(1, data.get(0).getNormal().getZ(), Math.pow(10, -10));
		Assert.assertEquals(-2.0 / 3.0, data.get(1).getNormal().getX(), Math.pow(10, -10));
		Assert.assertEquals(1.0 / 3.0, data.get(1).getNormal().getY(), Math.pow(10, -10));
		Assert.assertEquals(2.0 / 3.0, data.get(1).getNormal().getZ(), Math.pow(10, -10));
		Assert.assertEquals(2.0 / 3.0, data.get(2).getNormal().getX(), Math.pow(10, -10));
		Assert.assertEquals(1.0 / 3.0, data.get(2).getNormal().getY(), Math.pow(10, -10));
		Assert.assertEquals(2.0 / 3.0, data.get(2).getNormal().getZ(), Math.pow(10, -10));
	}
}
