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

package org.almostrealism.hardware.test;

import io.almostrealism.code.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class OperationOptimizationTests implements TestFeatures {
	@Test
	public void reshapeEnumerate() {
		if (SystemUtils.isAarch64() && skipLongTests) return;

		int seqLength = 1024;
		int heads = 12;
		int headSize = 64;
		int dim = heads * headSize;

		TraversalPolicy valueShape = shape(seqLength, heads, headSize);

		PackedCollection<?> values = new PackedCollection<>(valueShape);
		PackedCollection<?> out = new PackedCollection<>(shape(heads, headSize, seqLength));

		values.fill(pos -> Math.random());

		CollectionProducer<PackedCollection<?>> v =
				c(p(values)).reshape(shape(seqLength, dim))
				.enumerate(1, 1)
				.reshape(shape(heads, headSize, seqLength));

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("reshapeEnumerate test", false);
		op.add(a("reshapeEnumerate", traverseEach(p(out)), v));
		((OperationList) op.optimize()).get(profiles).run();

		profiles.print();

		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				for (int t = 0; t < seqLength; t++) {
					assertEquals(values.valueAt(t, h, i), out.valueAt(h, i, t));
				}
			}
		}
	}
}
