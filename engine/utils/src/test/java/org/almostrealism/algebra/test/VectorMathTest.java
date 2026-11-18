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
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.hardware.computations.HardwareEvaluable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for vector mathematical operations.
 */
public class VectorMathTest extends TestSuiteBase {

	/**
	 * Tests scalar power operation.
	 */
	@Test(timeout = 30000)
	public void scalarPow() {
		assertEquals(27, c(3).pow(3).evaluate());
	}

	/**
	 * Tests scalar power with dynamic collection.
	 */
	@Test(timeout = 30000)
	public void scalarPowDynamic() {
		Producer<PackedCollection> d = new DynamicCollectionProducer(shape(1), args -> {
			PackedCollection result = new PackedCollection(1);
			result.setMem(0, 3);
			return result;
		});
		CollectionProducer s = c(3);
		Producer<PackedCollection> p = s.pow(d);
		Evaluable<PackedCollection> ev = p.get();
		PackedCollection out = ev.evaluate();
		double result = out.toDouble(0);
		assertEquals(27, result);
	}

	/**
	 * Tests scalar multiplication.
	 */
	@Test(timeout = 30000)
	public void scalarMultiply() {
		CollectionProducer product = vector(1, 2, 3).multiply(c(2));
		Vector result = new Vector(product.get().evaluate(), 0);
		assertEquals(2, result.toDouble(0));
		assertEquals(4, result.toDouble(1));
		assertEquals(6, result.toDouble(2));
	}

	/**
	 * Tests product from vectors operation 2.
	 */
	@Test(timeout = 30000)
	public void productFromVectors2() {
		Producer<PackedCollection> a = vector(1.0, 2.0, 3.0);
		Producer<PackedCollection> b = vector(4.0, 5.0, 6.0);
		CollectionProducer yTimesZ = y(a).multiply(z(b));
		Producer<PackedCollection> s = add(yTimesZ, c(1));
		HardwareEvaluable<PackedCollection> so = (HardwareEvaluable<PackedCollection>) s.get();
		Assert.assertEquals(1, so.getArgsCount());
	}

	/**
	 * Tests product from vectors operation 3.
	 */
	@Test(timeout = 30000)
	public void productFromVectors3() {
		Producer<PackedCollection> a = vector(1.0, 2.0, 3.0);
		Producer<PackedCollection> b = vector(4.0, 5.0, 6.0);
		CollectionProducer yTimesZ = y(a).multiply(z(b));
		Producer<PackedCollection> s = subtract(yTimesZ, c(1));
		HardwareEvaluable<PackedCollection> so = (HardwareEvaluable<PackedCollection>) s.get();
		Assert.assertEquals(1, so.getArgsCount());
	}

	/**
	 * Tests product difference operation.
	 */
	@Test(timeout = 30000)
	public void productDifference() {
		verboseLog(() -> {
			Producer<PackedCollection> a = vector(1.0, 2.0, 3.0);
			Producer<PackedCollection> b = vector(4.0, 5.0, 6.0);
			CollectionProducer s = y(a).multiply(z(b)).subtract(z(a).multiply(y(b)));
			HardwareEvaluable<PackedCollection> so = (HardwareEvaluable<PackedCollection>) s.get();
			assertEquals(-3.0, so.evaluate());
			Assert.assertEquals(1, so.getArgsCount());
		});
	}

	/**
	 * Creates a cross product producer with a default vector.
	 */
	protected CollectionProducer crossProduct(Producer<PackedCollection> v) {
		return crossProduct(vector(0.0, 0.0, -1.0), v);
	}

	/**
	 * Tests cross product operation.
	 */
	@Test(timeout = 30000)
	public void crossProduct() {
		CollectionProducer cp = crossProduct(vector(100.0, -200.0, 0.0));

		HardwareEvaluable<PackedCollection> cpo = (HardwareEvaluable<PackedCollection>) cp.get();
		assertEquals(1, cpo.getArgsCount());

		Vector v = new Vector(cp.get().evaluate(), 0);
		log(String.valueOf(v));

		assertEquals(-200, v.toDouble(0));
		assertEquals(-100, v.toDouble(1));
		assertEquals(0, v.toDouble(2));
	}

	/**
	 * Tests normalized cross product operation 1.
	 */
	@Test(timeout = 30000)
	public void normalizedCrossProduct1() {
		CollectionProducer cp = normalize(crossProduct(vector(100.0, -200.0, 0.0)));

		HardwareEvaluable<PackedCollection> cpo = (HardwareEvaluable<PackedCollection>) cp.get();
		assertEquals(1, cpo.getArgsCount());

		Vector v = new Vector(cp.get().evaluate(), 0);
		v.print();

		// Expected values: -2/sqrt(5) and -1/sqrt(5)
		assertEquals(-0.8944271909999159, v.toDouble(0));
		assertEquals(-0.4472135954999579, v.toDouble(1));
		assertEquals(0, v.toDouble(2));
	}

	/**
	 * Tests normalized cross product operation 2.
	 */
	@Test(timeout = 30000)
	public void normalizedCrossProduct2() {
		CollectionProducer cp = normalize(crossProduct(v(shape(3), 0)));
		Evaluable<PackedCollection> ev = cp.get();

		Vector v = new Vector(ev.evaluate(new Vector(100, -200, 0)), 0);
		v.print();

		// Expected values: -2/sqrt(5) and -1/sqrt(5)
		assertEquals(-0.8944271909999159, v.toDouble(0));
		assertEquals(-0.4472135954999579, v.toDouble(1));
		assertEquals(0, v.toDouble(2));
	}

