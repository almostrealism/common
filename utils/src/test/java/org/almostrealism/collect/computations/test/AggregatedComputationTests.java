/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.uml.Signature;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class AggregatedComputationTests implements TestFeatures {
	@Test
	public void largeSum() {
		int w = 257;
		int h = 8192;
		int d = 1024;

		PackedCollection<?> a = new PackedCollection<>(shape(h, d))
				.randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(w, d))
				.randFill();

		CollectionProducer<PackedCollection<?>> sum =
				multiply(cp(a).repeat(w).each(),
							cp(b).traverse(1).repeat(h).each())
						.traverse(2)
						.sum();
		log(Signature.of(sum));

		PackedCollection<?> out = sum.evaluate();
		log("NaN Count = " + out.count(Double::isNaN));

		double data[] = out.toArray();

		int nanIndices[] = IntStream.range(0, data.length)
						.filter(i -> Double.isNaN(data[i]))
								.toArray();
		log(Arrays.toString(nanIndices));

		assertEquals(0, out.count(Double::isNaN));
	}
}
