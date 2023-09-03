/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph.model.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class LayerTests implements TestFeatures {
	@Test
	public void softmaxComputation() {
		int heads = 12;
		int len = 1024;
		int l = 64;

		PackedCollection<?> in = new PackedCollection<>(heads, len).randFill().traverseEach();
//		PackedCollection<?> subtractMax = new PackedCollection<>(heads, len);
//		PackedCollection<?> exp = new PackedCollection<>(heads, len);
//		PackedCollection<?> norm = new PackedCollection<>(heads, len);

		for (int h = 0; h < heads; h++) {
			for (int i = l; i < len; i++) {
				in.setMem(in.getShape().index(h, i), 0.0);
			}
		}

		Producer<PackedCollection<?>> input = p(in);
		boolean subtractMax = true;

		HardwareOperator.verboseLog(() -> {
//			cp(in).traverse(2).subtract(cp(in).traverse(1).max().expand(len, v -> v.repeat(len))).get().into(subtractMax.traverseEach()).evaluate();
//			cp(subtractMax).exp().get().into(exp).evaluate();
//			cp(exp).traverse(1).divide(cp(exp).traverse(1).sum().expand(len, v -> v.repeat(len))).get().into(norm.traverse(1)).evaluate();

			CollectionProducer<PackedCollection<?>> o = traverse(1, input);

			if (subtractMax) {
				o = o.max();
				o = o.expand(len, v -> v.repeat(len));
				o = traverse(2, input).subtractIgnoreZero(o);
			}

			o = o.expIgnoreZero().traverse(1);
			o = o.divide(o.sum().expand(len, v -> v.repeat(len)));

			// PackedCollection<?> output = o.get().evaluate();

			PackedCollection<?> output = new PackedCollection<>(heads, len);

			OperationList op = new OperationList();
			op.add(a(traverse(1, p(output)), o));
			op.optimize().get().run();

			for (int h = 0; h < heads; h++) {
				double max = in.valueAt(h, 0);
				for (int i = 1; i < l; i++) {
					if (in.valueAt(h, i) > max) {
						max = in.valueAt(h, i);
					}
				}

				double x[] = new double[len];
				double sum = 0.0;
				for (int i = 0; i < l; i++) {
					x[i] = subtractMax ? Math.exp(in.valueAt(h, i) - max) : Math.exp(in.valueAt(h, i));
					sum += x[i];
				}

				for (int i = 0; i < l; i++) {
					x[i] /= sum;
					double actual = output.valueAt(h, i);
					System.out.println("LayerTest[" + h + "] " + x[i] + " vs " + actual);
					assertEquals(x[i], actual);
				}
			}
		});
	}
}
