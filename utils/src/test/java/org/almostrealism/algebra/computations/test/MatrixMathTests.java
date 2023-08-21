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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class MatrixMathTests implements TestFeatures {
	@Test
	public void matmul() {
		matmul(768, 2048);
	}

	@Test
	public void matmulPowers() {
		for (int i = 1; i < 9; i++) {
			matmul(1 << i, 1 << i);
		}
	}

	protected void matmul(int dim, int width) {
		PackedCollection<?> matrix = new PackedCollection<>(dim, width);
		PackedCollection<?> vector = new PackedCollection<>(width);
		PackedCollection<?> result = new PackedCollection<>(dim);

		matrix.fill(pos -> Math.random());
		vector.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Matrix Test", false);
		op.add(a("matmul " + width, traverseEach(p(result)), matmul(p(matrix), p(vector))));
		Runnable r = ((OperationList) op.optimize()).get(profiles);

		for (int i = 0; i < 100; i++) {
			r.run();
		}

		profiles.print();
	}

	@Test
	public void sumPowers() {
		if (skipLongTests) return;

		for (int i = 1; i < 8; i++) {
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
