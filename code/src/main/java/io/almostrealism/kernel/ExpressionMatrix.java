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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Index;
import io.almostrealism.expression.IntegerConstant;

import java.util.OptionalDouble;
import java.util.function.Function;

public class ExpressionMatrix<T> {
	private final Expression[][] matrix;

	public ExpressionMatrix(Index row, Index col, Expression<T> expression) {
		int r = Math.toIntExact(row.getLimit().orElse(-1));
		int c = Math.toIntExact(col.getLimit().orElse(-1));
		if (c < 0 || r < 0)
			throw new IllegalArgumentException();

		this.matrix = new Expression[r][c];
		populate(row, col, expression);
	}

	protected ExpressionMatrix(Expression[][] matrix) {
		this.matrix = matrix;
	}

	protected void populate(Index row, Index col, Expression<T> e) {
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				matrix[i][j] = e.withIndex(row, i).withIndex(col, j).getSimplified();
			}
		}
	}

	public <O> ExpressionMatrix<O> apply(Function<Expression<T>, Expression<O>> function) {
		return apply(null, null, function);
	}

	public <O> ExpressionMatrix<O> apply(Index row, Index col, Function<Expression<T>, Expression<O>> function) {
		Expression result[][] = new Expression[matrix.length][matrix[0].length];

		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				result[i][j] = function.apply(matrix[i][j]);
				if (row != null) result[i][j] = result[i][j].withIndex(row, i);
				if (col != null) result[i][j] = result[i][j].withIndex(col, j);
				result[i][j] = result[i][j].getSimplified();
			}
		}

		return new ExpressionMatrix<>(result);
	}

	public Expression<T> allMatch() {
		Expression<T> e = matrix[0][0];
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (!e.equals(matrix[i][j])) return null;
			}
		}

		return e;
	}

	public Expression[] allColumnsMatch() {
		Expression[] result = new Expression[matrix[0].length];

		for (int i = 0; i < matrix[0].length; i++) {
			result[i] = matrix[i][0];

			for (int j = 0; j < matrix.length; j++) {
				if (!result[i].equals(matrix[i][j])) return null;
			}
		}

		return result;
	}

	public Expression uniqueNonZeroIndex(Index rowIndex) {
		Number nonZeroColumns[] = new Number[matrix[0].length];

		for (int i = 0; i < matrix[0].length; i++) {
			for (int j = 0; j < matrix.length; j++) {
				OptionalDouble v = matrix[j][i].doubleValue();

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
