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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.graph.mesh.TriangleIntersectAt;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Input;
import org.almostrealism.space.Triangle;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class TriangleTest implements TestFeatures {
	protected Triangle basicTriangle() {
		return new Triangle(
				new Vector(1.0, 1.0, -1.0),
				new Vector(-1.0, 1.0, -1.0),
				new Vector(0.0, -1.0, -1.0));
	}

	@Test(timeout = 10000)
	public void point() {
		PackedCollection data = Vector.bank(3);
		data.set(0, new Vector(1.0, 1.0, -1.0));
		data.set(1, new Vector(-1.0, 1.0, -1.0));
		data.set(2, new Vector(0.0, -1.0, -1.0));

		PackedCollection v = point(Input.value(new TraversalPolicy(3, 3), 0), 1)
				.evaluate(data.traverse(0));
		v.print();
		assertEquals(new Vector(-1.0, 1.0, -1.0), v);
	}

	/**
	 * This test will not pass unless {@link #point()} passes first.
	 */
	@Test(timeout = 10000)
	public void data() {
		HardwareOperator.verboseLog(() -> {
			PackedCollection data = basicTriangle().getData();

			assertEquals(-2.0, data.valueAt(0, 0));
			assertEquals(-1.0, data.valueAt(1, 0));
			assertEquals(-2.0, data.valueAt(1, 1));
		});
	}

	/**
	 * This test will not pass unless {@link #intersectAtDistance()} passes first.
	 */
	@Test(timeout = 10000)
	public void distance() {
		Triangle t = basicTriangle();

		Evaluable<Ray> r = ((Producer) ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get();
		Producer<PackedCollection> distance = () -> new AdaptEvaluable<>(Triangle.intersectAt, r, new Provider<>(t.getData().traverse(0)));
		PackedCollection s = distance.get().evaluate();
		s.print();

		assertEquals(1.0, s);
	}

	/**
	 * If {@link #data()} does not pass, then it is likely the values for
	 * {@link #triangle()} are wrong and so this test will also not pass.
	 */
	@Test(timeout = 10000)
	public void intersectAtDistance() {
		Ray in = new Ray((PackedCollection) ((Producer) ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get().evaluate(), 0);
		PackedCollection td = triangle();

		PackedCollection distance = Triangle.intersectAt.evaluate(in, td.traverse(0));
		log("distance = " + distance);
		assertEquals(1.0, distance.toDouble());
	}

	protected Producer<Ray> intersectAt() {
		return (Producer) basicTriangle().intersectAt(
				ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get(0);
	}

	protected CollectionProducer originProducer() {
		Producer noRank = ((ProducerWithRank) intersectAt()).getProducer();
		if (noRank instanceof ReshapeProducer)
			noRank = ((ReshapeProducer) noRank).getComputation();

		CollectionProducer originVector =
				(CollectionProducer)
						((CollectionProducerComputationBase) noRank).getInputs().get(1);
		if (originVector instanceof ReshapeProducer) {
			originVector = (CollectionProducer) ((ReshapeProducer) originVector).getComputation();
		}

		return (CollectionProducer) ((CollectionProducerComputationBase) originVector).getInputs().get(1);
	}

	@Test(timeout = 10000)
	public void origin() {
		CollectionProducer at = vector(originProducer());
		Evaluable<PackedCollection> ev = at.get();

		Vector p = new Vector(ev.evaluate(), 0);
		p.print();
		Assert.assertEquals(new Vector(0.0, 0.0, -1.0), p);
	}

	protected PackedCollection triangle() {
		Ray in = new Ray((PackedCollection) ((Producer) ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get().evaluate(), 0);
		System.out.println(in);

		PackedCollection data = new PackedCollection(9);
		PackedCollection tp = new PackedCollection(new TraversalPolicy(3, 3), 1,
				delegateSpec ->
					new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				data, 0);
		tp.set(0, new Vector(1.0, 1.0, -1.0));
		tp.set(1, new Vector(-1.0, 1.0, -1.0));
		tp.set(2, new Vector(0.0, -1.0, -1.0));

		Producer<PackedCollection> ptp = new CollectionProviderProducer<>(tp.traverse(0));

		PackedCollection td = triangle(ptp).get().evaluate();
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

	@Test(timeout = 10000)
	public void choiceTest() {
		Ray in = new Ray((PackedCollection) ((Producer) ray(0.0, 0.0, 0.0, 0.0, 0.0, -1.0)).get().evaluate(), 0);
		PackedCollection td = triangle();

		TriangleIntersectAt intersectAt = TriangleIntersectAt.construct(Input.value(shape(4, 3), 1),
				Input.value(shape(-1, 6), 0));

		GreaterThanCollection gts = (GreaterThanCollection) intersectAt.getInputs().get(4);
		Evaluable<PackedCollection> ev = gts.get();

		PackedCollection distance = ev.evaluate(in, td.traverse(0));
		assertEquals(1.0, distance.toDouble());

		distance = intersectAt.get().evaluate(in, td.traverse(0));
		assertEquals(1.0, distance.toDouble());
	}

	@Test(timeout = 10000)
	public void intersection() {
		if (skipKnownIssues) return;

		Evaluable<Ray> ev = intersectAt().get();

		Ray r = new Ray(ev.evaluate(), 0);
		r.print();
		assertEquals(new Ray(new Vector(0.0, 0.0, -1.0), new Vector(0.0, 0.0, 1.0)), r);
	}
}
