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

import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MatrixMathTests implements TestFeatures {
	private static boolean enableOptimization = false;
	private static boolean enableRepeat = true;

	@Test
	public void matmul() {
//		matmul(128, 64, true);
		matmul(2048, 1024, true);
	}

	@Test
	public void matmulPowers() {
		for (int i = 1; i < 8; i++) {
			matmul(1 << i, 1 << i, false);
		}
	}

	@Test
	public void matrix1() {
		int n = 2;
		int m = 2;
		int p = 4;

		PackedCollection<?> a = pack(2, 0, 0, 2).reshape(shape(n, m));
		PackedCollection<?> b = pack(1, 1, 0, 0, 0, 0, 1, 1).reshape(shape(m, p));

		CollectionProducer<PackedCollection<?>> product = matmul(cp(a), cp(b));

		PackedCollection<?> c = product.get().evaluate();
		c.traverse().print();

		PackedCollection<?> reference = new PackedCollection<>(shape(n, p));
		multiplyMatrices(n, m, p, a, b, reference);
		reference.traverse().print();

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < p; j++) {
				assertEquals(reference.valueAt(i, j), c.valueAt(i, j));
			}
		}
	}

	@Test
	public void matrix2() {
		int n = 2;
		int m = 3;
		int p = 4;

		PackedCollection<?> a = new PackedCollection<>(shape(n, m)).fill(Math::random);
		PackedCollection<?> b = new PackedCollection<>(shape(m, p)).fill(Math::random);

		CollectionProducer<PackedCollection<?>> product =
				cp(b).enumerate(1, 1)
				.reshape(p, m)
				.traverse(1)
				.repeat(n)
				.reshape(p, n, m)
				.traverse(1)
				.multiply(cp(a).repeat(p))
				.reshape(p, n, m).sum(2)
				.enumerate(1, 1)
				.reshape(n, p);

		PackedCollection<?> c = product.get().evaluate();

		print(n, p, c);

		PackedCollection<?> reference = new PackedCollection<>(shape(n, p));
		multiplyMatrices(n, m, p, a, b, reference);

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < p; j++) {
				assertEquals(reference.valueAt(i, j), c.valueAt(i, j));
			}
		}
	}

	private void multiplyMatrices(int n, int m, int p,
								  PackedCollection<?> matrix1,
								  PackedCollection<?> matrix2,
								  PackedCollection<?> destination) {
		int rows1 = n;
		int cols1 = m;
		int cols2 = p;

		double[] result = new double[rows1 * cols2];

		for (int i = 0; i < rows1; i++) {
			for (int j = 0; j < cols2; j++) {
				for (int k = 0; k < cols1; k++) {
					result[i * cols2 + j] += matrix1.valueAt(i, k) * matrix2.valueAt(k, j);
				}
			}
		}

		destination.setMem(result);
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

		verboseLog(() -> r.run());

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
		if (testDepth < 3) return;

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
