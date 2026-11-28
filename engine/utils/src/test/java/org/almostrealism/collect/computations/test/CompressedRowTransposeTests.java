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

import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.kernel.KernelIndex;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.SplitIndexTraversalOrdering;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Tests the transpose of a sparse matrix through {@link CollectionProducer#transpose()}: the
 * transpose of a collection read through a {@link CompressedRowTraversalOrdering} must read the
 * same memory through {@link CompressedRowTraversalOrdering#transpose() the ordering of the
 * transpose}, moving no entry, so that its product is the segmented reduction over each column
 * of the original matrix; and every computation of it must equal the same computation of the
 * dense transpose of the matrix, on every available backend.
 *
 * <p>The fixed matrix is the {@code (4, 6)} matrix</p>
 * <pre>
 * [[-1,  0, 0,  2,  0, 0],
 *  [ 0,  0, 0,  0,  0, 0],
 *  [ 3, -4, 5,  6, -7, 0],
 *  [ 0,  0, 8,  0,  0, 0]]
 * </pre>
 * <p>stored as eight entries, with an empty row, an empty last column, a full row and a row whose
 * entries are stored out of column order, so that the columns are not contiguous in memory and a
 * column without entries must produce a zero row of the transpose.</p>
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
	 * Returns the fixed matrix as a collection of its entries read through its ordering.
	 *
	 * @return the sparse matrix
	 */
	private PackedCollection sparse() {
		return new PackedCollection(shape(4, 6), 0, values(), 0,
				CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS));
	}

	/**
	 * Returns the dense transpose of a matrix, enumerating its columns so that every logical
	 * element is read through its ordering and stored in ordinary memory, as the reference a
	 * computation of the sparse transpose is compared with.
	 *
	 * @param matrix the matrix
	 * @param backend the backend to evaluate on
	 * @return the dense {@code (cols, rows)} transpose
	 */
	private PackedCollection denseTranspose(PackedCollection matrix, ComputeRequirement backend) {
		CollectionProducer columns = cp(matrix).enumerate(1, 1);
		PackedCollection dense = evaluate(columns.reshape(shape(columns).trim()), backend);
		assertTrue("the reference should be dense", dense.getShape().getOrder() == null);
		return dense;
	}

	/**
	 * Asserts, on every available backend, that the given computation of the transpose of a
	 * sparse matrix equals the same computation of its dense transpose, in shape and in every
	 * element.
	 *
	 * @param sparse    the sparse matrix
	 * @param operation builds the computation from a producer of the transposed matrix
	 */
	private void assertMatchesDenseTranspose(PackedCollection sparse,
											 Function<CollectionProducer, CollectionProducer> operation) {
		for (ComputeRequirement backend : backends()) {
			PackedCollection expected = evaluate(operation.apply(cp(denseTranspose(sparse, backend))), backend);
			PackedCollection actual = evaluate(operation.apply(cp(sparse).transpose()), backend);
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
	 * Asserts that the given computation of the transpose of the fixed matrix equals the same
	 * computation of its dense transpose.
	 *
	 * @param operation builds the computation from a producer of the transposed matrix
	 */
	private void assertFixed(Function<CollectionProducer, CollectionProducer> operation) {
		assertMatchesDenseTranspose(sparse(), operation);
	}

	/**
	 * The transpose of the producer of a sparse matrix is sparse: its shape is {@code (6, 4)} and
	 * carries the ordering of the transpose, whose rows are the columns of the original matrix and
	 * which addresses the same eight entries.
	 */
	@Test(timeout = 60000)
	public void transposeIsSparse() {
		PackedCollection matrix = sparse();
		CompressedRowTraversalOrdering order = (CompressedRowTraversalOrdering) matrix.getShape().getOrder();
		TraversalPolicy shape = shape(cp(matrix).transpose());
		assertTrue(Arrays.equals(new long[] { 6, 4 }, shape.extentLong()));

		TraversalOrdering result = shape.getOrder();
		assertTrue("the transpose should carry the ordering of the transpose", result == order.transpose());
		assertEquals(6, order.transpose().getRows());
		assertEquals(4, order.transpose().getCols());
		assertEquals(8, order.transpose().getLength().getAsInt());
		assertEquals(2, order.transpose().getMaxRowLength());
	}

	/**
	 * Transposing moves no entry: the evaluated transpose is a view of the memory holding the
	 * entries of the original matrix.
	 */
	@Test(timeout = 60000)
	public void transposeMovesNoEntries() {
		PackedCollection values = values();
		PackedCollection matrix = new PackedCollection(shape(4, 6), 0, values, 0,
				CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS));
		PackedCollection transposed = cp(matrix).transpose().evaluate();
		assertTrue("the transpose should read the entries in place",
				transposed.getRootDelegate().getMem() == values.getRootDelegate().getMem());
	}

	/**
	 * The transpose reads the memory of the matrix in an order the matrix's own elements cannot
	 * express, so it is not traversable and refuses to supply an expression for an element.
	 */
	@Test(timeout = 60000)
	public void transposeIsNotTraversable() {
		TraversableExpression<?> view = (TraversableExpression<?>) cp(sparse()).transpose();
		assertFalse("the transpose should not be traversable", view.isTraversable());
		assertTrue("the transpose should report no unique offset",
				view.uniqueNonZeroOffset(new KernelIndex(), new KernelIndex(), e(0)) == null);

		try {
			view.getValueAt(e(0));
			Assert.fail("the transpose should not supply an expression for an element");
		} catch (UnsupportedOperationException expected) {
			// the element must be read from memory through the ordering of the transpose
		}
	}

	/**
	 * A computation of a sparse matrix produces dense output, so its shape carries no ordering
	 * and its transpose is the ordinary traversable transpose rather than a view of entries.
	 */
	@Test(timeout = 60000)
	public void transposeOfComputedMatrixIsDense() {
		CollectionProducer computed = cp(sparse()).multiply(c(2.0));
		assertTrue("the computed matrix should be dense", shape(computed).getOrder() == null);

		CollectionProducer transposed = computed.transpose();
		assertTrue("the transpose of the computed matrix should be dense", shape(transposed).getOrder() == null);
		assertTrue(Arrays.equals(new long[] { 6, 4 }, shape(transposed).extentLong()));
		assertTrue("the transpose of the computed matrix should be traversable",
				((TraversableExpression<?>) transposed).isTraversable());
	}

	/** Read element by element, the transpose equals the dense transpose, including a zero row. */
	@Test(timeout = 60000)
	public void transposeReadsDense() { assertFixed(m -> m.multiply(c(2.0))); }

	/** The evaluated transpose, read on the host, equals the dense transpose. */
	@Test(timeout = 60000)
	public void transposeEvaluated() { assertFixed(m -> m); }

	/**
	 * The transpose of a computation of a sparse matrix, rather than of the matrix itself, equals
	 * the same computation of the dense transpose, read element by element and through a product.
	 */
	@Test(timeout = 60000)
	public void transposeOfComputedMatrix() {
		PackedCollection matrix = sparse();
		CollectionProducer x = cp(pack(1.0, 2.0, 3.0, 4.0));
		for (ComputeRequirement backend : backends()) {
			PackedCollection dense = denseTranspose(matrix, backend);
			double[] expected = evaluate(cp(dense).multiply(c(2.0)).add(c(1.0)), backend).toArray(0, 24);
			double[] actual = evaluate(cp(matrix).multiply(c(2.0)).transpose().add(c(1.0)), backend).toArray(0, 24);
			for (int i = 0; i < expected.length; i++) {
				assertEquals("element " + i + " on " + backend, expected[i], actual[i]);
			}

			double[] expectedProduct = evaluate(matmul(cp(dense).multiply(c(2.0)), x), backend).toArray(0, 6);
			double[] actualProduct = evaluate(matmul(cp(matrix).multiply(c(2.0)).transpose(), x), backend).toArray(0, 6);
			for (int i = 0; i < expectedProduct.length; i++) {
				assertEquals("product element " + i + " on " + backend, expectedProduct[i], actualProduct[i]);
			}
		}
	}

	/** The transpose of the transpose is the original matrix, read through its own ordering. */
	@Test(timeout = 60000)
	public void transposeTwice() {
		for (ComputeRequirement backend : backends()) {
			double[] expected = evaluate(cp(sparse()).multiply(c(3.0)), backend).toArray(0, 24);
			double[] actual = evaluate(cp(sparse()).transpose().transpose().multiply(c(3.0)), backend).toArray(0, 24);
			for (int i = 0; i < expected.length; i++) {
				assertEquals("element " + i + " on " + backend, expected[i], actual[i]);
			}
		}
	}

	/** The product of the transpose is the segmented reduction, not a dense product. */
	@Test(timeout = 60000)
	public void matmulTransposeEnumeratesColumns() {
		assertTrue(matmul(cp(sparse()).transpose(), cp(pack(1.0, 2.0, 3.0, 4.0)))
				instanceof SegmentedWeightedSumComputation);
	}

	/**
	 * The product of the transpose of the transpose is again the segmented reduction, and equals
	 * the product of the original matrix.
	 */
	@Test(timeout = 60000)
	public void matmulTransposeTwice() {
		CollectionProducer x = cp(pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0));
		assertTrue(matmul(cp(sparse()).transpose().transpose(), x) instanceof SegmentedWeightedSumComputation);

		for (ComputeRequirement backend : backends()) {
			double[] expected = evaluate(matmul(cp(sparse()), x), backend).toArray(0, 4);
			double[] actual = evaluate(matmul(cp(sparse()).transpose().transpose(), x), backend).toArray(0, 4);
			for (int i = 0; i < expected.length; i++) {
				assertEquals("element " + i + " on " + backend, expected[i], actual[i]);
			}
		}
	}

	/** The product of the transpose with a vector, checked against explicitly computed values. */
	@Test(timeout = 60000)
	public void matmulTransposeVectorExplicitValues() {
		double[] expected = { 8.0, -12.0, 47.0, 20.0, -21.0, 0.0 };
		for (ComputeRequirement backend : backends()) {
			double[] result = evaluate(matmul(cp(sparse()).transpose(), cp(pack(1.0, 2.0, 3.0, 4.0))), backend)
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
	 * The transpose of a sparse matrix that is a view starting part way into a larger collection
	 * reads the entries from the offset of the view.
	 */
	@Test(timeout = 60000)
	public void matmulTransposeOfOffsetView() {
		PackedCollection buffer = pack(9.0, 9.0, 9.0, 2.0, -1.0, 3.0, -4.0, 5.0, 6.0, -7.0, 8.0, 9.0);
		PackedCollection view = new PackedCollection(shape(8), 0, buffer, 3);
		PackedCollection matrix = new PackedCollection(shape(4, 6), 0, view, 0,
				CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS));
		assertMatchesDenseTranspose(matrix, m -> matmul(m, cp(pack(1.0, 2.0, 3.0, 4.0))));
	}

	/**
	 * The transpose of a sparse matrix whose entries are themselves read through an ordering (here
	 * stored in reverse and read back through a permutation) addresses the entries as the matrix
	 * does, through that ordering, so its product equals the product of the dense transpose.
	 */
	@Test(timeout = 60000)
	public void matmulTransposeOfNestedOrderedView() {
		PackedCollection reversed = pack(8.0, -7.0, 6.0, 5.0, -4.0, 3.0, -1.0, 2.0);
		PackedCollection entries = new PackedCollection(shape(8), 0, reversed, 0,
				SplitIndexTraversalOrdering.of(7, 6, 5, 4, 3, 2, 1, 0));
		PackedCollection matrix = new PackedCollection(shape(4, 6), 0, entries, 0,
				CompressedRowTraversalOrdering.of(6, OFFSETS, COLUMNS));
		assertMatchesDenseTranspose(matrix, m -> matmul(m, cp(pack(1.0, 2.0, 3.0, 4.0))));
		assertMatchesDenseTranspose(matrix, m -> m.multiply(c(2.0)));
	}

	/**
	 * Preparing the transpose binds the ordering of the transpose, so the transpose reports the
	 * arguments that ordering reads, and nothing for the producer of the matrix, which is a
	 * position of the process tree in its own right.
	 */
	@Test(timeout = 60000)
	public void transposeReportsTheArgumentsOfItsOrdering() {
		PackedCollection matrix = sparse();
		CompressedRowTraversalOrdering order = (CompressedRowTraversalOrdering) matrix.getShape().getOrder();
		ScopeLifecycle view = (ScopeLifecycle) cp(matrix).transpose();
		assertEquals(order.transpose().getArgumentCount(), view.getScopeArgumentCount(Integer.MAX_VALUE));
		assertTrue(order.transpose().getArgumentCount() > 0);
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

			PackedCollection matrix = new PackedCollection(shape(rows, cols), 0, values, 0,
					CompressedRowTraversalOrdering.of(cols, offsets, columns));
			assertMatchesDenseTranspose(matrix, m -> matmul(m, cp(g)));
			assertMatchesDenseTranspose(matrix, m -> matmul(m, cp(w)));
		}
	}

	/** A matrix without entries transposes to a matrix whose product is zero. */
	@Test(timeout = 60000)
	public void matmulTransposeEmpty() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(2, new int[] { 0, 0, 0, 0 }, new int[0]);
		PackedCollection matrix = new PackedCollection(shape(3, 2), 0, pack(0.0), 0, order);
		assertMatchesDenseTranspose(matrix, m -> matmul(m, cp(pack(1.0, 2.0, 3.0))));
	}

	/**
	 * The transposed product of a {@code 60000 × 60000} sparse matrix, one entry per row, whose
	 * logical size exceeds the range of an {@code int}, when the matrix is a view of a separate
	 * collection of entries.
	 */
	@Test(timeout = 120000)
	public void matmulTransposeOfViewBeyondIntRange() {
		int n = 60000;
		int[] offsets = IntStream.rangeClosed(0, n).toArray();
		int[] columns = IntStream.range(0, n).map(i -> (int) ((7L * i) % n)).toArray();
		int[] rowOfColumn = new int[n];
		for (int i = 0; i < n; i++) rowOfColumn[columns[i]] = i;

		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(n, offsets, columns);
		try (PackedCollection values = integers(0, n).mod(3).add(c(1.0)).evaluate();
			 PackedCollection g = integers(0, n).mod(11).evaluate()) {
			PackedCollection matrix = new PackedCollection(shape(n, n), 0, values, 0, order);
			PackedCollection y = matmul(cp(matrix).transpose(), cp(g)).evaluate();
			assertEquals(n, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int j = 0; j < n; j += 997) {
				int i = rowOfColumn[j];
				assertEquals("element " + j, (i % 3 + 1) * (i % 11), result[j]);
			}
		}
	}

	/**
	 * The transposed product of a {@code 60000 × 60000} sparse matrix that owns its entries,
	 * built as {@code new PackedCollection(new TraversalPolicy(order, n, n))} and filled with the
	 * cycle {@code 1, 2, 3} so that entry {@code i} is {@code i % 3 + 1}: the transpose reads the
	 * memory of the matrix itself, whose logical length exceeds the range of an {@code int}.
	 */
	@Test(timeout = 120000)
	public void matmulTransposeOfOwnedMatrixBeyondIntRange() {
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

			PackedCollection y = matmul(cp(matrix).transpose(), cp(g)).evaluate();
			assertEquals(n, y.getShape().getTotalSize());

			double[] result = y.toArray();
			for (int j = 0; j < n; j += 997) {
				int i = rowOfColumn[j];
				assertEquals("element " + j, (i % 3 + 1) * (i % 11), result[j]);
			}
		}
	}

	/**
	 * The transposed product of a {@code (2^24 + 4096) × 4096} matrix, one entry per row, is exact:
	 * the positions of the members of the transpose and its columns both exceed {@code 2^24},
	 * where a single-precision index would be rounded to a neighbouring entry, and the logical size
	 * of the matrix exceeds the range of an {@code int}.
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
			PackedCollection matrix = new PackedCollection(shape(rows, cols), 0, values, 0, order);
			PackedCollection y = matmul(cp(matrix).transpose(), cp(g)).evaluate();
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
