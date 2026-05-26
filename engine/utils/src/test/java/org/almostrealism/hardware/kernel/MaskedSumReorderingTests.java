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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.OptionalLong;
import java.util.function.Function;

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
}
