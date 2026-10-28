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
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Tests for {@link CompressedRowTraversalOrdering}: the logical-to-physical search on the host
 * and in kernels, a collection of entry values read through the ordering as a dense matrix,
 * {@code matmul} of that collection, validation, and the
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
	 * {@code matmul} of the ordered collection with {@code x = [1, 2, 3]} gives
	 * {@code [7, 0, 6]}.
	 */
	@Test(timeout = 60000)
	public void matmulOfOrderedCollection() {
		try (PackedCollection values = PackedCollection.of(1.0, 2.0, 3.0);
			 PackedCollection x = PackedCollection.of(1.0, 2.0, 3.0)) {
			PackedCollection a = matrix(CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS), values);
			assertMatches(new double[] { 7.0, 0.0, 6.0 },
					matmul(cp(a), cp(x)).evaluate().reshape(shape(3)));
		}
	}

	/**
	 * Index structures that are not CSR (including a row that stores a column twice, which
	 * would make reading the element and enumerating the entries disagree), and index collections that are not in the split
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
				() -> CompressedRowTraversalOrdering.of(2, new int[] { 0, 2 }, new int[] { 1, 1 }),
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
	 * A collection whose shape carries the ordering, built as
	 * {@code new PackedCollection(new TraversalPolicy(order, 3, 3))}, allocates memory for its
	 * three entries only, is filled entry by entry, and reads as the dense matrix both on the
	 * host and in kernels.
	 */
	@Test(timeout = 60000)
	public void orderedShapeStoresOnlyEntries() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);

		try (PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, 3, 3))) {
			Assert.assertEquals(3, matrix.getDelegatedLength());
			Assert.assertEquals(9, matrix.getMemLength());

			matrix.fill(1.0, 2.0, 3.0);
			assertMatches(DENSE, matrix);

			double[] doubled = new double[DENSE.length];
			for (int i = 0; i < DENSE.length; i++) doubled[i] = 2 * DENSE[i];
			assertMatches(doubled, cp(matrix).multiply(2.0).evaluate());
		}
	}

	/**
	 * A 70,000 × 70,000 matrix, whose 4.9 billion logical elements exceed the range of an
	 * {@code int}, stores only its two entries per row, and its product with a vector is exact:
	 * the product enumerates the entries of each row and never forms a logical index.
	 */
	@Test(timeout = 120000)
	public void productBeyondIntLogicalSize() {
		int n = 70_000;
		int[] offsets = new int[n + 1];
		int[] columns = new int[2 * n];
		for (int i = 0; i < n; i++) {
			offsets[i + 1] = 2 * (i + 1);
			columns[2 * i] = i;
			columns[2 * i + 1] = (int) ((i * 7L + 3) % n);
		}

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(n, offsets, columns);
		TraversalPolicy shape = new TraversalPolicy(n, n);
		Assert.assertTrue(shape.getTotalSizeLong() > Integer.MAX_VALUE);

		try (PackedCollection values = integers(0, 2 * n).mod(8).add(1.0).evaluate();
			 PackedCollection x = integers(0, n).mod(16).evaluate()) {
			PackedCollection matrix = new PackedCollection(shape, 0, values, 0, order);
			PackedCollection y = matmul(cp(matrix), cp(x)).evaluate();
			Assert.assertEquals(n, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int i = 0; i < n; i++) {
				double expected = (2 * i % 8 + 1) * (columns[2 * i] % 16)
						+ ((2 * i + 1) % 8 + 1) * (columns[2 * i + 1] % 16);
				Assert.assertEquals("row " + i, expected, result[i], 0.0);
			}
		}
	}
}
