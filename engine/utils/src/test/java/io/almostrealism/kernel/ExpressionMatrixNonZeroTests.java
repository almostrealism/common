/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;
import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@link ExpressionMatrix#uniqueNonZeroOffset(io.almostrealism.sequence.Index)}
 * answers exactly as testing every entry expression for a non-zero value does, for the
 * matrices that answer from their raw numbers without building an expression per entry: a
 * {@link SequenceMatrix}, and a {@link MaskMatrix} whose mask is one.
 *
 * <p>The matrices are built directly from explicit values (this test lives in the matrix
 * package for access to the sequence constructor) so that the analysed entries are known
 * exactly.</p>
 */
public class ExpressionMatrixNonZeroTests extends TestSuiteBase {

	/** Row index of the analysed matrices. */
	private final DefaultIndex row = new DefaultIndex("row", 3);

	/** Column index of the analysed matrices. */
	private final DefaultIndex col = new DefaultIndex("col", 3);

	/** One non-zero entry per row, on the diagonal (columns 0, 1, 2). */
	private static final int[][] ONE_HOT = {
			{ 2, 0, 0 },
			{ 0, 1, 0 },
			{ 0, 0, 1 }
	};

	/** No zero entries. */
	private static final int[][] DENSE = {
			{ 1, 2, 3 },
			{ 4, 5, 6 },
			{ 7, 8, 9 }
	};

	/** A zero in the last diagonal position, which {@link #ONE_HOT} selects. */
	private static final int[][] SPARSE = {
			{ 5, 0, 7 },
			{ 0, 3, 2 },
			{ 1, 6, 0 }
	};

	/** A 0/1 mask selecting the diagonal, valid for a mask stored as floating-point values. */
	private static final int[][] IDENTITY = {
			{ 1, 0, 0 },
			{ 0, 1, 0 },
			{ 0, 0, 1 }
	};

	/** Every row identical, with its single non-zero entry in the middle column. */
	private static final int[][] DUPLICATE_ROWS = {
			{ 0, 4, 0 },
			{ 0, 4, 0 },
			{ 0, 4, 0 }
	};

	/**
	 * A sequence matrix with exactly one non-zero entry per row yields that entry's column
	 * for every row.
	 */
	@Test(timeout = 30000)
	public void sequenceMatrixUniqueNonZero() {
		ExpressionMatrix<?> matrix = sequenceOf(ONE_HOT);

		Expression<?> offset = matrix.uniqueNonZeroOffset(row);
		Assert.assertNotNull("Each row has exactly one non-zero entry", offset);
		assertMatchesExpressionTest(matrix, offset);
		assertOffsets(offset, 0, 1, 2);
	}

	/**
	 * A sequence matrix with several non-zero entries in a row has no unique offset.
	 */
	@Test(timeout = 30000)
	public void sequenceMatrixSeveralNonZero() {
		ExpressionMatrix<?> matrix = sequenceOf(DENSE);

		Assert.assertNull(matrix.uniqueNonZeroOffset(row));
		assertMatchesExpressionTest(matrix, null);
	}

	/**
	 * A mask that selects one column per row over data that is never zero yields the
	 * selected column for every row.
	 */
	@Test(timeout = 30000)
	public void maskOverNonZeroData() {
		ExpressionMatrix<?> matrix = new MaskMatrix<>(row, col, sequenceOf(ONE_HOT), sequenceOf(DENSE));

		Expression<?> offset = matrix.uniqueNonZeroOffset(row);
		Assert.assertNotNull("The mask selects exactly one non-zero entry per row", offset);
		assertMatchesExpressionTest(matrix, offset);
		assertOffsets(offset, 0, 1, 2);
	}

	/**
	 * Where the mask selects a zero data entry the row has no non-zero entry, which maps
	 * to column zero exactly as the per-entry expression test does.
	 */
	@Test(timeout = 30000)
	public void maskOverDataWithZeros() {
		ExpressionMatrix<?> matrix = new MaskMatrix<>(row, col, sequenceOf(ONE_HOT), sequenceOf(SPARSE));

		Expression<?> offset = matrix.uniqueNonZeroOffset(row);
		Assert.assertNotNull("Each row has at most one non-zero entry", offset);
		assertMatchesExpressionTest(matrix, offset);
		assertOffsets(offset, 0, 1, 0);
	}

	/**
	 * A mask that selects every column over data that is never zero has no unique offset.
	 */
	@Test(timeout = 30000)
	public void maskSelectingSeveralColumns() {
		ExpressionMatrix<?> matrix = new MaskMatrix<>(row, col, sequenceOf(DENSE), sequenceOf(DENSE));

		Assert.assertNull(matrix.uniqueNonZeroOffset(row));
		assertMatchesExpressionTest(matrix, null);
	}

	/**
	 * Rows that repeat the row before them are answered through the duplicate chain, and
	 * the raw numbers found there agree with the entry expressions.
	 */
	@Test(timeout = 30000)
	public void sequenceMatrixDuplicateRows() {
		ExpressionMatrix<?> matrix = sequenceOf(DUPLICATE_ROWS);

		Expression<?> offset = matrix.uniqueNonZeroOffset(row);
		Assert.assertNotNull("Each row has exactly one non-zero entry", offset);
		assertMatchesExpressionTest(matrix, offset);
		assertOffsets(offset, 1, 1, 1);
	}

	/**
	 * A mask stored as floating-point values is not decided from its raw numbers; it
	 * falls back to the entry expressions and still selects the same columns.
	 */
	@Test(timeout = 30000)
	public void doubleMaskFallsBackToExpressions() {
		ExpressionMatrix<?> matrix = new MaskMatrix<>(row, col,
				sequenceOf(Double.class, IDENTITY), sequenceOf(DENSE));

		Expression<?> offset = matrix.uniqueNonZeroOffset(row);
		Assert.assertNotNull("The mask selects exactly one non-zero entry per row", offset);
		assertMatchesExpressionTest(matrix, offset);
		assertOffsets(offset, 0, 1, 2);
	}

	/** Creates a sequence matrix holding the given integer entries. */
	private SequenceMatrix<Integer> sequenceOf(int[][] entries) {
		return sequenceOf(Integer.class, entries);
	}

	/** Creates a sequence matrix holding the given entries, stored as the given number type. */
	private <N extends Number> SequenceMatrix<N> sequenceOf(Class<N> type, int[][] entries) {
		Number[] values = new Number[entries.length * entries[0].length];
		for (int i = 0; i < entries.length; i++) {
			for (int j = 0; j < entries[i].length; j++) {
				values[i * entries[i].length + j] = type == Double.class
						? (Number) Double.valueOf(entries[i][j]) : (Number) Integer.valueOf(entries[i][j]);
			}
		}

		return new SequenceMatrix<>(row, col, ArrayIndexSequence.of(type, values));
	}

	/**
	 * Checks that the offset equals the one obtained by testing every entry expression
	 * with {@code doubleValue().orElse(-1.0) != 0.0}.
	 */
	private void assertMatchesExpressionTest(ExpressionMatrix<?> matrix, Expression<?> offset) {
		Expression<?> expected = matrix.uniqueMatchingOffset(row,
				e -> e.doubleValue().orElse(-1.0) != 0.0);
		Assert.assertEquals(expected, offset);
	}

	/** Checks the column the offset expression selects for each row. */
	private void assertOffsets(Expression<?> offset, int... columns) {
		for (int i = 0; i < columns.length; i++) {
			Assert.assertEquals(columns[i], offset.withIndex(row, i).getSimplified().intValue().getAsInt());
		}
	}
}
