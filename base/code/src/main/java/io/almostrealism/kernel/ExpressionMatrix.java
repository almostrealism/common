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
import io.almostrealism.sequence.IndexChild;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A two-dimensional matrix of {@link Expression} values indexed by row and column
 * {@link Index} objects, used to optimise kernel expressions that depend on two index
 * dimensions simultaneously.
 *
 * <p>The matrix stores one expression value per {@code (row, col)} pair and provides
 * utilities for detecting when all entries are equal ({@link #allMatch()}), when rows
 * are identical ({@link #allColumnsMatch()}), or when there is a unique non-zero column
 * per row ({@link #uniqueNonZeroOffset}).</p>
 *
 * <p>Concrete subclasses are {@link SequenceMatrix} (backed by a flat {@link IndexSequence})
 * and {@link ExplicitExpressionMatrix} (backed by a 2-D expression array). The
 * {@link #create} factory method selects the appropriate subclass based on whether the
 * expression can be reduced to a flat sequence.</p>
 *
 * @param <T> the value type of the stored expressions
 */
public abstract class ExpressionMatrix<T> implements ConsoleFeatures {
	/** Maximum flat sequence length before explicit matrix expansion is refused. */
	public static long MAX_SEQUENCE_LENGTH = 1 << 24;

	/** When {@code true}, {@link MaskMatrix} optimisation is attempted during function application. */
	public static boolean enableMaskMatrix = true;

	/** When {@code true}, full explicit matrix expansion is permitted even for very large matrices. */
	public static boolean enableUnsequencedMatrices = false;

	/** Maximum total number of matrix entries ({@code rows * cols}) before expansion is refused. */
	public static long maxMatrixSize = (long) Math.min(MAX_SEQUENCE_LENGTH, 10e7);

	/** The row index variable; each distinct value corresponds to one matrix row. */
	protected final Index row;

	/** The column index variable; each distinct value corresponds to one matrix column. */
	protected final Index col;

	/** The number of rows in this matrix, derived from the row index limit. */
	protected final int rowCount;

	/** The number of columns in this matrix, derived from the column index limit. */
	protected final int colCount;

	/**
	 * Per-row duplicate map: {@code rowDuplicates[i] >= 0} indicates that row {@code i}
	 * is identical to the row at index {@code rowDuplicates[i]}, and {@code rowDuplicates[i] < 0}
	 * means the row is distinct.
	 */
	protected int rowDuplicates[];

	/**
	 * Creates a matrix backed by the given row and column indices.
	 *
	 * @param row the row index; its limit determines the row count
	 * @param col the column index; its limit determines the column count
	 * @throws IllegalArgumentException if either index has no limit
	 */
	protected ExpressionMatrix(Index row, Index col) {
		rowCount = Math.toIntExact(row.getLimit().orElse(-1));
		colCount = Math.toIntExact(col.getLimit().orElse(-1));
		if (colCount < 0 || rowCount < 0)
			throw new IllegalArgumentException();

		this.row = row;
		this.col = col;
	}

	/**
	 * Returns the row index for this matrix.
	 *
	 * @return the row index
	 */
	public Index getRow() { return row; }

	/**
	 * Returns the column index for this matrix.
	 *
	 * @return the column index
	 */
	public Index getColumn() { return col; }

	/**
	 * Returns the combined (row, col) child index for this matrix.
	 *
	 * @return the joint index
	 */
	public Index getIndex() { return Index.child(row, col); }

	/**
	 * Returns the number of rows in this matrix.
	 *
	 * @return the row count
	 */
	public int getRowCount() { return rowCount; }

	/**
	 * Returns the number of columns in this matrix.
	 *
	 * @return the column count
	 */
	public int getColumnCount() { return colCount; }

	/**
	 * Returns the number of distinct values in this matrix, or {@code -1} if unknown.
	 *
	 * @return the value count, or {@code -1}
	 */
	public int getValueCount() { return -1; }

	/**
	 * Returns the row-duplicate map.
	 *
	 * @return per-row duplicate indices; negative values indicate distinct rows
	 */
	public int[] getRowDuplicates() { return rowDuplicates; }

	/**
	 * Returns the expression value at the given row and column.
	 *
	 * @param i the row index
	 * @param j the column index
	 * @return the expression at {@code (i, j)}
	 */
	public abstract Expression<T> valueAt(int i, int j);

	/**
	 * Applies the given function to every entry and returns the resulting matrix,
	 * using {@link MatrixFunctionEvaluator} to select the most efficient representation.
	 *
	 * @param <O>      the output expression type
	 * @param function the transformation to apply
	 * @return the transformed matrix, or {@code null} if evaluation is not possible
	 */
	public <O> ExpressionMatrix<O> apply(Function<Expression<T>, Expression<O>> function) {
		return MatrixFunctionEvaluator.apply(this, function);
	}

	/**
	 * Returns the single expression value if all entries in the matrix are equal,
	 * or {@code null} if any entries differ.
	 *
	 * @return the common expression value, or {@code null}
	 */
	public Expression<T> allMatch() {
		Expression<T> e = valueAt(0, 0);
		for (int i = 0; i < rowCount; i++) {
			if (rowDuplicates[i] < 0) {
				for (int j = 0; j < colCount; j++) {
					if (!e.equals(valueAt(i, j))) return null;
				}
			}
		}

		return e;
	}

	/**
	 * Returns the column values as an {@link IndexSequence} if the matrix can be
	 * represented as a flat sequence, or {@code null} otherwise.
	 *
	 * @return a column sequence, or {@code null}
	 */
	public IndexSequence columnSequence() { return null; }

	/**
	 * Returns an array of per-row expressions if every column in each row is identical
	 * (i.e., all columns match within each row), or {@code null} if any row has differing columns.
	 *
	 * @return per-row expressions, or {@code null}
	 */
	public Expression[] allColumnsMatch() {
		if ((long) rowCount * colCount > MAX_SEQUENCE_LENGTH)
			return null;

		Expression[] result = new Expression[rowCount];

		for (int i = 0; i < rowCount; i++) {
			result[i] = valueAt(i, 0);

			for (int j = 0; j < colCount; j++) {
				if (!result[i].equals(valueAt(i, j))) return null;
			}
		}

		return result;
	}

	/**
	 * Returns an expression that maps each row index to the unique non-zero column in
	 * that row, or {@code null} if any row has zero or more than one non-zero entry.
	 *
	 * @param rowIndex the row index expression used to parameterise the result
	 * @return an index expression, or {@code null}
	 */
	public Expression uniqueNonZeroOffset(Index rowIndex) {
		return uniqueMatchingOffset(rowIndex, e -> e.doubleValue().orElse(-1.0) != 0.0);
	}

	/**
	 * Returns an expression that maps each row index to the unique column satisfying the
	 * predicate in that row, or {@code null} if any row has zero or more than one matching entry.
	 *
	 * @param rowIndex  the row index expression used to parameterise the result
	 * @param predicate the predicate that identifies the target column
	 * @return an index expression, or {@code null}
	 */
	public Expression uniqueMatchingOffset(Index rowIndex, Predicate<Expression<?>> predicate) {
		Number matchingColumns[] = new Number[rowCount];

		for (int i = 0; i < rowCount; i++) {
			for (int j = 0; j < colCount; j++) {
				Expression e = valueAt(i, j);
				if (e == null) return null;

				if (predicate.test(e)) {
					if (matchingColumns[i] != null) return null;
					matchingColumns[i] = j;
				}
			}

			if (matchingColumns[i] == null) matchingColumns[i] = 0;
		}

		IndexSequence seq = ArrayIndexSequence.of(Integer.class, matchingColumns);
		return seq.getExpression(rowIndex);
	}

	/** {@inheritDoc} */
	@Override
	public Console console() {
		return Scope.console;
	}

	/**
	 * Evaluates the given expression as a flat {@link IndexSequence} over the combined
	 * (row, col) child index, or returns {@code null} if the expression cannot be sequenced.
	 *
	 * @param <T>  the expression type
	 * @param row  the row index
	 * @param col  the column index
	 * @param e    the expression to evaluate
	 * @return a flat sequence, or {@code null}
	 */
	protected static <T> IndexSequence sequence(Index row, Index col, Expression<T> e) {
		IndexChild child = Index.child(row, col);
		if (child.getLimit().isEmpty()) return null;

		IndexValues values = new IndexValues();
		values.put(child, 0);

		if (!e.isValue(values))
			return null;

		return e.sequence(child, child.getLimit().getAsLong(), MAX_SEQUENCE_LENGTH);
	}

	/**
	 * Creates an {@link ExpressionMatrix} for the given expression indexed by row and col.
	 * Returns a {@link SequenceMatrix} when the expression can be flattened to a sequence,
	 * an {@link ExplicitExpressionMatrix} when explicit expansion is feasible, or
	 * {@code null} when neither is possible.
	 *
	 * @param <T>        the expression type
	 * @param row        the row index
	 * @param col        the column index
	 * @param expression the expression to represent
	 * @return the matrix, or {@code null}
	 */
	public static <T> ExpressionMatrix<T> create(Index row, Index col, Expression<T> expression) {
		if (row.getLimit().isEmpty() || col.getLimit().isEmpty()) return null;

		IndexSequence seq = sequence(row, col, expression);

		if (seq != null) {
			return new SequenceMatrix<>(row, col, seq);
		} else if (Index.child(row, col, (long) Integer.MAX_VALUE) == null) {
			return null;
		} else if (row.getLimit().getAsLong() * col.getLimit().getAsLong() > maxMatrixSize) {
			return null;
		}

		return new ExplicitExpressionMatrix<>(row, col, expression);
	}

	/**
	 * Creates an {@link ExpressionMatrix} for the given expression and immediately applies
	 * a transformation function to all entries.
	 *
	 * @param <T>        the input expression type
	 * @param <O>        the output expression type
	 * @param row        the row index
	 * @param col        the column index
	 * @param expression the source expression
	 * @param function   the transformation to apply
	 * @return the transformed matrix, or {@code null}
	 */
	public static <T, O> ExpressionMatrix<O> create(Index row, Index col, Expression<T> expression,
												 Function<Expression<T>, Expression<O>> function) {
		ExpressionMatrix<T> matrix = create(row, col, expression);
		if (matrix == null) return null;

		return matrix.apply(function);
	}
}
