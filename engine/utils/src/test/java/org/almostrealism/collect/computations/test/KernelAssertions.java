/*
 * Copyright 2024 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Assert;

public interface KernelAssertions extends TestSettings, ConsoleFeatures {
	default void pool2d(int r, int c, int d, int w, PackedCollection input, PackedCollection output) {
		System.out.println("Validate Pool2D: Output shape = " + output.getShape());
		System.out.println("Validate Pool2D: Output size = " + output.getShape().getSize());

		int r2 = r / w;
		int c2 = c / w;

		for (int copy = 0; copy < d; copy++) {
			for (int i = 0; i < r2; i++) {
				for (int j = 0; j < c2; j++) {
					// System.out.println("Assertions: " + i + ", " + j);

					double expected = -Math.pow(10, 5);

					for (int k = 0; k < w; k++) {
						for (int l = 0; l < w; l++) {
							expected = Math.max(expected, input.valueAt(i * w + k, j * w + l, copy));
						}
					}

					double actual = output.valueAt(i, j, copy);

					if (verboseLogs)
						log("Pool2D Assertions[" + i + ", " + j + "]: Expected " +
								expected + " vs actual " + actual);

					Assert.assertEquals(expected, actual, 0.0001);
				}
			}
		}
	}
}
