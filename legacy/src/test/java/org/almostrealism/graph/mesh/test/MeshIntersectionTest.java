package org.almostrealism.graph.mesh.test;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RayFromVectors;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.hardware.DynamicProducerForMemWrapper;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MeshIntersectionTest implements HardwareFeatures, CodeFeatures {
	private VectorProducer origin1 = vector(0.0, 1.0, 1.0);
	private VectorProducer direction1 = vector(0.0, 0.0, -1.0);
	private VectorProducer origin2 = vector( -0.1, -1.0, 1.0);
	private VectorProducer direction2 = vector(0.0, 0.0, -1.0);

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

	protected VectorProducer abc(MeshData data) { return v(data.get(0).getABC()); }
	protected VectorProducer def(MeshData data) { return v(data.get(0).getDEF()); }
	protected VectorProducer jkl(MeshData data) { return v(data.get(0).getJKL()); }
	protected VectorProducer normal(MeshData data) { return v(data.get(0).getNormal()); }

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

		VectorProducer h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		ScalarProducer f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		ScalarProducer u = TriangleIntersectAt.u(
												TriangleIntersectAt.s(jkl(data1), origin1),
												TriangleIntersectAt.h(def(data1), direction1),
												f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());
	}

	@Test
	public void conjunction() {
		System.out.println(def(data1).get().evaluate());

		VectorProducer h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		ScalarProducer f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		VectorProducer s = TriangleIntersectAt.s(jkl(data1), origin1);
		System.out.println("s = " + s.get().evaluate());

		ScalarProducer u = TriangleIntersectAt.u(s, h, f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());

		ScalarProducer v = TriangleIntersectAt.v(direction1, f,
							TriangleIntersectAt.q(abc(data1), s));
		System.out.println("v = " + v.get().evaluate().getValue());

		// h.compact();
		DynamicAcceleratedOperation ho = (DynamicAcceleratedOperation) h.get();
		System.out.println(ho.getFunctionDefinition());
		Assert.assertEquals(1, ho.getArgsCount());

		// f.compact();
		DynamicAcceleratedOperation fo = (DynamicAcceleratedOperation) f.get();
		System.out.println(fo.getFunctionDefinition());
		Assert.assertEquals(1, fo.getArgsCount());

		// u.compact();
		DynamicAcceleratedOperation uo = (DynamicAcceleratedOperation) u.get();
		System.out.println(uo.getFunctionDefinition());
		Assert.assertEquals(1, uo.getArgsCount());

		// v.compact();
		DynamicAcceleratedOperation vo = (DynamicAcceleratedOperation) v.get();
		System.out.println(vo.getFunctionDefinition());
		Assert.assertEquals(1, vo.getArgsCount());

		AcceleratedConjunctionScalar acs = new AcceleratedConjunctionScalar(
				scalar(1.0), scalar(-1.0),
				u.greaterThan(0.0, true),
				u.lessThan(1.0, true),
				v.greaterThan(0.0, true),
				u.add(v).lessThan(1.0, true));

		// acs.compact();

		System.out.println(acs.getFunctionDefinition());
		Assert.assertEquals(1, acs.getArgsCount());
	}

	@Test
	public void data2() {
		VectorProducer abc = abc(data2);
		VectorProducer def = def(data2);
		VectorProducer jkl = jkl(data2);

		System.out.println("abc = " + abc.get().evaluate());
		System.out.println("def = " + def.get().evaluate());
		System.out.println("jkl = " + jkl.get().evaluate());

		VectorProducer h = TriangleIntersectAt.h(def(data2), direction2);
		System.out.println("h = " + h.get().evaluate());

		ScalarProducer f = TriangleIntersectAt.f(abc(data2), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		ScalarProducer u = TriangleIntersectAt.u(
				TriangleIntersectAt.s(jkl(data2), origin2),
				TriangleIntersectAt.h(def(data2), direction2),
				f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());

		VectorProducer s = TriangleIntersectAt.s(jkl(data2), origin2);
		System.out.println("s = " + s.get().evaluate());

		VectorProducer q = TriangleIntersectAt.q(abc(data2), s);
		System.out.println("q = " + q.get().evaluate());

		ScalarProducer v = TriangleIntersectAt.v(direction2, f.pow(-1.0), q);
		System.out.println("v = " + v.get().evaluate().getValue());
	}

	@Test
	public void intersectAt1() {
		evaluate(intersection(), true);
	}

	@Test
	public void intersectAtCompact1() {
		TriangleIntersectAt intersect = intersection();
		// intersect.compact();
		System.out.println(intersect.getFunctionDefinition());
		evaluate(intersect, true);
	}

	protected void evaluate(TriangleIntersectAt intersect, boolean assertions) {
		double distance = intersect.evaluate(new Object[] {
				data1.get(0), origin1.get().evaluate(), direction1.get().evaluate() }).getValue();
		System.out.println("distance = " + distance);
		if (assertions) Assert.assertEquals(1.0, distance, Math.pow(10, -10));
	}

	@Test
	public void intersectionKernel1() {
		ScalarBank distances = new ScalarBank(1);
		RayFromVectors ray = new RayFromVectors(origin1, direction1);
		// ray.compact();
		data1.evaluateIntersectionKernel(compileProducer(ray), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).getValue());
		Assert.assertEquals(1.0, distances.get(0).getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectAt2() {
		double distance = intersection().evaluate(
				data2.get(0), origin2.get().evaluate(), direction2.get().evaluate()).getValue();
		System.out.println("distance = " + distance);
		Assert.assertEquals(1.0, distance, Math.pow(10, -10));
	}

	@Test
	public void intersectionKernel2() {
		ScalarBank distances = new ScalarBank(1);
		RayFromVectors ray = new RayFromVectors(origin2, direction2);
		// ray.compact();
		data2.evaluateIntersectionKernel(compileProducer(ray), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).getValue());
		Assert.assertEquals(1.0, distances.get(0).getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectionKernel3() {
		KernelizedEvaluable<Ray> ray = new DynamicProducerForMemWrapper<>(args -> ray(i -> Math.random()).get().evaluate()).get();
		ScalarBank distances = new ScalarBank(100);
		data2.evaluateIntersectionKernel(ray, distances, new MemoryBank[0]);
		// TODO  Assertions
	}
}
