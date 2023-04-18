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

package org.almostrealism.algebra.test;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.algebra.computations.VectorExpressionComputation;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RayExpressionComputation;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
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


	protected VectorProducerBase originProducer() {
		Producer<Ray> noRank = ((ProducerWithRank) intersectAt()).getProducer();
		return (VectorProducerBase) (Supplier) ((RayExpressionComputation) noRank).getInputs().get(1);
	}

	protected VectorExpressionComputation originPointProducer() {
		VectorProducerBase origin = originProducer();
		return (VectorExpressionComputation) ((OperationAdapter) origin).getInputs().get(1);
	}

	protected VectorExpressionComputation originDirectionProducer() {
		VectorProducerBase origin = originProducer();
		return (VectorExpressionComputation) ((OperationAdapter) origin).getInputs().get(2);
	}

	@Test
	public void originComposition() {
		VectorExpressionComputation o = originPointProducer();
		Evaluable<Vector> evo = o.get();

		Vector vo = evo.evaluate();
		System.out.println(vo);
		Assert.assertEquals(new Vector(0.0, 0.0, 0.0), vo);

		VectorExpressionComputation d = originDirectionProducer();
		Evaluable<Vector> evd = d.get();

		Vector vd = evd.evaluate();
		System.out.println(vd);
		Assert.assertEquals(new Vector(0.0, 0.0, -1.0), vd);
	}

	@Test
	public void origin() {
		VectorProducerBase at = originProducer();
		Evaluable<Vector> ev = at.get();

		Vector p = ev.evaluate();
		System.out.println(p);
		Assert.assertEquals(new Vector(0.0, 0.0, -1.0), p);
	}

	protected PackedCollection<?> triangle() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		System.out.println(in);

		PackedCollection<Vector> tp = Vector.bank(3);
		tp.set(0, new Vector(1.0, 1.0, -1.0));
		tp.set(1, new Vector(-1.0, 1.0, -1.0));
		tp.set(2, new Vector(0.0, -1.0, -1.0));

		PackedCollection<?> td = triangle(p(tp)).get().evaluate().reshape(shape(4, 3).traverse(1));
		Assert.assertEquals(-2.0, td.get(0).toDouble(0), 0.0001);
		Assert.assertEquals(0.0, td.get(0).toDouble(1), 0.0001);
		Assert.assertEquals(0.0, td.get(0).toDouble(2), 0.0001);
		Assert.assertEquals(-1.0, td.get(1).toDouble(0), 0.0001);
		Assert.assertEquals(-2.0, td.get(1).toDouble(1), 0.0001);
		Assert.assertEquals(0.0, td.get(1).toDouble(2), 0.0001);
		Assert.assertEquals(1.0, td.get(2).toDouble(0), 0.0001);
		Assert.assertEquals(1.0, td.get(2).toDouble(1), 0.0001);
		Assert.assertEquals(-1.0, td.get(2).toDouble(2), 0.0001);
		Assert.assertEquals(0.0, td.get(3).toDouble(0), 0.0001);
		Assert.assertEquals(0.0, td.get(3).toDouble(1), 0.0001);
		Assert.assertEquals(1.0, td.get(3).toDouble(2), 0.0001);
//		Assert.assertEquals(new Vector(-2.0, 0.0, 0.0), td.get(0));
//		Assert.assertEquals(new Vector(-1.0, -2.0, 0.0), td.get(1));
//		Assert.assertEquals(new Vector(1.0, 1.0, -1.0), td.get(2));
//		Assert.assertEquals(new Vector(0.0, 0.0, 1.0), td.get(3));
		return td;
	}

	@Test
	public void choiceTest() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		PackedCollection<?> td = triangle();

		TriangleIntersectAt intersectAt = new TriangleIntersectAt(PassThroughEvaluable.of(1),
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
		PackedCollection<?> td = triangle();

		Scalar distance = Triangle.intersectAt.evaluate(in, td);
		System.out.println("distance = " + distance);
		Assert.assertEquals(1.0, distance.getValue(), Math.pow(10, -10));
	}

	@Test
	public void intersectionTest() {
		Evaluable<Ray> ev = intersectAt().get();

		Ray r = ev.evaluate();
		System.out.println(r);
		Assert.assertEquals(new Ray(new Vector(0.0, 0.0, -1.0), new Vector(0.0, 0.0, 1.0)), r);
	}
}
