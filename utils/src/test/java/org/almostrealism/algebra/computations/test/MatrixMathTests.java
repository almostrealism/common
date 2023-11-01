/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.code.OperationProfile;
import io.almostrealism.kernel.KernelPreferences;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MatrixMathTests implements TestFeatures {
	private static boolean enableOptimization = false;
	private static boolean enableRepeat = true;

	@Test
	public void matmul() {
		if (KernelPreferences.isPreferLoops()) {
			matmul(2048, 1024, true);
		} else {
			matmul(128, 64, true);
		}
	}

	@Test
	public void matmulPowers() {
		for (int i = 1; i < 8; i++) {
			matmul(1 << i, 1 << i, false);
		}
	}

	protected void matmul(int dim, int width, boolean validate) {
		PackedCollection<?> matrix = new PackedCollection<>(dim, width);
		PackedCollection<?> vector = new PackedCollection<>(width);
		PackedCollection<?> result = new PackedCollection<>(dim);

		matrix.fill(pos -> Math.random());
		vector.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Matrix Test", false);
		op.add(a("matmul " + width, traverseEach(p(result)), matmul(p(matrix), p(vector))));
		Runnable r = enableOptimization ? ((OperationList) op.optimize()).get(profiles) : op.get(profiles);

		HardwareOperator.verboseLog(() -> r.run());

		if (enableRepeat) {
			profiles.clear();

			for (int i = 0; i < 5000; i++) {
				r.run();
			}
		}

		profiles.print();

		if (validate) {
			for (int i = 0; i < dim; i++) {
				double v = 0.0;

				for (int j = 0; j < width; j++) {
					v += matrix.valueAt(i, j) * vector.valueAt(j);
				}

				assertEquals(v, result.valueAt(i));
			}
		}
	}

	@Test
	public void sumPowers() {
		if (skipLongTests) return;

		for (int i = 1; i < 7; i++) {
			sum(600, 1 << i);
		}
	}

	protected void sum(int count, int dim) {
		PackedCollection<?> vectors = new PackedCollection<>(count, dim);
		PackedCollection<?> result = new PackedCollection<>(count);

		vectors.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Vector Test", false);
		op.add(a("sum " + dim, traverseEach(p(result)), sum(traverse(1, p(vectors)))));
		Runnable r = ((OperationList) op.optimize()).get(profiles);

//		HardwareOperator.verboseLog(() -> {
//			r.run();
//		});

		for (int i = 0; i < 50000; i++) {
			r.run();
		}

		for (int i = 0; i < count; i++) {
			double expected = 0.0;

			for (int j = 0; j < dim; j++) {
				expected += vectors.valueAt(i, j);
			}

			assertEquals(expected, result.valueAt(i));
		}

		profiles.print();
	}
}
