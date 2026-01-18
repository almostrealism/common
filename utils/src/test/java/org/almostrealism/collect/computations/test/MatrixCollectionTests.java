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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class MatrixCollectionTests implements TestFeatures {
	@Test(timeout = 30000)
	public void matrixMultiplyMap() {
		int size = 48;
		int n = size;
		int d = size;

		PackedCollection x = new PackedCollection(shape(n));
		x.fill(pos -> Math.random());

		PackedCollection weight = new PackedCollection(shape(d, n));
		weight.fill(pos -> Math.random());

		kernelTest(() -> reduce(traverse(1, p(weight)),
							v -> v.multiply(p(x)).sum()),
				output -> {
					for (int i = 0; i < d; i++) {
						double v = 0.0;

						for (int j = 0; j < n; j++) {
							v += weight.valueAt(i, j) * x.valueAt(j);
						}

						Assert.assertEquals(output.valueAt(i, 0), v, 1e-5);
					}
				}, false, false, true);
	}

	@Test(timeout = 30000)
	public void matrixMultiplyRepeat() {
		int size = 768;
		int n = size;
		int d = size;

		PackedCollection x = new PackedCollection(shape(n));
		x.fill(pos -> Math.random());

		PackedCollection weight = new PackedCollection(shape(d, n));
		weight.fill(pos -> Math.random());

		kernelTest(() -> multiply(traverseEach(p(weight)), traverseEach(repeat(d, p(x)))).traverse(1).sum(),
				output -> {
					for (int i = 0; i < d; i++) {
						double v = 0.0;

						for (int j = 0; j < n; j++) {
							v += weight.valueAt(i, j) * x.valueAt(j);
						}

						assertEquals(output.valueAt(i, 0), v);
					}
				}, false, false, true);
	}
}
