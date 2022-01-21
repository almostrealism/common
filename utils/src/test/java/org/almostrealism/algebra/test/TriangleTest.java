/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.computations.VectorProduct;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RayFromVectors;
import org.almostrealism.geometry.computations.RayOrigin;
import org.almostrealism.geometry.computations.RayPointAt;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.graph.mesh.TrianglePointData;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.almostrealism.space.Triangle;
import org.almostrealism.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

public class TriangleTest implements CodeFeatures {
	protected Producer<Ray> intersectAt() {
		Triangle t = new Triangle(new Vector(1.0, 1.0, -1.0),
				new Vector(-1.0, 1.0, -1.0),
				new Vector(0.0, -1.0, -1.0));
		return t.intersectAt(ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get(0);
	}


	protected RayPointAt originProducer() {
		Producer<Ray> noRank = ((ProducerWithRank) intersectAt()).getProducer();
		return (RayPointAt) ((RayFromVectors) noRank).getInputProducer(1);
	}

	protected RayOrigin originPointProducer() {
		RayPointAt origin = originProducer();
		return (RayOrigin) origin.getInputProducer(1);
	}

	protected VectorProduct originDirectionProducer() {
		RayPointAt origin = originProducer();
		return (VectorProduct) origin.getInputProducer(2);
	}

	@Test
	public void originComposition() {
		RayOrigin o = originPointProducer();
		Evaluable<Vector> evo = o.get();
		((OperationAdapter) evo).compile();

		Vector vo = evo.evaluate();
		System.out.println(vo);
		Assert.assertEquals(new Vector(0.0, 0.0, 0.0), vo);

		VectorProduct d = originDirectionProducer();
		Evaluable<Vector> evd = d.get();
		((OperationAdapter) evd).compile();

		Vector vd = evd.evaluate();
		System.out.println(vd);
		Assert.assertEquals(new Vector(0.0, 0.0, -1.0), vd);
	}

	@Test
	public void origin() {
		RayPointAt at = originProducer();
		Evaluable<Vector> ev = at.get();
		((OperationAdapter) ev).compile();

		Vector p = ev.evaluate();
		System.out.println(p);
		Assert.assertEquals(p, new Vector(0.0, 0.0, -1.0));
	}

	protected TriangleData triangle() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		System.out.println(in);

		TrianglePointData tp = new TrianglePointData();
		tp.setP1(new Vector(1.0, 1.0, -1.0));
		tp.setP2(new Vector(-1.0, 1.0, -1.0));
		tp.setP3(new Vector(0.0, -1.0, -1.0));

		TriangleData td = triangle(p(tp)).get().evaluate();
		Assert.assertEquals(new Vector(-2.0, 0.0, 0.0), td.getABC());
		Assert.assertEquals(new Vector(-1.0, -2.0, 0.0), td.getDEF());
		Assert.assertEquals(new Vector(1.0, 1.0, -1.0), td.getJKL());
		Assert.assertEquals(new Vector(0.0, 0.0, 1.0), td.getNormal());
		return td;
	}

	@Test
	public void choiceTest() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		TriangleData td = triangle();

		TriangleIntersectAt intersectAt = new TriangleIntersectAt(PassThroughEvaluable.of(TriangleData.class, 1),
				PassThroughEvaluable.of(Ray.class, 0, -1));

		GreaterThanScalar gts = (GreaterThanScalar) (Supplier) intersectAt.getInputs().get(4);
		AcceleratedConjunctionScalar acs = (AcceleratedConjunctionScalar) (Supplier) gts.getInputs().get(3);

		Evaluable<Scalar> ev = gts.get();
		((OperationAdapter) ev).compile();

		Scalar distance = ev.evaluate(in, td);
		System.out.println(distance);
		Assert.assertEquals(1.0, distance.getValue(), Math.pow(10, -10));

		distance = intersectAt.get().evaluate(in, td);
		System.out.println("distance = " + distance);
		Assert.assertEquals(1.0, distance.getValue(), Math.pow(10, -10));
	}

	@Test
	public void distanceTest() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		TriangleData td = triangle();

		Scalar distance = Triangle.intersectAt.evaluate(in, td);
		System.out.println("distance = " + distance);
		Assert.assertEquals(1.0, distance.getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectionTest() {
		Evaluable<Ray> ev = intersectAt().get();
		((OperationAdapter) ev).compile();

		Ray r = ev.evaluate();
		System.out.println(r);
		Assert.assertEquals(r, new Ray(new Vector(0.0, 0.0, -1.0), new Vector(0.0, 0.0, 1.0)));
	}
}
