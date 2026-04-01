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

import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.collect.CollectionExpressionAdapter;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mask;

import java.util.function.Function;

/**
 * A specialised {@link MatrixFunctionEvaluator} optimised for {@link SequenceMatrix} inputs.
 *
 * <p>When the function depends only on the backing sequence value (not on the full
 * {@code (row, col)} index), this evaluator maps the function over the set of distinct
 * sequence values and then remaps the result back through the original index mapping.
 * This avoids redundant function evaluations when many matrix entries share the same
 * underlying value.</p>
 *
 * <p>If value-level mapping fails (e.g. the function depends on additional state), the
 * evaluator falls back to the standard {@link MatrixFunctionEvaluator#attemptSequence()}.</p>
 *
 * @param <I> the input expression type
 * @param <O> the output expression type
 */
public class SequenceFunctionEvaluator<I, O> extends MatrixFunctionEvaluator<I, O> {
	/** The number of distinct values in the input sequence. */
	private int valueCount;

	/** Cache of pre-computed results for each distinct sequence value, or {@code null} if unused. */
	private Expression<O> resultCache[];

	/**
	 * Creates a {@link SequenceFunctionEvaluator} for the given {@link SequenceMatrix} and function.
	 *
	 * @param input    the sequence-backed input matrix
	 * @param function the transformation to apply
	 */
	public SequenceFunctionEvaluator(SequenceMatrix<I> input, Function<Expression<I>, Expression<O>> function) {
		super(input, function);
		valueCount = input.getValueCount();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>First attempts value-level mapping: if the function result depends only on a
	 * temporary value index (not on row/col), the function is applied once per distinct
	 * value and the results are remapped through the backing sequence. Falls back to the
	 * standard sequence attempt if value-level mapping fails.</p>
	 */
	@Override
	public IndexSequence attemptSequence() {
		// Determine if the result is independent of the row and column
		if (valueCount >= 0) {
			IndexSequence seq = ((SequenceMatrix) getInput()).getSequence();
			Index index = CollectionExpressionAdapter.generateTemporaryIndex(valueCount);
			Expression<?> e = getFunction().apply((Expression) index);
			if (e.isValue(IndexValues.of(index))) {
				setupRowDuplicates(true);

				if (valueCount > 100000000) {
					alert("Creating sequence with length " + valueCount);
				}

				IndexSequence results = e.sequence(index, valueCount, ExpressionMatrix.MAX_SEQUENCE_LENGTH);
				if (results == null) return super.attemptSequence();

				if (results.getMod() == 1) {
					return ArrayIndexSequence.of(results.valueAt(0), seq.lengthLong());
				}

				resultCache = new Expression[valueCount];
				return seq.map(i -> results.valueAt(i.intValue()));
			} else if (e instanceof Mask && e.getChildren().get(0).isValue(IndexValues.of(index))) {
				warn("Detected mask which could have been sequenced");
			}
		}

		return super.attemptSequence();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns {@code true} if the result cache has not been populated (value-level
	 * mapping was not used) or if the input matrix has distinct rows.</p>
	 */
	public boolean isMultiRow() { return resultCache == null || multiRow; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>When the result cache is populated (value-level mapping was used), looks up the
	 * cached result for the sequence value at {@code (i, j)} rather than reapplying the
	 * function.</p>
	 */
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
