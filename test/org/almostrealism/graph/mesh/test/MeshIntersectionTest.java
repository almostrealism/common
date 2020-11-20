package org.almostrealism.graph.mesh.test;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.ScalarSupplier;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.geometry.RayFromVectors;
import org.almostrealism.graph.mesh.DefaultVertexData;
import org.almostrealism.graph.mesh.Mesh;
import org.almostrealism.graph.mesh.MeshData;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.Provider;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MeshIntersectionTest implements HardwareFeatures, CodeFeatures {
	private VectorSupplier origin1 = vector(0.0, 1.0, 1.0);
	private VectorSupplier direction1 = vector(0.0, 0.0, -1.0);
	private VectorSupplier origin2 = vector( -0.1, -1.0, 1.0);
	private VectorSupplier direction2 = vector(0.0, 0.0, -1.0);

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

	protected VectorSupplier abc(MeshData data) { return v(data.get(0).getABC()); }
	protected VectorSupplier def(MeshData data) { return v(data.get(0).getDEF()); }
	protected VectorSupplier jkl(MeshData data) { return v(data.get(0).getJKL()); }
	protected VectorSupplier normal(MeshData data) { return v(data.get(0).getNormal()); }

	protected TriangleIntersectAt intersection() {
		return TriangleIntersectAt.construct(PassThroughProducer.of(TriangleData.class, 0),
										new RayFromVectors(PassThroughProducer.of(Vector.class, 1),
															PassThroughProducer.of(Vector.class, 2)));
	}

	@Test
	public void normal1() {
		Vector normal = normal(data1).get().evaluate();
		System.out.println("normal = " + normal);
	}

	@Test
	public void data1() {
		System.out.println(def(data1).get().evaluate());

		VectorSupplier h = TriangleIntersectAt.h(def(data1), direction1);
		System.out.println("h = " + h.get().evaluate());

		ScalarSupplier f = TriangleIntersectAt.f(abc(data1), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		ScalarSupplier u = TriangleIntersectAt.u(
												TriangleIntersectAt.s(jkl(data1), origin1),
												TriangleIntersectAt.h(def(data1), direction1),
												f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());
	}

	@Test
	public void data2() {
		VectorSupplier abc = abc(data2);
		VectorSupplier def = def(data2);
		VectorSupplier jkl = jkl(data2);

		System.out.println("abc = " + abc.get().evaluate());
		System.out.println("def = " + def.get().evaluate());
		System.out.println("jkl = " + jkl.get().evaluate());

		VectorSupplier h = TriangleIntersectAt.h(def(data2), direction2);
		System.out.println("h = " + h.get().evaluate());

		ScalarSupplier f = TriangleIntersectAt.f(abc(data2), h);
		System.out.println("f = " + f.get().evaluate().getValue());

		ScalarSupplier u = TriangleIntersectAt.u(
				TriangleIntersectAt.s(jkl(data2), origin2),
				TriangleIntersectAt.h(def(data2), direction2),
				f.pow(-1.0));
		System.out.println("u = " + u.get().evaluate().getValue());

		VectorSupplier s = TriangleIntersectAt.s(jkl(data2), origin2);
		System.out.println("s = " + s.get().evaluate());

		VectorSupplier q = TriangleIntersectAt.q(abc(data2), s);
		System.out.println("q = " + q.get().evaluate());

		ScalarSupplier v = TriangleIntersectAt.v(direction2, f.pow(-1.0), q);
		System.out.println("v = " + v.get().evaluate().getValue());
	}

	@Test
	public void intersectAt1() {
		evaluate(intersection(), true);
	}

	@Test
	public void intersectAtCompact1() {
		TriangleIntersectAt intersect = intersection();
		intersect.compact();
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
		ray.compact();
		data1.evaluateIntersectionKernel(compileProducer(ray), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).getValue());
		Assert.assertEquals(1.0, distances.get(0).getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectAt2() {
		double distance = intersection().evaluate(new Object[] {
				data2.get(0), origin2.get().evaluate(), direction2.get().evaluate() }).getValue();
		System.out.println("distance = " + distance);
		Assert.assertEquals(1.0, distance, Math.pow(10, -10));
	}

	@Test
	public void intersectionKernel2() {
		ScalarBank distances = new ScalarBank(1);
		RayFromVectors ray = new RayFromVectors(origin2, direction2);
		ray.compact();
		data2.evaluateIntersectionKernel(compileProducer(ray), distances, new MemoryBank[0]);
		System.out.println("distance = " + distances.get(0).getValue());
		Assert.assertEquals(1.0, distances.get(0).getValue(), Math.pow(10, -10));
	}
}
