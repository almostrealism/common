/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

/**
 * Tests for matrix math computations.
 */
public class MatrixMathTests extends TestSuiteBase {
	/** Enables operation optimization in tests. */
	private static final boolean enableOptimization = false;
	/** Enables test repetition. */
	private static final boolean enableRepeat = true;

	/** Base dispatch-iteration count for {@link #sum} on Metal and CPU backends. */
	private static final int SUM_ITERATIONS = 50000;

	/**
	 * Per-dispatch slowdown of the OpenCL backend relative to Metal (measured ~4.6x on a
	 * self-hosted macOS runner). When OpenCL is the only GPU available, {@link #sum} divides its
	 * iteration count by this so the throughput test runs in a wall-clock time comparable to the
	 * Metal lane rather than exceeding the timeout — the dispatch path is still exercised (tens of
	 * thousands of dispatches), just not the full count that only Metal completes in budget.
	 */
	private static final int CL_ITERATION_DIVISOR = 5;

	/**
	 * Tests matrix multiplication with very small matrices.
	 */
	@Test(timeout = 30000)
	public void matmulVerySmall() {
		matmul(0, 2, 4, enableOptimization, true);
	}

	/**
	 * Tests batch matrix multiplication with very small matrices.
	 */
	@Test(timeout = 30000)
	public void matmulVerySmallBatch() {
		matmul(5, 2, 4, enableOptimization, true);
	}

	/**
	 * Tests matrix multiplication with small matrices.
	 */
	@Test(timeout = 30000)
	public void matmulSmall() {
		matmul(0, 12, 4, enableOptimization, true);
	}

	/**
	 * Tests batch matrix multiplication with small matrices.
	 */
	@Test(timeout = 30000)
	public void matmulSmallBatch() {
		matmul(10, 12, 4, enableOptimization, true);
	}

	/**
	 * Tests matrix multiplication with medium-sized matrices.
	 */
	@Test(timeout = 30000)
	public void matmulMedium() {
		matmul(8, 64, 32, enableOptimization, true);
	}

	/**
	 * Tests matrix multiplication with large matrices.
	 */
	@Test(timeout = 30000)
	@TestDepth(1)
	public void matmulLarge() {
		matmul(2, 2048, 1024, enableOptimization, true);
	}

	/**
	 * Tests matrix multiplication with powers of 2 sizes.
	 */
	@Test(timeout = 20000)
	@TestDepth(1)
	public void matmulPowers() {
		for (int i = 1; i < 8; i++) {
			matmul(0, 1 << i, 1 << i, enableOptimization, false);
		}
	}

