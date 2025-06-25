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

package org.almostrealism.algebra.test;

import io.almostrealism.code.AdaptEvaluable;
import io.almostrealism.code.ComputableBase;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Input;
import org.almostrealism.space.Triangle;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

public class TriangleTest implements TestFeatures {
	protected Triangle basicTriangle() {
		return new Triangle(
				new Vector(1.0, 1.0, -1.0),
				new Vector(-1.0, 1.0, -1.0),
				new Vector(0.0, -1.0, -1.0));
	}

	@Test
	public void point() {
		PackedCollection<Vector> data = Vector.bank(3);
		data.set(0, new Vector(1.0, 1.0, -1.0));
		data.set(1, new Vector(-1.0, 1.0, -1.0));
		data.set(2, new Vector(0.0, -1.0, -1.0));

		Vector v = point(Input.value(new TraversalPolicy(3, 3), 0), 1)
				.evaluate(data.traverse(0));
		v.print();
		Assert.assertEquals(new Vector(-1.0, 1.0, -1.0), v);
	}

	/**
	 * This test will not pass unless {@link #point()} passes first.
	 */
	@Test
	public void data() {
		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> data = basicTriangle().getData();

			assertEquals(-2.0, data.valueAt(0, 0));
			assertEquals(-1.0, data.valueAt(1, 0));
			assertEquals(-2.0, data.valueAt(1, 1));
		});
	}

	/**
	 * This test will not pass unless {@link #intersectAtDistance()} passes first.
	 */
	@Test
	public void distance() {
		Triangle t = basicTriangle();

		Evaluable<Ray> r = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get();
		Supplier<Evaluable<? extends Scalar>> distance = () -> new AdaptEvaluable<>(Triangle.intersectAt, r, new Provider<>(t.getData()));
		Scalar s = distance.get().evaluate();
		System.out.println(s);

		assertEquals(1.0, s);
	}

	/**
	 * If {@link #data()} does not pass, then it is likely the values for
	 * {@link #triangle()} are wrong and so this test will also not pass.
	 */
	@Test
	public void intersectAtDistance() {
		HardwareOperator.verboseLog(() -> {
			Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
			PackedCollection<?> td = triangle();

			Scalar distance = Triangle.intersectAt.evaluate(in, td);
			log("distance = " + distance);
			Assert.assertEquals(1.0, distance.getValue(), Math.pow(10, -10));
		});
	}

	protected Producer<Ray> intersectAt() {
		return basicTriangle().intersectAt(
				ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get(0);
	}

	protected CollectionProducerComputationBase<Vector, Vector> originProducer() {
		Producer<Ray> noRank = ((ProducerWithRank) intersectAt()).getProducer();
		CollectionProducerComputationBase<Vector, Vector> originVector =
				(CollectionProducerComputationBase)
						((CollectionProducerComputationBase) noRank).getInputs().get(1);

		return (CollectionProducerComputationBase<Vector, Vector>) originVector.getInputs().get(1);
	}

	protected Producer<Vector> originPointProducer() {
		CollectionProducerComputationBase<Vector, Vector> origin = originProducer();
		return vector((Producer) ((ComputableBase) origin).getInputs().get(1));
	}

	protected Producer<Vector> originDirectionProducer() {
		CollectionProducerComputationBase<Vector, Vector> origin = originProducer();
		return vector((Producer) ((ComputableBase) origin).getInputs().get(2));
	}

	@Test
	public void originComposition() {
		Producer<Vector> o = originPointProducer();
		Evaluable<Vector> evo = o.get();

		Vector vo = evo.evaluate();
		System.out.println(vo);
		Assert.assertEquals(new Vector(0.0, 0.0, 0.0), vo);

		Producer<Vector> d = originDirectionProducer();
		Evaluable<Vector> evd = d.get();

		Vector vd = evd.evaluate();
		System.out.println(vd);
		Assert.assertEquals(new Vector(0.0, 0.0, -1.0), vd);
	}

	@Test
	public void origin() {
		HardwareOperator.verboseLog(() -> {
			CollectionProducerComputation<Vector> at = vector(originProducer());
			Evaluable<Vector> ev = at.get();

			Vector p = ev.evaluate();
			p.print();
			Assert.assertEquals(new Vector(0.0, 0.0, -1.0), p);
		});
	}

	protected PackedCollection<?> triangle() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		System.out.println(in);

		PackedCollection<?> data = new PackedCollection<>(9);
		PackedCollection<Vector> tp = new PackedCollection<>(new TraversalPolicy(3, 3), 1,
				delegateSpec ->
					new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				data, 0);
		tp.set(0, new Vector(1.0, 1.0, -1.0));
		tp.set(1, new Vector(-1.0, 1.0, -1.0));
		tp.set(2, new Vector(0.0, -1.0, -1.0));

		Producer<PackedCollection<?>> ptp = new CollectionProviderProducer<>(tp.traverse(0));

		PackedCollection<?> td = triangle(ptp).get().evaluate();
		td = td.traverse(1);
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
		return td;
	}

	@Test
	public void choiceTest() {
		Ray in = ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0).get().evaluate();
		PackedCollection<?> td = triangle();

		TriangleIntersectAt intersectAt = TriangleIntersectAt.construct(Input.value(shape(4, 3), 1),
				Input.value(Ray.shape(), 0));

		GreaterThanScalar gts = (GreaterThanScalar) (Supplier) intersectAt.getInputs().get(4);
		AcceleratedConjunctionScalar acs = (AcceleratedConjunctionScalar) (Supplier) gts.getInputs().get(3);

		Evaluable<Scalar> ev = gts.get();

		Scalar distance = ev.evaluate(in, td);
		System.out.println(distance);
		Assert.assertEquals(1.0, distance.getValue(), Math.pow(10, -10));

		distance = intersectAt.get().evaluate(in, td);
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
