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

import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Equals;
import io.almostrealism.expression.Expression;

import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public class ExpressionMatrix<T> {
	private final Index row;
	private final Index col;
	private final int rowCount;
	private final int colCount;

	private IndexSequence seq;
	private Expression[][] matrix;
	private int rowDuplicates[];

	private ExpressionMatrix(Index row, Index col) {
		rowCount = Math.toIntExact(row.getLimit().orElse(-1));
		colCount = Math.toIntExact(col.getLimit().orElse(-1));
		if (colCount < 0 || rowCount < 0)
			throw new IllegalArgumentException();

		this.row = row;
		this.col = col;
	}

	public ExpressionMatrix(Index row, Index col,
							Expression<T> expression) {
		this(row, col);
		this.matrix = new Expression[rowCount][colCount];
		this.rowDuplicates = new int[rowCount];
		populate(expression);
	}

	protected ExpressionMatrix(Index row, Index col,
							   IndexSequence seq,
							   Expression[][] matrix,
							   int[] rowDuplicates) {
		this(row, col);
		this.seq = seq;
		this.matrix = matrix;
		this.rowDuplicates = rowDuplicates;
	}

	protected void populate(Expression<T> e) {
		seq = sequence(row, col, e);

		if (seq == null) {
			for (int i = 0; i < rowCount; i++) {
				for (int j = 0; j < colCount; j++) {
					matrix[i][j] = e.withIndex(row, i).withIndex(col, j).getSimplified();
				}
			}
		} else {
			for (int i = 0; i < rowCount; i++) {
				rowDuplicates[i] = -1;
				boolean duplicate = true;

				for (int j = 0; j < colCount; j++) {
					Number v = seq.valueAt(i * colCount + j);

					if (i == 0 || !v.equals(seq.valueAt((i - 1) * colCount + j))) {
						duplicate = false;
					}
				}

				if (duplicate) {
					rowDuplicates[i] = rowDuplicates[i - 1] < 0 ? i - 1 : rowDuplicates[i - 1];
				}
			}
		}
	}

	protected Number sequenceValueAt(int i, int j) {
		return seq.valueAt(i * colCount + j);
	}

	public Expression<T> valueAt(int i, int j) {
		if (rowDuplicates.length <= i) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[i] >= 0) {
			return valueAt(rowDuplicates[i], j);
		}

		return seq == null ? matrix[i][j] :
				(Expression) Constant.of(sequenceValueAt(i, j));
	}

	public <O> ExpressionMatrix<O> apply(Function<Expression<T>, Expression<O>> function) {
		Expression result[][] = new Expression[rowCount][colCount];

		boolean rowDependent = false;
		int rowDuplicates[] = new int[rowCount];

		FunctionEvaluator<O> evaluator = new FunctionEvaluator<>(function);

		i: for (int i = 0; i < rowCount; i++) {
			if (!rowDependent && this.rowDuplicates[i] >= 0) {
				rowDuplicates[i] = this.rowDuplicates[i];
				continue i;
			} else {
				rowDuplicates[i] = -1;
			}

			j: for (int j = 0; j < colCount; j++) {
				result[i][j] = evaluator.valueAt(i, j);
				if (result[i][j] == null) continue j;

				if (row != null && result[i][j].contains(row)) {
					rowDependent = true;
					result[i][j] = result[i][j].withIndex(row, i);
				}

				if (col != null && result[i][j].contains(col)) {
					result[i][j] = result[i][j].withIndex(col, j);
				}

				result[i][j] = result[i][j].getSimplified();
			}
		}

		return new ExpressionMatrix<>(row, col, null, result, rowDuplicates);
	}

	public Expression<T> allMatch() {
		Expression<T> e = matrix[0][0];
		for (int i = 0; i < matrix.length; i++) {
			if (rowDuplicates[i] < 0) {
				for (int j = 0; j < matrix[i].length; j++) {
					if (!e.equals(valueAt(i, j))) return null;
				}
			}
		}

		return e;
	}

	public Expression[] allColumnsMatch() {
		Expression[] result = new Expression[matrix[0].length];

		for (int i = 0; i < matrix.length; i++) {
			result[i] = valueAt(i, 0);

			for (int j = 0; j < matrix[i].length; j++) {
				if (!result[i].equals(valueAt(i, j))) return null;
			}
		}

		return result;
	}

	public Expression uniqueNonZeroOffset(Index rowIndex) {
		return uniqueMatchingOffset(rowIndex, e -> e.doubleValue().orElse(-1.0) != 0.0);
	}

	public Expression uniqueMatchingOffset(Index rowIndex, Predicate<Expression<?>> predicate) {
		Number matchingColumns[] = new Number[matrix.length];

		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				Expression e = valueAt(i, j);
				if (e == null) return null;

				if (predicate.test(e)) {
					if (matchingColumns[i] != null) return null;
					matchingColumns[i] = j;
				}
			}

			if (matchingColumns[i] == null) matchingColumns[i] = 0;
		}

		IndexSequence seq = IndexSequence.of(matchingColumns);
		return seq.getExpression(rowIndex);
	}

	protected class FunctionEvaluator<O> {
		private Function<Expression<T>, Expression<O>> function;
		private Expression<O> resultCache[];

		public FunctionEvaluator(Function<Expression<T>, Expression<O>> function) {
			this.function = function;

			if (seq != null && seq.getType() == Integer.class) {
				resultCache = new Expression[Math.toIntExact(seq.max() + 1)];
			}
		}

		public Expression<O> valueAt(int i, int j) {
			if (resultCache == null) return function.apply(ExpressionMatrix.this.valueAt(i, j));

			int index = sequenceValueAt(i, j).intValue();
			if (resultCache[index] == null) {
				resultCache[index] = function.apply(ExpressionMatrix.this.valueAt(i, j));
			}

			return resultCache[index];
		}
	}

	protected static <T> IndexSequence sequence(Index row, Index col, Expression<T> e) {
		IndexChild child = Index.child(row, col);
		if (child.getLimit().isEmpty()) return null;

		IndexValues values = new IndexValues();
		values.put(child, 0);

		if (!e.isValue(values)) return null;
		return e.sequence(child, Math.toIntExact(child.getLimit().getAsLong()));
	}

	public static <T> ExpressionMatrix<T> create(Index row, Index col, Expression<T> expression) {
		if (row.getLimit().isEmpty() || col.getLimit().isEmpty()) return null;
		return new ExpressionMatrix<>(row, col, expression);
	}
}
