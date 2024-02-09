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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class RotationTests implements TestFeatures {
	@Test
	public void ropeRotation() {
		int heads = 12;
		int headSize = 32;

		TraversalPolicy shape = shape(heads, headSize, 2);

		PackedCollection<?> in = new PackedCollection<>(shape).randFill();
		PackedCollection<?> weights = new PackedCollection<>(shape(1024, headSize, 2)).randFill();

		int p = 28;

		Producer<PackedCollection<?>> pos = c(p, 0, 0);

		CollectionProducer<PackedCollection<?>> q = c(p(in)).traverse(2);
		CollectionProducer<PackedCollection<?>> r = subset(shape(1, headSize, 2),
															c(p(weights)), pos);
		// r = c(p(r.get().evaluate()));

		// CollectionProducer<PackedCollection<?>> o = multiplyComplex(traverse(1, p(in)), r.reshape(headSize, 2));
		CollectionProducer<PackedCollection<?>> o = multiplyComplex(traverse(1, p(in)), r.traverse(1));

		// TODO  Optimization should not be necessary
		// PackedCollection<?> out = o.get().evaluate();
		PackedCollection<?> out = ((Evaluable<PackedCollection<?>>) ((ParallelProcess) o).optimize().get()).evaluate();

		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				double q0 = in.valueAt(h, i, 0);
				double q1 = in.valueAt(h, i, 1);
				double fcr = weights.valueAt(p, i, 0);
				double fci = weights.valueAt(p, i, 1);

				double expected = q0 * fcr - q1 * fci;
				double actual = out.valueAt(h, i, 0);
				System.out.println("RotationTests[" + h + "][" + i + "]: " + expected + " vs " + actual);
				assertEquals(expected, actual);

				expected = q0 * fci + q1 * fcr;
				actual = out.valueAt(h, i, 1);
				System.out.println("RotationTests[" + h + "][" + i + "]: " + expected + " vs " + actual);
				assertEquals(expected, actual);
			}
		}
	}
}
