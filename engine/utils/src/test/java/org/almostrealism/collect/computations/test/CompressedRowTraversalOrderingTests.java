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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.SparseMatrix;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Tests for {@link CompressedRowTraversalOrdering}: the logical-to-physical search on the host
 * and in kernels, a collection of entry values read through the ordering as a dense matrix,
 * dense {@code matmul} of that collection against the sparse product, validation, and the
 * logical-size limit that keeps a connectome-scale matrix from being an ordered collection.
 *
 * <p>Unless stated otherwise the matrix is
 * {@code [[1, 0, 2], [0, 0, 0], [0, 3, 0]]}, with row offsets {@code [0, 2, 2, 3]} and
 * columns {@code [0, 2, 1]}.</p>
 */
public class CompressedRowTraversalOrderingTests extends TestSuiteBase implements MatrixFeatures {
	/** The row offsets of the example matrix. */
	private static final int[] OFFSETS = { 0, 2, 2, 3 };

	/** The columns of the example matrix. */
	private static final int[] COLUMNS = { 0, 2, 1 };

	/** The example matrix, row-major. */
	private static final double[] DENSE = { 1.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 3.0, 0.0 };

	/**
	 * Returns the given values read through the given ordering as a {@code (rows, cols)}
	 * collection.
	 *
	 * @param order  the ordering
	 * @param values the entry values
	 * @return the ordered collection
	 */
	private PackedCollection matrix(CompressedRowTraversalOrdering order, PackedCollection values) {
		return new PackedCollection(shape(order.getRows(), order.getCols()), 0, values, 0, order);
	}

