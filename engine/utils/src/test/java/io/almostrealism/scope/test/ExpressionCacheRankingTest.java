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
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests that {@link ExpressionCache#getFrequentExpressions()} ranks the expressions the
 * cache holds as they stand when it is asked, although it keeps a ranking and returns it
 * again for as long as no lookup has changed the cache.
 */
public class ExpressionCacheRankingTest extends TestSuiteBase {

	/** Number of lookups that leave an expression seen often enough to be ranked. */
	private static final int FREQUENT = 4;

	/**
	 * Asking for the ranking again, with no lookup in between, returns the same ranking.
	 */
	@Test(timeout = 10000)
	public void rankingIsReturnedAgainUntilTheNextLookup() {
		ExpressionCache cache = new ExpressionCache();
		Expression<?> first = chain("a", 16);

		lookUp(cache, first, FREQUENT);
		List<Expression<?>> ranking = cache.getFrequentExpressions();

		Assert.assertEquals(List.of(first), ranking);
		Assert.assertSame(ranking, cache.getFrequentExpressions());
	}

	/**
	 * A lookup made after the ranking was returned is reflected by the next ranking, while
	 * the ranking returned before it is left as it was.
	 */
	@Test(timeout = 10000)
	public void rankingFollowsLookupsMadeAfterIt() {
		ExpressionCache cache = new ExpressionCache();
		Expression<?> first = chain("a", 16);
		Expression<?> second = chain("b", 20);

		lookUp(cache, first, FREQUENT);
		List<Expression<?>> before = cache.getFrequentExpressions();

		lookUp(cache, second, FREQUENT);
		List<Expression<?>> after = cache.getFrequentExpressions();

		Assert.assertEquals(List.of(first), before);
		Assert.assertEquals("the costlier expression saves more, so it ranks first",
				List.of(second, first), after);
	}

	/**
	 * An expression that has not been seen often enough is ranked once further lookups
	 * make it frequent, even though the earlier lookups of it already left it in the cache.
	 */
	@Test(timeout = 10000)
	public void rankingFollowsLookupsOfCachedExpressions() {
		ExpressionCache cache = new ExpressionCache();
		Expression<?> expression = chain("c", 16);

		lookUp(cache, expression, 2);
		Assert.assertEquals(List.of(), cache.getFrequentExpressions());

		lookUp(cache, expression, 2);
		Assert.assertEquals(List.of(expression), cache.getFrequentExpressions());
	}

	/**
	 * The ranking is shared by everything that asks for it, so it cannot be modified.
	 */
	@Test(timeout = 10000)
	public void rankingCannotBeModified() {
		ExpressionCache cache = new ExpressionCache();
		lookUp(cache, chain("d", 16), FREQUENT);
		List<Expression<?>> ranking = cache.getFrequentExpressions();

		try {
			ranking.clear();
			Assert.fail("the shared ranking should not be modifiable");
		} catch (UnsupportedOperationException e) {
			Assert.assertEquals(1, cache.getFrequentExpressions().size());
		}
	}

	/**
	 * Looks the given expression up in the cache the given number of times. The first
	 * lookup inserts it, and every later one records another occurrence of it.
	 *
	 * @param cache      the cache
	 * @param expression the expression, which must be one the cache keeps
	 * @param lookups    the number of lookups
	 */
	private static void lookUp(ExpressionCache cache, Expression<?> expression, int lookups) {
		Assert.assertTrue("the expression should be one the cache keeps",
				ScopeSettings.isExpressionCacheTarget(expression));

		for (int i = 0; i < lookups; i++) {
			cache.get(expression);
		}
	}

	/**
	 * Returns an expression of alternating products and sums over distinct references,
	 * which keeps every operation as its own node, so its compute cost grows with its length.
	 *
	 * @param name   the prefix of the reference names
	 * @param length the number of operations
	 * @return the expression
	 */
	private static Expression<?> chain(String name, int length) {
		Expression<?> chain = new StaticReference<>(Double.class, name + "0");

		for (int i = 1; i <= length; i++) {
			Expression<?> next = new StaticReference<>(Double.class, name + i);
			chain = i % 2 == 1 ? chain.multiply(next) : chain.add(next);
		}

		return chain;
	}
}
