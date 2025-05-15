/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class PackedCollectionTests implements TestFeatures {

	@Test
	public void transpose() {
		PackedCollection<?> data = new PackedCollection<>(shape(10, 4))
				.randFill();
		PackedCollection<?> transposed = data.transpose();

		// Assert transposed dimensions
		assertEquals(4, transposed.getShape().length(0));
		assertEquals(10, transposed.getShape().length(1));

		// Assert transposed values
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 10; j++) {
				log("i: " + i + ", j: " + j);
				assertEquals(data.valueAt(j, i), transposed.valueAt(i, j));
			}
		}
	}

	@Test
	public void clear() {
		PackedCollection<?> data = new PackedCollection<>(4);
		data.setMem(0, 1.0, 2.0, 3.0, 4.0);
		data.clear();
		assertEquals(0, data.toArray(0, 4)[1]);
	}
}
