/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * Tests for {@link SparseMatrix}: parity of the CSR product with dense {@code matmul},
 * degenerate rows, composition with other computations, integer-exact row offsets above
 * {@code 2^24}, input validation, and a connectome-scale footprint and throughput check.
 *
 * <p>The index structure of each matrix is generated on the host and enters the device through
 * {@link PackedCollection#ofSplitIndices(int...)}; values and vectors are generated on the device
 * and read back only to compute the host reference each result is checked against.</p>
 */
public class SparseMatrixTests extends TestSuiteBase implements MatrixFeatures {
	/** {@code 2^24}, the largest bound below which every integer is exact in single precision. */
	private static final int SINGLE_PRECISION_LIMIT = 1 << 24;

	/** Absolute tolerance for comparisons that accumulate rounding error in FP32. */
	private static final double TOLERANCE = 1e-3;

	/**
	 * Builds a random CSR index structure in which row 0 is empty, row 1 (when present) holds
	 * every column, and every other entry is present with the given density.
	 *
	 * @param random  the random source
	 * @param rows    the number of rows
	 * @param cols    the number of columns
	 * @param density the probability that an entry of an ordinary row is present
	 * @return the row offsets followed by the columns
	 */
	private int[][] randomStructure(Random random, int rows, int cols, double density) {
		int[] offsets = new int[rows + 1];
		int[] columns = new int[rows * cols];
		int k = 0;
		for (int i = 0; i < rows; i++) {
			offsets[i] = k;
			for (int j = 0; j < cols; j++) {
				if (i == 1 || (i > 1 && random.nextDouble() < density)) {
					columns[k++] = j;
				}
			}
		}

		offsets[rows] = k;
		return new int[][] { offsets, Arrays.copyOf(columns, k) };
	}

	/**
	 * Computes {@code A·X} on the host for a CSR matrix and a row-major {@code (cols, width)}
	 * operand, optionally with every row offset rounded through single precision as an ordinary
	 * FP32 collection would store it.
	 *
	 * @param offsets the row offsets
	 * @param columns the column of each entry
	 * @param values  the value of each entry
	 * @param x       the operand
	 * @param width   the number of operand columns
	 * @param round   whether to round the offsets through {@code float}
	 * @return the row-major {@code (rows, width)} product
	 */
	private double[] product(int[] offsets, int[] columns, double[] values, double[] x,
							 int width, boolean round) {
		int rows = offsets.length - 1;
		double[] y = new double[rows * width];
		for (int i = 0; i < rows; i++) {
			int start = round ? (int) (float) offsets[i] : offsets[i];
			int end = Math.min(columns.length, round ? (int) (float) offsets[i + 1] : offsets[i + 1]);
			for (int k = start; k < end; k++) {
				for (int n = 0; n < width; n++) {
					y[i * width + n] += values[k] * x[columns[k] * width + n];
				}
			}
		}
		return y;
	}

	/**
	 * Asserts that a result matches the expected values element by element.
	 *
	 * @param expected  the expected values
	 * @param actual    the result
	 * @param tolerance the absolute tolerance
	 */
	private void assertMatches(double[] expected, PackedCollection actual, double tolerance) {
		Assert.assertEquals(expected.length, actual.getMemLength());
		double[] values = actual.toArray();
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("element " + i, expected[i], values[i], tolerance);
		}
	}

	/**
	 * On random matrices of several shapes and densities, each including an empty row and a
	 * fully dense row, the sparse product with a vector matches dense {@code matmul} of the
	 * materialised matrix and the host reference, and has shape {@code (rows)}; the product with
	 * a three-column matrix matches likewise with shape {@code (rows, 3)}; and the materialised
	 * matrix itself matches the host CSR structure. The largest shape takes the dense
	 * large-output path; the smallest is a single empty element.
	 */
	@Test(timeout = 240000)
	public void parityWithDenseMatmul() {
		Random random = new Random(42);
		int[][] shapes = { { 1, 1 }, { 2, 3 }, { 7, 5 }, { 64, 48 }, { 300, 200 }, { 1500, 40 } };
		double[] densities = { 1.0, 0.5, 0.3, 0.1, 0.02, 0.2 };
		int width = 3;

		for (int s = 0; s < shapes.length; s++) {
			int rows = shapes[s][0];
			int cols = shapes[s][1];
			int[][] structure = randomStructure(random, rows, cols, densities[s]);
			int nnz = structure[1].length;

			try (SparseMatrix a = SparseMatrix.of(rows, cols, structure[0], structure[1],
						rand(shape(Math.max(nnz, 1))).subtract(0.5).evaluate());
				 PackedCollection vector = rand(shape(cols)).subtract(0.5).evaluate();
				 PackedCollection matrix = rand(shape(cols, width)).subtract(0.5).evaluate()) {
				double[] values = a.getValues().toArray();
				double[] x = vector.toArray();
				double[] m = matrix.toArray();
				log("shape=(" + rows + ", " + cols + ") nnz=" + nnz);

				PackedCollection dense = a.toDense().evaluate();
				double[] identity = new double[cols * cols];
				for (int j = 0; j < cols; j++) identity[j * cols + j] = 1.0;
				assertMatches(product(structure[0], structure[1], values, identity, cols, false),
						dense, 1e-6);

				PackedCollection sparseResult = a.multiply(cp(vector)).evaluate();
				Assert.assertEquals(1, sparseResult.getShape().getDimensions());
				Assert.assertEquals(rows, sparseResult.getShape().length(0));
				assertMatches(matmul(cp(dense), cp(vector)).evaluate().toArray(), sparseResult, TOLERANCE);
				assertMatches(product(structure[0], structure[1], values, x, 1, false),
						sparseResult, TOLERANCE);

				PackedCollection sparseMatrixResult = a.multiply(cp(matrix)).evaluate();
				Assert.assertEquals(rows, sparseMatrixResult.getShape().length(0));
				Assert.assertEquals(width, sparseMatrixResult.getShape().length(1));
				assertMatches(matmul(cp(dense), cp(matrix)).evaluate().toArray(),
						sparseMatrixResult, TOLERANCE);
				assertMatches(product(structure[0], structure[1], values, m, width, false),
						sparseMatrixResult, TOLERANCE);
			}
		}
	}

	/**
	 * Empty rows produce {@code 0}, a matrix with no entries at all produces the zero vector, and
	 * a matrix whose only non-empty row is dense computes the dot product of that row with the
	 * vector.
	 */
	@Test(timeout = 60000)
	public void degenerateRows() {
		try (SparseMatrix empty = SparseMatrix.of(3, 4, new int[4], new int[0], new PackedCollection(1));
			 PackedCollection vector = PackedCollection.of(1.5, -2.0, 3.25, 0.5)) {
			assertMatches(new double[3], empty.multiply(cp(vector)).evaluate(), 0.0);
		}

		try (SparseMatrix single = SparseMatrix.of(3, 4, new int[] { 0, 0, 4, 4 },
				new int[] { 0, 1, 2, 3 }, PackedCollection.of(2.0, 1.0, -1.0, 4.0));
			 PackedCollection vector = PackedCollection.of(1.5, -2.0, 3.25, 0.5)) {
			double dot = 2.0 * 1.5 + 1.0 * -2.0 + -1.0 * 3.25 + 4.0 * 0.5;
			assertMatches(new double[] { 0.0, dot, 0.0 }, single.multiply(cp(vector)).evaluate(), 1e-6);
		}
	}

	/**
	 * The product composes with other computations on both sides: its vector may be a
	 * computation, its result may feed another computation, and entries may repeat a column.
	 *
	 * <p>The matrix is {@code [[1, 0, 2], [0, 0, 0], [0, 3, 0], [4, 0, 4]]}, with column 0 of
	 * row 3 stored as two entries of {@code 2}. The vector is {@code 2 * [1, 2, 3]}, so
	 * {@code A·x = [14, 0, 12, 32]}, and the result adds {@code 1}.</p>
	 */
	@Test(timeout = 60000)
	public void composesWithComputations() {
		try (SparseMatrix a = SparseMatrix.of(4, 3,
				new int[] { 0, 2, 2, 3, 6 },
				new int[] { 0, 2, 1, 0, 2, 0 },
				PackedCollection.of(1.0, 2.0, 3.0, 2.0, 4.0, 2.0));
			 PackedCollection vector = PackedCollection.of(1.0, 2.0, 3.0)) {
			PackedCollection result = a.multiply(cp(vector).multiply(2.0)).add(1.0).evaluate();
			assertMatches(new double[] { 15.0, 1.0, 13.0, 33.0 }, result, 1e-6);
		}
	}

	/**
	 * Row offsets above {@code 2^24} are exact. Every row holds {@code 4097} entries, so the
	 * offsets of the last rows exceed {@code 2^24}, and half of those are odd, which an FP32
	 * collection would round to a neighbouring even value, shifting the row's range by one
	 * entry. Columns are {@code k mod 7} and the vector is {@code x[c] = c + 1}, so adjacent
	 * entries gather different values; with unit values every sum is an integer below
	 * {@code 2^24}, so the products are exact and a shifted range is observable as a different
	 * sum. The host reference confirms that rounded offsets would change the result.
	 */
	@Test(timeout = 600000)
	public void offsetsAboveSinglePrecision() {
		int rowLength = 4097;
		int rows = SINGLE_PRECISION_LIMIT / (rowLength - 1) + 4;
		int nnz = rows * rowLength;
		int cols = 7;

		int[] offsets = new int[rows + 1];
		for (int i = 0; i <= rows; i++) offsets[i] = i * rowLength;

		int[] columns = new int[nnz];
		for (int k = 0; k < nnz; k++) columns[k] = k % cols;

		double[] values = new double[nnz];
		Arrays.fill(values, 1.0);
		double[] x = new double[cols];
		for (int c = 0; c < cols; c++) x[c] = c + 1;

		double[] expected = product(offsets, columns, values, x, 1, false);
		Assert.assertFalse("FP32-rounded offsets must change the result",
				Arrays.equals(expected, product(offsets, columns, values, x, 1, true)));

		try (SparseMatrix a = SparseMatrix.of(rows, cols, offsets, columns, new PackedCollection(nnz).fill(1.0));
			 PackedCollection vector = integers(1, cols + 1).evaluate()) {
			assertMatches(x, vector, 0.0);
			assertMatches(expected, a.multiply(cp(vector)).evaluate(), 0.0);
		}
	}

	/**
	 * Invalid CSR structures and mismatched operands are rejected rather than producing a kernel
	 * that reads out of range: too few offsets, decreasing offsets, a column out of range, too few
	 * values, offsets that are not split lanes, and an operand with the wrong number of rows.
	 */
	@Test(timeout = 30000)
	public void rejectsInvalidInput() {
		assertRejected(() -> SparseMatrix.of(2, 2, new int[] { 0, 1 }, new int[] { 0 }, PackedCollection.of(1.0)));
		assertRejected(() -> SparseMatrix.of(2, 2, new int[] { 0, 2, 1 }, new int[] { 0 }, PackedCollection.of(1.0)));
		assertRejected(() -> SparseMatrix.of(1, 2, new int[] { 0, 1 }, new int[] { 2 }, PackedCollection.of(1.0)));
		assertRejected(() -> SparseMatrix.of(1, 2, new int[] { 0, 2 }, new int[] { 0, 1 }, PackedCollection.of(1.0)));
		assertRejected(() -> new SparseMatrix(1, 2, new PackedCollection(2),
				PackedCollection.ofSplitIndices(0), PackedCollection.of(1.0)));

		try (SparseMatrix a = SparseMatrix.of(1, 2, new int[] { 0, 1 }, new int[] { 1 }, PackedCollection.of(1.0));
			 PackedCollection vector = PackedCollection.of(1.0, 2.0, 3.0)) {
			assertRejected(() -> a.multiply(cp(vector)));
		}
	}

	/**
	 * Asserts that an action is rejected with an {@link IllegalArgumentException}.
	 *
	 * @param action the action
	 */
	private void assertRejected(Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			return;
		}

		Assert.fail("Expected IllegalArgumentException");
	}

	/**
	 * A connectome-scale product: about 166k rows and twenty million entries with in-degrees
	 * spread from zero to several hundred. Records the device footprint of the matrix and the time
	 * per product, and checks the result against the host reference.
	 */
	@Test(timeout = 1800000)
	public void connectomeScale() {
		if (skipLongTests) return;

		int rows = 166_000;
		int cols = 166_000;
		Random random = new Random(7);

		int[] offsets = new int[rows + 1];
		for (int i = 0; i < rows; i++) {
			offsets[i + 1] = offsets[i] + random.nextInt(241);
		}

		int nnz = offsets[rows];
		int[] columns = new int[nnz];
		for (int k = 0; k < nnz; k++) columns[k] = random.nextInt(cols);

		try (SparseMatrix a = SparseMatrix.of(rows, cols, offsets, columns,
					rand(shape(nnz)).subtract(0.5).evaluate());
			 PackedCollection vector = rand(shape(cols)).subtract(0.5).evaluate()) {
			long footprint = (long) (a.getRowOffsets().getMemLength() + a.getColumns().getMemLength()
					+ a.getValues().getMemLength()) * Hardware.getLocalHardware().getPrecision().bytes();
			log("rows=" + rows + " nnz=" + nnz + " footprintBytes=" + footprint);

			Evaluable<PackedCollection> product = a.multiply(cp(vector)).get();
			double[] expected = product(offsets, columns, a.getValues().toArray(), vector.toArray(), 1, false);
			assertMatches(expected, product.evaluate(), TOLERANCE);

			int runs = 5;
			long start = System.nanoTime();
			for (int r = 0; r < runs; r++) {
				product.evaluate().destroy();
			}

			long perProduct = (System.nanoTime() - start) / runs;
			log("nanosPerProduct=" + perProduct);
		}
	}
}
