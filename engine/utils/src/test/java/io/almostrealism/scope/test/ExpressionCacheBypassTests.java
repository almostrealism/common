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

package io.almostrealism.scope.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.ExplicitExpressionMatrix;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ExpressionCache#bypass(Runnable)} and for its use while an
 * {@link ExplicitExpressionMatrix} is populated.
 *
 * <p>The entries of an explicit matrix exist only for index analysis, so building them
 * must leave the cache of the kernel being compiled untouched while still producing the
 * entries that direct substitution produces.</p>
 */
public class ExpressionCacheBypassTests extends TestSuiteBase {

	/** Number of rows in the analysed matrix. */
	private static final int ROWS = 6;

	/** Number of columns in the analysed matrix. */
	private static final int COLUMNS = 5;

	/**
	 * No cache is active while a bypassed task runs, and the cache that was active before
	 * is active again once it returns.
	 */
	@Test(timeout = 10000)
	public void bypassSuspendsAndRestoresActiveCache() {
		ExpressionCache cache = new ExpressionCache();

		cache.use(() -> {
			Assert.assertSame(cache, ExpressionCache.getCurrent());
			ExpressionCache.bypass(() -> Assert.assertNull(ExpressionCache.getCurrent()));
			Assert.assertSame(cache, ExpressionCache.getCurrent());
		});

		Assert.assertNull(ExpressionCache.getCurrent());
	}

	/**
	 * The cache that was active before a bypassed task is restored even when the task
	 * fails, and the failure reaches the caller.
	 */
	@Test(timeout = 10000)
	public void bypassRestoresCacheWhenTaskFails() {
		ExpressionCache cache = new ExpressionCache();

		cache.use(() -> {
			try {
				ExpressionCache.bypass(() -> {
					throw new IllegalStateException("bypassed task failed");
				});
				Assert.fail("The failure of the bypassed task should reach the caller");
			} catch (IllegalStateException e) {
				Assert.assertSame(cache, ExpressionCache.getCurrent());
			}
		});
	}

	/**
	 * The value-returning form of {@link ExpressionCache#bypass} runs its task with no
	 * cache active, returns the task's value, and restores the cache that was active.
	 */
	@Test(timeout = 10000)
	public void bypassReturnsTheSuppliedValue() {
		ExpressionCache cache = new ExpressionCache();

		cache.use(() -> {
			String value = ExpressionCache.bypass(() -> {
				Assert.assertNull(ExpressionCache.getCurrent());
				return "bypassed";
			});

			Assert.assertEquals("bypassed", value);
			Assert.assertSame(cache, ExpressionCache.getCurrent());
		});

		Assert.assertNull(ExpressionCache.getCurrent());
	}

	/**
	 * Populating an {@link ExplicitExpressionMatrix} while a compilation cache is active
	 * produces the entries of direct substitution without inserting any of them into that
	 * cache.
	 *
	 * <p>The same substitutions performed directly under an active cache do populate it,
	 * which shows that the entries are cache targets and that the compilation cache stays
	 * empty because of the bypass rather than because of the choice of expression.</p>
	 */
	@Test(timeout = 30000)
	public void matrixPopulationLeavesActiveCacheEmpty() {
		DefaultIndex row = new DefaultIndex("row", ROWS);
		DefaultIndex col = new DefaultIndex("col", COLUMNS);
		DefaultIndex free = new DefaultIndex("free");

		// The free index keeps every entry symbolic, so the matrix cannot be
		// reduced to an index sequence and has to be populated explicitly
		Expression<?> target = row.multiply(7).add(col).add(free)
				.divide(3).imod(5).getSimplified();

		Expression<?>[][] expected = new Expression<?>[ROWS][COLUMNS];
		ExpressionCache direct = new ExpressionCache();
		direct.use(() -> {
			for (int i = 0; i < ROWS; i++) {
				for (int j = 0; j < COLUMNS; j++) {
					expected[i][j] = target.withIndex(row, i).withIndex(col, j);
				}
			}
		});
		Assert.assertFalse("Matrix entries should be cache targets", direct.isEmpty());

		ExpressionCache compilation = new ExpressionCache();
		ExpressionMatrix<?> matrix = compilation.use(() -> ExpressionMatrix.create(row, col, target));

		Assert.assertTrue("The matrix should be populated explicitly",
				matrix instanceof ExplicitExpressionMatrix);
		Assert.assertTrue("Matrix entries should not enter the active cache", compilation.isEmpty());

		int[] duplicates = matrix.getRowDuplicates();
		Assert.assertEquals(ROWS, duplicates.length);
		for (int i = 0; i < ROWS; i++) {
			Assert.assertEquals("Row " + i + " should not be recorded as a duplicate", -1, duplicates[i]);
		}

		for (int i = 0; i < ROWS; i++) {
			for (int j = 0; j < COLUMNS; j++) {
				Assert.assertEquals(expected[i][j], matrix.valueAt(i, j));
			}
		}
	}

	/**
	 * Rows whose entries do not depend on the row index are recorded as duplicates of the
	 * first row, and {@link ExpressionMatrix#valueAt} follows the duplicate chain so that
	 * every row returns the first row's entries.
	 *
	 * <p>This exercises the row-deduplication path of population, which the fully symbolic
	 * matrix above deliberately avoids by making every row distinct.</p>
	 */
	@Test(timeout = 30000)
	public void rowIndependentEntriesDeduplicateRows() {
		DefaultIndex row = new DefaultIndex("row", ROWS);
		DefaultIndex col = new DefaultIndex("col", COLUMNS);
		DefaultIndex free = new DefaultIndex("free");

		// The entries depend on the column and a free index but not on the row, so every
		// row duplicates the first; the free index keeps the matrix explicit
		Expression<?> target = col.add(free).imod(5).getSimplified();

		ExpressionMatrix<?> matrix = new ExpressionCache().use(() ->
				ExpressionMatrix.create(row, col, target));

		Assert.assertTrue("The matrix should be populated explicitly",
				matrix instanceof ExplicitExpressionMatrix);

		// Row 0 has no predecessor, and every later row must point directly at
		// row 0 rather than at its immediate predecessor
		int[] duplicates = matrix.getRowDuplicates();
		Assert.assertEquals(ROWS, duplicates.length);
		Assert.assertEquals(-1, duplicates[0]);
		for (int i = 1; i < ROWS; i++) {
			Assert.assertEquals("Row " + i + " should be recorded as a duplicate of row 0", 0, duplicates[i]);
		}

		for (int i = 0; i < ROWS; i++) {
			for (int j = 0; j < COLUMNS; j++) {
				Assert.assertEquals("Every row should resolve to the first row's entry",
						matrix.valueAt(0, j), matrix.valueAt(i, j));
			}
		}
	}
}
