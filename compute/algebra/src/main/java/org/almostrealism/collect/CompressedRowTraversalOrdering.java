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

package org.almostrealism.collect;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.collect.OrderingSegments;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Conjunction;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * A traversal ordering in compressed-sparse-row (CSR) form: it lays the stored entries of a
 * {@code (rows, cols)} matrix out row by row, so that a collection of the entry values, read
 * through this ordering, is the sparse matrix.
 *
 * <p>The ordering is defined by two index collections, both stored split across two lanes in
 * the integer-exact layout of {@link SplitIndexTraversalOrdering} and each read through an
 * ordering of that kind:</p>
 * <ul>
 *   <li>{@code rowOffsets}: the {@code rows + 1} row boundaries, where the entries of row
 *       {@code i} occupy physical positions {@code rowOffsets[i] .. rowOffsets[i + 1] - 1}.</li>
 *   <li>{@code columns}: the column of the entry at each physical position.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // [[1, 0, 2],
 * //  [0, 0, 0],
 * //  [0, 3, 0]]
 * CompressedRowTraversalOrdering order = CompressedRowTraversalOrdering.of(3,
 *         new int[] { 0, 2, 2, 3 }, new int[] { 0, 2, 1 });
 * PackedCollection matrix = new PackedCollection(new TraversalPolicy(order, 3, 3));
 * matrix.fill(1.0, 2.0, 3.0);
 * }</pre>
 * <p>The collection's memory holds only the entries. An existing collection of entry values can
 * also be read through the ordering, with
 * {@code new PackedCollection(shape(3, 3), 0, values, 0, order)}.</p>
 *
 * <h2>Logical to physical: a bounded search</h2>
 * <p>{@link #indexOf(Expression)} maps logical index {@code i * cols + j} to the position of
 * the entry of row {@code i} whose column is {@code j}, or {@code -1} (an absent element, read
 * as zero) when the row has none. The position is found by a search of the row, rendered as a
 * chain of conditionals, one per position up to the {@link #getMaxRowLength() longest row}, in
 * the same way that {@link IndexMaskTraversalOrdering} searches its mask. Every reader of the
 * collection is therefore correct, but each read costs one comparison per entry of the longest
 * row, and a reader that visits every logical element (such as a dense product) performs
 * {@code rows * cols} such reads. The columns of each row are distinct, so that every logical
 * element is at most one entry, and reading the elements and enumerating the entries agree.</p>
 *
 * <h2>Physical to logical: enumerating the members of a row</h2>
 * <p>A computation that needs only the stored entries, such as a product of the matrix,
 * enumerates the members of each row instead: the ordering is its own
 * {@link #segments(int) enumeration} of rows, giving the {@link #start(Expression) start} and
 * {@link #length(Expression) length} of each row and the {@link #offset(Expression) column} of
 * each entry. This visits each stored entry once.</p>
 *
 * <h2>Scale</h2>
 * <p>A collection read through this ordering stores only its entries, so its logical shape may
 * exceed the range of an {@code int}: a computation that enumerates its rows never forms a
 * logical index. Reading it through {@link #indexOf(Expression)} forms the logical index
 * {@code i * cols + j}, and is therefore available only for matrices whose {@code rows * cols}
 * fits that bound. The index collections and the physical positions are exact for any
 * {@code int}.</p>
 *
 * @see SplitIndexTraversalOrdering
 * @see IndexMaskTraversalOrdering
 *
 * @author Michael Murray
 */
public class CompressedRowTraversalOrdering implements TraversalOrdering, OrderingSegments {
	/** The number of columns, which separates the row and column of a logical index. */
	private final int cols;

	/** The {@code rows + 1} row boundaries, each the position of the first entry of a row. */
	private final SplitIndexTraversalOrdering rowOffsets;

	/** The column of each entry, by physical position. */
	private final SplitIndexTraversalOrdering columns;

	/** The number of entries of the longest row, read from the row offsets when first needed. */
	private Integer maxRowLength;

	/** The number of entries, the last row offset, read from the row offsets when first needed. */
	private Integer entryCount;

	/**
	 * Creates an ordering over pre-built index collections, validating that their shapes are
	 * mutually consistent. The contents are not read back from the device here; use
	 * {@link #of(int, int[], int[])} to build an ordering from host index arrays with full
	 * validation of the row offsets and columns.
	 *
	 * @param rows       the number of rows
	 * @param cols       the number of columns
	 * @param rowOffsets the row boundaries as split lanes of shape {@code (rows + 1, 2)}, as
	 *                   produced by {@link PackedCollection#ofSplitIndices(int...)}
	 * @param columns    the column of each entry as split lanes of shape {@code (m, 2)}
	 * @throws IllegalArgumentException if a dimension is not positive, an index collection is
	 *         not in the split layout, or {@code rowOffsets} does not have {@code rows + 1} lanes
	 */
	public CompressedRowTraversalOrdering(int rows, int cols, PackedCollection rowOffsets,
										  PackedCollection columns) {
		this(cols, new SplitIndexTraversalOrdering(rowOffsets), new SplitIndexTraversalOrdering(columns), null);

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
	}

	/**
	 * Creates an ordering from the orderings that read its row offsets and its columns.
	 *
	 * @param cols         the number of columns
	 * @param rowOffsets   the row boundaries
	 * @param columns      the column of each entry
	 * @param maxRowLength the length of the longest row, or {@code null} if not yet known
	 */
	private CompressedRowTraversalOrdering(int cols, SplitIndexTraversalOrdering rowOffsets,
										   SplitIndexTraversalOrdering columns, Integer maxRowLength) {
		this.cols = cols;
		this.rowOffsets = rowOffsets;
		this.columns = columns;
		this.maxRowLength = maxRowLength;
	}

	/**
	 * Returns the number of rows.
	 *
	 * @return the number of rows
	 */
	public int getRows() { return rowOffsets.getLanes().getShape().length(0) - 1; }

	/**
	 * Returns the number of columns.
	 *
	 * @return the number of columns
	 */
	public int getCols() { return cols; }

	/**
	 * Returns the row boundaries.
	 *
	 * @return the row offsets as split lanes of shape {@code (rows + 1, 2)}
	 */
	public PackedCollection getRowOffsets() { return rowOffsets.getLanes(); }

	/**
	 * Returns the column of each entry.
	 *
	 * @return the columns as split lanes of shape {@code (m, 2)}
	 */
	public PackedCollection getColumns() { return columns.getLanes(); }

	/**
	 * Returns the number of entries of the longest row, which bounds the search performed by
	 * {@link #indexOf(Expression)}. It is read back from the row offsets the first time it is
	 * needed.
	 *
	 * @return the length of the longest row
	 */
	public int getMaxRowLength() {
		if (maxRowLength == null) {
			int[] offsets = rowOffsets.getPositions();
			maxRowLength = IntStream.range(0, offsets.length - 1)
					.map(i -> offsets[i + 1] - offsets[i]).max().orElse(0);
		}

		return maxRowLength;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Searches row {@code idx / cols} for an entry whose column is {@code idx % cols},
	 * comparing one position per entry of the longest row, and returns the position of the
	 * first match or {@code -1}. Positions beyond the end of the row are excluded by comparing
	 * the search offset with the row length; the column read for such a position is wrapped
	 * into the column collection so that it never leaves its memory.</p>
	 */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		Expression<?> row = idx.divide(cols);
		Expression<Integer> column = idx.imod(cols);
		Expression<Integer> start = start(row);
		Expression<Integer> length = length(row);
		int capacity = getColumns().getShape().length(0);

		Expression<Integer> position = new IntegerConstant(-1);
		for (int k = getMaxRowLength() - 1; k >= 0; k--) {
			Expression<Integer> entry = start.add(k).toInt();
			Expression<Integer> entryColumn = offset(entry.imod(capacity));
			Expression<Boolean> found = Conjunction.of(new IntegerConstant(k).lessThan(length), entryColumn.eq(column));
			position = Conditional.of(found, entry, position);
		}

		return position;
	}

	/**
	 * Returns the number of physical positions addressed, which is the number of entries:
	 * the last row offset. It is read back from the row offsets the first time it is needed.
	 *
	 * @return the number of entries
	 */
	@Override
	public OptionalInt getLength() {
		if (entryCount == null) {
			entryCount = rowOffsets.getPosition(getRows());
		}

		return OptionalInt.of(entryCount);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns an ordering whose row offsets and columns are both
	 * {@link SplitIndexTraversalOrdering#bind(ArgumentProvider) bound} to the kernel arguments the
	 * provider supplies for them.</p>
	 */
	@Override
	public CompressedRowTraversalOrdering bind(ArgumentProvider provider) {
		return new CompressedRowTraversalOrdering(cols, rowOffsets.bind(provider), columns.bind(provider), maxRowLength);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The segments of a compressed-sparse-row ordering are its rows, so this ordering
	 * enumerates segments whose length is its number of columns, and is itself the
	 * enumeration.</p>
	 */
	@Override
	public Optional<OrderingSegments> segments(int segmentLength) {
		return segmentLength == cols ? Optional.of(this) : Optional.empty();
	}

	/**
	 * Returns the integer-exact physical position of the first entry of the given row.
	 *
	 * @param row the row index
	 * @return the position of the row's first entry, {@code rowOffsets[row]}
	 */
	@Override
	public Expression<Integer> start(Expression<?> row) {
		return rowOffsets.indexOf(row.toInt());
	}

	/**
	 * Returns the number of entries of the given row.
	 *
	 * @param row the row index
	 * @return {@code rowOffsets[row + 1] - rowOffsets[row]}
	 */
	@Override
	public Expression<Integer> length(Expression<?> row) {
		return rowOffsets.indexOf(row.add(1).toInt()).subtract(start(row)).toInt();
	}

	/**
	 * Returns the column of the entry at the given physical position.
	 *
	 * @param position the physical position of an entry
	 * @return {@code columns[position]}
	 */
	@Override
	public Expression<Integer> offset(Expression<?> position) {
		return columns.indexOf(position.toInt());
	}

	/**
	 * Creates an ordering from a host CSR index structure, validating it and uploading each
	 * index array in one transfer.
	 *
	 * @param cols       the number of columns
	 * @param rowOffsets the {@code rows + 1} row boundaries: starting at {@code 0} and
	 *                   non-decreasing, with at least one row
	 * @param columns    the column of each entry, in {@code [0, cols)} and distinct within each
	 *                   row, as many as the last row offset
	 * @return the ordering
	 * @throws IllegalArgumentException if the arrays are not a valid CSR structure with
	 *         {@code cols} columns
	 */
	public static CompressedRowTraversalOrdering of(int cols, int[] rowOffsets, int[] columns) {
		int rows = rowOffsets.length - 1;
		if (rows <= 0) {
			throw new IllegalArgumentException("Sparse matrix row count " + rows + " must be positive");
		}

		if (rowOffsets[0] != 0 || rowOffsets[rows] != columns.length) {
			throw new IllegalArgumentException("Row offsets must run from 0 to the entry count "
					+ columns.length + ", not " + rowOffsets[0] + " to " + rowOffsets[rows]);
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

		for (int i = 0; i < rows; i++) {
			int start = rowOffsets[i];
			int length = rowOffsets[i + 1] - start;
			if (IntStream.range(start, start + length).map(k -> columns[k]).distinct().count() != length) {
				throw new IllegalArgumentException("Row " + i + " stores a column more than once");
			}
		}

		// A structure without entries keeps one unused column, since a collection cannot be empty
		return new CompressedRowTraversalOrdering(rows, cols,
				PackedCollection.ofSplitIndices(rowOffsets),
				PackedCollection.ofSplitIndices(columns.length == 0 ? new int[1] : columns));
	}
}