	/**
	 * Tests matrix multiply with identity-style input.
	 */
	@Test(timeout = 30000)
	public void matrix1() {
		int n = 2;
		int m = 2;
		int p = 4;

		PackedCollection a = pack(2, 0, 0, 2).reshape(shape(n, m));
		PackedCollection b = pack(1, 1, 0, 0, 0, 0, 1, 1).reshape(shape(m, p));

		CollectionProducer product = matmul(cp(a), cp(b));

		PackedCollection c = product.get().evaluate();
		c.traverse(1).print();
		log("--");

		PackedCollection reference = new PackedCollection(shape(n, p));
		multiplyMatrices(n, m, p, a, b, reference);
		reference.traverse().print();

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < p; j++) {
				assertEquals(reference.valueAt(i, j), c.valueAt(i, j));
			}
		}
	}

	/**
	 * Tests matrix multiply with enumerate and repeat.
	 */
	@Test(timeout = 30000)
	public void matrix2() {
		int n = 2;
		int m = 3;
		int p = 4;

		PackedCollection a = new PackedCollection(shape(n, m)).fill(Math::random);
		PackedCollection b = new PackedCollection(shape(m, p)).fill(Math::random);

		CollectionProducer product =
				cp(b).enumerate(1, 1)
				.reshape(p, m)
				.traverse(1)
				.repeat(n)
				.reshape(p, n, m)
				.traverse(1)
				.multiply(cp(a).repeat(p))
				.reshape(p, n, m)
				.sum(2).traverse(0)
				.enumerate(1, 1)
				.reshape(n, p);

		PackedCollection c = product.get().evaluate();

		print(n, p, c);

		PackedCollection reference = new PackedCollection(shape(n, p));
		multiplyMatrices(n, m, p, a, b, reference);

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < p; j++) {
				assertEquals(reference.valueAt(i, j), c.valueAt(i, j));
			}
		}
	}

	/**
	 * Multiplies two matrices using standard algorithm.
	 */
	private void multiplyMatrices(int n, int m, int p,
								  PackedCollection matrix1,
								  PackedCollection matrix2,
								  PackedCollection destination) {
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

		a(cp(destination), c(result).reshape(destination.getShape())).get().run();
	}

	/**
	 * Tests matrix multiplication with given batch and dimension parameters.
	 */
	protected void matmul(int batches, int dim, int width, boolean optimize, boolean validate) {
		PackedCollection matrix = new PackedCollection(dim, width);
		PackedCollection vector;
		PackedCollection result;

		if (batches > 0) {
			vector = new PackedCollection(batches, width, 1);
			result = new PackedCollection(batches, dim);
		} else {
			vector = new PackedCollection(width);
			result = new PackedCollection(dim);
		}

		matrix.fill(pos -> Math.random());
		vector.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Matrix Test", false);
		op.add(a("matmul " + width, traverseEach(p(result)), matmul(p(matrix), p(vector))));
		Runnable r = optimize ? ((OperationList) op.optimize()).get(profiles) : op.get(profiles);

		verboseLog(() -> r.run());

		if (enableRepeat) {
			profiles.clear();

			for (int i = 0; i < 5000; i++) {
				r.run();
			}
		}

		profiles.print();

		if (validate) {
			if (batches > 0) {
				for (int n = 0; n < batches; n++) {
					for (int i = 0; i < dim; i++) {
						double v = 0.0;

						for (int j = 0; j < width; j++) {
							v += matrix.valueAt(i, j) * vector.valueAt(n, j);
						}

						assertEquals(v, result.valueAt(n, i));
					}
				}
			} else {
				for (int i = 0; i < dim; i++) {
					double v = 0.0;

					for (int j = 0; j < width; j++) {
						v += matrix.valueAt(i, j) * vector.valueAt(j);
					}

					assertEquals(v, result.valueAt(i));
				}
			}
		}
	}

	/**
	 * Tests matrix sum of powers computation.
	 */
	@Test(timeout = 4 * 60000)
	@TestDepth(3)
	public void sumPowers() {
		for (int i = 1; i < 7; i++) {
			sum(600, 1 << i);
		}
	}

	/**
	 * Tests sum operation with specified count and dimension.
	 */
	protected void sum(int count, int dim) {
		PackedCollection vectors = new PackedCollection(count, dim);
		PackedCollection result = new PackedCollection(count);

		vectors.fill(pos -> Math.random());

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("Vector Test", false);
		op.add(a("sum " + dim, traverseEach(p(result)), sum(traverse(1, p(vectors)))));
		Runnable r = ((OperationList) op.optimize()).get(profiles);

		// OpenCL dispatches ~CL_ITERATION_DIVISORx slower per kernel than Metal; when it is the only
		// GPU, run proportionally fewer iterations so the throughput test finishes in a Metal-like
		// wall-clock time instead of timing out.
		Hardware hardware = Hardware.getLocalHardware();
		boolean clOnlyGpu = hardware.isAvailable(ComputeRequirement.GPU)
				&& !hardware.isAvailable(ComputeRequirement.MTL);
		int iterations = clOnlyGpu ? SUM_ITERATIONS / CL_ITERATION_DIVISOR : SUM_ITERATIONS;
		for (int i = 0; i < iterations; i++) {
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
