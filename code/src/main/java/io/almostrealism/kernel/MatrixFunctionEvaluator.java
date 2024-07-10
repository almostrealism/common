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
			return e.sequence(index, len, Integer.MAX_VALUE);
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

	@Override
	public Console console() { return Scope.console; }
}