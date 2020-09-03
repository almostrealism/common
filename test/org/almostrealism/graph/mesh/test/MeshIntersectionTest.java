package org.almostrealism.graph.mesh.test;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.geometry.RayFromVectors;
import org.almostrealism.graph.mesh.DefaultVertexData;
import org.almostrealism.graph.mesh.Mesh;
import org.almostrealism.graph.mesh.MeshData;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.math.MemoryBank;
import org.almostrealism.util.PassThroughProducer;
import org.almostrealism.util.StaticProducer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MeshIntersectionTest {
	private VectorProducer origin1 = StaticProducer.of(new Vector(0.0, 0.0, 1.0));
	private VectorProducer direction1 = StaticProducer.of(new Vector(0.0, 0.0, -1.0));
	private VectorProducer origin2 = StaticProducer.of(new Vector(0.0, 0.0, 10.0));
	private VectorProducer direction2 = StaticProducer.of(new Vector(-0.43418192863464355, 0.9011321067810059, -10.0));

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

	protected VectorProducer abc() { return StaticProducer.of(data1.get(0).getABC()); }
	protected VectorProducer def() { return StaticProducer.of(data1.get(0).getDEF()); }
	protected VectorProducer jkl() { return StaticProducer.of(data1.get(0).getJKL()); }
	protected VectorProducer normal() { return StaticProducer.of(data1.get(0).getNormal()); }

	protected TriangleIntersectAt intersection() {
		return new TriangleIntersectAt(PassThroughProducer.of(TriangleData.class, 0),
										new RayFromVectors(PassThroughProducer.of(Vector.class, 1),
															PassThroughProducer.of(Vector.class, 2)));
	}

	@Test
	public void data() {
		VectorProducer h = TriangleIntersectAt.h(def(), direction1);
		System.out.println("h = " + h.evaluate());

		ScalarProducer f = TriangleIntersectAt.f(abc(), h);
		System.out.println("f = " + f.evaluate().getValue());

		ScalarProducer u = TriangleIntersectAt.u(
												TriangleIntersectAt.s(jkl(), origin1),
												TriangleIntersectAt.h(def(), direction1),
												f.pow(-1.0));
		System.out.println("u = " + u.evaluate().getValue());
	}

	@Test
	public void intersectAt1() {
		evaluate(intersection(), true);
	}

	@Test
	public void intersectAtCompact1() {
		TriangleIntersectAt intersect = intersection();
		intersect.compact();
		evaluate(intersect, true);
	}

	protected void evaluate(TriangleIntersectAt intersect, boolean assertions) {
		double distance = intersect.evaluate(new Object[] {
				data1.get(0), origin1.evaluate(), direction1.evaluate() }).getValue();
		System.out.println("distance = " + distance);
		if (assertions) Assert.assertEquals(1.0, distance, Math.pow(10, -10));
	}

	@Test
	public void intersectionKernel1() {
		ScalarBank distances = new ScalarBank(1);
		RayFromVectors ray = new RayFromVectors(origin1, direction1);
		ray.compact();
		data1.evaluateIntersectionKernel(ray, distances, new MemoryBank[0], 0, distances.getCount());
		System.out.println("distance = " + distances.get(0).getValue());
		Assert.assertEquals(1.0, distances.get(0).getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectAt2() {
		double distance = intersection().evaluate(new Object[] {
				data2.get(0), origin2.evaluate(), direction2.evaluate() }).getValue();
		System.out.println("distance = " + distance);
		Assert.assertEquals(-1.0, distance, Math.pow(10, -10));
	}

	public void intersectionKernel2() {

	}
}
