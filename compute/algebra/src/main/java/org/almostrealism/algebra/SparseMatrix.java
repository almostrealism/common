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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.SplitIndexTraversalOrdering;

/**
 * A sparse matrix in compressed-sparse-row (CSR) form, with a matrix–vector (and
 * matrix–matrix) product computed by a segmented reduction.
 *
 * <h2>Storage</h2>
 * <p>The matrix is held as three device collections:</p>
 * <ul>
 *   <li>{@code rowOffsets}: the {@code rows + 1} row boundaries, where the entries of row
 *       {@code i} are {@code rowOffsets[i] .. rowOffsets[i + 1] - 1}.</li>
 *   <li>{@code columns}: the column of each entry.</li>
 *   <li>{@code values}: the value of each entry.</li>
 * </ul>
 * <p>Both index collections are stored split across two lanes, shape {@code (n, 2)}, in the
 * layout of {@link SplitIndexTraversalOrdering}: at tens of millions of entries an offset exceeds
 * {@code 2^24} and would be rounded in an FP32 collection, and the split layout also lifts any
 * limit on the column count. Every flat address into {@code columns} and {@code values} is formed
 * from the offsets on integer expressions in the kernel, so it is exact for any {@code int} entry
 * count. The entry collections may hold more elements than the last row offset; the extra
 * capacity is never read.</p>
 *
 * <p>The index structure is host data and enters device memory through
 * {@link PackedCollection#ofSplitIndices(int...)} in {@link #of(int, int, int[], int[], PackedCollection)};
 * the values are already a device collection, produced by a computation or by the ingest surface.</p>
 *
 * <h2>Product</h2>
 * <p>{@link #multiply(Producer)} computes {@code A·x} as a gather followed by a segmented
 * reduction ({@link SegmentedWeightedSumComputation}): one kernel thread per output element,
 * which loops over exactly that row's entries and writes its output once, deterministically and
 * without atomics.</p>
 *
 * <h2>Design decisions</h2>
 * <p><strong>Data-dependent loop bound, not padded rows.</strong> The row loop is a native loop
 * whose bound, {@code rowOffsets[i + 1] - rowOffsets[i]}, is read from device memory, so rows of
 * any length are handled without padding and without wasted work on long rows. A degree-binned
 * padded-row (ELL) layout was the alternative, needed only if a data-dependent bound did not
 * compile and run correctly on every backend. It does, on both the native (FP64) and Metal
 * (FP32) backends: a product with 166,000 rows and about twenty million entries runs in roughly
 * 5 ms on Metal and 13 ms on the native backend. So no padding layout is provided. The kernel
 * depends only on the operand shapes, so matrices with the same shape and capacity share one
 * compiled kernel.</p>
 *
 * <p><strong>Arrays held directly, not a sparse ordering.</strong> A
 * {@link io.almostrealism.collect.TraversalOrdering} maps logical to physical positions within a
 * single collection, while CSR is three arrays whose relationship is the segmented reduction
 * itself. The ordering machinery is used only where it fits: the integer-exact layout of the
 * index arrays.</p>
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
 * @see SegmentedWeightedSumComputation
 * @see SplitIndexTraversalOrdering
 *
 * @author Michael Murray
 */
public class SparseMatrix implements MatrixFeatures, Destroyable {
	/** The number of rows. */
	private final int rows;

	/** The number of columns. */
	private final int cols;

	/** The {@code rows + 1} row boundaries, split into lanes of shape {@code (rows + 1, 2)}. */
	private final PackedCollection rowOffsets;

	/** The column of each entry, split into lanes of shape {@code (m, 2)}. */
	private final PackedCollection columns;

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
		if (rows <= 0 || cols <= 0) {
			throw new IllegalArgumentException("Sparse matrix dimensions (" + rows + ", " + cols
					+ ") must be positive");
		}

		TraversalPolicy offsetShape = new TraversalPolicy(rows + 1, SplitIndexTraversalOrdering.LANES);
		if (!rowOffsets.getShape().equalsIgnoreAxis(offsetShape)) {
			throw new IllegalArgumentException("Row offsets " + rowOffsets.getShape()
					+ " must have shape " + offsetShape);
		}

