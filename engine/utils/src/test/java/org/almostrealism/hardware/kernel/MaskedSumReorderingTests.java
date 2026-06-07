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
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Investigation tests for the {@code generateReordering} branch in
 * {@link Sum#simplify(KernelStructureContext, int)}.
 *
 * <p>When a {@link Sum} is simplified in a context that exposes a
 * {@link KernelTraversalProvider} and every child is
 * {@link Expression#isSingleIndexMasked() single-index masked}, {@code Sum.simplify}
 * either rewrites the sum via {@link KernelTraversalProvider#generateReordering(Expression)}
 * (when {@link Sum#enableGenerateReordering} is {@code true}) or throws
 * {@link UnsupportedOperationException} (the current default, used as a "this needs
 * attention" landmine).</p>
 *
 * <p>The sparse-Jacobian deltas produced by the subset/concat/product gradient
 * computations create exactly this shape: a {@link Sum} of {@link Mask}ed terms whose
 * guard is an {@code index == constant} comparison. These tests exist to answer three
 * questions about the reordering feature without depending on the cache/ordering state
 * that makes the failure intermittent in the full pipeline:</p>
 * <ol>
 *   <li><b>Is it invoked under the right conditions?</b> {@link #throwsWhenDisabledForSmallMaskedSum()}
 *       shows the branch fires (and the landmine throws) even for a two-term sum that
 *       reordering would not actually transform &mdash; the RoPE-gradient case.</li>
 *   <li><b>Does it work?</b> {@link #passthroughIsCorrectBelowThreshold()} and
 *       {@link #reorderedTableContentsAreCorrect()} verify the values produced (or that
 *       would be precomputed into the lookup table) match the un-reordered sum.</li>
 *   <li><b>Is it useful?</b> {@link #reorderingCollapsesWideMaskedSum()} measures the
 *       emitted-expression size with and without reordering for a wide masked sum.</li>
 * </ol>
 */
public class MaskedSumReorderingTests extends TestSuiteBase implements ExpressionFeatures {

	/** Width (number of distinct child nodes) at/above which {@code generateReordering} rewrites. */
	private static final int REORDER_THRESHOLD = 16;

	/** Language operations used to render expressions to strings for size assertions. */
	private final LanguageOperations lang = new LanguageOperationsStub();

	/** Reused so {@link Expression#withIndex(io.almostrealism.kernel.Index, int)} matches the guards. */
	private final KernelIndex index = new KernelIndex();

	/**
	 * Builds a {@link Sum} of {@code n} single-index-masked terms: term {@code j} evaluates
	 * to {@code j + 1} when the kernel index equals {@code j}, and to {@code 0} otherwise.
	 * This mirrors the structure of a sparse-Jacobian row produced by a gradient computation.
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
	 * Creates a {@link KernelStructureContext} whose {@link KernelTraversalProvider} is a real
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
	 * Confirms the masked-sum branch is reachable and, with reordering disabled (the default),
	 * throws for a small two-term sum &mdash; the exact RoPE-gradient shape that fails in CI.
	 * Reordering would not transform a two-term sum (below {@link #REORDER_THRESHOLD}), so the
	 * throw is a pure gate, not a consequence of an unimplemented rewrite.
	 */
	@Test(timeout = 30000)
	public void throwsWhenDisabledForSmallMaskedSum() {
		boolean previous = Sum.enableGenerateReordering;
		Sum.enableGenerateReordering = false;

		try {
			Expression<?> sum = maskedSum(2);
			Assert.assertTrue("expected a Sum of masked terms", sum instanceof Sum);

			sum.simplify(traversalContext(2), 0);
			Assert.fail("expected UnsupportedOperationException from the masked-sum branch");
		} catch (UnsupportedOperationException expected) {
			// landmine fired as designed
		} finally {
			Sum.enableGenerateReordering = previous;
		}
	}

	/**
	 * With reordering enabled but the sum below the rewrite threshold, the branch must fall
	 * through to a normal sum and preserve semantics: at kernel index {@code k} the value is
	 * {@code k + 1} for each masked position.
	 */
	@Test(timeout = 30000)
	public void passthroughIsCorrectBelowThreshold() {
		boolean previous = Sum.enableGenerateReordering;
		Sum.enableGenerateReordering = true;

		try {
			int n = 4;
			Expression<?> simplified = maskedSum(n).simplify(traversalContext(n), 0);

			for (int k = 0; k < n; k++) {
				double actual = simplified.withIndex(index, k).getSimplified()
						.doubleValue().orElseThrow();
				Assert.assertEquals("position " + k, k + 1, actual, 1e-9);
			}
		} finally {
			Sum.enableGenerateReordering = previous;
		}
	}

	/**
	 * Verifies the values that {@code generateReordering} precomputes into the lookup table
	 * (one per kernel index) match the un-reordered masked sum, for a wide sum at/above the
	 * rewrite threshold. This validates correctness of what the table would contain.
	 */
	@Test(timeout = 30000)
	public void reorderedTableContentsAreCorrect() {
		int n = REORDER_THRESHOLD + 4;
		Expression<?> sum = maskedSum(n);

		for (int k = 0; k < n; k++) {
			double actual = sum.withIndex(index, k).getSimplified().doubleValue().orElseThrow();
			Assert.assertEquals("position " + k, k + 1, actual, 1e-9);
		}
	}

	/**
	 * End-to-end correctness: compiles and executes a real computation whose per-element
	 * expression is a wide masked sum, so {@code generateReordering} fires during the actual
	 * compile pipeline and materialises a {@link KernelTraversalOperation} lookup table. The
	 * output is compared against the same computation compiled with table generation disabled
	 * (via {@link KernelTraversalOperationGenerator#enableGeneration}), which falls through to
	 * the inlined sum. Both must equal the oracle {@code output[i] == i + 1}.
	 *
	 * <p>This closes the gap left by the expression-level tests, which validate the table
	 * <em>contents</em> symbolically but not the compiled-and-executed kernel.</p>
	 */
	@Test(timeout = 120000)
	public void reorderedComputationMatchesInlinedEndToEnd() {
		int n = REORDER_THRESHOLD + 8;

		boolean previousReorder = Sum.enableGenerateReordering;
		boolean previousGeneration = KernelTraversalOperationGenerator.enableGeneration;
		Sum.enableGenerateReordering = true;

		try {
			// Reference: reordering branch taken, but lookup-table generation suppressed,
			// so the masked sum is emitted inline.
			KernelTraversalOperationGenerator.enableGeneration = false;
			PackedCollection inlined = evaluateMaskedSumComputation(n, "inlined");

			// Subject: lookup-table generation active, exercising the materialised table.
			KernelTraversalOperationGenerator.enableGeneration = true;
			PackedCollection reordered = evaluateMaskedSumComputation(n, "reordered");

			for (int i = 0; i < n; i++) {
				Assert.assertEquals("inlined[" + i + "]", i + 1, inlined.toDouble(i), 1e-9);
				Assert.assertEquals("reordered[" + i + "]", i + 1, reordered.toDouble(i), 1e-9);
			}
		} finally {
			Sum.enableGenerateReordering = previousReorder;
			KernelTraversalOperationGenerator.enableGeneration = previousGeneration;
		}
	}

	/**
	 * Builds and evaluates a one-dimensional computation of length {@code n} whose value at
	 * each kernel index is the {@link #maskedSum(int) masked sum}. By construction the result
	 * is {@code [1, 2, ..., n]}.
	 *
	 * @param n   the output length and number of masked terms
	 * @param tag a unique name suffix so distinct variants are not conflated by program reuse
	 * @return the evaluated output collection
	 */
	private PackedCollection evaluateMaskedSumComputation(int n, String tag) {
		TraversalPolicy shape = shape(n);
		CollectionExpression expression =
				DefaultCollectionExpression.create(shape, idx -> maskedSum(n));
		return new DefaultTraversableExpressionComputation("maskedSumE2E_" + tag, shape, expression)
				.get().evaluate();
	}

	/**
	 * With reordering enabled and the sum at/above the threshold, the rewrite must fire: the
	 * result is no longer a {@link Sum}, and its emitted expression is dramatically smaller
	 * than the inlined sum. This quantifies the usefulness of the optimization.
	 */
	@Test(timeout = 30000)
	public void reorderingCollapsesWideMaskedSum() {
		boolean previous = Sum.enableGenerateReordering;
		Sum.enableGenerateReordering = true;

		try {
			int n = REORDER_THRESHOLD + 8;
			Expression<?> sum = maskedSum(n);
			int inlinedSize = sum.getExpression(lang).length();

			Expression<?> simplified = sum.simplify(traversalContext(n), 0);
			int reorderedSize = simplified.getExpression(lang).length();

			log("inlined masked-sum chars=" + inlinedSize + " reordered chars=" + reorderedSize);

			Assert.assertFalse("reordering should replace the Sum with a lookup reference",
					simplified instanceof Sum);
			Assert.assertTrue("reordered expression should be smaller than the inlined sum",
					reorderedSize < inlinedSize);
		} finally {
			Sum.enableGenerateReordering = previous;
		}
	}

	// ---------------------------------------------------------------------
	// Trade-off landscape investigation
	//
	// The tests above answer "does it work" and "does it shrink the kernel body."
	// The tests below answer "when is it worth doing." They emit measurements
	// rather than asserting on absolute timings (the latter are environment
	// dependent), but each one asserts a single qualitative invariant so that
	// silent regressions in the cost model are caught.
	//
	// The cost of {@code generateReordering} (see
	// {@link KernelTraversalOperationGenerator#generateReordering(Expression)})
	// has two independent drivers:
	//   1. {@code n}     - the number of masked children in the Sum.
	//                      This sets the per-index simplification cost.
	//   2. {@code count} - the kernel element count.
	//                      The generator simplifies the expression once per
	//                      kernel index, so total cost is O(count * simplify(n)).
	//
	// Master never hit this path (the projection computations used
	// Conditional.direct() to avoid Mask conversion); on this branch the
	// path is reachable and {@code count} is decoupled from {@code n}, which is
	// the failure mode for convDelta-style gradients where {@code count} is
	// large (output_shape * input_shape) but each Sum has a modest {@code n}.
	// ---------------------------------------------------------------------

	/**
	 * A {@link KernelStructureContext} whose {@link KernelTraversalProvider} is {@code null}.
	 * In this context {@link Sum#simplify(KernelStructureContext, int)} skips the
	 * reordering branch entirely and falls through to the inlined sum, regardless of
	 * {@link Sum#enableGenerateReordering}. Used as the baseline for cost comparisons.
	 *
	 * @return a context with no traversal provider
	 */
	private KernelStructureContext fallthroughContext() {
		return new KernelStructureContext() {
			@Override
			public OptionalLong getKernelMaximum() { return OptionalLong.empty(); }

			@Override
			public KernelSeriesProvider getSeriesProvider() { return null; }

			@Override
			public KernelTraversalProvider getTraversalProvider() { return null; }
		};
	}

	/**
	 * Builds a {@link Sum} of {@code outerN} terms where each term is a single-index-masked
	 * <em>index-dependent</em> body: {@code Mask.of(index.eq(j), (j+1) * (idx+1) * (idx+2) *
	 * ... * (idx+bodyDepth))}.
	 *
	 * <p>The body is constructed as a product chain involving the kernel index so that it
	 * does not fold to a constant during local simplification. This is necessary because the
	 * naive "body is a sum of constants" construction collapses to a single constant before
	 * reordering even runs, hiding the body-complexity cost.</p>
	 *
	 * <p>This mirrors what arises when the chain rule contracts through a sparse projection
	 * Jacobian: each entry of the contracted Sum is the gradient of one masked output and
	 * carries non-constant arithmetic that must be re-simplified once per kernel index by
	 * {@code generateReordering}.</p>
	 *
	 * @param outerN    number of masked outer terms
	 * @param bodyDepth number of index-dependent factors multiplied into each mask body
	 * @return the constructed sum
	 */
	private Expression<?> maskedSumWithBody(int outerN, int bodyDepth) {
		Expression<?>[] terms = new Expression[outerN];
		for (int j = 0; j < outerN; j++) {
			Expression<?> body = e((double) (j + 1));
			for (int k = 0; k < bodyDepth; k++) {
				body = body.multiply((Expression<Double>) index.add(e(k + 1)).toDouble());
			}
			terms[j] = Mask.of(index.eq(j), body);
		}
		return Sum.of(terms);
	}

	/**
	 * Times {@code sumFactory.get().simplify(ctxFactory.apply(...), 0)} {@code iters} times,
	 * each iteration with a freshly built sum and a freshly built context (so the per-context
	 * lookup-table cache does not skew repeated measurements), and returns the minimum
	 * elapsed time in nanoseconds. Minimum is used rather than mean to suppress GC/JIT
	 * jitter; this is a relative measurement, not a benchmark.
	 *
	 * @param sumFactory builds a fresh sum each iteration
	 * @param ctxFactory builds a fresh context each iteration (passed the sum it will simplify)
	 * @param iters      number of timed iterations
	 * @return minimum elapsed nanoseconds across iterations
	 */
	private long minSimplifyNs(Supplier<Expression<?>> sumFactory,
							   Function<Expression<?>, KernelStructureContext> ctxFactory,
							   int iters) {
		long best = Long.MAX_VALUE;
		for (int i = 0; i < iters; i++) {
			Expression<?> sum = sumFactory.get();
			KernelStructureContext ctx = ctxFactory.apply(sum);
			long t0 = System.nanoTime();
			sum.simplify(ctx, 0);
			long elapsed = System.nanoTime() - t0;
			if (elapsed < best) best = elapsed;
		}
		return best;
	}

	/**
	 * Sweeps the width {@code n} of a flat masked sum with {@code count == n} and reports,
	 * for each width, the emitted-expression size and the {@code simplify} cost both with
	 * and without reordering.
	 *
	 * <p>Expected shape of the data:</p>
	 * <ul>
	 *   <li>Inlined emitted-size grows linearly in {@code n}.</li>
	 *   <li>Reordered emitted-size is roughly constant (a single array reference) above
	 *       {@link #REORDER_THRESHOLD}.</li>
	 *   <li>Inlined simplify time grows ~linearly in {@code n}.</li>
	 *   <li>Reordered simplify time grows ~quadratically in {@code n} when {@code count == n}
	 *       (per-index simplification of an {@code O(n)} expression done {@code n} times).</li>
	 * </ul>
	 *
	 * <p>This is the test to read first when reasoning about whether the optimization is
	 * profitable as a function of width alone.</p>
	 */
	@Test(timeout = 180000)
	@TestDepth(2)
	public void widthSweepCompileCostAndSize() {
		int[] widths = { 2, 4, 8, 16, 32, 64, 128, 256, 512 };

		boolean previous = Sum.enableGenerateReordering;
		int previousBudget = ScopeSettings.maxReorderingBudget;
		Sum.enableGenerateReordering = true;
		ScopeSettings.maxReorderingBudget = Integer.MAX_VALUE;

		try {
			// JIT warmup. Both paths are exercised at a representative width.
			for (int i = 0; i < 3; i++) {
				maskedSum(64).simplify(fallthroughContext(), 0);
				maskedSum(64).simplify(traversalContext(64), 0);
			}

			log(String.format("%-8s %-14s %-18s %-16s %-20s",
					"width", "inlinedChars", "inlineSimplifyUs", "reorderedChars", "reorderSimplifyUs"));

			int previousInlinedChars = -1;
			int previousReorderedChars = -1;

			for (int n : widths) {
				final int width = n;
				long inlineNs = minSimplifyNs(() -> maskedSum(width),
						sum -> fallthroughContext(), 3);
				long reorderNs = minSimplifyNs(() -> maskedSum(width),
						sum -> traversalContext(width), 3);

				int inlinedChars = maskedSum(width).simplify(fallthroughContext(), 0)
						.getExpression(lang).length();
				int reorderedChars = maskedSum(width).simplify(traversalContext(width), 0)
						.getExpression(lang).length();

				log(String.format("%-8d %-14d %-18d %-16d %-20d",
						width, inlinedChars, inlineNs / 1000, reorderedChars, reorderNs / 1000));

				if (previousInlinedChars >= 0) {
					Assert.assertTrue("inlined size must grow monotonically with width",
							inlinedChars >= previousInlinedChars);
				}
				if (width >= REORDER_THRESHOLD && previousReorderedChars >= 0) {
					// Above threshold the reordered form should not blow up with width.
					Assert.assertTrue("reordered size should not exceed inlined size above threshold",
							reorderedChars <= inlinedChars);
				}

				previousInlinedChars = inlinedChars;
				previousReorderedChars = reorderedChars;
			}
		} finally {
			Sum.enableGenerateReordering = previous;
			ScopeSettings.maxReorderingBudget = previousBudget;
		}
	}

	/**
	 * At a fixed (modest) width above the rewrite threshold, sweeps the kernel element count
	 * over several orders of magnitude. The reordering generator simplifies the expression
	 * once per kernel index, so total compile cost is expected to grow linearly in
	 * {@code count} even though the {@code Sum} itself is unchanged.
	 *
	 * <p>This is the trade-off that breaks {@code convDeltaSmall}: the masked sums that
	 * arise from sparse-Jacobian gradients have a modest number of terms but are simplified
	 * in a context whose kernel count is {@code output_size * input_size}. Inlined cost is
	 * decoupled from {@code count}; reordered cost is not.</p>
	 */
	@Test(timeout = 180000)
	@TestDepth(2)
	public void kernelCountSweepCompileCost() {
		final int width = REORDER_THRESHOLD + 16;
		int[] counts = { 16, 64, 256, 1024, 4096 };

		boolean previous = Sum.enableGenerateReordering;
		int previousBudget = ScopeSettings.maxReorderingBudget;
		Sum.enableGenerateReordering = true;
		ScopeSettings.maxReorderingBudget = Integer.MAX_VALUE;

		try {
			// Warmup.
			for (int i = 0; i < 3; i++) {
				maskedSum(width).simplify(traversalContext(64), 0);
			}

			log("kernelCountSweepCompileCost width=" + width);
			log(String.format("%-10s %-18s %-14s %-18s",
					"count", "reorderSimplifyUs", "reorderChars", "inlineSimplifyUs"));

			long firstReorderUs = -1;
			long lastReorderUs = -1;

			for (int count : counts) {
				final int kernelCount = count;

				long reorderNs = minSimplifyNs(() -> maskedSum(width),
						sum -> traversalContext(kernelCount), 3);
				long inlineNs = minSimplifyNs(() -> maskedSum(width),
						sum -> fallthroughContext(), 3);
				int reorderedChars = maskedSum(width)
						.simplify(traversalContext(kernelCount), 0)
						.getExpression(lang).length();

				log(String.format("%-10d %-18d %-14d %-18d",
						kernelCount, reorderNs / 1000, reorderedChars, inlineNs / 1000));

				if (firstReorderUs < 0) firstReorderUs = reorderNs / 1000;
				lastReorderUs = reorderNs / 1000;
			}

			// The whole point of this test: at fixed width the inlined path's cost is
			// independent of count, while the reordered path's cost grows with count.
			// Verify that growing count by 256x does in fact slow reordering substantially.
			Assert.assertTrue("reorder cost should grow with kernel count (first="
					+ firstReorderUs + "us last=" + lastReorderUs + "us)",
					lastReorderUs > firstReorderUs * 2);
		} finally {
			Sum.enableGenerateReordering = previous;
			ScopeSettings.maxReorderingBudget = previousBudget;
		}
	}

	/**
	 * At fixed width and fixed kernel count, sweeps the body complexity of each masked
	 * term. The reordering generator simplifies the body once per kernel index, so a heavier
	 * body multiplies the cost by its own simplification cost.
	 *
	 * <p>The flat {@link #maskedSum(int)} case has a trivial constant body; chain-rule
	 * gradients produce masked terms whose body is itself an arithmetic expression. This
	 * test quantifies how rapidly the optimization stops paying off as that body grows.</p>
	 */
	@Test(timeout = 180000)
	@TestDepth(2)
	public void bodyComplexitySweepCompileCost() {
		final int width = REORDER_THRESHOLD + 8;
		final int kernelCount = 256;
		int[] bodyDepths = { 0, 1, 2, 4, 8, 16 };

		boolean previous = Sum.enableGenerateReordering;
		int previousBudget = ScopeSettings.maxReorderingBudget;
		Sum.enableGenerateReordering = true;
		ScopeSettings.maxReorderingBudget = Integer.MAX_VALUE;

		try {
			// Warmup.
			for (int i = 0; i < 3; i++) {
				maskedSumWithBody(width, 2).simplify(traversalContext(kernelCount), 0);
			}

			log("bodyComplexitySweepCompileCost width=" + width + " count=" + kernelCount);
			log(String.format("%-10s %-18s %-14s %-18s",
					"bodyDepth", "reorderSimplifyUs", "reorderChars", "inlineSimplifyUs"));

			long firstReorderUs = -1;
			long lastReorderUs = -1;

			for (int body : bodyDepths) {
				final int bodySize = body;

				long reorderNs = minSimplifyNs(() -> maskedSumWithBody(width, bodySize),
						sum -> traversalContext(kernelCount), 3);
				long inlineNs = minSimplifyNs(() -> maskedSumWithBody(width, bodySize),
						sum -> fallthroughContext(), 3);
				int reorderedChars = maskedSumWithBody(width, bodySize)
						.simplify(traversalContext(kernelCount), 0)
						.getExpression(lang).length();

				log(String.format("%-10d %-18d %-14d %-18d",
						bodySize, reorderNs / 1000, reorderedChars, inlineNs / 1000));

				if (firstReorderUs < 0) firstReorderUs = reorderNs / 1000;
				lastReorderUs = reorderNs / 1000;
			}

			// Body complexity is expected to amplify cost only when the body is not
			// constant-foldable. With the index-dependent body used here the cost should
			// strictly grow with depth; tolerate +20% noise on the lowest depth.
			Assert.assertTrue("reorder cost should grow with index-dependent body depth (first="
					+ firstReorderUs + "us last=" + lastReorderUs + "us)",
					lastReorderUs > firstReorderUs * 6 / 5);
		} finally {
			Sum.enableGenerateReordering = previous;
			ScopeSettings.maxReorderingBudget = previousBudget;
		}
	}

	/**
	 * The reordering generator caches lookup tables by the structurally-equal source
	 * expression within a single {@link KernelStructureContext}: see {@code variables.get(
	 * expression)} in {@link KernelTraversalOperationGenerator#generateReordering(Expression)}.
	 * Equality is {@link Expression#compare(Expression)}, which short-circuits on cached
	 * metrics (type, depth, node count, hash) before recursing, so the cache lookup is
	 * cheap even for deeply nested keys.
	 *
	 * <p>A second simplification of an equivalent sum in the same context skips the
	 * entire per-index simplify loop. The remaining cost is the lookup plus whatever
	 * {@code super.simplify} does to the children, which is independent of the kernel
	 * count.</p>
	 *
	 * <p>The expected warm/cold ratio is dominated by how much of the cold path is the
	 * per-index loop. With wide sums and large kernel counts the loop dominates and warm
	 * is dramatically faster; for the modest dimensions used here we still expect a
	 * meaningful improvement.</p>
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
			// With structural caching the warm path skips the per-index simplify loop
			// entirely; for these dimensions the warm path should be well under half
			// the cost of the cold path.
			Assert.assertTrue("warm simplify should be at least 2x faster than cold (cold="
					+ firstNs / 1000 + "us warm=" + secondNs / 1000 + "us)",
					secondNs * 2 < firstNs);
		} finally {
			Sum.enableGenerateReordering = previous;
		}
	}

	/**
	 * Verifies the {@link ScopeSettings#maxReorderingBudget} guard in
	 * {@link Sum#simplify(KernelStructureContext, int)}. A masked sum that would normally
	 * trigger {@code generateReordering} (above {@link #REORDER_THRESHOLD} children, in a
	 * context that exposes a {@link KernelTraversalProvider}) must instead fall through to
	 * the inlined form when the predicted {@code count * nodeCount} exceeds the budget.
	 *
	 * <p>Asserts both directions of the guard:</p>
	 * <ul>
	 *   <li>With a very small budget, the simplified result is still a {@link Sum} - the
	 *       reordering rewrite was skipped.</li>
	 *   <li>With an effectively unlimited budget, the rewrite fires as before and the
	 *       result is no longer a {@link Sum}.</li>
	 * </ul>
	 */
	@Test(timeout = 30000)
	public void budgetGuardSkipsExpensiveReordering() {
		boolean previousReorder = Sum.enableGenerateReordering;
		int previousBudget = ScopeSettings.maxReorderingBudget;
		Sum.enableGenerateReordering = true;

		try {
			int n = REORDER_THRESHOLD + 16;
			int largeCount = 1 << 14;

			ScopeSettings.maxReorderingBudget = 1024;
			Expression<?> overBudget = maskedSum(n).simplify(traversalContext(largeCount), 0);
			Assert.assertTrue("over-budget sum should fall through to the inlined Sum",
					overBudget instanceof Sum);

			ScopeSettings.maxReorderingBudget = Integer.MAX_VALUE;
			Expression<?> withinBudget = maskedSum(n).simplify(traversalContext(n), 0);
			Assert.assertFalse("within-budget sum should be replaced by a lookup reference",
					withinBudget instanceof Sum);
		} finally {
			Sum.enableGenerateReordering = previousReorder;
			ScopeSettings.maxReorderingBudget = previousBudget;
		}
	}

	/**
	 * The {@code enableGenerateReordering = false} landmine throws when the masked-sum
	 * branch is reached. The budget guard must run <em>before</em> that throw: if the
	 * reordering wouldn't have fired anyway (because it would exceed the budget), the
	 * landmine should also stay silent and the simplification should fall through.
	 *
	 * <p>This protects callers that want to keep the landmine enabled while disabling
	 * reordering for genuinely expensive cases via budget alone.</p>
	 */
	@Test(timeout = 30000)
	public void budgetGuardSuppressesLandmineForOverBudgetCase() {
		boolean previousReorder = Sum.enableGenerateReordering;
		int previousBudget = ScopeSettings.maxReorderingBudget;
		Sum.enableGenerateReordering = false;
		ScopeSettings.maxReorderingBudget = 1;

		try {
			int n = REORDER_THRESHOLD + 4;
			Expression<?> result = maskedSum(n).simplify(traversalContext(n * 4), 0);
			Assert.assertTrue("over-budget sum should fall through to the inlined Sum"
					+ " even with the landmine flag", result instanceof Sum);
		} finally {
			Sum.enableGenerateReordering = previousReorder;
			ScopeSettings.maxReorderingBudget = previousBudget;
		}
	}
}
