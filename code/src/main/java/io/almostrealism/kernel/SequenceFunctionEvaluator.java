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
import io.almostrealism.expression.Mask;

import java.util.function.Function;

public class SequenceFunctionEvaluator<I, O> extends MatrixFunctionEvaluator<I, O> {
	private int valueCount;
	private Expression<O> resultCache[];

	public SequenceFunctionEvaluator(SequenceMatrix<I> input, Function<Expression<I>, Expression<O>> function) {
		super(input, function);
		valueCount = input.getValueCount();
	}

	@Override
	public IndexSequence attemptSequence() {
		// Determine if the result is independent of the row and column
		if (valueCount >= 0) {
			IndexSequence seq = ((SequenceMatrix) getInput()).getSequence();
			Index index = new DefaultIndex("evalIndex", valueCount);
			Expression<?> e = getFunction().apply((Expression) index);
			if (e.isValue(IndexValues.of(index))) {
				setupRowDuplicates(true);

				IndexSequence results = e.sequence(index, valueCount);
				if (results.getMod() == 1) {
					return ArrayIndexSequence.of(results.valueAt(0), seq.lengthLong());
				}

				resultCache = new Expression[valueCount];
				return seq.map(i -> results.valueAt(i.intValue()));
			} else if (e instanceof Mask && e.getChildren().get(0).isValue(IndexValues.of(index))) {
				log("Detected mask which could have been sequenced");
			}
		}

		return super.attemptSequence();
	}

	public boolean isMultiRow() { return resultCache == null || multiRow; }

	@Override
	public Expression<O> valueAt(int i, int j) {
		if (resultCache == null) return getFunction().apply(getInput().valueAt(i, j));

		int index = ((SequenceMatrix) getInput()).sequenceValueAt(i, j).intValue();
		if (resultCache[index] == null) {
			resultCache[index] = super.valueAt(i, j);
		}

		return resultCache[index];
	}
}
