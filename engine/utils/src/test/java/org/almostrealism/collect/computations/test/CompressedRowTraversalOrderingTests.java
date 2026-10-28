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

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.SplitIndexTraversalOrdering;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

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
	 * The ordering enumerates segments only of its own row length: asking for segments of any
	 * other length is empty, so a computation falls back to reading through
	 * {@link CompressedRowTraversalOrdering#indexOf(io.almostrealism.expression.Expression)}.
	 * The enumeration gives the start and length of each row, including the empty one, and the
	 * column of each entry.
	 */
	@Test(timeout = 30000)
	public void segmentsOnlyOfRowLength() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);
		Assert.assertTrue(order.segments(3).isPresent());
		Assert.assertSame(order, order.segments(3).get());
		Assert.assertTrue(order.segments(1).isEmpty());
		Assert.assertTrue(order.segments(9).isEmpty());

		int[] starts = { 0, 2, 2 };
		int[] lengths = { 2, 0, 1 };
		for (int i = 0; i < starts.length; i++) {
			Assert.assertEquals("start " + i, starts[i], order.start(e(i)).intValue().getAsInt());
			Assert.assertEquals("length " + i, lengths[i], order.length(e(i)).intValue().getAsInt());
		}

		for (int p = 0; p < COLUMNS.length; p++) {
			Assert.assertEquals("column " + p, COLUMNS[p], order.offset(e(p)).intValue().getAsInt());
		}
	}

	/**
	 * The segmented product rejects a non-positive width, a matrix that is not two-dimensional,
	 * and a matrix whose ordering cannot enumerate its rows; {@code matmul} of a matrix of the
	 * latter kind therefore takes the dense path and still gives the dense product.
	 */
	@Test(timeout = 60000)
	public void segmentedProductRejectsInvalidOperands() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);

		try (PackedCollection values = PackedCollection.of(1.0, 2.0, 3.0);
			 PackedCollection x = PackedCollection.of(1.0, 2.0, 3.0);
			 PackedCollection repeated = PackedCollection.of(1.0, 2.0)) {
			PackedCollection sparse = matrix(order, values);
			PackedCollection flat = new PackedCollection(shape(9), 0, values, 0, order);
			PackedCollection unenumerable = new PackedCollection(shape(2, 2), 0, repeated, 0,
					new RepeatTraversalOrdering(2));

			Runnable[] invalid = {
					() -> new SegmentedWeightedSumComputation("matmul", 0, cp(sparse), cp(x)),
					() -> new SegmentedWeightedSumComputation("matmul", -1, cp(sparse), cp(x)),
					() -> new SegmentedWeightedSumComputation("matmul", 1, cp(flat), cp(x)),
					() -> new SegmentedWeightedSumComputation("matmul", 1, cp(unenumerable), cp(repeated))
			};

			for (int i = 0; i < invalid.length; i++) {
				boolean rejected = false;
				try {
					invalid[i].run();
				} catch (IllegalArgumentException expected) {
					rejected = true;
				}

				Assert.assertTrue("operands " + i + " should be rejected", rejected);
			}

			// [[1, 2], [1, 2]] * [1, 2] = [5, 5]
			assertMatches(new double[] { 5.0, 5.0 },
					matmul(cp(unenumerable), cp(repeated)).evaluate().reshape(shape(2)));
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
	 * When an ordered collection is folded into an argument aggregate, it occupies only its
	 * three stored entries rather than its nine logical elements, so the argument after it is
	 * placed at position 3. Copying into the aggregate moves exactly the stored entries, and
	 * copying back writes the aggregate's values into those entries without touching the
	 * argument that follows. This holds on every driver, not only the ones that aggregate
	 * kernel arguments.
	 */
	@Test(timeout = 60000)
	public void aggregateHoldsOnlyStoredEntries() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, 3, 3));
			 PackedCollection dense = PackedCollection.of(7.0, 8.0)) {
			matrix.fill(1.0, 2.0, 3.0);

			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), length -> {
						PackedCollection aggregate = new PackedCollection(length);
						aggregates.add(aggregate);
						return aggregate;
					});

			try {
				map.get(p(matrix));
				map.get(p(dense));

				Assert.assertEquals(5, map.getAggregateLength());
				Assert.assertEquals("[0,3,]", map.describeAggregatePositions());
				Assert.assertEquals("len=5 [0:3,3:2,]", map.describeAggregate());

				Semaphore copiedIn = Submittable.submit(map.getPrepareOperations(), null);
				if (copiedIn != null) copiedIn.waitFor();
				Assert.assertEquals(1, aggregates.size());
				PackedCollection aggregate = aggregates.get(0);
				Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 7.0, 8.0 },
						aggregate.toArray(0, 5), 0.0);

				aggregate.fill(10.0, 20.0, 30.0, 40.0, 50.0);
				Semaphore copiedOut = Submittable.submit(map.getPostprocessOperations(null), null);
				if (copiedOut != null) copiedOut.waitFor();
				assertMatches(new double[] { 10.0, 0.0, 20.0, 0.0, 0.0, 0.0, 0.0, 30.0, 0.0 }, matrix);
				assertMatches(new double[] { 40.0, 50.0 }, dense);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A root whose ordering gathers from more memory than it has logical elements (positions
	 * 5, 3 and 1 of a 6-element backing, read as 3 elements) occupies all of that backing
	 * memory in the aggregate, and its copy into and out of the aggregate moves all of it.
	 */
	@Test(timeout = 60000)
	public void aggregateHoldsGatheredBackingMemory() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection gathered = new PackedCollection(
					new TraversalPolicy(SplitIndexTraversalOrdering.of(5, 3, 1), 3));
			 PackedCollection dense = PackedCollection.of(7.0, 8.0)) {
			Assert.assertEquals(3, gathered.getMemLength());
			Assert.assertEquals(6, gathered.getDelegatedLength());
			gathered.fill(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
			assertMatches(new double[] { 6.0, 4.0, 2.0 }, gathered);

			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), length -> {
						PackedCollection aggregate = new PackedCollection(length);
						aggregates.add(aggregate);
						return aggregate;
					});

			try {
				map.get(p(gathered));
				map.get(p(dense));

				Assert.assertEquals(8, map.getAggregateLength());
				Assert.assertEquals("len=8 [0:6,6:2,]", map.describeAggregate());

				Semaphore copiedIn = Submittable.submit(map.getPrepareOperations(), null);
				if (copiedIn != null) copiedIn.waitFor();
				Assert.assertEquals(1, aggregates.size());
				PackedCollection aggregate = aggregates.get(0);
				Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0 },
						aggregate.toArray(0, 8), 0.0);

				aggregate.fill(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0);
				Semaphore copiedOut = Submittable.submit(map.getPostprocessOperations(null), null);
				if (copiedOut != null) copiedOut.waitFor();
				assertMatches(new double[] { 60.0, 40.0, 20.0 }, gathered);
				assertMatches(new double[] { 70.0, 80.0 }, dense);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A clone of an ordered collection copies the backing memory, whether that is smaller than
	 * the logical length (a CSR matrix storing 3 of 9 elements) or larger (a gather reading 3
	 * elements from 6), and reads it through the same ordering; a later write to the original
	 * does not reach the clone.
	 */
	@Test(timeout = 30000)
	public void orderedCloneCopiesBackingMemory() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);

		try (PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, 3, 3));
			 PackedCollection gathered = new PackedCollection(
					 new TraversalPolicy(SplitIndexTraversalOrdering.of(5, 3, 1), 3))) {
			matrix.fill(1.0, 2.0, 3.0);
			gathered.fill(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);

			try (PackedCollection matrixClone = matrix.clone();
				 PackedCollection gatheredClone = gathered.clone()) {
				Assert.assertEquals(3, matrixClone.getDelegatedLength());
				Assert.assertEquals(6, gatheredClone.getDelegatedLength());

				matrix.fill(9.0);
				gathered.fill(9.0);

				assertMatches(new double[] { 1.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 3.0, 0.0 }, matrixClone);
				assertMatches(new double[] { 6.0, 4.0, 2.0 }, gatheredClone);
			}
		}
	}

	/**
	 * Moving an ordered collection to another memory provider, and back again (which reuses the
	 * version kept on the original provider), carries its backing memory: the three stored
	 * entries of a CSR matrix that keeps 3 of 9 elements, and all six positions a gather reads
	 * its 3 elements from. The collection reads the same values afterwards.
	 */
	@Test(timeout = 30000)
	public void reallocateMovesBackingMemory() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);
		JVMMemoryProvider jvm = new JVMMemoryProvider();

		try (PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, 3, 3));
			 PackedCollection gathered = new PackedCollection(
					 new TraversalPolicy(SplitIndexTraversalOrdering.of(5, 3, 1), 3))) {
			matrix.fill(1.0, 2.0, 3.0);
			gathered.fill(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
			MemoryProvider<?> original = matrix.getMem().getProvider();

			matrix.reallocate(jvm);
			gathered.reallocate(jvm);
			Assert.assertSame(jvm, matrix.getMem().getProvider());
			Assert.assertSame(jvm, gathered.getMem().getProvider());
			assertMatches(DENSE, matrix);
			assertMatches(new double[] { 6.0, 4.0, 2.0 }, gathered);
			Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0 },
					gathered.getMem().toArray(0, 6), 0.0);

			matrix.setMem(2, 30.0);
			matrix.reallocate(original);
			gathered.reallocate(original);
			assertMatches(new double[] { 1.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 30.0, 0.0 }, matrix);
			assertMatches(new double[] { 6.0, 4.0, 2.0 }, gathered);
		}
	}

	/**
	 * A view of an ordered root without an ordering of its own addresses the root's logical
	 * elements, which the root's ordering maps to its stored entries. A reshape and a view at a
	 * logical offset of a CSR matrix that stores 3 of its 9 elements therefore span logical
	 * positions beyond the 3 allocated ones and read the dense values there, while a view past
	 * the logical length is rejected.
	 */
	@Test(timeout = 30000)
	public void viewOfOrderedRootAddressesLogicalElements() {
		CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3, OFFSETS, COLUMNS);

		try (PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, 3, 3))) {
			matrix.fill(1.0, 2.0, 3.0);

			assertMatches(DENSE, matrix.reshape(shape(9)));
			assertMatches(Arrays.copyOfRange(DENSE, 3, 9), new PackedCollection(shape(6), 0, matrix, 3));

			boolean rejected = false;
			try {
				new PackedCollection(shape(6), 0, matrix, 4);
			} catch (HardwareException expected) {
				rejected = true;
			}

			Assert.assertTrue("a view past the logical length should be rejected", rejected);
		}
	}

	/**
	 * The backing memory of an ordered collection is measured once, when the collection is
	 * created: writes are bounded by the ordering's physical length, but they never ask the
	 * ordering for it again (an ordering may read its index collections back to the host to
	 * answer). A write past the physical length is rejected even though it is within the
	 * logical length.
	 */
	@Test(timeout = 30000)
	public void physicalLengthMeasuredOnce() {
		AtomicInteger lengthQueries = new AtomicInteger();
		RepeatTraversalOrdering order = new RepeatTraversalOrdering(4) {
			@Override
			public OptionalInt getLength() {
				lengthQueries.incrementAndGet();
				return super.getLength();
			}
		};

		try (PackedCollection repeated = new PackedCollection(new TraversalPolicy(order, 12))) {
			Assert.assertEquals(4, repeated.getDelegatedLength());
			Assert.assertEquals(12, repeated.getMemLength());

			int queries = lengthQueries.get();
			repeated.fill(5.0, 6.0, 7.0, 8.0);
			repeated.setMem(3, 1.0);
			Assert.assertEquals(4, repeated.getDelegatedLength());
			Assert.assertEquals(queries, lengthQueries.get());
			assertMatches(new double[] { 5, 6, 7, 1, 5, 6, 7, 1, 5, 6, 7, 1 }, repeated);

			boolean rejected = false;
			try {
				repeated.setMem(4, 9.0);
			} catch (IllegalArgumentException expected) {
				rejected = true;
			}

			Assert.assertTrue("a write past the physical length should be rejected", rejected);
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
