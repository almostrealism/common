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
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Applies a transformation function to every entry of an {@link ExpressionMatrix} and
 * produces the resulting matrix, selecting the most compact representation available.
 *
 * <p>The evaluator attempts representations in the following order of preference:</p>
 * <ol>
 *   <li>{@link MaskMatrix} — when the function produces {@link Mask} expressions and the
 *       mask portion reduces to a constant or sequence ({@link #getMaskResult()}).</li>
 *   <li>{@link SequenceMatrix} — when the composed expression can be evaluated as a flat
 *       {@link IndexSequence} ({@link #attemptSequence()}).</li>
 *   <li>{@link ExplicitExpressionMatrix} — full expansion of all {@code (row, col)} pairs
 *       (only when {@link ExpressionMatrix#enableUnsequencedMatrices} is {@code true}).</li>
 * </ol>
 *
 * <p>The specialised subclass {@link SequenceFunctionEvaluator} overrides
 * {@link #attemptSequence()} to take advantage of the backing sequence when the input
 * is a {@link SequenceMatrix}.</p>
 *
 * @param <I> the input expression type
 * @param <O> the output expression type
 */
public class MatrixFunctionEvaluator<I, O> implements ConsoleFeatures {
	/** The input matrix to be transformed. */
	private ExpressionMatrix<I> input;

	/** The transformation function applied to each matrix entry. */
	private Function<Expression<I>, Expression<O>> function;

	/** Per-row duplicate map for the result matrix, computed during {@link #setupRowDuplicates}. */
	private int resultRowDuplicates[];

	/**
	 * {@code true} if the result has at least one distinct row beyond the first,
	 * i.e. the result depends on the row index.
	 */
	protected boolean multiRow;

	/**
	 * Creates a {@link MatrixFunctionEvaluator} for the given input matrix and function.
	 *
	 * @param input    the input matrix
	 * @param function the transformation to apply
	 */
	public MatrixFunctionEvaluator(ExpressionMatrix<I> input, Function<Expression<I>, Expression<O>> function) {
		this.input = input;
		this.function = function;
	}

	/**
	 * Returns the input matrix.
	 *
	 * @return the input matrix
	 */
	public ExpressionMatrix<I> getInput() { return input; }

	/**
	 * Returns the transformation function.
	 *
	 * @return the function applied to each matrix entry
	 */
	public Function<Expression<I>, Expression<O>> getFunction() {
		return function;
	}

	/**
	 * Attempts to evaluate the composed expression as a flat {@link IndexSequence}.
	 * Returns the sequence when the function result depends only on the combined (row, col)
	 * index, or {@code null} if it depends on additional state.
	 *
	 * @return the result sequence, or {@code null}
	 */
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

	/**
	 * Returns {@code true} if the result matrix has distinct rows (depends on the row index).
	 *
	 * @return {@code true} if the result is row-dependent
	 */
	public boolean isMultiRow() { return true; }

	/**
	 * Initialises the per-row duplicate map for the result.
	 *
	 * @param rowIndependent when {@code true}, copies row-duplicate information from the input;
	 *                       when {@code false}, marks all rows as distinct
	 */
	protected void setupRowDuplicates(boolean rowIndependent) {
		resultRowDuplicates = new int[input.getRowCount()];

		for (int i = 0; i < resultRowDuplicates.length; i++) {
			resultRowDuplicates[i] = rowIndependent ? input.getRowDuplicates()[i] : -1;
			if (i > 0 && resultRowDuplicates[i] < 0) multiRow = true;
		}
	}

	/**
	 * Returns the result row-duplicate map, or {@code null} if {@link #setupRowDuplicates}
	 * has not yet been called.
	 *
	 * @return the per-row duplicate map
	 */
	public int[] getResultRowDuplicates() { return resultRowDuplicates; }

	/**
	 * Returns the transformed expression at the given row and column.
	 *
	 * @param i the row index
	 * @param j the column index
	 * @return the transformed expression at {@code (i, j)}
	 */
	public Expression<O> valueAt(int i, int j) {
		return function.apply(input.valueAt(i, j));
	}

	/**
	 * Attempts to produce a {@link MaskMatrix} result when the function outputs {@link Mask}
	 * expressions. Returns {@code null} when mask optimisation is disabled or the mask
	 * portion cannot be represented as a matrix.
	 *
	 * @return a mask matrix, or {@code null}
	 */
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

	/**
	 * Evaluates the transformation and returns the most compact {@link ExpressionMatrix}
	 * available: mask result, sequence result, or full explicit expansion (if enabled).
	 *
	 * @return the transformed matrix, or {@code null} if none can be produced
	 */
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

	/** {@inheritDoc} */
	@Override
	public Console console() { return Scope.console; }

	/**
	 * Creates the appropriate {@link MatrixFunctionEvaluator} subclass for the given input
	 * and function, selecting {@link SequenceFunctionEvaluator} for {@link SequenceMatrix} inputs.
	 *
	 * @param <I>      the input expression type
	 * @param <O>      the output expression type
	 * @param input    the input matrix
	 * @param function the transformation function
	 * @return the evaluator
	 */
	public static <I, O> MatrixFunctionEvaluator<I, O> create(ExpressionMatrix<I> input,
															  Function<Expression<I>, Expression<O>> function) {
		if (input instanceof SequenceMatrix) {
			return new SequenceFunctionEvaluator<>((SequenceMatrix<I>) input, function);
		} else {
			return new MatrixFunctionEvaluator<>(input, function);
		}
	}

	/**
	 * Convenience method that creates an evaluator and immediately returns its result.
	 *
	 * @param <I>      the input expression type
	 * @param <O>      the output expression type
	 * @param input    the input matrix
	 * @param function the transformation function
	 * @return the transformed matrix, or {@code null}
	 */
	public static <I, O> ExpressionMatrix<O> apply(ExpressionMatrix<I> input, Function<Expression<I>, Expression<O>> function) {
		return create(input, function).getResult();
	}
}
