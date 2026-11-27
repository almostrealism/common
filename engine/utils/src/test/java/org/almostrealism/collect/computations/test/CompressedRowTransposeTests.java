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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Tests the structural transpose of a sparse matrix,
 * {@link CompressedRowTraversalOrdering#transpose(PackedCollection)}: the transposed matrix must
 * carry a compressed-sparse-row ordering of the transpose, so that its product is the segmented
 * reduction over each column of the original matrix, and every product of it must equal the
 * product of the dense transpose of the original matrix, on every available backend.
 *
 * <p>The fixed matrix is the {@code (4, 6)} matrix</p>
 * <pre>
 * [[-1,  0, 0,  2,  0, 0],
 *  [ 0,  0, 0,  0,  0, 0],
 *  [ 3, -4, 5,  6, -7, 0],
 *  [ 0,  0, 8,  0,  0, 0]]
 * </pre>
 * <p>stored as eight entries, with an empty row, an empty last column, a full row and a row whose
 * entries are stored out of column order, so that the permutation of the entries is not the
 * identity and a column without entries must produce a zero row of the transpose.</p>
 */
public class CompressedRowTransposeTests extends TestSuiteBase implements MatrixFeatures, BackendTestFeatures {
	/** The row offsets of the fixed matrix. */
	private static final int[] OFFSETS = { 0, 2, 2, 7, 8 };

	/** The column of each entry of the fixed matrix. */
	private static final int[] COLUMNS = { 3, 0, 0, 1, 2, 3, 4, 2 };

	/**
	 * Returns the entries of the fixed matrix, in row-major physical order.
	 *
	 * @return the eight entries
	 */
	private PackedCollection values() {
		return pack(2.0, -1.0, 3.0, -4.0, 5.0, 6.0, -7.0, 8.0);
	}

	/**
	 * Asserts, on every available backend, that the given product of the structural transpose of
	 * a sparse matrix equals the same product of its dense transpose, in shape and in every
	 * element. The dense transpose is the logical transpose of the sparse matrix, which reads every
	 * element through the ordering, evaluated into ordinary memory.
	 *
	 * @param order     the ordering of the sparse matrix
	 * @param sparse    the sparse matrix, read through the ordering
	 * @param operation builds the product from a producer of the transposed matrix
	 */
	private void assertMatchesDenseTranspose(CompressedRowTraversalOrdering order, PackedCollection sparse,
											 Function<CollectionProducer, CollectionProducer> operation) {
		PackedCollection transposed = order.transpose(sparse);

		for (ComputeRequirement backend : backends()) {
			PackedCollection denseTranspose = evaluate(cp(sparse).transpose(), backend);
			assertTrue("the reference should be dense", denseTranspose.getShape().getOrder() == null);
			PackedCollection expected = evaluate(operation.apply(cp(denseTranspose)), backend);
			PackedCollection actual = evaluate(operation.apply(cp(transposed)), backend);
			assertTrue("shape on " + backend + ": expected " + expected.getShape()
							+ " but was " + actual.getShape(),
					Arrays.equals(expected.getShape().extentLong(), actual.getShape().extentLong()));

			double[] e = expected.toArray(0, expected.getShape().getTotalSize());
			double[] a = actual.toArray(0, actual.getShape().getTotalSize());
			for (int i = 0; i < e.length; i++) {
				assertEquals("element " + i + " on " + backend, e[i], a[i]);
			}
		}
	}

	/**
	 * Asserts that the given product of the structural transpose of the fixed matrix equals the
	 * product of its dense transpose.
	 *
	 * @param operation builds the product from a producer of the transposed matrix
	 */
	private void assertFixed(Function<CollectionProducer, CollectionProducer> operation) {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS);
		PackedCollection sparse = new PackedCollection(shape(4, 6), 0, values(), 0, order);
		assertMatchesDenseTranspose(order, sparse, operation);
	}

	/**
	 * The transpose carries a compressed-sparse-row ordering of the {@code (6, 4)} transpose whose
	 * row offsets are the column pointers of the original matrix, and its memory holds the entries
	 * in column-major order.
	 */
	@Test(timeout = 60000)
	public void transposeStructure() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS);
		PackedCollection transposed = order.transpose(values());
		assertTrue(Arrays.equals(new long[] { 6, 4 }, transposed.getShape().extentLong()));

		TraversalOrdering result = transposed.getShape().getOrder();
		assertTrue("the transpose should carry a CSR ordering", result instanceof CompressedRowTraversalOrdering);
		CompressedRowTraversalOrdering t = (CompressedRowTraversalOrdering) result;
		assertEquals(6, t.getRows());
		assertEquals(4, t.getCols());
		assertEquals(8, t.getLength().getAsInt());

		assertEquals(8, transposed.getDelegatedLength());
		double[] expected = { -1.0, 3.0, -4.0, 5.0, 8.0, 2.0, 6.0, -7.0 };
		double[] actual = transposed.getDelegate().toArray(0, 8);
		for (int i = 0; i < expected.length; i++) {
			assertEquals("entry " + i, expected[i], actual[i]);
		}
	}

	/**
	 * The entries may be a view that starts part way into a larger collection: the transpose
	 * reads them from the absolute offset of the view, not from the start of its backing memory.
	 */
	@Test(timeout = 60000)
	public void transposeOfOffsetView() {
		PackedCollection buffer = pack(9.0, 9.0, 9.0, 2.0, -1.0, 3.0, -4.0, 5.0, 6.0, -7.0, 8.0, 9.0);
		PackedCollection view = new PackedCollection(shape(8), 0, buffer, 3);
		PackedCollection transposed = CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS).transpose(view);

		double[] expected = { -1.0, 3.0, -4.0, 5.0, 8.0, 2.0, 6.0, -7.0 };
		double[] actual = transposed.getDelegate().toArray(0, 8);
		for (int i = 0; i < expected.length; i++) {
			assertEquals("entry " + i, expected[i], actual[i]);
		}
	}

	/** Entries that are fewer than the stored entries of the matrix are rejected. */
	@Test(timeout = 60000)
	public void transposeRejectsTooFewEntries() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS);
		try {
			order.transpose(pack(1.0, 2.0, 3.0));
			throw new AssertionError("three entries should be rejected for a matrix storing eight");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("8"));
		}
	}

	/**
	 * The sparse matrix itself, a {@code 60000 × 60000} matrix with one entry per row whose
	 * logical size exceeds the range of an {@code int}, may be transposed: the entries are read
	 * from its backing memory rather than through a view bounded by its logical length.
	 */
	@Test(timeout = 120000)
	public void transposeOfMatrixBeyondIntRange() {
		int n = 60000;
		int[] offsets = IntStream.rangeClosed(0, n).toArray();
		int[] columns = IntStream.range(0, n).map(i -> (int) ((7L * i) % n)).toArray();
		int[] rowOfColumn = new int[n];
		for (int i = 0; i < n; i++) rowOfColumn[columns[i]] = i;

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(n, offsets, columns);
		try (PackedCollection values = integers(0, n).mod(3).add(c(1.0)).evaluate();
			 PackedCollection g = integers(0, n).mod(11).evaluate()) {
			PackedCollection matrix = new PackedCollection(shape(n, n), 0, values, 0, order);
			PackedCollection transposed = order.transpose(matrix);
			PackedCollection y = matmul(cp(transposed), cp(g)).evaluate();
			assertEquals(n, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int j = 0; j < n; j += 997) {
				int i = rowOfColumn[j];
				assertEquals("element " + j, (i % 3 + 1) * (i % 11), result[j]);
			}
		}
	}

	/**
	 * A {@code 60000 × 60000} sparse matrix that owns its entries, built as
	 * {@code new PackedCollection(new TraversalPolicy(order, n, n))} and filled with the cycle
	 * {@code 1, 2, 3} so that entry {@code i} is {@code i % 3 + 1}, may be transposed: it is its
	 * own root, so the entries must be read through a view bounded by its physical length rather
	 * than by its logical length, which exceeds the range of an {@code int}.
	 */
	@Test(timeout = 120000)
	public void transposeOfOwnedMatrixBeyondIntRange() {
		int n = 60000;
		int[] offsets = IntStream.rangeClosed(0, n).toArray();
		int[] columns = IntStream.range(0, n).map(i -> (int) ((7L * i) % n)).toArray();
		int[] rowOfColumn = new int[n];
		for (int i = 0; i < n; i++) rowOfColumn[columns[i]] = i;

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(n, offsets, columns);
		try (PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, n, n));
			 PackedCollection g = integers(0, n).mod(11).evaluate()) {
			assertEquals(n, matrix.getDelegatedLength());
			matrix.fill(1.0, 2.0, 3.0);

			PackedCollection transposed = order.transpose(matrix);
			PackedCollection y = matmul(cp(transposed), cp(g)).evaluate();
			assertEquals(n, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int j = 0; j < n; j += 997) {
				int i = rowOfColumn[j];
				assertEquals("element " + j, (i % 3 + 1) * (i % 11), result[j]);
			}
		}
	}

	/** Read element by element, the transpose equals the dense transpose, including a zero row. */
	@Test(timeout = 60000)
	public void transposeReadsDense() { assertFixed(m -> m.multiply(1.0)); }

	/** The product of the transpose is the segmented reduction, not a dense product. */
	@Test(timeout = 60000)
	public void matmulTransposeEnumeratesColumns() {
		PackedCollection transposed = CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS).transpose(values());
		assertTrue(matmul(cp(transposed), cp(pack(1.0, 2.0, 3.0, 4.0)))
				instanceof SegmentedWeightedSumComputation);
	}

	/** The product of the transpose with a vector, checked against explicitly computed values. */
	@Test(timeout = 60000)
	public void matmulTransposeVectorExplicitValues() {
		double[] expected = { 8.0, -12.0, 47.0, 20.0, -21.0, 0.0 };
		PackedCollection transposed = CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS).transpose(values());
		for (ComputeRequirement backend : backends()) {
			double[] result = evaluate(matmul(cp(transposed), cp(pack(1.0, 2.0, 3.0, 4.0))), backend)
					.toArray(0, expected.length);
			for (int i = 0; i < expected.length; i++) {
				assertEquals("element " + i + " on " + backend, expected[i], result[i]);
			}
		}
	}

	/** The product of the transpose with a vector. */
	@Test(timeout = 60000)
	public void matmulTransposeVector() { assertFixed(m -> matmul(m, cp(pack(1.0, 2.0, 3.0, 4.0)))); }

	/** The product of the transpose with a column vector of shape {@code (4, 1)}. */
	@Test(timeout = 60000)
	public void matmulTransposeColumnVector() {
		assertFixed(m -> matmul(m, cp(pack(1.0, 2.0, 3.0, 4.0).reshape(shape(4, 1)))));
	}

	/** The product of the transpose with a {@code (4, 3)} matrix. */
	@Test(timeout = 60000)
	public void matmulTransposeMatrix() {
		assertFixed(m -> matmul(m, cp(pack(1.0, -1.0, 2.0,
				0.5, 3.0, -2.0,
				4.0, 1.0, 0.0,
				5.0, 2.0, -3.0).reshape(shape(4, 3)))));
	}

	/** The product of the transpose with a batch of three row vectors, traversed along its rows. */
	@Test(timeout = 60000)
	public void matmulTransposeRowVectorBatch() {
		assertFixed(m -> matmul(m, cp(pack(1.0, -1.0, 2.0, 0.5,
				3.0, -2.0, 4.0, 1.0,
				0.0, 5.0, 2.0, -3.0).reshape(shape(3, 4)).traverse(1))));
	}

	/**
	 * The transposed product of random {@code (60, 45)} matrices with empty rows, empty columns, a
	 * full row and a full column equals the product of the dense transpose, for a vector and a
	 * matrix operand.
	 */
	@Test(timeout = 120000)
	public void matmulTransposeRandom() {
		int rows = 60;
		int cols = 45;
		Random random = new Random(17);

		for (int trial = 0; trial < 3; trial++) {
			int fullRow = random.nextInt(rows);
			int fullColumn = random.nextInt(cols);
			int[] offsets = new int[rows + 1];
			List<Integer> columnList = new ArrayList<>();
			for (int i = 0; i < rows; i++) {
				offsets[i] = columnList.size();
				boolean emptyRow = i != fullRow && i % 5 == 1;
				for (int j = cols - 1; j >= 0; j--) {
					boolean stored = i == fullRow || (j == fullColumn) || random.nextInt(6) == 0;
					if (stored && !emptyRow && j % 9 != 4) columnList.add(j);
				}
			}

			offsets[rows] = columnList.size();
			int[] columns = columnList.stream().mapToInt(Integer::intValue).toArray();
			PackedCollection values = integers(0, columns.length).multiply(7.0 + trial).mod(11).subtract(c(5.0)).evaluate();
			PackedCollection g = integers(0, rows).mod(7).subtract(c(3.0)).evaluate();
			PackedCollection w = integers(0, rows * 2).mod(5).subtract(c(2.0)).evaluate().reshape(shape(rows, 2));

			CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(cols, offsets, columns);
			PackedCollection matrix = new PackedCollection(shape(rows, cols), 0, values, 0, order);
			assertMatchesDenseTranspose(order, matrix, m -> matmul(m, cp(g)));
			assertMatchesDenseTranspose(order, matrix, m -> matmul(m, cp(w)));
		}
	}

	/** A matrix without entries transposes to a matrix whose product is zero. */
	@Test(timeout = 60000)
	public void matmulTransposeEmpty() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(2, new int[] { 0, 0, 0, 0 }, new int[0]);
		PackedCollection matrix = new PackedCollection(shape(3, 2), 0, pack(0.0), 0, order);
		assertMatchesDenseTranspose(order, matrix, m -> matmul(m, cp(pack(1.0, 2.0, 3.0))));
	}

	/**
	 * The transposed product of a {@code (2^24 + 4096) × 4096} matrix, one entry per row, is exact:
	 * the permutation that reorders the entries and the columns of the transpose both exceed
	 * {@code 2^24}, where a single-precision index would be rounded to a neighbouring entry, and
	 * the logical size of the matrix exceeds the range of an {@code int}.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void matmulTransposeBeyondFloatRange() {
		int cols = 4096;
		int rows = (1 << 24) + cols;
		int[] offsets = IntStream.rangeClosed(0, rows).toArray();
		int[] columns = IntStream.range(0, rows).map(i -> i % cols).toArray();

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(cols, offsets, columns);
		try (PackedCollection values = integers(0, rows).mod(3).add(c(1.0)).evaluate();
			 PackedCollection g = integers(0, rows).mod(7).evaluate()) {
			PackedCollection transposed = order.transpose(values);
			PackedCollection y = matmul(cp(transposed), cp(g)).evaluate();
			assertEquals(cols, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int j = 0; j < cols; j++) {
				long expected = 0;
				for (int i = j; i < rows; i += cols) expected += (long) (i % 3 + 1) * (i % 7);
				assertEquals("element " + j, (double) expected, result[j]);
			}
		}
	}
}