	/**
	 * Asserts that a result matches the expected values element by element.
	 *
	 * @param expected the expected values
	 * @param actual   the result
	 */
	private void assertMatches(double[] expected, PackedCollection actual) {
		Assert.assertEquals(expected.length, actual.getMemLength());
		double[] values = actual.toArray();
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("element " + i, expected[i], values[i], 1e-6);
		}
	}

	/**
	 * On the host, every logical index maps to the position of its entry, or to {@code -1}
	 * when its row holds no entry in that column, including every element of the empty row;
	 * the length is the entry count and the search is bounded by the longest row.
	 */
	@Test(timeout = 60000)
	public void hostIndexOfSearchesRow() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);
		Assert.assertEquals(3, order.getRows());
		Assert.assertEquals(3, order.getCols());
		Assert.assertEquals(3, order.getLength().getAsInt());
		Assert.assertEquals(2, order.getMaxRowLength());

		int[] expected = { 0, -1, 1, -1, -1, -1, -1, 2, -1 };
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("index " + i, expected[i], order.indexOf(i));
		}
	}

	/**
	 * A kernel reading the values through the ordering reads the dense matrix: stored entries
	 * at their logical positions, including an entry stored out of column order, and zero
	 * everywhere else, including a trailing empty row whose start is the entry count.
	 */
	@Test(timeout = 60000)
	public void orderedCollectionReadsAsDenseMatrix() {
		try (PackedCollection values = PackedCollection.of(1.0, 2.0, 3.0)) {
			PackedCollection matrix = matrix(CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS), values);
			double[] doubled = new double[DENSE.length];
			for (int i = 0; i < DENSE.length; i++) doubled[i] = 2 * DENSE[i];
			assertMatches(doubled, cp(matrix).multiply(2.0).evaluate());
		}

		try (PackedCollection values = PackedCollection.of(4.0, 5.0, 6.0)) {
			CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3,
					new int[] { 0, 2, 3, 3 }, new int[] { 2, 0, 1 });
			assertMatches(new double[] { 5.0, 0.0, 4.0, 0.0, 6.0, 0.0, 0.0, 0.0, 0.0 },
					cp(matrix(order, values)).multiply(1.0).evaluate());
		}
	}

	/**
	 * On a {@code 64 × 48} matrix whose rows hold every third, every column, no column, or a
	 * single column, a kernel reading the ordered collection reads every element correctly, so
	 * the search covers rows of every length up to the longest.
	 */
	@Test(timeout = 120000)
	public void orderedCollectionReadsLargerMatrix() {
		int rows = 64;
		int cols = 48;
		int[] offsets = new int[rows + 1];
		int[] columns = new int[rows * cols];
		double[] dense = new double[rows * cols];
		int k = 0;
		for (int i = 0; i < rows; i++) {
			offsets[i] = k;
			for (int j = 0; j < cols; j++) {
				boolean present = i % 4 == 0 ? j % 3 == 0 : i % 4 == 1 || (i % 4 == 3 && j == i % cols);
				if (present) {
					columns[k] = j;
					dense[i * cols + j] = k + 1;
					k++;
				}
			}
		}

		offsets[rows] = k;
		CompressedRowTraversalOrdering order =
				CompressedRowTraversalOrdering.of(cols, offsets, Arrays.copyOf(columns, k));
		Assert.assertEquals(cols, order.getMaxRowLength());
		Assert.assertEquals(k, order.getLength().getAsInt());

		try (PackedCollection values = integers(1, k + 1).evaluate()) {
			assertMatches(dense, cp(matrix(order, values)).multiply(1.0).evaluate());
		}
	}

	/**
	 * Dense {@code matmul} of the ordered collection is correct and agrees with the segmented
	 * sparse product of the same structure: with {@code x = [1, 2, 3]} both give
	 * {@code [7, 0, 6]}.
	 */
	@Test(timeout = 60000)
	public void denseMatmulOfOrderedCollectionMatchesSparseProduct() {
		try (SparseMatrix a = SparseMatrix.of(3, 3, OFFSETS, COLUMNS, PackedCollection.of(1.0, 2.0, 3.0));
			 PackedCollection x = PackedCollection.of(1.0, 2.0, 3.0)) {
			double[] expected = { 7.0, 0.0, 6.0 };
			assertMatches(expected, a.multiply(cp(x)).evaluate());
			assertMatches(expected, matmul(cp(matrix(a.getOrdering(), a.getValues())), cp(x))
					.evaluate().reshape(shape(3)));
			assertMatches(DENSE, a.toDense().evaluate());
		}
	}

	/**
	 * A logical element stored as two entries of the same row reads as the first of them in
	 * the ordered view, while the product sums both.
	 */
	@Test(timeout = 60000)
	public void repeatedColumnReadsFirstEntry() {
		try (SparseMatrix a = SparseMatrix.of(1, 2, new int[] { 0, 2 }, new int[] { 1, 1 },
					PackedCollection.of(2.0, 5.0));
			 PackedCollection x = PackedCollection.of(1.0, 3.0)) {
			Assert.assertEquals(0, a.getOrdering().indexOf(1));
			assertMatches(new double[] { 0.0, 2.0 },
					cp(matrix(a.getOrdering(), a.getValues())).multiply(1.0).evaluate());
			assertMatches(new double[] { 21.0 }, a.multiply(cp(x)).evaluate());
		}
	}

	/**
	 * Index structures that are not CSR, and index collections that are not in the split
	 * layout or disagree on the row count, are rejected.
	 */
	@Test(timeout = 30000)
	public void rejectsInvalidStructure() {
		Runnable[] invalid = {
				() -> CompressedRowTraversalOrdering.of(2, new int[] { 0 }, new int[0]),
				() -> CompressedRowTraversalOrdering.of(2, new int[] { 1, 1 }, new int[] { 0 }),
				() -> CompressedRowTraversalOrdering.of(2, new int[] { 0, 2, 1 }, new int[] { 0 }),
				() -> CompressedRowTraversalOrdering.of(2, new int[] { 0, 1 }, new int[] { 2 }),
				() -> CompressedRowTraversalOrdering.of(2, new int[] { 0, 1 }, new int[] { -1 }),
				() -> CompressedRowTraversalOrdering.of(0, new int[] { 0, 0 }, new int[0]),
				() -> new CompressedRowTraversalOrdering(2, 2,
						PackedCollection.ofSplitIndices(0, 1), PackedCollection.ofSplitIndices(0)),
				() -> new CompressedRowTraversalOrdering(1, 2,
						new PackedCollection(2), PackedCollection.ofSplitIndices(0)),
				() -> new CompressedRowTraversalOrdering(1, 2,
						PackedCollection.ofSplitIndices(0, 1), new PackedCollection(3))
		};

		for (int i = 0; i < invalid.length; i++) {
			boolean rejected = false;
			try {
				invalid[i].run();
			} catch (IllegalArgumentException expected) {
				rejected = true;
			}

			Assert.assertTrue("structure " + i + " should be rejected", rejected);
		}
	}

	/**
	 * A matrix without entries has length zero and a search bound of zero, so every element
	 * is absent.
	 */
	@Test(timeout = 30000)
	public void emptyStructureHasNoMembers() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(4, new int[3], new int[0]);
		Assert.assertEquals(0, order.getLength().getAsInt());
		Assert.assertEquals(0, order.getMaxRowLength());
		Assert.assertEquals(1, order.getColumns().getShape().length(0));
		for (int i = 0; i < 8; i++) {
			Assert.assertEquals(-1, order.indexOf(i));
		}
	}

	/**
	 * The ordering itself is defined at any scale, but a {@link PackedCollection} measures its
	 * logical shape as an {@code int} even when its ordering stores only a few entries, so a
	 * 166,000 × 166,000 matrix cannot be an ordered collection: sizing it overflows.
	 */
	@Test(timeout = 30000)
	public void orderedCollectionLimitedToIntLogicalSize() {
		int n = 166_000;
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(n, new int[n + 1], new int[0]);
		Assert.assertEquals(0, order.getLength().getAsInt());

		try (PackedCollection values = new PackedCollection(1)) {
			new PackedCollection(new TraversalPolicy(n, n), 0, values, 0, order);
			Assert.fail("Expected a logical size of " + ((long) n * n) + " elements to be rejected");
		} catch (ArithmeticException expected) {
			Assert.assertEquals("integer overflow", expected.getMessage());
		}
	}
}
