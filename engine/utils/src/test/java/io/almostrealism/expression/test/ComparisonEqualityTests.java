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

package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Greater;
import io.almostrealism.expression.Less;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.ExpressionCache;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that structural equality of comparison expressions distinguishes strict
 * from inclusive comparisons, so that caches keyed by {@link Expression} never hand
 * one back in place of the other.
 */
public class ComparisonEqualityTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/**
	 * {@code a > b} and {@code a >= b} have identical operands and rendering shape but
	 * differ at equality; they must not compare equal.
	 */
	@Test(timeout = 30000)
	public void strictAndInclusiveGreaterDiffer() {
		Expression<?> strict = Greater.of(kernel(), e(5));
		Expression<?> inclusive = Greater.of(kernel(), e(5), true);
		Expression<?> strictAgain = Greater.of(kernel(), e(5));

		Assert.assertNotEquals(strict, inclusive);
		Assert.assertNotEquals(inclusive, strict);
		Assert.assertEquals(strict, strictAgain);
		Assert.assertEquals(strict.hashCode(), strictAgain.hashCode());
	}

	/**
	 * The same holds for {@code a < b} and {@code a <= b}.
	 */
	@Test(timeout = 30000)
	public void strictAndInclusiveLessDiffer() {
		Expression<?> strict = Less.of(kernel(), e(5));
		Expression<?> inclusive = Less.of(kernel(), e(5), true);

		Assert.assertNotEquals(strict, inclusive);
		Assert.assertEquals(inclusive, Less.of(kernel(), e(5), true));
	}

	/**
	 * A structural expression cache asked for the inclusive comparison after seeing the
	 * strict one must return the inclusive one, not the cached strict instance.
	 */
	@Test(timeout = 30000)
	public void expressionCacheKeepsThemApart() {
		ExpressionCache cache = new ExpressionCache();
		Expression<?> strict = cache.get(Greater.of(kernel(), e(5)));
		Expression<?> inclusive = cache.get(Greater.of(kernel(), e(5), true));

		Assert.assertNotSame(strict, inclusive);
		Assert.assertTrue(((Greater) inclusive).getExpression(lang).contains(">="));
		Assert.assertFalse(((Greater) strict).getExpression(lang).contains(">="));
	}
}
