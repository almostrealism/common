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
import io.almostrealism.scope.ExpressionCache;

/**
 * An {@link ExpressionMatrix} backed by a two-dimensional array of evaluated
 * {@link Expression} values.
 *
 * <p>This implementation is used when the expression cannot be reduced to a flat
 * {@link io.almostrealism.sequence.IndexSequence}. A matrix created from an expression
 * evaluates its entries on demand: {@link #valueAt(int, int)} substitutes the requested
 * {@code (row, col)} pair when it is asked for, and the full array is only populated
 * when {@link #getRowDuplicates()} is called. Every entry is a symbolic substitution,
 * and an analysis of an index matrix typically consults its dimensions and at most a
 * few of its entries (for example, {@link #allColumnsMatch()} stops at the first
 * column that differs), so evaluating all {@code rowCount * colCount} entries up
 * front would mostly produce entries that are never read.</p>
 *
 * <p>Row deduplication is applied when the matrix is populated: rows whose entries are
 * all identical to the preceding row are recorded in {@link ExpressionMatrix#rowDuplicates}
 * and resolve to the earlier row's entries.</p>
 *
 * <p>Evaluating entries on demand mutates the matrix, so an instance must not be shared
 * between threads; it is meant to be created and consumed by a single analysis.</p>
 *
 * <p>When {@link #enableProactiveSimplification} is {@code true}, each matrix entry is
 * simplified immediately after evaluation, at the cost of additional compile time.</p>
 *
 * @param <T> the value type of the stored expressions
 */
public class ExplicitExpressionMatrix<T> extends ExpressionMatrix<T> {
	/** When {@code true}, each entry is simplified immediately after evaluation. */
	public static boolean enableProactiveSimplification = false;

	/**
	 * The expression the entries are substituted from, or {@code null} for a matrix
	 * created from an already evaluated array.
	 */
	private Expression<?> expression;

	/** The 2-D array of evaluated expressions, indexed by {@code [row][col]}, once populated. */
	private Expression[][] matrix;

	/** The row most recently substituted to evaluate an entry on demand, or {@code -1}. */
	private int substitutedRow = -1;

	/** The {@link #expression} with the row index replaced by {@link #substitutedRow}. */
	private Expression<?> substitutedRowExpression;

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
	 * Creates a matrix of the given expression at every {@code (row, col)} position.
	 *
	 * <p>No entry is evaluated here. Each is substituted when {@link #valueAt(int, int)}
	 * first requests it, and the matrix is populated in full only if
	 * {@link #getRowDuplicates()} is called.</p>
	 *
	 * @param row the row index
	 * @param col the column index
	 * @param e   the expression to evaluate
	 */
	protected ExplicitExpressionMatrix(Index row, Index col, Expression<T> e) {
		super(row, col);
		this.expression = e.getSimplified();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Populates the matrix first if it has not been populated yet, since the
	 * row-duplicate map is a property of every row's entries.</p>
	 */
	@Override
	public int[] getRowDuplicates() {
		if (matrix == null) populate(expression);
		return rowDuplicates;
	}

	/**
	 * Evaluates the given expression at every {@code (row, col)} position and builds
	 * the internal matrix and row-duplicate map.
	 *
	 * <p>The row index is substituted once per row rather than once per entry, and the
	 * entries are built with the thread's {@link ExpressionCache} bypassed: they exist only
	 * for analysis and never reach generated code, so deduplicating them saves nothing while
	 * flooding the cache of the kernel being compiled (see {@link ExpressionCache#bypass}).</p>
	 *
	 * @param e the expression to populate from
	 */
	protected void populate(Expression e) {
		matrix = new Expression[rowCount][colCount];
		rowDuplicates = new int[rowCount];

		ExpressionCache.bypass(() -> {
			for (int i = 0; i < rowCount; i++) {
				populateRow(i, e.withIndex(row, i));
			}
		});

		if (rowDuplicates[0] == 0) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Fills one row of the matrix from an expression in which the row index has already
	 * been substituted, and records whether the row duplicates the one before it.
	 *
	 * @param i             the row being filled
	 * @param rowExpression the populated expression with the row index replaced by {@code i}
	 */
	protected void populateRow(int i, Expression rowExpression) {
		rowDuplicates[i] = -1;
		boolean duplicate = true;

		for (int j = 0; j < colCount; j++) {
			matrix[i][j] = rowExpression.withIndex(col, j);

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

	/**
	 * {@inheritDoc}
	 *
	 * <p>Once the matrix is populated, follows the row-duplicate chain to find the
	 * canonical row, then returns the stored expression at that row and the given
	 * column. Before that, the entry is substituted on demand; a duplicate row's entry
	 * is equal to the canonical row's, so the result is the same either way.</p>
	 */
	@Override
	public Expression<T> valueAt(int i, int j) {
		if (matrix == null) {
			return substitute(i, j);
		}

		if (rowDuplicates.length <= i || rowDuplicates[i] == i) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[i] >= 0) {
			return valueAt(rowDuplicates[i], j);
		}

		return matrix[i][j];
	}

	/**
	 * Evaluates a single entry of a matrix that has not been populated, substituting the
	 * row index into {@link #expression} once per row and the column index once per entry,
	 * exactly as {@link #populate(Expression)} does.
	 *
	 * <p>The substitution runs with the thread's {@link ExpressionCache} bypassed, for the
	 * reason given in {@link #populate(Expression)}. The most recently substituted row is
	 * retained, so reading a row's entries in order substitutes the row index only once.</p>
	 *
	 * @param i the row index
	 * @param j the column index
	 * @return the expression at {@code (i, j)}
	 * @throws UnsupportedOperationException if {@code i} is not a row of this matrix
	 * @throws IndexOutOfBoundsException if {@code j} is not a column of this matrix
	 */
	protected Expression<T> substitute(int i, int j) {
		if (i < 0 || i >= rowCount) {
			throw new UnsupportedOperationException();
		} else if (j < 0 || j >= colCount) {
			throw new IndexOutOfBoundsException(j);
		}

		Expression<?> entry = ExpressionCache.bypass(() -> {
			if (substitutedRow != i) {
				substitutedRowExpression = expression.withIndex(row, i);
				substitutedRow = i;
			}

			Expression<?> value = substitutedRowExpression.withIndex(col, j);
			return enableProactiveSimplification ? value.getSimplified() : value;
		});

		return (Expression<T>) entry;
	}
}
