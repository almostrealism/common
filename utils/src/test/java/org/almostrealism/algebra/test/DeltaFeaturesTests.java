/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.ComputationBase;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class DeltaFeaturesTests implements DeltaFeatures, TestFeatures {
	@Test(timeout = 60000)
	public void embeddedProduct() {
		int dim = 3;
		int count = 2;

		PackedCollection v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection w1 = pack(4, -3, 2);
		PackedCollection w2 = pack(2, 1, 5);
		CollectionProducer x = x(-1, dim);

		// f(x) = w2 * x
		// g(x) = w1 * x
		// f(g(x)) = w2 * w1 * x
		CollectionProducer c = x.mul(p(w1)).mul(p(w2));

		// dy = f'(g(x))
		//    = w2
		Producer<PackedCollection> in = AlgebraFeatures.matchInput(c, x).get();
		Evaluable<PackedCollection> dy = generateIsolatedDelta((ComputationBase) c, in).get();
		PackedCollection dout = dy.evaluate(v);
		dout.print();

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(w2.toDouble(j), dout.toDouble(i * dim * dim + j * dim + k));
					} else {
						assertEquals(0.0, dout.toDouble(i * dim * dim + j * dim + k));
					}
				}
			}
		}
	}

	@Test(timeout = 60000)
	public void embeddedPower() {
		if (skipKnownIssues) return;

		int dim = 3;
		int count = 2;

		PackedCollection v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();

		CollectionProducer x = x(dim);

		// f(x) = x^2
		CollectionProducer c = x.each().pow(2.0);

		// dy = f'(x)
		//    = 2x
		Producer<PackedCollection> in = AlgebraFeatures.matchInput(c, x).get();
		Evaluable<PackedCollection> dy = generateIsolatedDelta((ComputationBase) c, in).get();
		PackedCollection dout = dy.evaluate(v);
		dout.print();

		for (int i = 0; i < count; i++) {
			for (int j = 0 ; j < dim; j++) {
				for (int k = 0; k < dim; k++) {
					if (j == k) {
						assertEquals(2 * v.valueAt(i, j), dout.valueAt(i, j, k));
					} else {
						assertEquals(0.0, dout.valueAt(i, j, k));
					}
				}
			}
		}
	}

	@Test(timeout = 60000)
	public void embeddedSum() {
		// f(x) = x0 + x1, x2 + x3
		// g(x) = w * x
		// f(g(x)) = w0 * (x0 + x1), w1 * (x2 + x3)
		int dim = 2;

		PackedCollection w = pack(4, -3);
		PackedCollection input =
				pack(1, 2, 3, 4)
				.reshape(dim, dim);
		CollectionProducer c =
				cp(input)
				.sum(1)
				.multiply(cp(w));

		// dy = f'(g(x))
		//    = w0, w1
		Producer<PackedCollection> in = AlgebraFeatures.matchInput(c, cp(input)).get();
		Evaluable<PackedCollection> dy = generateIsolatedDelta((ComputationBase) c, in).get();
		PackedCollection dout = dy.evaluate();
		dout.traverse().print();

		for (int j = 0 ; j < dim; j++) {
			for (int k = 0; k < dim; k++) {
				if (j == k) {
					assertEquals(w.toDouble(j), dout.valueAt(j, 0, k, 0));
				} else {
					assertEquals(0.0, dout.valueAt(j, 0, k, 0));
				}
			}
		}
	}

	@Test(timeout = 60000)
	public void embeddedRepeat() {
		// f(x) = x0, x0
		// g(x) = w * x
		// f(g(x)) = w0 * (x0), w1 * (x0)
		int dim = 2;

		PackedCollection w = pack(4, -3);
		PackedCollection input = pack(3);
		CollectionProducer c =
				cp(input)
						.repeat(2)
						.multiply(cp(w));

		// dy = f'(g(x))
		//    = w0, w1
		Producer<PackedCollection> in = AlgebraFeatures.matchInput(c, cp(input)).get();
		Evaluable<PackedCollection> dy = generateIsolatedDelta((ComputationBase) c, in).get();
		PackedCollection dout = dy.evaluate();
		dout.traverse().print();

		for (int j = 0 ; j < dim; j++) {
			for (int k = 0; k < dim; k++) {
				if (j == k) {
					assertEquals(w.toDouble(j), dout.valueAt(j, 0, k, 0));
				} else {
					assertEquals(0.0, dout.valueAt(j, 0, k, 0));
				}
			}
		}
	}
}
