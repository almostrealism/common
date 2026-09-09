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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Greater;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.hardware.kernel.KernelSeriesCache;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link KernelSeriesCache} keys its conversions structurally (by
 * {@link Expression#equals(Object)}), so that a separately built but structurally
 * equal expression is answered from the cache rather than re-derived, and that
 * expressions distinguished only by non-child state (such as a strict versus
 * inclusive {@link Greater} comparison) are cached independently rather than
 * colliding.
 */
public class KernelSeriesCacheTest extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * A second, separately constructed expression that is structurally equal to
	 * (but not the same object as) one already converted must be answered from the
	 * cache: the exact same result object is returned, and no fresh derivation is
	 * needed.
	 */
	@Test(timeout = 30000)
	public void structurallyEqualExpressionsShareOneConversion() {
		int savedMin = KernelSeriesCache.minNodeCountMatch;
		KernelSeriesCache.minNodeCountMatch = 1;

		try {
			int count = 64;
			KernelSeriesCache cache = new KernelSeriesCache(
					new OperationMetadata("kernelSeriesCacheTest", "kernelSeriesCacheTest"),
					count, true, null);

			Expression<?> a = kernel().multiply(3).add(5);
			Expression<?> b = kernel().multiply(3).add(5);
			Assert.assertNotSame("The two expressions must be separate instances for this test to be meaningful", a, b);
			Assert.assertEquals("The two expressions must be structurally equal for this test to be meaningful", a, b);

			Expression resultA = cache.getSeries(a, kernel());
			Expression resultB = cache.getSeries(b, kernel());

			Assert.assertNotSame("A derivable expression must be converted to a different form", a, resultA);
			Assert.assertSame("A structurally equal expression must hit the cache and reuse the prior conversion",
					resultA, resultB);

			for (int i = 0; i < count; i++) {
				Assert.assertEquals("at " + i, 3 * i + 5,
						resultA.value(new IndexValues().put(kernel(), i)).intValue());
			}
		} finally {
			KernelSeriesCache.minNodeCountMatch = savedMin;
		}
	}

	/**
	 * A repeated request for an expression that was already given up on (no closed
	 * form found) must be answered from the failure set without a second attempt,
	 * returning the original expression unchanged.
	 */
	@Test(timeout = 30000)
	public void matchFailuresAreNotRetried() {
		int savedMin = KernelSeriesCache.minNodeCountMatch;
		KernelSeriesCache.minNodeCountMatch = 1;

		try {
			int count = 64;
			KernelSeriesCache cache = new KernelSeriesCache(
					new OperationMetadata("kernelSeriesCacheTest", "kernelSeriesCacheTest"),
					count, true, null);

			// Two independent, unrelated kernel positions multiplied together never
			// reduces to a single closed form recognised by this provider.
			Expression<?> irregular = kernel().imod(37).add(1).multiply(kernel().divide(11).imod(5).add(1));

			Expression firstAttempt = cache.getSeries(irregular, kernel());
			Expression secondAttempt = cache.getSeries(irregular, kernel());

			Assert.assertSame("An expression with no closed form must be returned unchanged", irregular, firstAttempt);
			Assert.assertSame("A repeated failure must be short-circuited rather than re-attempted",
					irregular, secondAttempt);
		} finally {
			KernelSeriesCache.minNodeCountMatch = savedMin;
		}
	}

	/**
	 * A strict comparison ({@code a > b}) and its inclusive counterpart
	 * ({@code a >= b}) must be cached independently: caching by structural equality
	 * only remains safe because {@link Greater#compare(Expression)} distinguishes
	 * the {@code orEqual} flag, so a regression there would show up here as one
	 * comparison's converted form being handed back for the other.
	 */
	@Test(timeout = 30000)
	public void strictAndInclusiveComparisonsAreCachedIndependently() {
		int savedMin = KernelSeriesCache.minNodeCountMatch;
		KernelSeriesCache.minNodeCountMatch = 1;

		try {
			int count = 16;
			KernelSeriesCache cache = new KernelSeriesCache(
					new OperationMetadata("kernelSeriesCacheTest", "kernelSeriesCacheTest"),
					count, true, null);

			Expression<?> strict = Greater.of(kernel(), e(5));
			Expression<?> inclusive = Greater.of(kernel(), e(5), true);

			Index index = kernel();
			Expression strictResult = cache.getSeries(strict, index);
			Expression inclusiveResult = cache.getSeries(inclusive, index);

			Assert.assertNotSame("Strict and inclusive comparisons must not share a cache entry",
					strictResult, inclusiveResult);

			for (int i = 0; i < count; i++) {
				int expectedStrict = i > 5 ? 1 : 0;
				int expectedInclusive = i >= 5 ? 1 : 0;
				Assert.assertEquals("strict at " + i, expectedStrict,
						strictResult.value(new IndexValues().put(kernel(), i)).intValue());
				Assert.assertEquals("inclusive at " + i, expectedInclusive,
						inclusiveResult.value(new IndexValues().put(kernel(), i)).intValue());
			}
		} finally {
			KernelSeriesCache.minNodeCountMatch = savedMin;
		}
	}
}