	/**
	 * Tests vector power operation.
	 */
	@Test(timeout = 30000)
	public void vectorPow() {
		Vector in = new Vector(3, 4, 5);
		Vector result = new Vector(vector(c(in).pow(2)).get().evaluate(), 0);
		assertEquals(9, result.toDouble(0));
		assertEquals(16, result.toDouble(1));
		assertEquals(25, result.toDouble(2));
	}

	/**
	 * Tests vector normalization.
	 */
	@Test(timeout = 30000)
	public void normalize() {
		PackedCollection v = new PackedCollection(3).randFill();
		PackedCollection result = normalize(cp(v)).evaluate();
		double length = result.doubleStream().map(d -> d * d).sum();
		assertEquals(1.0, length);
	}

	/**
	 * Tests normalization with random vector.
	 */
	@Test(timeout = 30000)
	public void normalizeRandom() {
		PackedCollection result = normalize(new Random(shape(2))).evaluate();
		double length = result.doubleStream().map(d -> d * d).sum();
		assertEquals(1.0, length);
	}

	/**
	 * Tests that {@code x}, {@code y} and {@code z} of a batch of vectors give the component of
	 * every vector, {@code (n, 1)}, so that combining them with another per-vector value, here
	 * the squared length, reads each vector's own component rather than that of the first.
	 */
	@Test(timeout = 60000)
	public void batchedComponents() {
		int n = 64;
		PackedCollection v = new PackedCollection(shape(n, 3)).randFill();
		CollectionProducer[] components = { x(cp(v)), y(cp(v)), z(cp(v)) };

		for (int c = 0; c < 3; c++) {
			assertEquals(n, shape(components[c]).getTotalSize());

			PackedCollection result = dotProduct(cp(v), cp(v)).add(components[c]).evaluate();
			assertEquals(n, result.getMemLength());

			for (int i = 0; i < n; i++) {
				double lengthSq = 0.0;
				for (int k = 0; k < 3; k++) {
					lengthSq += v.toDouble(3 * i + k) * v.toDouble(3 * i + k);
				}

				assertEquals(lengthSq + v.toDouble(3 * i + c), result.toDouble(i));
			}
		}
	}

	/** Tests that {@code x}, {@code y} and {@code z} of a single vector are its components. */
	@Test(timeout = 30000)
	public void singleVectorComponents() {
		Producer<PackedCollection> v = vector(1.0, 2.0, 3.0);
		assertEquals(1.0, x(v).evaluate().toDouble(0));
		assertEquals(2.0, y(v).evaluate().toDouble(0));
		assertEquals(3.0, z(v).evaluate().toDouble(0));
		assertEquals(1, shape(z(v)).getTotalSize());
	}

	/**
	 * Tests that a column-shaped vector {@code (3, 1)} gives each of its components, as a
	 * {@code (1, 1)} value, rather than being sliced along its trailing axis of length one.
	 */
	@Test(timeout = 30000)
	public void columnVectorComponents() {
		PackedCollection v = pack(1.0, 2.0, 3.0).reshape(3, 1);
		CollectionProducer[] components = { x(cp(v)), y(cp(v)), z(cp(v)) };

		for (int c = 0; c < 3; c++) {
			assertEquals(2, shape(components[c]).getDimensions());
			assertEquals(1, shape(components[c]).length(0));
			assertEquals(1, shape(components[c]).length(1));
			assertEquals(c + 1.0, components[c].evaluate().toDouble(0));
		}
	}

	/**
	 * Tests that a batch of one vector {@code (1, 3)} keeps its batch axis, giving
	 * {@code (1, 1)} as {@link #dotProduct} does, rather than being reduced to {@code (1)}.
	 */
	@Test(timeout = 30000)
	public void singleItemBatchComponents() {
		PackedCollection v = pack(4.0, 5.0, 6.0).reshape(1, 3);
		CollectionProducer[] components = { x(cp(v)), y(cp(v)), z(cp(v)) };

		for (int c = 0; c < 3; c++) {
			assertEquals(2, shape(components[c]).getDimensions());
			assertEquals(1, shape(components[c]).length(0));
			assertEquals(1, shape(components[c]).length(1));
			assertEquals(c + 4.0, components[c].evaluate().toDouble(0));
		}
	}

	/**
	 * Tests that a batch of column-shaped vectors {@code (n, 3, 1)} gives the component of
	 * every vector, {@code (n, 1, 1)}, taken from the axis of length three.
	 */
	@Test(timeout = 60000)
	public void batchedColumnVectorComponents() {
		int n = 8;
		PackedCollection v = new PackedCollection(shape(n, 3, 1)).randFill();

		for (int c = 0; c < 3; c++) {
			CollectionProducer component = vectorComponent(cp(v), c);
			assertEquals(n, shape(component).length(0));
			assertEquals(1, shape(component).length(1));
			assertEquals(1, shape(component).length(2));

			PackedCollection result = component.evaluate();
			assertEquals(n, result.getMemLength());

			for (int i = 0; i < n; i++) {
				assertEquals(v.toDouble(3 * i + c), result.toDouble(i));
			}
		}
	}

	/** Tests that a component index beyond the components of each vector is rejected. */
	@Test(timeout = 30000)
	public void componentIndexOutOfRangeIsRejected() {
		PackedCollection v = new PackedCollection(shape(4, 3)).randFill();

		try {
			vectorComponent(cp(v), 3);
			Assert.fail("index 3 of a (4, 3) batch should be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("out of range"));
		}
	}
}