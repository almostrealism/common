/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArgumentList;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MeshIntersectionTest implements TestFeatures {
	private Producer<Vector> origin1 = vector(0.0, 1.0, 1.0);
	private Producer<Vector> direction1 = vector(0.0, 0.0, -1.0);
	private Producer<Vector> origin2 = vector( -0.1, -1.0, 1.0);
	private Producer<Vector> direction2 = vector(0.0, 0.0, -1.0);

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

	protected Producer<Vector> abc(MeshData data) { return v(new Vector(data.get(0).get(0), 0)); }
	protected Producer<Vector> def(MeshData data) { return v(new Vector(data.get(0).get(1), 0)); }
	protected Producer<Vector> jkl(MeshData data) { return v(new Vector(data.get(0).get(2), 0)); }
	protected Producer<Vector> normal(MeshData data) { return v(new Vector(data.get(0).get(3), 0)); }

	protected TriangleIntersectAt intersection() {
		return TriangleIntersectAt.construct(Input.value(shape(4, 3), 0),
										ray(Input.value(shape(-1, 3), 1),
															Input.value(shape(-1, 3), 2)));
	}

	@Test
	public void normal1() {
		Vector normal = normal(data1).get().evaluate();
		System.out.println("normal = " + normal);
	}

	@Test
	public void data1() {
		System.out.println(def(data1).get().evaluate());

		CollectionProducer<Vector> h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		CollectionProducer<PackedCollection<?>> f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().toDouble());

		Producer<PackedCollection<?>> u = TriangleIntersectAt.u(
												TriangleIntersectAt.s(jkl(data1), origin1),
												TriangleIntersectAt.h(def(data1), direction1),
												f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().toDouble());
	}

	@Test
	public void conjunction() {
		System.out.println(def(data1).get().evaluate());

		CollectionProducer<Vector> h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		CollectionProducer<PackedCollection<?>> f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().toDouble());

		Producer<Vector> s = TriangleIntersectAt.s(jkl(data1), origin1);
		System.out.println("s = " + s.get().evaluate());

		Producer<PackedCollection<?>> u = TriangleIntersectAt.u(s, h, f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().toDouble());

		Producer<PackedCollection<?>> v = TriangleIntersectAt.v(direction1, f,
							TriangleIntersectAt.q(abc(data1), s));
		System.out.println("v = " + v.get().evaluate().toDouble());

		HardwareEvaluable<Vector> ho = (HardwareEvaluable) h.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, ho.getArgsCount());

		HardwareEvaluable<Scalar> fo = (HardwareEvaluable) f.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, fo.getArgsCount());

		HardwareEvaluable<PackedCollection<?>> uo = (HardwareEvaluable<PackedCollection<?>>) u.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, uo.getArgsCount());

		HardwareEvaluable<PackedCollection<?>> vo = (HardwareEvaluable<PackedCollection<?>>) v.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, vo.getArgsCount());

		AcceleratedConjunctionScalar acs = new AcceleratedConjunctionScalar(
				scalar(1.0), scalar(-1.0),
				scalarGreaterThan((Producer) u, scalar(0.0), true),
				scalarLessThan((Producer) u, scalar(1.0), true),
				scalarGreaterThan((Producer) v, scalar(0.0), true),
				scalarLessThan((Producer) add(u, v), scalar(1.0), true));
		ArgumentList<Scalar> evs = (ArgumentList) acs.get();
		if (enableArgumentCountAssertions) Assert.assertEquals(1, evs.getArgsCount());
	}

	@Test
	public void data2() {
		if (testDepth < 1) return;

		Producer<Vector> abc = abc(data2);
		Producer<Vector> def = def(data2);
		Producer<Vector> jkl = jkl(data2);

		System.out.println("abc = " + abc.get().evaluate());
		System.out.println("def = " + def.get().evaluate());
		System.out.println("jkl = " + jkl.get().evaluate());

		CollectionProducer<Vector> h = TriangleIntersectAt.h(def(data2), direction2);
		System.out.println("h = " + h.get().evaluate());

		CollectionProducer<PackedCollection<?>> f = TriangleIntersectAt.f(abc(data2), h);
		System.out.println("f = " + f.get().evaluate().toDouble());

		Producer<PackedCollection<?>> u = TriangleIntersectAt.u(
				TriangleIntersectAt.s(jkl(data2), origin2),
				TriangleIntersectAt.h(def(data2), direction2),
				f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().toDouble());

		Producer<Vector> s = TriangleIntersectAt.s(jkl(data2), origin2);
		System.out.println("s = " + s.get().evaluate());

		CollectionProducer<Vector> q = TriangleIntersectAt.q(abc(data2), s);
		System.out.println("q = " + q.get().evaluate());

		Producer<PackedCollection<?>> v = TriangleIntersectAt.v(direction2, f.pow(-1.0), q);
		System.out.println("v = " + v.get().evaluate().toDouble());
	}

	@Test
	public void intersectAt1() {
		TriangleIntersectAt intersect = intersection();
		Evaluable<PackedCollection<?>> ev = intersect.get();
		double distance = ev.evaluate(data1.get(0).traverse(0), origin1.get().evaluate(), direction1.get().evaluate()).toDouble();
		assertEquals(1.0, distance);
	}

	@Test
	public void intersectionKernel1() {
		PackedCollection<PackedCollection<?>> distances = new PackedCollection<>(shape(1, 1).traverse(1));
		Producer<Ray> ray = ray(origin1, direction1);
		data1.evaluateIntersectionKernelScalar(ray.get(), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).toDouble());
		assertEquals(1.0, distances.get(0).toDouble());
	}

	@Test
	public void intersectAt2() {
		double distance = ((PackedCollection) intersection().get().evaluate(
				data2.get(0).traverse(0), origin2.get().evaluate(), direction2.get().evaluate())).toDouble();
		System.out.println("distance = " + distance);
		assertEquals(1.0, distance);
	}

	@Test
	public void intersectionKernel2() {
		PackedCollection<PackedCollection<?>> distances = new PackedCollection<>(shape(1, 1).traverse(1));
		CollectionProducer<Ray> ray = ray(origin2, direction2);
		data2.evaluateIntersectionKernelScalar(ray.get(), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).toDouble());
		assertEquals(1.0, distances.get(0));
	}

	@Test
	public void intersectionKernel3() {
		Evaluable<Ray> ray = new DynamicProducerForMemoryData<>(args -> ray(i -> Math.random()).get().evaluate()).get();
		PackedCollection<PackedCollection<?>> distances = new PackedCollection<>(shape(200, 1).traverse(1));
		data2.evaluateIntersectionKernelScalar(ray, distances, new MemoryBank[0]);
		// TODO  Assertions
	}
}
