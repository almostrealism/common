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

package org.almostrealism.collect.test;

import io.almostrealism.collect.RepeatOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class CollectionOrderingTests implements TestFeatures {
	@Test
	public void repeatOrdering() {
		PackedCollection<?> root = pack(2.0, 3.0, 1.0);
		PackedCollection<?> repeated = new PackedCollection<>(shape(4, 3), 1,
										root, 0, new RepeatOrdering(3));
		repeated.print();

		assertEquals(2.0, repeated.valueAt(0, 0));
		assertEquals(3.0, repeated.valueAt(0, 1));
		assertEquals(1.0, repeated.valueAt(0, 2));
		assertEquals(2.0, repeated.valueAt(1, 0));
		assertEquals(3.0, repeated.valueAt(1, 1));
		assertEquals(1.0, repeated.valueAt(1, 2));
	}

	@Test
	public void repeatOrderingProduct() {
		PackedCollection<?> root = pack(2.0, 3.0, 1.0);
		PackedCollection<?> repeated = new PackedCollection<>(shape(4, 3), 1,
				root, 0, new RepeatOrdering(3));

		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> product = c(2).multiply(cp(repeated)).evaluate();
			product.print();

			assertEquals(4.0, product.valueAt(0, 0));
			assertEquals(6.0, product.valueAt(0, 1));
			assertEquals(2.0, product.valueAt(0, 2));
			assertEquals(4.0, product.valueAt(3, 0));
			assertEquals(6.0, product.valueAt(3, 1));
			assertEquals(2.0, product.valueAt(3, 2));
		});
	}
}
