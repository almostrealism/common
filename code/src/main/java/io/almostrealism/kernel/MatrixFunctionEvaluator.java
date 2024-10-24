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

import io.almostrealism.collect.CollectionExpressionAdapter;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mask;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

public class MatrixFunctionEvaluator<I, O> implements ConsoleFeatures {
	private ExpressionMatrix<I> input;
	private Function<Expression<I>, Expression<O>> function;

	private int resultRowDuplicates[];
	protected boolean multiRow;

	public MatrixFunctionEvaluator(ExpressionMatrix<I> input, Function<Expression<I>, Expression<O>> function) {
		this.input = input;
		this.function = function;
	}

	public ExpressionMatrix<I> getInput() { return input; }

	public Function<Expression<I>, Expression<O>> getFunction() {
		return function;
	}

	public IndexSequence attemptSequence() {
		// Determine if the result is dependent on the row and column,
		// but no other variable
		Index index = Index.child(input.getRow(), input.getColumn());
		Expression<?> e = function.apply((Expression) index);
		if (e.isValue(IndexValues.of(index))) {
			setupRowDuplicates(false);

			long len = isMultiRow() ? index.getLimit().orElse(-1) : input.getColumnCount();
			return e.sequence(index, len, ExpressionMatrix.MAX_SEQUENCE_LENGTH);
		}

		// Otherwise, the result is dependent on more information
		// than can be known ahead of time
		setupRowDuplicates(false);
		return null;
	}

	public boolean isMultiRow() { return true; }

	protected void setupRowDuplicates(boolean rowIndependent) {
		resultRowDuplicates = new int[input.getRowCount()];

		for (int i = 0; i < resultRowDuplicates.length; i++) {
			resultRowDuplicates[i] = rowIndependent ? input.getRowDuplicates()[i] : -1;
			if (i > 0 && resultRowDuplicates[i] < 0) multiRow = true;
		}
	}

	public int[] getResultRowDuplicates() { return resultRowDuplicates; }

	public Expression<O> valueAt(int i, int j) {
		return function.apply(input.valueAt(i, j));
	}

	public ExpressionMatrix<O> getMaskResult() {
		if (!ExpressionMatrix.enableMaskMatrix) return null;

		int valueCount = input.getValueCount();
		Index index = valueCount > 0 ?
				CollectionExpressionAdapter.generateTemporaryIndex(valueCount)
				: CollectionExpressionAdapter.generateTemporaryIndex();
		Expression<O> e = function.apply((Expression) index);
		if (!(e instanceof Mask)) return null;

		ExpressionMatrix<?> mask = apply(getInput(),
				in -> function.apply(in).getChildren().get(0));
		if (mask == null) return null;

		Optional<Boolean> m = Optional.ofNullable(mask.allMatch())
				.map(Expression::booleanValue)
				.orElse(Optional.empty());
		if (!m.orElse(true)) {
			return new SequenceMatrix<>(input.getRow(), input.getColumn(),
					ArrayIndexSequence.of(0, getInput().getIndex().getLimit().getAsLong()),
					IntStream.range(0, input.getRowCount()).map(i -> i == 0 ? -1 : 0).toArray());
		}

		ExpressionMatrix<O> value = apply(getInput(),
				in -> (Expression<O>) function.apply(in).getChildren().get(1));
		if (value == null || m.orElse(false)) return value;

		return new MaskMatrix<>(input.getRow(), input.getColumn(), mask, value);
	}

	public ExpressionMatrix<O> getResult() {
		ExpressionMatrix<O> maskResult = getMaskResult();
		if (maskResult != null) return maskResult;

		IndexSequence resultSeq = attemptSequence();

		if (resultSeq != null) {
			return new SequenceMatrix<>(input.getRow(), input.getColumn(),
					resultSeq, resultRowDuplicates);
		} else if (!ExpressionMatrix.enableUnsequencedMatrices) {
			return null;
		}

		log("Expanding full ExpressionMatrix (" + input.getRowCount() + "x" + input.getColumnCount() + ")");

		Expression result[][] = new Expression[input.getRowCount()][input.getColumnCount()];

		boolean rowDependent = false;
		int rowDuplicates[] = new int[input.getRowCount()];

		i: for (int i = 0; i < rowDuplicates.length; i++) {
			if (!rowDependent && input.getRowDuplicates()[i] >= 0) {
				rowDuplicates[i] = input.getRowDuplicates()[i];
				continue i;
			} else {
				rowDuplicates[i] = -1;
			}

			j: for (int j = 0; j < input.getColumnCount(); j++) {
				result[i][j] = valueAt(i, j);
				if (result[i][j] == null) continue j;

				if (input.getRow() != null && result[i][j].containsIndex(input.getRow())) {
					rowDependent = true;
					result[i][j] = result[i][j].withIndex(input.getRow(), i);
				}

				if (input.getColumn() != null && result[i][j].containsIndex(input.getColumn())) {
					result[i][j] = result[i][j].withIndex(input.getColumn(), j);
				}

				result[i][j] = result[i][j].getSimplified();
			}
		}

		return new ExplicitExpressionMatrix<>(input.getRow(), input.getColumn(), result, rowDuplicates);
	}

	@Override
	public Console console() { return Scope.console; }

	public static <I, O> MatrixFunctionEvaluator<I, O> create(ExpressionMatrix<I> input,
															  Function<Expression<I>, Expression<O>> function) {
		if (input instanceof SequenceMatrix) {
			return new SequenceFunctionEvaluator<>((SequenceMatrix<I>) input, function);
		} else {
			return new MatrixFunctionEvaluator<>(input, function);
		}
	}

	public static <I, O> ExpressionMatrix<O> apply(ExpressionMatrix<I> input, Function<Expression<I>, Expression<O>> function) {
		return create(input, function).getResult();
	}
}