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

public class SequenceMatrix<T> extends ExpressionMatrix<T> {
	private IndexSequence seq;

	protected SequenceMatrix(Index row, Index col,
							   IndexSequence seq) {
		super(row, col);
		this.seq = seq;
		populate();
	}

	protected SequenceMatrix(Index row, Index col,
							 IndexSequence seq,
							 int rowDuplicates[]) {
		super(row, col);
		this.seq = seq;
		this.rowDuplicates = rowDuplicates;
	}

	protected void populate() {
		if (seq == null) {
			throw new UnsupportedOperationException();
		}

		rowDuplicates = new int[rowCount];
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

		if (rowDuplicates[0] == 0) {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public int getValueCount() {
		if (seq.getType() != Double.class) return Math.toIntExact(seq.max() + 1);
		return super.getValueCount();
	}

	public IndexSequence getSequence() { return seq; }

	protected Number sequenceValueAt(int i, int j) {
		return seq.valueAt(i * colCount + j);
	}

	@Override
	public Expression<T> valueAt(int i, int j) {
		if (rowDuplicates.length <= i || rowDuplicates[i] == i) {
			throw new UnsupportedOperationException();
		}

		if (rowDuplicates[i] >= 0) {
			return valueAt(rowDuplicates[i], j);
		}

		return (Expression) Constant.of(sequenceValueAt(i, j));
	}

	@Override
	public IndexSequence columnSequence() {
		Number[] result = new Number[rowCount];

		for (int i = 0; i < rowCount; i++) {
			result[i] = sequenceValueAt(i, 0);

			for (int j = 0; j < colCount; j++) {
				if (!result[i].equals(sequenceValueAt(i, j))) return null;
			}
		}

		return ArrayIndexSequence.of(result);
	}
}
