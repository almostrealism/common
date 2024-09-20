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

public class ExplicitExpressionMatrix<T> extends ExpressionMatrix<T> {

	private Expression[][] matrix;

	protected ExplicitExpressionMatrix(Index row, Index col,
							   			Expression[][] matrix,
									   	int[] rowDuplicates) {
		super(row, col);
		this.matrix = matrix;
		this.rowDuplicates = rowDuplicates;
	}

	protected ExplicitExpressionMatrix(Index row, Index col, Expression<T> e) {
		super(row, col);
		populate(e.getSimplified());
	}

	protected void populate(Expression e) {
		matrix = new Expression[rowCount][colCount];
		rowDuplicates = new int[rowCount];

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

		if (rowDuplicates[0] == 0) {
			throw new UnsupportedOperationException();
		}
	}

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
