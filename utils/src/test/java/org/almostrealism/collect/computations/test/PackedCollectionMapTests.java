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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class PackedCollectionMapTests implements TestFeatures {
	@Test
	public void map3d() {
		PackedCollection<?> input = tensor(shape(8, 3, 3)).pack();
		PackedCollection<?> filter = tensor(shape(3, 3)).pack();

		HardwareOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = traverse(1, p(input)).map(v -> v.multiply(p(filter)));
			PackedCollection<?> output = product.get().evaluate();
			System.out.println(output.getShape());

			for (int i = 0; i < 8; i++) {
				for (int j = 0; j < 3; j++) {
					for (int k = 0; k < 3; k++) {
						Assert.assertEquals(input.toDouble(input.getShape().index(i, j, k)) *
										filter.toDouble(filter.getShape().index(j, k)),
								output.toDouble(output.getShape().index(i, j, k)), 0.0001);
					}
				}
			}
		});
	}
}
