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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.scope.ExpressionDuplicationScanner;
import io.almostrealism.scope.ExpressionDuplicationScanner.Report;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link ExpressionDuplicationScanner}.
 *
 * <p>The tests build {@link Expression} trees with known duplication structure and
 * verify that the scanner counts identity-shared and structurally-equal nodes
 * correctly. They do not exercise any production workload — per-workload
 * adaptors are a separate concern.</p>
 *
 * <p>Test inputs use {@link Mask#of(Expression, Expression)} rather than
 * {@code Sum.of(...)} so that the framework's constant-folding does not collapse
 * structurally equal leaves before the scanner can observe them.</p>
 */
public class ExpressionDuplicationScannerTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * A single leaf node has one canonical node and no duplication.
	 */
	@Test(timeout = 10000)
	public void singleLeafHasNoDuplication() {
		Expression<?> leaf = new IntegerConstant(7);

		Report r = ExpressionDuplicationScanner.scan(leaf);

		Assert.assertEquals(1, r.getTotalNodes());
		Assert.assertEquals(1, r.getDistinctNodes());
		Assert.assertEquals(0, r.getDuplicateNodes());
		Assert.assertEquals(0.0, r.duplicationRatio(), 1e-9);
	}

	/**
	 * Two structurally equal but identity-distinct {@link Mask} trees produce a
	 * non-zero duplication ratio: every node in the second copy has an
	 * {@code .equals()} counterpart already counted from the first.
	 */
	@Test(timeout = 10000)
	public void structurallyEqualMasksAreCountedAsDuplicates() {
		KernelIndex idx = new KernelIndex();
		Expression<?> a = Mask.of(idx.eq(0), e(1.0));
		Expression<?> b = Mask.of(idx.eq(0), e(1.0));

		Report r = ExpressionDuplicationScanner.scan(List.of(a, b));

		log(r.fullTable());

		Assert.assertNotSame("test setup invalid: roots should be distinct instances", a, b);
		Assert.assertEquals("roots are structurally equal", a, b);
		Assert.assertTrue("expected duplicates: " + r.summary(),
				r.getTotalNodes() > r.getDistinctNodes());
		Assert.assertTrue("expected non-zero duplication ratio: " + r.summary(),
				r.duplicationRatio() > 0.0);
	}

	/**
	 * A subtree referenced from two parents by identity is genuine DAG sharing —
	 * the memory is already pooled and the scanner must not double-count it. The
	 * invariant: rebuilding the same expression tree but with the shared guard
	 * constructed independently must produce <em>more</em> unique-by-identity
	 * nodes (one extra guard subtree), while leaving the distinct-by-{@code equals}
	 * count unchanged (because the two constructed-fresh guards are still
	 * {@code .equals()}).
	 *
	 * <p>Note that the test does not assert "zero duplication." Both
	 * configurations have a small amount of unavoidable internal duplication
	 * because {@link Mask#of(Expression, Expression)} allocates a fresh
	 * {@code IntegerConstant(0)} per call (the synthetic negative branch of the
	 * underlying {@code Conditional}). That hidden zero is exactly the kind of
	 * redundancy interning would eliminate — its presence here is real, not a
	 * scanner bug.</p>
	 */
	@Test(timeout = 10000)
	public void identitySharedSubtreesAreNotDoubleCounted() {
		KernelIndex idx = new KernelIndex();
		Expression<Boolean> sharedGuard = idx.eq(5);
		Expression<?> sharedMask1 = Mask.of(sharedGuard, e(1.0));
		Expression<?> sharedMask2 = Mask.of(sharedGuard, e(2.0));

		Expression<?> unsharedMask1 = Mask.of(idx.eq(5), e(1.0));
		Expression<?> unsharedMask2 = Mask.of(idx.eq(5), e(2.0));

		Report shared = ExpressionDuplicationScanner.scan(List.of(sharedMask1, sharedMask2));
		Report unshared = ExpressionDuplicationScanner.scan(List.of(unsharedMask1, unsharedMask2));

		log("shared:   " + shared.summary());
		log("unshared: " + unshared.summary());

		Assert.assertTrue(
				"identity sharing should reduce total unique-by-identity count: shared="
						+ shared.summary() + " unshared=" + unshared.summary(),
				shared.getTotalNodes() < unshared.getTotalNodes());
		Assert.assertEquals(
				"distinct-by-equals count should be invariant under identity sharing: shared="
						+ shared.summary() + " unshared=" + unshared.summary(),
				shared.getDistinctNodes(), unshared.getDistinctNodes());
	}

	/**
	 * Passing the same root twice in the input collection must not inflate the
	 * counts; the scanner deduplicates roots by identity.
	 */
	@Test(timeout = 10000)
	public void duplicateRootsAreCountedOnce() {
		KernelIndex idx = new KernelIndex();
		Expression<?> root = Mask.of(idx.eq(0), e(1.0));

		Report singleRoot = ExpressionDuplicationScanner.scan(root);
		Report doubleRoot = ExpressionDuplicationScanner.scan(List.of(root, root));

		Assert.assertEquals(singleRoot.getTotalNodes(), doubleRoot.getTotalNodes());
		Assert.assertEquals(singleRoot.getDistinctNodes(), doubleRoot.getDistinctNodes());
	}

	/**
	 * The shape that motivated the investigation: a wide masked-sum-style
	 * collection of distinct-but-shape-shared sub-trees. Walking two independent
	 * copies of the same structure should produce a duplication ratio above
	 * roughly 0.4 — enough to confirm the scanner picks up the intended signal
	 * without being so tight that constant folding or hash collisions could flake
	 * the test.
	 */
	@Test(timeout = 10000)
	public void wideRepeatedShapeShowsSubstantialDuplication() {
		KernelIndex idx = new KernelIndex();
		int n = 8;

		Expression<?>[] copyA = new Expression[n];
		Expression<?>[] copyB = new Expression[n];
		for (int j = 0; j < n; j++) {
			copyA[j] = Mask.of(idx.eq(j), e((double) (j + 1)));
			copyB[j] = Mask.of(idx.eq(j), e((double) (j + 1)));
		}

		List<Expression<?>> roots = new ArrayList<>();
		Collections.addAll(roots, copyA);
		Collections.addAll(roots, copyB);

		Report r = ExpressionDuplicationScanner.scan(roots);

		log(r.fullTable());

		Assert.assertTrue("expected substantial duplication for repeated identical shapes: "
				+ r.summary(), r.duplicationRatio() > 0.4);
	}

	/**
	 * The per-class breakdown should surface the subclass that actually
	 * accumulates duplicates. In the wide-repeated-shape workload that is the
	 * {@link Mask} subclass — every entry in {@code copyA} has an equals
	 * counterpart in {@code copyB}.
	 */
	@Test(timeout = 10000)
	public void perClassBreakdownIdentifiesDuplicatedSubclass() {
		KernelIndex idx = new KernelIndex();
		int n = 4;

		List<Expression<?>> roots = new ArrayList<>();
		for (int j = 0; j < n; j++) {
			roots.add(Mask.of(idx.eq(j), e((double) (j + 1))));
			roots.add(Mask.of(idx.eq(j), e((double) (j + 1))));
		}

		Report r = ExpressionDuplicationScanner.scan(roots);

		log(r.fullTable());

		ExpressionDuplicationScanner.ClassStats maskStats = r.getByClass().get("Mask");
		Assert.assertNotNull("Mask class stats must be present: " + r.summary(), maskStats);
		Assert.assertEquals("Mask total reflects 2n distinct-by-identity instances",
				2L * n, maskStats.getTotalNodes());
		Assert.assertEquals("Mask distinct reflects n canonical classes",
				(long) n, maskStats.getDistinctNodes());
	}
}
