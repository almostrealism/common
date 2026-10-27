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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.SegmentedWeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.SplitIndexTraversalOrdering;

/**
 * A sparse matrix in compressed-sparse-row (CSR) form: a collection of entry values laid out
 * by a {@link CompressedRowTraversalOrdering}, with a matrix–vector (and matrix–matrix) product
 * computed by a segmented reduction over the members of each row.
 *
 * <h2>Storage</h2>
 * <p>The matrix is the {@code values} collection read through a
 * {@link CompressedRowTraversalOrdering}, which holds the index structure:</p>
 * <ul>
 *   <li>{@code rowOffsets}: the {@code rows + 1} row boundaries, where the entries of row
 *       {@code i} are {@code rowOffsets[i] .. rowOffsets[i + 1] - 1}.</li>
 *   <li>{@code columns}: the column of each entry.</li>
 * </ul>
 * <p>Both index collections are stored split across two lanes, shape {@code (n, 2)}, in the
 * layout of {@link SplitIndexTraversalOrdering}: at tens of millions of entries an offset exceeds
 * {@code 2^24} and would be rounded in an FP32 collection, and the split layout also lifts any
 * limit on the column count. Every flat address into {@code columns} and {@code values} is formed
 * from the offsets on integer expressions in the kernel, so it is exact for any {@code int} entry
 * count. The values collection may hold more elements than the last row offset; the extra
 * capacity is never read.</p>
 *
 * <p>The index structure is host data and enters device memory through
 * {@link PackedCollection#ofSplitIndices(int...)} in {@link #of(int, int, int[], int[], PackedCollection)};
 * the values are already a device collection, produced by a computation or by the ingest surface.</p>
 *
 * <h2>Product</h2>
 * <p>{@link #multiply(Producer)} computes {@code A·x} as a gather followed by a segmented
 * reduction ({@link SegmentedWeightedSumComputation}): one kernel thread per output element,
 * which enumerates exactly that row's entries and writes its output once, deterministically and
 * without atomics. The loop bound, {@code rowOffsets[i + 1] - rowOffsets[i]}, is read from device
 * memory, so rows of any length are handled without padding. This runs on both the native
 * (FP64) and Metal (FP32) backends: a product with 166,000 rows and about twenty million
 * entries runs in roughly 5 ms on Metal and 13 ms on the native backend. The kernel depends
 * only on the operand shapes, so matrices with the same shape and capacity share one compiled
 * kernel.</p>
 *
 * <h2>Why the product is not dense {@code matmul} of an ordered collection</h2>
 * <p>The {@code (rows, cols)} collection of the values read through the ordering,
 * {@code new PackedCollection(getShape(), 0, getValues(), 0, getOrdering())}, is a correct view
 * of the matrix: every computation reading it sees the dense matrix, with zero for every absent
 * element. It is not the representation the product runs on, because two existing operations
 * do not support it at the scale this type exists for:</p>
 * <ul>
 *   <li><strong>Logical size.</strong> A {@link PackedCollection} measures its logical shape
 *       as an {@code int} even when an ordering stores only the entries, so a shape of more than
 *       {@link Integer#MAX_VALUE} logical elements cannot be constructed. A 166,000 × 166,000
 *       matrix has about {@code 2.8 * 10^10} logical elements.</li>
 *   <li><strong>Reductions read every logical element.</strong> Dense {@code matmul} sums over
 *       every column of every row, reading each element through
 *       {@link CompressedRowTraversalOrdering#indexOf}, which searches the row: that is
 *       {@code rows * cols} searches per product, each as long as the longest row, where the
 *       segmented reduction visits each stored entry once. The cost is already prohibitive in
 *       compilation: for a 300 × 200 matrix with one 200-entry row, dense {@code matmul} of
 *       the ordered collection with a {@code (200, 3)} operand fails to compile after more than
 *       two minutes, while the segmented product of the same operands compiles and runs in
 *       seconds.</li>
 * </ul>
 * <p>Once ordered collections may exceed {@code int} logical sizes and reductions enumerate the
 * members of a compressed ordering, this type reduces to that ordered collection.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // [[1, 0, 2],
 * //  [0, 0, 0],
 * //  [0, 3, 0]]
 * SparseMatrix a = SparseMatrix.of(3, 3,
 *         new int[] { 0, 2, 2, 3 },
 *         new int[] { 0, 2, 1 },
 *         values);                            // a device collection holding 1, 2, 3
 * CollectionProducer y = a.multiply(cp(x));   // shape (3)
 * }</pre>
 *
 * @see CompressedRowTraversalOrdering
 * @see SegmentedWeightedSumComputation
 * @see SplitIndexTraversalOrdering
 *
 * @author Michael Murray
 */
public class SparseMatrix implements MatrixFeatures, Destroyable {
	/** The index structure, which lays the values out as a {@code (rows, cols)} matrix. */
	private final CompressedRowTraversalOrdering ordering;

	/** The value of each entry. */
	private final PackedCollection values;

	/**
	 * Creates a sparse matrix over pre-built CSR collections, validating that their shapes are
	 * mutually consistent. The contents are not read back from the device; use
	 * {@link #of(int, int, int[], int[], PackedCollection)} to build a matrix from host index
	 * arrays with full validation of the row offsets and columns.
	 *
	 * @param rows       the number of rows
	 * @param cols       the number of columns
	 * @param rowOffsets the row boundaries as split lanes of shape {@code (rows + 1, 2)}, as
	 *                   produced by {@link PackedCollection#ofSplitIndices(int...)}
	 * @param columns    the column of each entry as split lanes of shape {@code (m, 2)}
	 * @param values     the value of each entry
	 * @throws IllegalArgumentException if a dimension is not positive, an index collection is
	 *         not in the split layout, {@code rowOffsets} does not have {@code rows + 1} lanes,
	 *         or {@code values} holds fewer elements than {@code columns} has lanes
	 */
	public SparseMatrix(int rows, int cols, PackedCollection rowOffsets,
						PackedCollection columns, PackedCollection values) {
		this(new CompressedRowTraversalOrdering(rows, cols, rowOffsets, columns), values);
	}

