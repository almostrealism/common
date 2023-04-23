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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.algebra.computations.VectorExpressionComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import static org.almostrealism.Ops.ops;

public class VectorMathTest implements TestFeatures {
	@Test
	public void scalarPow() {
		Scalar result = scalar(3).pow(3).get().evaluate();
		assertEquals(27, result);
	}

	@Test
	public void scalarPowDynamic() {
		Producer<Scalar> d = () -> args -> new Scalar(3);
		ExpressionComputation<Scalar> s = scalar(3);
		Producer<Scalar> p = s.pow(d);
		Evaluable<Scalar> ev = p.get();
		PackedCollection<?> out = ev.evaluate();
		double result = out.toDouble(0);
		assertEquals(27, result);
	}

	@Test
	public void scalarMultiply() {
		VectorProducerBase product = scalarMultiply(vector(1, 2, 3), 2);
		Vector result = product.get().evaluate();
		assertEquals(2, result.getX());
		assertEquals(4, result.getY());
		assertEquals(6, result.getZ());
	}

	@Test
	public void productFromVectors1() {
		Producer<Vector> a = vector(1.0, 2.0, 3.0);
		Producer<Vector> b = vector(4.0, 5.0, 6.0);
		ScalarProducerBase s = y(a).multiply(z(b));
		Evaluable<Scalar> so = s.get();
		// Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productFromVectors2() {
		Producer<Vector> a = vector(1.0, 2.0, 3.0);
		Producer<Vector> b = vector(4.0, 5.0, 6.0);
		ScalarProducerBase s = y(a).multiply(z(b)).add(1);
		KernelizedEvaluable<Scalar> so = s.get();
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productFromVectors3() {
		Producer<Vector> a = vector(1.0, 2.0, 3.0);
		Producer<Vector> b = vector(4.0, 5.0, 6.0);
		ScalarProducerBase s = y(a).multiply(z(b)).subtract(1);
		KernelizedEvaluable<Scalar> so = s.get();
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productDifference() {
		HardwareOperator.verboseLog(() -> {
			Producer<Vector> a = vector(1.0, 2.0, 3.0);
			Producer<Vector> b = vector(4.0, 5.0, 6.0);
			ScalarProducerBase s = y(a).multiply(z(b))
					.subtract(z(a).multiply(y(b)));
			KernelizedEvaluable<Scalar> so = s.get();
			assertEquals(-3.0, so.evaluate());
			Assert.assertEquals(1, so.getArgsCount());
		});
	}

	protected VectorExpressionComputation crossProduct(Producer<Vector> v) {
		return crossProduct(vector(0.0, 0.0, -1.0), v);
	}

	@Test
	public void crossProduct() {
		VectorProducerBase cp = crossProduct(vector(100.0, -100.0, 0.0)
						.subtract(vector(0.0, 100.0, 0.0)));

		Vector v = cp.get().evaluate();
		System.out.println(v);

		Assert.assertEquals(-200, v.getX(), Math.pow(10, -10));
		Assert.assertEquals(-100, v.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, v.getZ(), Math.pow(10, -10));
	}

	@Test
	public void crossProductCompact() {
		VectorProducerBase cp = crossProduct(vector(100.0, -200.0, 0.0));

		KernelizedEvaluable<Vector> cpo = cp.get();
		Assert.assertEquals(1, cpo.getArgsCount());

		Vector v = cp.get().evaluate();
		System.out.println(v);

		Assert.assertEquals(-200, v.getX(), Math.pow(10, -10));
		Assert.assertEquals(-100, v.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, v.getZ(), Math.pow(10, -10));
	}
}
