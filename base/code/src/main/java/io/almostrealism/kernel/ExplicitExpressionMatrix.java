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

import io.almostrealism.sequence.Index;
import io.almostrealism.expression.Expression;

/**
 * An {@link ExpressionMatrix} backed by a two-dimensional array of pre-evaluated
 * {@link Expression} values.
 *
 * <p>This implementation is used when the expression cannot be reduced to a flat
 * {@link io.almostrealism.sequence.IndexSequence}. Every {@code (row, col)} pair is
 * evaluated eagerly during construction. Row deduplication is applied automatically:
 * rows whose entries are all identical to the preceding row are recorded in
 * {@link ExpressionMatrix#rowDuplicates} and are not re-evaluated.</p>
 *
 * <p>When {@link #enableProactiveSimplification} is {@code true}, each matrix entry is
 * simplified immediately after evaluation, at the cost of additional compile time.</p>
 *
 * @param <T> the value type of the stored expressions
 */
public class ExplicitExpressionMatrix<T> extends ExpressionMatrix<T> {
	/** When {@code true}, each entry is simplified immediately after evaluation. */
	public static boolean enableProactiveSimplification = false;

	/** The 2-D array of pre-evaluated expressions, indexed by {@code [row][col]}. */
	private Expression[][] matrix;

	/**
	 * Creates a matrix from a pre-evaluated 2-D expression array and its row-duplicate map.
	 *
	 * @param row            the row index
	 * @param col            the column index
	 * @param matrix         the pre-evaluated expression array
	 * @param rowDuplicates  the per-row duplicate map
	 */
	protected ExplicitExpressionMatrix(Index row, Index col,
							   			Expression[][] matrix,
									   	int[] rowDuplicates) {
		super(row, col);
		this.matrix = matrix;
		this.rowDuplicates = rowDuplicates;
	}

	/**
	 * Creates a matrix by evaluating the given expression at every {@code (row, col)} position.
	 *
	 * @param row the row index
	 * @param col the column index
	 * @param e   the expression to evaluate
	 */
	protected ExplicitExpressionMatrix(Index row, Index col, Expression<T> e) {
		super(row, col);
		populate(e.getSimplified());
	}

	/**
	 * Evaluates the given expression at every {@code (row, col)} position and builds
	 * the internal matrix and row-duplicate map.
	 *
	 * @param e the expression to populate from
	 */
	protected void populate(Expression e) {
		matrix = new Expression[rowCount][colCount];
		rowDuplicates = new int[rowCount];

		for (int i = 0; i < rowCount; i++) {
			rowDuplicates[i] = -1;
			boolean duplicate = true;

			for (int j = 0; j < colCount; j++) {
				matrix[i][j] = e.withIndex(row, i).withIndex(col, j);

				if (enableProactiveSimplification)
					matrix[i][j] = matrix[i][j].getSimplified();

				if (i == 0 || !valueAt(i, j).equals(valueAt((i - 1), j))) {
					duplicate = false;
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
	 * <p>Follows the row-duplicate chain to find the canonical row, then returns the
	 * stored expression at that row and the given column.</p>
	 */
	@Override
	public Expression<T> valueAt(int i, int j) {
		if (rowDuplicates.length <= i || rowDuplicates[i] == i) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[i] >= 0) {
			return valueAt(rowDuplicates[i], j);
		}

		return matrix[i][j];
	}
}
