/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.kernel.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.ExplicitExpressionMatrix;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the on-demand evaluation of {@link ExplicitExpressionMatrix} entries.
 *
 * <p>An explicit matrix created from an expression substitutes each entry only when it
 * is requested, and populates its full array only when its row-duplicate map is
 * requested. These tests check that the entries and the row-duplicate map produced
 * that way are exactly those of direct substitution, whatever order the entries are
 * read in, and that a matrix over a very large index space costs nothing until its
 * entries are read.</p>
 */
public class ExplicitExpressionMatrixTests extends TestSuiteBase {

	/** Number of rows in the small analysed matrices. */
	private static final int ROWS = 6;

	/** Number of columns in the small analysed matrices. */
	private static final int COLUMNS = 5;

	/**
	 * Entries read one at a time, in an order that changes row on every read, are the
	 * entries of direct substitution.
	 *
	 * <p>Reading column by column defeats the retained row substitution, so every read
	 * substitutes a different row from the one before it.</p>
	 */
	@Test(timeout = 30000)
	public void entriesReadInAnyOrderMatchDirectSubstitution() {
		DefaultIndex row = new DefaultIndex("row", ROWS);
		DefaultIndex col = new DefaultIndex("col", COLUMNS);
		DefaultIndex free = new DefaultIndex("free");

		// The free index keeps every entry symbolic, so the matrix is explicit
		Expression<?> target = row.multiply(7).add(col).add(free)
				.divide(3).imod(5).getSimplified();

		ExpressionMatrix<?> matrix = ExpressionMatrix.create(row, col, target);
		Assert.assertTrue("The matrix should be explicit",
				matrix instanceof ExplicitExpressionMatrix);

		for (int j = 0; j < COLUMNS; j++) {
			for (int i = 0; i < ROWS; i++) {
				Assert.assertEquals(target.withIndex(row, i).withIndex(col, j), matrix.valueAt(i, j));
			}
		}

		for (int i = 0; i < ROWS; i++) {
			for (int j = 0; j < COLUMNS; j++) {
				Assert.assertEquals(target.withIndex(row, i).withIndex(col, j), matrix.valueAt(i, j));
			}
		}
	}

	/**
	 * The row-duplicate map is computed when it is requested, after entries have already
	 * been read on demand, and it agrees with those entries: a target that does not depend
	 * on the row makes every later row a duplicate of the first, and the entries read
	 * after population are unchanged.
	 */
	@Test(timeout = 30000)
	public void rowDuplicatesAreComputedWhenRequested() {
		DefaultIndex row = new DefaultIndex("row", ROWS);
		DefaultIndex col = new DefaultIndex("col", COLUMNS);
		DefaultIndex free = new DefaultIndex("free");

		Expression<?> target = col.add(free).imod(5).getSimplified();

		ExpressionMatrix<?> matrix = ExpressionMatrix.create(row, col, target);
		Assert.assertTrue("The matrix should be explicit",
				matrix instanceof ExplicitExpressionMatrix);

		Expression<?> before = matrix.valueAt(ROWS - 1, COLUMNS - 1);
		Assert.assertEquals(target.withIndex(row, ROWS - 1).withIndex(col, COLUMNS - 1), before);

		int[] duplicates = matrix.getRowDuplicates();
		Assert.assertEquals(ROWS, duplicates.length);
		Assert.assertEquals(-1, duplicates[0]);
		for (int i = 1; i < ROWS; i++) {
			Assert.assertEquals("Row " + i + " should be recorded as a duplicate of row 0", 0, duplicates[i]);
		}

		Assert.assertEquals(before, matrix.valueAt(ROWS - 1, COLUMNS - 1));
		for (int i = 0; i < ROWS; i++) {
			for (int j = 0; j < COLUMNS; j++) {
				Assert.assertEquals(target.withIndex(row, i).withIndex(col, j), matrix.valueAt(i, j));
			}
		}
	}

	/**
	 * Reading entries on demand and populating the matrix while a compilation cache is
	 * active leaves that cache empty, as population during construction did: the entries
	 * exist only for analysis and are built with the cache bypassed.
	 */
	@Test(timeout = 30000)
	public void onDemandEntriesLeaveActiveCacheEmpty() {
		DefaultIndex row = new DefaultIndex("row", ROWS);
		DefaultIndex col = new DefaultIndex("col", COLUMNS);
		DefaultIndex free = new DefaultIndex("free");

		Expression<?> target = row.multiply(7).add(col).add(free)
				.divide(3).imod(5).getSimplified();

		ExpressionCache compilation = new ExpressionCache();
		compilation.use(() -> {
			ExpressionMatrix<?> matrix = ExpressionMatrix.create(row, col, target);

			for (int i = 0; i < ROWS; i++) {
				for (int j = 0; j < COLUMNS; j++) {
					Assert.assertNotNull(matrix.valueAt(i, j));
				}
			}

			Assert.assertEquals(ROWS, matrix.getRowDuplicates().length);
		});

		Assert.assertTrue("Matrix entries should not enter the active cache", compilation.isEmpty());
	}

	/**
	 * An explicit matrix over the largest index space that {@link ExpressionMatrix#create}
	 * accepts is created, and answers {@link ExpressionMatrix#allColumnsMatch()}, without
	 * substituting its sixteen million entries: the columns of the first row differ, so
	 * only the entries needed to see that are evaluated.
	 *
	 * <p>The timeout is what this test asserts. Substituting every entry up front takes
	 * tens of seconds for this matrix, while evaluating the handful of entries the
	 * check reads takes a fraction of a second.</p>
	 */
	@Test(timeout = 10000)
	public void largeMatrixIsNotMaterialized() {
		int size = 4096;
		DefaultIndex row = new DefaultIndex("row", size);
		DefaultIndex col = new DefaultIndex("col", size);
		DefaultIndex free = new DefaultIndex("free");

		Expression<?> target = row.multiply(size).add(col).add(free)
				.divide(3).imod(size).getSimplified();

		ExpressionMatrix<?> matrix = ExpressionMatrix.create(row, col, target);
		Assert.assertTrue("The matrix should be explicit",
				matrix instanceof ExplicitExpressionMatrix);
		Assert.assertEquals(size, matrix.getRowCount());
		Assert.assertEquals(size, matrix.getColumnCount());

		Assert.assertNull("The columns of each row differ", matrix.allColumnsMatch());
		Assert.assertEquals(target.withIndex(row, size - 1).withIndex(col, size - 1),
				matrix.valueAt(size - 1, size - 1));
	}
}
