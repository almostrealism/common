/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.kernel;

import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;

/**
 * An {@link ExpressionMatrix} backed by a flat {@link IndexSequence} that stores
 * all {@code rowCount * colCount} values in a single array.
 *
 * <p>Individual entries are retrieved by computing the flat offset
 * {@code i * colCount + j} into the backing sequence. Row deduplication is applied
 * at construction time by {@link #populate()}: consecutive rows whose column values
 * are all identical are recorded in {@link ExpressionMatrix#rowDuplicates} to avoid
 * redundant work during subsequent analysis.</p>
 *
 * @param <T> the value type of the stored expressions
 */
public class SequenceMatrix<T> extends ExpressionMatrix<T> {
	/** The flat sequence of all matrix values, stored in row-major order. */
	private IndexSequence seq;

	/**
	 * Creates a {@link SequenceMatrix} from a flat sequence and builds the row-duplicate map.
	 *
	 * @param row the row index
	 * @param col the column index
	 * @param seq the flat row-major sequence of matrix values
	 */
	protected SequenceMatrix(Index row, Index col,
							   IndexSequence seq) {
		super(row, col);
		this.seq = seq;
		populate();
	}

	/**
	 * Creates a {@link SequenceMatrix} from a flat sequence and a pre-computed row-duplicate map,
	 * bypassing the populate step.
	 *
	 * @param row            the row index
	 * @param col            the column index
	 * @param seq            the flat row-major sequence of matrix values
	 * @param rowDuplicates  the pre-computed row-duplicate map
	 */
	protected SequenceMatrix(Index row, Index col,
							 IndexSequence seq,
							 int rowDuplicates[]) {
		super(row, col);
		this.seq = seq;
		this.rowDuplicates = rowDuplicates;
	}

	/**
	 * Builds the row-duplicate map by comparing consecutive rows.
	 * A row is a duplicate when every column value is identical to the corresponding
	 * column value in the preceding row.
	 */
	protected void populate() {
		if (seq == null) {
			throw new UnsupportedOperationException();
		}

		rowDuplicates = new int[rowCount];
		rowDuplicates[0] = -1;

		i: for (int i = 1; i < rowCount; i++) {
			rowDuplicates[i] = -1;
			if (seq.lengthLong() > MAX_SEQUENCE_LENGTH) {
				continue i;
			}

			boolean duplicate = true;

			if (seq.getMod() > colCount || colCount % seq.getMod() != 0) {
				for (int j = 0; j < colCount; j++) {
					Number v = seq.valueAt(i * colCount + j);

					if (!v.equals(seq.valueAt((i - 1) * colCount + j))) {
						duplicate = false;
					}
				}
			}

			if (duplicate) {
				rowDuplicates[i] = rowDuplicates[i - 1] < 0 ? i - 1 : rowDuplicates[i - 1];
			}
		}

		if (rowDuplicates[0] == 0) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>For integer-typed sequences, returns the maximum value plus one (i.e., the range
	 * size). Falls back to the default {@code -1} for floating-point sequences.</p>
	 */
	@Override
	public int getValueCount() {
		if (seq.getType() != Double.class) return Math.toIntExact(seq.max() + 1);
		return super.getValueCount();
	}

	/**
	 * Returns the backing flat sequence.
	 *
	 * @return the flat row-major sequence
	 */
	public IndexSequence getSequence() { return seq; }

	/**
	 * Returns the raw numeric value at the given row and column from the backing sequence.
	 *
	 * @param i the row index
	 * @param j the column index
	 * @return the numeric value at {@code (i, j)}
	 */
	protected Number sequenceValueAt(int i, int j) {
		return seq.valueAt(i * colCount + j);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Follows the row-duplicate chain to find the canonical row, then wraps the
	 * corresponding numeric value in a constant expression.</p>
	 */
	@Override
	public Expression<T> valueAt(int i, int j) {
		if (rowDuplicates.length <= i || rowDuplicates[i] == i) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[i] >= 0) {
			return valueAt(rowDuplicates[i], j);
		}

		return (Expression) Constant.of(sequenceValueAt(i, j));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a column sequence (one value per row) when every column in a given row
	 * has the same value, or {@code null} if any row has differing columns or the
	 * sequence exceeds {@link ExpressionMatrix#MAX_SEQUENCE_LENGTH}.</p>
	 */
	@Override
	public IndexSequence columnSequence() {
		if (seq.lengthLong() > MAX_SEQUENCE_LENGTH) {
			return null;
		}

		Number[] result = new Number[rowCount];

		for (int i = 0; i < rowCount; i++) {
			result[i] = sequenceValueAt(i, 0);

			for (int j = 0; j < colCount; j++) {
				if (!result[i].equals(sequenceValueAt(i, j))) return null;
			}
		}

		return ArrayIndexSequence.of(result);
	}
}
