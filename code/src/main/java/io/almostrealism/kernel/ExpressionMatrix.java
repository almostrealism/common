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
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Function;
import java.util.function.Predicate;

public abstract class ExpressionMatrix<T> implements ConsoleFeatures {
	public static long MAX_SEQUENCE_LENGTH = 1 << 24;

	public static boolean enableMaskMatrix = true;
	public static boolean enableUnsequencedMatrices = false;
	public static long maxMatrixSize = (long) 10e7;

	protected final Index row;
	protected final Index col;
	protected final int rowCount;
	protected final int colCount;

	protected int rowDuplicates[];

	protected ExpressionMatrix(Index row, Index col) {
		rowCount = Math.toIntExact(row.getLimit().orElse(-1));
		colCount = Math.toIntExact(col.getLimit().orElse(-1));
		if (colCount < 0 || rowCount < 0)
			throw new IllegalArgumentException();

		this.row = row;
		this.col = col;
	}

	public Index getRow() { return row; }
	public Index getColumn() { return col; }
	public Index getIndex() { return Index.child(row, col); }

	public int getRowCount() { return rowCount; }
	public int getColumnCount() { return colCount; }
	public int getValueCount() { return -1; }

	public int[] getRowDuplicates() { return rowDuplicates; }

	public abstract Expression<T> valueAt(int i, int j);

	public <O> ExpressionMatrix<O> apply(Function<Expression<T>, Expression<O>> function) {
		return MatrixFunctionEvaluator.apply(this, function);
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

	public IndexSequence columnSequence() { return null; }

	public Expression[] allColumnsMatch() {
		Expression[] result = new Expression[rowCount];

		for (int i = 0; i < rowCount; i++) {
			result[i] = valueAt(i, 0);

			for (int j = 0; j < colCount; j++) {
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

		IndexSequence seq = ArrayIndexSequence.of(Integer.class, matchingColumns);
		return seq.getExpression(rowIndex);
	}

	@Override
	public Console console() {
		return Scope.console;
	}

	protected static <T> IndexSequence sequence(Index row, Index col, Expression<T> e) {
		IndexChild child = Index.child(row, col);
		if (child.getLimit().isEmpty()) return null;

		IndexValues values = new IndexValues();
		values.put(child, 0);

		if (!e.isValue(values))
			return null;

		return e.sequence(child, child.getLimit().getAsLong(), MAX_SEQUENCE_LENGTH);
	}

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

	public static <T, O> ExpressionMatrix<O> create(Index row, Index col, Expression<T> expression,
												 Function<Expression<T>, Expression<O>> function) {
		ExpressionMatrix<T> matrix = create(row, col, expression);
		if (matrix == null) return null;

		return matrix.apply(function);
	}
}
