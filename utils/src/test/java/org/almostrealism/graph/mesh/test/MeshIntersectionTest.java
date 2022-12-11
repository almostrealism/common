/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.graph.mesh.test;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RayFromVectors;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MeshIntersectionTest implements TestFeatures {
	private VectorProducerBase origin1 = vector(0.0, 1.0, 1.0);
	private VectorProducerBase direction1 = vector(0.0, 0.0, -1.0);
	private VectorProducerBase origin2 = vector( -0.1, -1.0, 1.0);
	private VectorProducerBase direction2 = vector(0.0, 0.0, -1.0);

	private MeshData data1, data2;

	protected Mesh mesh1() {
		DefaultVertexData data = new DefaultVertexData(3, 1);
		data.getVertices().set(0, new Vector(0.0, 100.0, 0.0));
		data.getVertices().set(1, new Vector(-100.0, -100.0, 0.0));
		data.getVertices().set(2, new Vector(100.0, -100.0, 0.0));
		data.setTriangle(0, 0, 1, 2);

		return new Mesh(data);
	}

	protected Mesh mesh2() {
		DefaultVertexData data = new DefaultVertexData(3, 1);
		data.getVertices().set(0, new Vector(0.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(-1.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(1.0, -1.0, 0.0));
		data.setTriangle(0, 0, 1, 2);

		return new Mesh(data);
	}

	@Before
	public void init() {
		data1 = mesh1().getMeshData();
		data2 = mesh2().getMeshData();
	}

	protected VectorProducerBase abc(MeshData data) { return v(data.get(0).getABC()); }
	protected VectorProducerBase def(MeshData data) { return v(data.get(0).getDEF()); }
	protected VectorProducerBase jkl(MeshData data) { return v(data.get(0).getJKL()); }
	protected VectorProducerBase normal(MeshData data) { return v(data.get(0).getNormal()); }

	protected TriangleIntersectAt intersection() {
		return TriangleIntersectAt.construct(PassThroughEvaluable.of(TriangleData.class, 0),
										new RayFromVectors(PassThroughEvaluable.of(Vector.class, 1),
															PassThroughEvaluable.of(Vector.class, 2)));
	}

	@Test
	public void normal1() {
		Vector normal = normal(data1).get().evaluate();
		System.out.println("normal = " + normal);
	}

	@Test
	public void data1() {
		System.out.println(def(data1).get().evaluate());

		VectorProducerBase h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		ScalarProducerBase f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		ScalarProducerBase u = TriangleIntersectAt.u(
												TriangleIntersectAt.s(jkl(data1), origin1),
												TriangleIntersectAt.h(def(data1), direction1),
												f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());
	}

	@Test
	public void conjunction() {
		System.out.println(def(data1).get().evaluate());

		VectorProducerBase h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		ScalarProducerBase f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		VectorProducerBase s = TriangleIntersectAt.s(jkl(data1), origin1);
		System.out.println("s = " + s.get().evaluate());

		ScalarProducerBase u = TriangleIntersectAt.u(s, h, f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());

		ScalarProducerBase v = TriangleIntersectAt.v(direction1, f,
							TriangleIntersectAt.q(abc(data1), s));
		System.out.println("v = " + v.get().evaluate().getValue());

		KernelizedEvaluable<Vector> ho = h.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, ho.getArgsCount());

		KernelizedEvaluable<Scalar> fo = f.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, fo.getArgsCount());

		KernelizedEvaluable<Scalar> uo = u.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, uo.getArgsCount());

		KernelizedEvaluable<Scalar> vo = v.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, vo.getArgsCount());

		AcceleratedConjunctionScalar acs = new AcceleratedConjunctionScalar(
				scalar(1.0), scalar(-1.0),
				u.greaterThan(0.0, true),
				u.lessThan(1.0, true),
				v.greaterThan(0.0, true),
				u.add(v).lessThan(1.0, true));
		KernelizedEvaluable<Scalar> evs = acs.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, evs.getArgsCount());
	}

	@Test
	public void data2() {
		VectorProducerBase abc = abc(data2);
		VectorProducerBase def = def(data2);
		VectorProducerBase jkl = jkl(data2);

		System.out.println("abc = " + abc.get().evaluate());
		System.out.println("def = " + def.get().evaluate());
		System.out.println("jkl = " + jkl.get().evaluate());

		VectorProducerBase h = TriangleIntersectAt.h(def(data2), direction2);
		System.out.println("h = " + h.get().evaluate());

		ScalarProducerBase f = TriangleIntersectAt.f(abc(data2), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		ScalarProducerBase u = TriangleIntersectAt.u(
				TriangleIntersectAt.s(jkl(data2), origin2),
				TriangleIntersectAt.h(def(data2), direction2),
				f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());

		VectorProducerBase s = TriangleIntersectAt.s(jkl(data2), origin2);
		System.out.println("s = " + s.get().evaluate());

		VectorProducerBase q = TriangleIntersectAt.q(abc(data2), s);
		System.out.println("q = " + q.get().evaluate());

		ScalarProducerBase v = TriangleIntersectAt.v(direction2, f.pow(-1.0), q);
		System.out.println("v = " + v.get().evaluate().getValue());
	}

	@Test
	public void intersectAt1() {
		evaluate(intersection(), true);
	}

	@Test
	public void intersectAtCompact1() {
		TriangleIntersectAt intersect = intersection();
		intersect.compile();
		evaluate(intersect, true);
	}

	protected void evaluate(TriangleIntersectAt intersect, boolean assertions) {
		Evaluable<Scalar> ev = intersect.get();
		((OperationAdapter) ev).compile();
		double distance = ev.evaluate(data1.get(0), origin1.get().evaluate(), direction1.get().evaluate()).getValue();
		System.out.println("distance = " + distance);
		if (assertions) assertEquals(1.0, distance);
	}

	@Test
	public void intersectionKernel1() {
		ScalarBank distances = new ScalarBank(1);
		RayFromVectors ray = new RayFromVectors(origin1, direction1);
		data1.evaluateIntersectionKernel(ray.get(), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).getValue());
		Assert.assertEquals(1.0, distances.get(0).getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectAt2() {
		double distance = intersection().get().evaluate(
				data2.get(0), origin2.get().evaluate(), direction2.get().evaluate()).getValue();
		System.out.println("distance = " + distance);
		Assert.assertEquals(1.0, distance, Math.pow(10, -10));
	}

	@Test
	public void intersectionKernel2() {
		ScalarBank distances = new ScalarBank(1);
		RayFromVectors ray = new RayFromVectors(origin2, direction2);
		data2.evaluateIntersectionKernel(ray.get(), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).getValue());
		assertEquals(1.0, distances.get(0));
	}

	@Test
	public void intersectionKernel3() {
		KernelizedEvaluable<Ray> ray = new DynamicProducerForMemoryData<>(args -> ray(i -> Math.random()).get().evaluate()).get();
		ScalarBank distances = new ScalarBank(100);
		data2.evaluateIntersectionKernel(ray, distances, new MemoryBank[0]);
		// TODO  Assertions
	}
}