		TraversalPolicy columnShape = columns.getShape();
		TraversalPolicy splitColumns = new TraversalPolicy(columnShape.length(0), SplitIndexTraversalOrdering.LANES);
		if (!columnShape.equalsIgnoreAxis(splitColumns)) {
			throw new IllegalArgumentException("Columns " + columnShape
					+ " must have shape (m, " + SplitIndexTraversalOrdering.LANES + ")");
		}

		if (values.getMemLength() < columnShape.length(0)) {
			throw new IllegalArgumentException(values.getMemLength() + " values given for "
					+ columnShape.length(0) + " columns");
		}

		this.rows = rows;
		this.cols = cols;
		this.rowOffsets = rowOffsets;
		this.columns = columns;
		this.values = values;
	}

	/**
	 * Returns the number of rows.
	 *
	 * @return the number of rows
	 */
	public int getRows() { return rows; }

	/**
	 * Returns the number of columns.
	 *
	 * @return the number of columns
	 */
	public int getCols() { return cols; }

	/**
	 * Returns the shape {@code (rows, cols)} of the dense matrix this matrix represents.
	 *
	 * @return the dense shape
	 */
	public TraversalPolicy getShape() { return new TraversalPolicy(rows, cols); }

	/**
	 * Returns the row boundaries.
	 *
	 * @return the row offsets as split lanes of shape {@code (rows + 1, 2)}
	 */
	public PackedCollection getRowOffsets() { return rowOffsets; }

	/**
	 * Returns the column of each entry.
	 *
	 * @return the columns as split lanes of shape {@code (m, 2)}
	 */
	public PackedCollection getColumns() { return columns; }

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
		TraversalPolicy shape = shape(x);
		int size = shape.getTotalSize();
		if (shape.getDimensions() > 2 || shape.length(0) != cols) {
			throw new IllegalArgumentException("Operand of shape " + shape
					+ " cannot multiply a sparse matrix with " + cols + " columns");
		}

		return new SegmentedWeightedSumComputation("sparseMultiply", rows, size / cols,
				x, cp(values), cp(columns), cp(rowOffsets));
	}

	/**
	 * Returns this matrix materialised as a dense {@code (rows, cols)} matrix, computed on the
	 * device as the product of this matrix with the identity. The result holds
	 * {@code rows * cols} elements, so this is intended for matrices small enough to be dense.
	 *
	 * @return a producer of the dense matrix
	 */
	public CollectionProducer toDense() {
		return multiply(identity(cols));
	}

	/** Releases the device memory of the three CSR collections. */
	@Override
	public void destroy() {
		rowOffsets.destroy();
		columns.destroy();
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
	 * @throws IllegalArgumentException if the arrays are not a valid CSR matrix of the given shape
	 */
	public static SparseMatrix of(int rows, int cols, int[] rowOffsets, int[] columns,
								  PackedCollection values) {
		if (rowOffsets.length != rows + 1) {
			throw new IllegalArgumentException(rowOffsets.length + " row offsets given for "
					+ rows + " rows; expected " + (rows + 1));
		}

		if (rowOffsets[0] != 0 || rowOffsets[rows] != columns.length) {
			throw new IllegalArgumentException("Row offsets must run from 0 to the entry count "
					+ columns.length + ", not " + rowOffsets[0] + " to " + rowOffsets[rows]);
		}

		if (values.getMemLength() < columns.length) {
			throw new IllegalArgumentException(values.getMemLength() + " values given for "
					+ columns.length + " entries");
		}

		for (int i = 0; i < rows; i++) {
			if (rowOffsets[i + 1] < rowOffsets[i]) {
				throw new IllegalArgumentException("Row offsets decrease at row " + i);
			}
		}

		for (int k = 0; k < columns.length; k++) {
			if (columns[k] < 0 || columns[k] >= cols) {
				throw new IllegalArgumentException("Column " + columns[k] + " of entry " + k
						+ " is outside [0, " + cols + ")");
			}
		}

		// A matrix without entries keeps one unused column, since a collection cannot be empty
		return new SparseMatrix(rows, cols,
				PackedCollection.ofSplitIndices(rowOffsets),
				PackedCollection.ofSplitIndices(columns.length == 0 ? new int[1] : columns),
				values);
	}
}
