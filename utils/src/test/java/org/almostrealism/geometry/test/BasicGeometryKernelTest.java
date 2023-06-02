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

package org.almostrealism.geometry.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;

public class BasicGeometryKernelTest implements TestFeatures {
	// TODO  @Test
	public void test() {
		PackedCollection<Scalar> b = integers(0, 10)
				.map(shape(1), v -> scalar(v))
				.scalarMap(v -> scalarsMultiply(v, v(10)))
				.collect(dims -> Scalar.scalarBank(dims.length(1)));
		// TODO  Create translation matrix
		assertEquals(20, b.get(2));
	}
}
