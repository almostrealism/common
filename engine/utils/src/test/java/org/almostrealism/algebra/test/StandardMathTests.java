/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

public class StandardMathTests extends TestSuiteBase {
	@Test(timeout = 30000)
	public void add() {
		CollectionProducer sum = add(c(1.0), c(2.0));
		Evaluable ev = sum.get();
		System.out.println(ev.evaluate());
		assertEquals(3.0, ev.evaluate());
	}

	@Test(timeout = 30000)
	public void divide() {
		int dim = 256;
		PackedCollection in = new PackedCollection(dim).randnFill();
		CollectionProducer inp = cp(in);
		CollectionProducer o = divide(c(1.0), inp.traverseEach());

		Assert.assertEquals(1, o.getShape().getDimensions());
		Assert.assertEquals(256, o.getShape().length(0));

		PackedCollection out = o.get().evaluate();
		Assert.assertEquals(1, out.getShape().getDimensions());
		Assert.assertEquals(256, out.getShape().length(0));

		for (int i = 0; i < dim; i++) {
			double expected = 1.0 / in.valueAt(i);
			double actual = out.valueAt(i);
			assertEquals(expected, actual);
		}
	}

	@Test(timeout = 30000)
	public void multiplyBroadcast() {
		PackedCollection in = new PackedCollection(shape(12, 32, 2)).randFill();
		PackedCollection x = new PackedCollection(shape(32, 2)).randFill();
		Producer<PackedCollection> o = c(p(in)).traverse(1).multiply(c(p(x)));

		verboseLog(() -> {
			PackedCollection result = o.get().evaluate();

			for (int n = 0; n < 12; n++) {
				for (int i = 0; i < 32; i++) {
					for (int j = 0; j < 2; j++) {
						double expected = in.valueAt(n, i, j) * x.valueAt(i, j);
						double actual = result.valueAt(n, i, j);
						assertEquals(expected, actual);
					}
				}
			}
		});
	}

	@Test(timeout = 30000)
	public void silu() {
		int dim = 256;
		PackedCollection in = new PackedCollection(dim).randnFill();
		CollectionProducer inp = cp(in);
		Producer<PackedCollection> o = inp.traverseEach().sigmoid().multiply(inp.traverseEach());

		PackedCollection out = o.get().evaluate();

		for (int i = 0; i < dim; i++) {
			double expected = in.valueAt(i) / (1.0f + Math.exp(-in.valueAt(i)));
			double actual = out.valueAt(i);
			assertEquals(expected, actual);
		}
	}
}
