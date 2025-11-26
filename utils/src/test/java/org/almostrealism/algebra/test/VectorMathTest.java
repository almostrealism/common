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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class VectorMathTest implements TestFeatures {
	@Test
	public void scalarPow() {
		assertEquals(27, c(3).pow(3).evaluate());
	}

	@Test
	public void scalarPowDynamic() {
		Producer<PackedCollection<?>> d = new DynamicCollectionProducer<>(shape(1), args -> {
			PackedCollection<?> result = new PackedCollection<>(1);
			result.setMem(0, 3);
			return result;
		});
		CollectionProducer<PackedCollection<?>> s = c(3);
		Producer<PackedCollection<?>> p = s.pow(d);
		Evaluable<PackedCollection<?>> ev = p.get();
		PackedCollection<?> out = ev.evaluate();
		double result = out.toDouble(0);
		assertEquals(27, result);
	}

	@Test
	public void scalarMultiply() {
		CollectionProducer<PackedCollection<?>> product = vector(1, 2, 3).multiply(c(2));
		Vector result = new Vector(product.get().evaluate(), 0);
		assertEquals(2, result.getX());
		assertEquals(4, result.getY());
		assertEquals(6, result.getZ());
	}

	@Test
	public void productFromVectors2() {
		Producer<Vector> a = vector(1.0, 2.0, 3.0);
		Producer<Vector> b = vector(4.0, 5.0, 6.0);
		CollectionProducer<?> yTimesZ = y(a).multiply(z(b));
		Producer<PackedCollection<?>> s = add((Producer) yTimesZ, c(1));
		HardwareEvaluable<PackedCollection<?>> so = (HardwareEvaluable<PackedCollection<?>>) s.get();
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productFromVectors3() {
		Producer<Vector> a = vector(1.0, 2.0, 3.0);
		Producer<Vector> b = vector(4.0, 5.0, 6.0);
		CollectionProducer<?> yTimesZ = y(a).multiply(z(b));
		Producer<PackedCollection<?>> s = subtract((Producer) yTimesZ, c(1));
		HardwareEvaluable<PackedCollection<?>> so = (HardwareEvaluable<PackedCollection<?>>) s.get();
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productDifference() {
		verboseLog(() -> {
			Producer<Vector> a = vector(1.0, 2.0, 3.0);
			Producer<Vector> b = vector(4.0, 5.0, 6.0);
			CollectionProducer s = y(a).multiply(z(b)).subtract(z(a).multiply(y(b)));
			HardwareEvaluable<PackedCollection<?>> so = (HardwareEvaluable<PackedCollection<?>>) s.get();
			assertEquals(-3.0, so.evaluate());
			Assert.assertEquals(1, so.getArgsCount());
		});
	}

	protected CollectionProducer<Vector> crossProduct(Producer<Vector> v) {
		return vector(crossProduct(vector(0.0, 0.0, -1.0), v));
	}

	@Test
	public void crossProduct() {
		CollectionProducer<Vector> cp = crossProduct(vector(100.0, -200.0, 0.0));

		HardwareEvaluable<Vector> cpo = (HardwareEvaluable<Vector>) cp.get();
		assertEquals(1, cpo.getArgsCount());

		Vector v = cp.get().evaluate();
		System.out.println(v);

		assertEquals(-200, v.getX());
		assertEquals(-100, v.getY());
		assertEquals(0, v.getZ());
	}

	@Test
	public void normalizedCrossProduct1() {
		CollectionProducer<Vector> cp = normalize(crossProduct(vector(100.0, -200.0, 0.0)));

		HardwareEvaluable<Vector> cpo = (HardwareEvaluable<Vector>) cp.get();
		assertEquals(1, cpo.getArgsCount());

		Vector v = new Vector(cp.get().evaluate(), 0);
		v.print();

		// Expected values: -2/sqrt(5) and -1/sqrt(5)
		assertEquals(-0.8944271909999159, v.getX());
		assertEquals(-0.4472135954999579, v.getY());
		assertEquals(0, v.getZ());
	}

	@Test
	public void normalizedCrossProduct2() {
		CollectionProducer<Vector> cp = normalize(crossProduct(v(shape(3), 0)));
		Evaluable<Vector> ev = cp.get();

		Vector v = new Vector(ev.evaluate(new Vector(100, -200, 0)), 0);
		v.print();

		// Expected values: -2/sqrt(5) and -1/sqrt(5)
		assertEquals(-0.8944271909999159, v.getX());
		assertEquals(-0.4472135954999579, v.getY());
		assertEquals(0, v.getZ());
	}

	@Test
	public void vectorPow() {
		Vector in = new Vector(3, 4, 5);
		Vector result = vector(c(in).pow(2)).get().evaluate();
		assertEquals(9, result.getX());
		assertEquals(16, result.getY());
		assertEquals(25, result.getZ());
	}

	@Test
	public void normalize() {
		PackedCollection<?> v = new PackedCollection<>(3).randFill();
		PackedCollection<?> result = normalize(cp(v)).evaluate();
		double length = result.doubleStream().map(d -> d * d).sum();
		assertEquals(1.0, length);
	}

	@Test
	public void normalizeRandom() {
		PackedCollection<?> result = normalize(new Random(shape(2))).evaluate();
		double length = result.doubleStream().map(d -> d * d).sum();
		assertEquals(1.0, length);
	}
}
