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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Regression test for the {@link KernelTraversalOperationGenerator} lookup-table
 * cache. The cache is supposed to amortise repeated structurally-equal expression
 * lookups within a single {@link KernelStructureContext}: the second simplification
 * of an equivalent sum should reuse the cached array reference and skip the
 * per-index simplification loop entirely.
 *
 * <p>Until this branch's fix landed, the cache was keyed by the rendered expression
 * string in an {@link java.util.IdentityHashMap} — i.e. lookups required reference
 * equality on freshly-allocated {@link String} keys, so they never hit. Switching
 * the maps to {@link java.util.HashMap} keyed by {@link Expression} directly makes
 * the cache use structural equality
 * ({@link Expression#equals(Object)} → {@link Expression#compare(Expression)}),
 * which delegates to cached structural metrics (type, depth, node count, hash)
 * for O(1) early rejection.</p>
 */
public class KernelTraversalCacheTests extends TestSuiteBase implements ExpressionFeatures {

	/** Width at/above which {@code generateReordering} rewrites; matches {@code minimumChildren}. */
	private static final int REORDER_THRESHOLD = 16;

	/** Reused so {@link Expression#withIndex(io.almostrealism.kernel.Index, int)} matches the guards. */
	private final KernelIndex index = new KernelIndex();

	/**
	 * Builds a {@link Sum} of {@code n} single-index-masked terms: term {@code j} evaluates
	 * to {@code j + 1} when the kernel index equals {@code j}, and to {@code 0} otherwise.
	 * Wide masked sums are the shape that triggers the reordering rewrite and exercises
	 * the per-context lookup-table cache.
	 *
	 * @param n the number of masked terms
	 * @return the constructed sum expression
	 */
	private Expression<?> maskedSum(int n) {
		Expression<?>[] terms = new Expression[n];
		for (int j = 0; j < n; j++) {
			terms[j] = Mask.of(index.eq(j), e((double) (j + 1)));
		}
		return Sum.of(terms);
	}

	/**
	 * Creates a {@link KernelStructureContext} backed by a real
	 * {@link KernelTraversalOperationGenerator} configured for a fixed kernel count.
	 *
	 * @param count the (fixed) kernel element count
	 * @return a context exposing a live traversal provider
	 */
	private KernelStructureContext traversalContext(int count) {
		Function<Producer<?>, ArrayVariable<?>> variableFactory =
				p -> new ArrayVariable<>(Double.class, "reorderTable",
						(Expression) new IntegerConstant(count));

		KernelTraversalOperationGenerator generator =
				new KernelTraversalOperationGenerator(count, true, variableFactory);

		return new KernelStructureContext() {
			@Override
			public OptionalLong getKernelMaximum() { return OptionalLong.of(count); }

			@Override
			public KernelSeriesProvider getSeriesProvider() { return null; }

			@Override
			public KernelTraversalProvider getTraversalProvider() { return generator; }
		};
	}

	/**
	 * A second simplification of a structurally equivalent masked sum in the same
	 * context must hit the cache and skip the per-index simplify loop. Both
	 * results must still be non-{@link Sum} references (the lookup-table variable),
	 * and the warm run must be at least 2x faster than the cold one — well within
	 * the headroom the cache provides at these modest dimensions.
	 */
	@Test(timeout = 60000)
	public void reorderingCacheAmortisesRepeatedSimplify() {
		int n = REORDER_THRESHOLD + 8;
		int count = n * 4;
		KernelStructureContext ctx = traversalContext(count);

		boolean previous = Sum.enableGenerateReordering;
		Sum.enableGenerateReordering = true;

		try {
			// Cold simplify - builds and caches the lookup table.
			long t0 = System.nanoTime();
			Expression<?> firstResult = maskedSum(n).simplify(ctx, 0);
			long firstNs = System.nanoTime() - t0;

			// Warm simplify - same context, same structural form, should hit the cache.
			t0 = System.nanoTime();
			Expression<?> secondResult = maskedSum(n).simplify(ctx, 0);
			long secondNs = System.nanoTime() - t0;

			log("cold simplify=" + firstNs / 1000 + "us warm simplify=" + secondNs / 1000
					+ "us ratio=" + String.format("%.2f", secondNs / (double) firstNs));

			Assert.assertFalse("cold simplify should produce a non-Sum reference",
					firstResult instanceof Sum);
			Assert.assertFalse("warm simplify should produce a non-Sum reference",
					secondResult instanceof Sum);
			Assert.assertTrue("warm simplify should be at least 2x faster than cold (cold="
					+ firstNs / 1000 + "us warm=" + secondNs / 1000 + "us)",
					secondNs * 2 < firstNs);
		} finally {
			Sum.enableGenerateReordering = previous;
		}
	}
}
