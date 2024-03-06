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
import org.almostrealism.algebra.DeltaFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Test;

import java.util.stream.IntStream;

public class DeltaFeaturesTests implements DeltaFeatures, TestFeatures {
	static {
		NativeCompiler.enableInstructionSetMonitoring = !TestSettings.skipLongTests;
		MetalProgram.enableProgramMonitoring = !TestSettings.skipLongTests;
	}

	@Test
	public void embedded1() {
		int dim = 3;
		int count = 2;

		PackedCollection<?> v = pack(IntStream.range(0, count * dim).boxed()
				.mapToDouble(Double::valueOf).toArray())
				.reshape(count, dim).traverse();
		PackedCollection<?> w1 = pack(4, -3, 2);
		PackedCollection<?> w2 = pack(2, 1, 5);
		CollectionProducer<PackedCollection<?>> x = x(dim);

		// f(x) = w2 * x
		// g(x) = w1 * x
		// f(g(x)) = w2 * w1 * x
		CollectionProducer<PackedCollection<?>> c = x.mul(p(w1)).mul(p(w2));

		// dy = f'(g(x))
		//    = w2
		Producer<PackedCollection<?>> in = matchInput(c, x);
		Evaluable<PackedCollection<?>> dy = generateIsolatedDelta(shape(in), (ComputationBase) c, in).get();
		PackedCollection<?> dout = dy.evaluate(v);
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
}
