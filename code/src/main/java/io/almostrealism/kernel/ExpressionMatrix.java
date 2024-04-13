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
import io.almostrealism.expression.IntegerConstant;

import java.util.OptionalDouble;
import java.util.function.Function;

public class ExpressionMatrix<T> {
	private final Expression[][] matrix;
	private final int rowDuplicates[];

	public ExpressionMatrix(Index row, Index col, Expression<T> expression) {
		int r = Math.toIntExact(row.getLimit().orElse(-1));
		int c = Math.toIntExact(col.getLimit().orElse(-1));
		if (c < 0 || r < 0)
			throw new IllegalArgumentException();

		this.matrix = new Expression[r][c];
		this.rowDuplicates = new int[r];
		populate(row, col, expression);
	}

	protected ExpressionMatrix(Expression[][] matrix, int[] rowDuplicates) {
		this.matrix = matrix;
		this.rowDuplicates = rowDuplicates;
	}

	protected void populate(Index row, Index col, Expression<T> e) {
		IndexSequence seq = sequence(row, col, e);

		if (seq == null) {
			for (int i = 0; i < matrix.length; i++) {
				for (int j = 0; j < matrix[i].length; j++) {
					matrix[i][j] = e.withIndex(row, i).withIndex(col, j).getSimplified();
				}
			}
		} else {
			for (int i = 0; i < matrix.length; i++) {
				rowDuplicates[i] = -1;
				boolean duplicate = true;

				for (int j = 0; j < matrix[i].length; j++) {
					Number v = seq.valueAt(i * matrix[i].length + j);
					matrix[i][j] = Constant.of(v);

					if (i == 0 || !matrix[i][j].equals(valueAt(i - 1, j))) {
						duplicate = false;
					}
				}

				if (duplicate) {
					rowDuplicates[i] = rowDuplicates[i - 1] < 0 ? i - 1 : rowDuplicates[i - 1];
				}
			}
		}
	}

	protected IndexSequence sequence(Index row, Index col, Expression<T> e) {
		if (!(col instanceof DefaultIndex)) return null;

		IndexChild child = Index.child(row, (DefaultIndex) col);
		if (child.getLimit().isEmpty()) return null;

		IndexValues values = new IndexValues();
		values.put(child, 0);

		if (!e.isValue(values)) return null;
		return e.sequence(child, Math.toIntExact(child.getLimit().getAsLong()));
	}

	public Expression<T> valueAt(int row, int col) {
		if (rowDuplicates.length <= row) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[row] >= 0) {
			return matrix[rowDuplicates[row]][col];
		}

		return matrix[row][col];
	}

	public <O> ExpressionMatrix<O> apply(Function<Expression<T>, Expression<O>> function) {
		return apply(null, null, function);
	}

	public <O> ExpressionMatrix<O> apply(Index row, Index col, Function<Expression<T>, Expression<O>> function) {
		Expression result[][] = new Expression[matrix.length][matrix[0].length];

		int rowDuplicates[] = new int[matrix.length];

		i: for (int i = 0; i < matrix.length; i++) {
			if (row == null && this.rowDuplicates[i] >= 0) {
				rowDuplicates[i] = this.rowDuplicates[i];
				continue i;
			} else {
				rowDuplicates[i] = -1;
			}

			j: for (int j = 0; j < matrix[i].length; j++) {
				result[i][j] = function.apply(matrix[i][j]);
				if (result[i][j] == null) continue j;

				if (row != null) result[i][j] = result[i][j].withIndex(row, i);
				if (col != null) result[i][j] = result[i][j].withIndex(col, j);
				result[i][j] = result[i][j].getSimplified();
			}
		}

		return new ExpressionMatrix<>(result, rowDuplicates);
	}

	public Expression<T> allMatch() {
		Expression<T> e = matrix[0][0];
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (!e.equals(valueAt(i, j))) return null;
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

	public Expression uniqueNonZeroIndex(Index rowIndex) {
		Number nonZeroColumns[] = new Number[matrix.length];

		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				Expression e = valueAt(i, j);
				if (e == null) return null;

				OptionalDouble v = e.doubleValue();

				if (v.isEmpty() || v.getAsDouble() != 0.0) {
					if (nonZeroColumns[i] != null) return null;
					nonZeroColumns[i] = j;
				}
			}
		}

		IndexSequence seq = IndexSequence.of(nonZeroColumns);
		return seq.getExpression(rowIndex);
	}

	public static <T> ExpressionMatrix<T> create(Index row, Index col, Expression<T> expression) {
		if (row.getLimit().isEmpty() || col.getLimit().isEmpty()) return null;
		return new ExpressionMatrix<>(row, col, expression);
	}
}