	/**
	 * Creates a sparse matrix of the given values laid out by the given ordering.
	 *
	 * @param ordering the index structure
	 * @param values   the value of each entry
	 * @throws IllegalArgumentException if {@code values} holds fewer elements than the
	 *         ordering has column lanes
	 */
	public SparseMatrix(CompressedRowTraversalOrdering ordering, PackedCollection values) {
		int capacity = ordering.getColumns().getShape().length(0);
		if (values.getMemLength() < capacity) {
			throw new IllegalArgumentException(values.getMemLength() + " values given for "
					+ capacity + " columns");
		}

		this.ordering = ordering;
		this.values = values;
	}

	/**
	 * Returns the number of rows.
	 *
	 * @return the number of rows
	 */
	public int getRows() { return ordering.getRows(); }

	/**
	 * Returns the number of columns.
	 *
	 * @return the number of columns
	 */
	public int getCols() { return ordering.getCols(); }

	/**
	 * Returns the shape {@code (rows, cols)} of the dense matrix this matrix represents.
	 *
	 * @return the dense shape
	 */
	public TraversalPolicy getShape() { return new TraversalPolicy(getRows(), getCols()); }

	/**
	 * Returns the ordering that lays the values out as the matrix.
	 *
	 * @return the index structure
	 */
	public CompressedRowTraversalOrdering getOrdering() { return ordering; }

	/**
	 * Returns the row boundaries.
	 *
	 * @return the row offsets as split lanes of shape {@code (rows + 1, 2)}
	 */
	public PackedCollection getRowOffsets() { return ordering.getRowOffsets(); }

	/**
	 * Returns the column of each entry.
	 *
	 * @return the columns as split lanes of shape {@code (m, 2)}
	 */
	public PackedCollection getColumns() { return ordering.getColumns(); }

	/**
	 * Returns the value of each entry.
	 *
	 * @return the values
	 */
	public PackedCollection getValues() { return values; }

	/**
	 * Returns the product {@code A·x}. For a vector {@code x} of {@code cols} elements (or a
	 * {@code (cols, 1)} column), {@code y[i] = Σ values[k] · x[columns[k]]} over the entries
	 * {@code k} of row {@code i}, of shape {@code (rows)}. For a matrix {@code x} of shape
	 * {@code (cols, n)} the product has shape {@code (rows, n)}. A row without entries yields
	 * {@code 0}.
	 *
	 * @param x the vector or matrix, with {@code cols} rows
	 * @return a producer of the product
	 * @throws IllegalArgumentException if {@code x} does not have {@code cols} rows
	 */
	public CollectionProducer multiply(Producer<PackedCollection> x) {
		int cols = getCols();
		TraversalPolicy shape = shape(x);
		int size = shape.getTotalSize();
		if (shape.getDimensions() > 2 || shape.length(0) != cols) {
			throw new IllegalArgumentException("Operand of shape " + shape
					+ " cannot multiply a sparse matrix with " + cols + " columns");
		}

		return new SegmentedWeightedSumComputation("sparseMultiply", getRows(), size / cols,
				x, cp(values), cp(getColumns()), cp(getRowOffsets()));
	}

	/**
	 * Returns this matrix materialised as a dense {@code (rows, cols)} matrix, computed on the
	 * device as the product of this matrix with the identity. The result holds
	 * {@code rows * cols} elements, so this is intended for matrices small enough to be dense.
	 *
	 * <p>This is not the values read through the {@link #getOrdering() ordering}: that view is
	 * correct, but each of its elements is a search of its row, so a computation consuming it
	 * compiles one search per element (see the class documentation).</p>
	 *
	 * @return a producer of the dense matrix
	 */
	public CollectionProducer toDense() {
		return multiply(identity(getCols()));
	}

	/** Releases the device memory of the index structure and the values. */
	@Override
	public void destroy() {
		getRowOffsets().destroy();
		getColumns().destroy();
		values.destroy();
	}

	/**
	 * Creates a sparse matrix from a host CSR index structure and a device collection of
	 * values, validating the structure and uploading each index array in one transfer.
	 *
	 * @param rows       the number of rows
	 * @param cols       the number of columns
	 * @param rowOffsets the {@code rows + 1} row boundaries: starting at {@code 0},
	 *                   non-decreasing, and ending at the number of entries
	 * @param columns    the column of each entry, in {@code [0, cols)}
	 * @param values     the value of each entry, at least as many elements as {@code columns}
	 *                   (and at least one, even when there are no entries)
	 * @return the sparse matrix, which takes ownership of {@code values}
	 * @throws IllegalArgumentException if {@code rows} is not positive, or the arrays are not a
	 *         valid CSR matrix of the given shape
	 */
	public static SparseMatrix of(int rows, int cols, int[] rowOffsets, int[] columns,
								  PackedCollection values) {
		if (rows <= 0) {
			throw new IllegalArgumentException("Sparse matrix row count " + rows + " must be positive");
		}

		if (rowOffsets.length != rows + 1) {
			throw new IllegalArgumentException(rowOffsets.length + " row offsets given for "
					+ rows + " rows; expected " + (rows + 1));
		}

		if (values.getMemLength() < columns.length) {
			throw new IllegalArgumentException(values.getMemLength() + " values given for "
					+ columns.length + " entries");
		}

		return new SparseMatrix(CompressedRowTraversalOrdering.of(cols, rowOffsets, columns), values);
	}
}
