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
import io.almostrealism.expression.Expression;

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
		populate(expression.getSimplified());
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

	protected void populate(Expression e) {
		seq = sequence(row, col, e);

		if (seq == null) {
			for (int i = 0; i < rowCount; i++) {
				rowDuplicates[i] = -1;
				boolean duplicate = true;

				for (int j = 0; j < colCount; j++) {
					matrix[i][j] = e.withIndex(row, i).withIndex(col, j).getSimplified();
					if (i == 0 || !valueAt(i, j).equals(valueAt((i - 1), j))) {
						duplicate = false;
					}
				}

				if (duplicate) {
					rowDuplicates[i] = rowDuplicates[i - 1] < 0 ? i - 1 : rowDuplicates[i - 1];
				}
			}
		} else {
			rowDuplicates[0] = -1;

			for (int i = 1; i < rowCount; i++) {
				rowDuplicates[i] = -1;
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
		}

		if (rowDuplicates[0] == 0) {
			throw new UnsupportedOperationException();
		}
	}

	protected Number sequenceValueAt(int i, int j) {
		return seq.valueAt(i * colCount + j);
	}

	public Expression<T> valueAt(int i, int j) {
		if (rowDuplicates.length <= i || rowDuplicates[i] == i) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[i] >= 0) {
			return valueAt(rowDuplicates[i], j);
		}

		return seq == null ? matrix[i][j] :
				(Expression) Constant.of(sequenceValueAt(i, j));
	}

	public <O> ExpressionMatrix<O> apply(Function<Expression<T>, Expression<O>> function) {
		FunctionEvaluator<O> evaluator = new FunctionEvaluator<>(function);
		IndexSequence resultSeq = evaluator.attemptSequence();

		if (resultSeq != null) {
			return new ExpressionMatrix<>(row, col, resultSeq, null, evaluator.resultRowDuplicates);
		}

		Expression result[][] = new Expression[rowCount][colCount];

		boolean rowDependent = false;
		int rowDuplicates[] = new int[rowCount];

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

		IndexSequence seq = IndexSequence.of(Integer.class, matchingColumns);
		return seq.getExpression(rowIndex);
	}

	protected class FunctionEvaluator<O> {
		private Function<Expression<T>, Expression<O>> function;
		private Expression<O> resultCache[];
		private int resultRowDuplicates[];
		private boolean multiRow;

		public FunctionEvaluator(Function<Expression<T>, Expression<O>> function) {
			this.function = function;

			if (seq != null && seq.getType() == Integer.class) {
				resultCache = new Expression[Math.toIntExact(seq.max() + 1)];
			}
		}

		public IndexSequence attemptSequence() {
			// Determine if the result is independent of the row and column
			if (resultCache != null) {
				Index index = new DefaultIndex("evalIndex", resultCache.length);
				Expression<?> e = function.apply((Expression) index);
				if (e.isValue(IndexValues.of(index))) {
					setupRowDuplicates(true);

					IndexSequence results = e.sequence(index, resultCache.length);
					if (results.getMod() == 1) {
						return IndexSequence.of(results.valueAt(0), seq.length());
					}

					return seq.map(i -> results.valueAt(i.intValue()));
				}
			}

			// Determine if the result is dependent on the row and column,
			// but no other variable
			Index index = Index.child(row, col);
			Expression<?> e = function.apply((Expression) index);
			if (e.isValue(IndexValues.of(index))) {
				setupRowDuplicates(false);

				int len = isMultiRow() ? rowCount * colCount : colCount;
				return e.sequence(index, len);
			}

			// Otherwise, the result is dependent on more information
			// than can be known ahead of time
			setupRowDuplicates(false);
			return null;
		}

		public boolean isMultiRow() { return resultCache == null || multiRow; }

		private void setupRowDuplicates(boolean rowIndependent) {
			resultRowDuplicates = new int[rowCount];

			for (int i = 0; i < rowCount; i++) {
				resultRowDuplicates[i] = rowIndependent ? rowDuplicates[i] : -1;
				if (i > 0 && resultRowDuplicates[i] < 0) multiRow = true;
			}
		}

		public Expression<O> valueAt(int i, int j) {
			if (resultCache == null) return function.apply(ExpressionMatrix.this.valueAt(i, j));

			int index = ExpressionMatrix.this.sequenceValueAt(i, j).intValue();
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

		if (!e.isValue(values))
			return null;

		return e.sequence(child, Math.toIntExact(child.getLimit().getAsLong()));
	}

	public static <T> ExpressionMatrix<T> create(Index row, Index col, Expression<T> expression) {
		if (row.getLimit().isEmpty() || col.getLimit().isEmpty()) return null;
		return new ExpressionMatrix<>(row, col, expression);
	}
}
