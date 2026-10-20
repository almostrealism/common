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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.OptionalLong;

/**
 * Regression tests for the memoized bounds analysis of {@link Expression}: the context-free
 * {@link Expression#upperBound(KernelStructureContext) upper} and
 * {@link Expression#lowerBound(KernelStructureContext) lower} bounds and the
 * {@link Expression#isPossiblyNegative() sign} of a composite node.
 *
 * <p>Index arithmetic is a DAG in which one sub-expression is reachable by many paths, as in
 * the index decomposition of {@link TraversalPolicy#position(Expression)}, which refers to
 * each level twice in the next. Bounds analysis recurses through the children, so without
 * memoization a shared node is analysed once per path, which is exponential in the depth of
 * the expression. These tests pin that each distinct composite node is analysed once while
 * {@link ScopeSettings#enableBoundsMemoization} is enabled, that the flag genuinely controls
 * it, that bounds for a specific {@link KernelStructureContext} are not memoized, and that the
 * memoized bounds of real index expressions are those an unmemoized analysis computes.</p>
 */
public class ExpressionBoundsMemoizationTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Builds a binary DAG of depth 24 whose internal nodes each use one child instance for
	 * both operands, so the graph has 25 distinct composite nodes but roughly {@code 2^25}
	 * paths. Verifies that each bound and the sign are computed exactly once per distinct node,
	 * that asking again computes nothing, and that the bounds are correct.
	 */
	@Test(timeout = 30000)
	public void sharedSubexpressionBoundedOncePerNode() {
		boolean previous = ScopeSettings.enableBoundsMemoization;
		ScopeSettings.enableBoundsMemoization = true;

		try {
			int[] counts = new int[3];
			Expression<Integer> node = sharedDag(counts, 24);
			Assert.assertTrue("expected an exponential number of paths", node.countNodes() > 1_000_000);

			// Leaf bounds 2 + 3 = 5, doubled once per level for 24 levels
			Assert.assertEquals(5L << 24, node.upperBound(null).getAsLong());
			Assert.assertEquals(5L << 24, node.lowerBound(null).getAsLong());
			Assert.assertFalse(node.isPossiblyNegative());
			Assert.assertEquals(25, counts[0]);
			Assert.assertEquals(25, counts[1]);
			Assert.assertEquals(25, counts[2]);

			node.upperBound(null);
			node.lowerBound(null);
			node.isPossiblyNegative();
			Assert.assertEquals(25, counts[0]);
			Assert.assertEquals(25, counts[1]);
			Assert.assertEquals(25, counts[2]);
		} finally {
			ScopeSettings.enableBoundsMemoization = previous;
		}
	}

	/**
	 * Verifies that the memoization is controlled by the flag: with it disabled, a node shared
	 * by both operands of its parent is analysed once per path, so a small shared DAG computes
	 * its bounds more times than it has distinct nodes, with the same result.
	 */
	@Test(timeout = 30000)
	public void memoizationDisabledRecomputesSharedNodesPerPath() {
		boolean previous = ScopeSettings.enableBoundsMemoization;
		ScopeSettings.enableBoundsMemoization = false;

		try {
			int[] counts = new int[3];
			Expression<Integer> root = sharedDag(counts, 1);

			Assert.assertEquals(10L, root.upperBound(null).getAsLong());
			Assert.assertFalse(root.isPossiblyNegative());
			Assert.assertEquals(3, counts[0]);
			Assert.assertEquals(3, counts[2]);

			root.upperBound(null);
			Assert.assertEquals(6, counts[0]);
		} finally {
			ScopeSettings.enableBoundsMemoization = previous;
		}
	}

	/**
	 * Bounds requested for a specific {@link KernelStructureContext} depend on that context,
	 * so they are computed for every request rather than memoized: the same expression of a
	 * {@link KernelIndex} reports the bound each context implies, and the context-free bound
	 * is unaffected by the context-specific requests made before it.
	 */
	@Test(timeout = 30000)
	public void contextSpecificBoundsAreNotMemoized() {
		Expression<?> index = new KernelIndex().add(1);

		Assert.assertEquals(10L, index.upperBound(new NoOpKernelStructureContext(10)).getAsLong());
		Assert.assertEquals(20L, index.upperBound(new NoOpKernelStructureContext(20)).getAsLong());
		Assert.assertTrue(index.upperBound(null).isEmpty());
		Assert.assertEquals(30L, index.upperBound(new NoOpKernelStructureContext(30)).getAsLong());
	}

	/**
	 * Decomposes a kernel index into the positions of a six-dimensional shape with
	 * {@link TraversalPolicy#position(Expression)}, once with memoization disabled and once
	 * with it enabled, and verifies that every position reports the same bounds and sign
	 * either way.
	 */
	@Test(timeout = 60000)
	public void positionBoundsMatchUnmemoizedAnalysis() {
		TraversalPolicy shape = new TraversalPolicy(3, 5, 4, 7, 2, 6);
		boolean previous = ScopeSettings.enableBoundsMemoization;

		try {
			ScopeSettings.enableBoundsMemoization = false;
			Expression[] expected = shape.position(new KernelIndex(new NoOpKernelStructureContext(shape.getTotalSize())));

			ScopeSettings.enableBoundsMemoization = true;
			Expression[] actual = shape.position(new KernelIndex(new NoOpKernelStructureContext(shape.getTotalSize())));

			Assert.assertEquals(expected.length, actual.length);

			for (int i = 0; i < expected.length; i++) {
				ScopeSettings.enableBoundsMemoization = false;
				OptionalLong upper = expected[i].upperBound(null);
				OptionalLong lower = expected[i].lowerBound(null);
				boolean negative = expected[i].isPossiblyNegative();

				ScopeSettings.enableBoundsMemoization = true;
				Assert.assertEquals("upper bound of position " + i, upper, actual[i].upperBound(null));
				Assert.assertEquals("lower bound of position " + i, lower, actual[i].lowerBound(null));
				Assert.assertEquals("sign of position " + i, negative, actual[i].isPossiblyNegative());
				Assert.assertEquals("position " + i,
						expected[i].getExpression(Expression.defaultLanguage()),
						actual[i].getExpression(Expression.defaultLanguage()));
			}
		} finally {
			ScopeSettings.enableBoundsMemoization = previous;
		}
	}

	/**
	 * Builds a DAG of {@link BoundsCountingSum}s over the leaves 2 and 3, in which each
	 * level uses the previous level for both of its operands.
	 *
	 * @param counts the accumulators for upper bound, lower bound and sign computations
	 * @param depth  the number of levels above the bottom node
	 * @return the root of the DAG
	 */
	private Expression<Integer> sharedDag(int[] counts, int depth) {
		Expression<Integer> node = new BoundsCountingSum(e(2), e(3), counts);

		for (int i = 0; i < depth; i++) {
			node = new BoundsCountingSum(node, node, counts);
		}

		return node;
	}

	/**
	 * A {@link Sum} that records how many times its bounds and sign are computed, so a test
	 * can observe whether shared nodes are analysed once per node or once per path.
	 */
	private static final class BoundsCountingSum extends Sum<Integer> {
		/** Accumulators for upper bound, lower bound and sign computations, in that order. */
		private final int[] counts;

		/**
		 * @param a      the first operand
		 * @param b      the second operand
		 * @param counts the shared accumulators incremented on each computation
		 */
		BoundsCountingSum(Expression<? extends Number> a, Expression<? extends Number> b, int[] counts) {
			super(a, b);
			this.counts = counts;
		}

		@Override
		protected OptionalLong computeUpperBound(KernelStructureContext context) {
			counts[0]++;
			return super.computeUpperBound(context);
		}

		@Override
		protected OptionalLong computeLowerBound(KernelStructureContext context) {
			counts[1]++;
			return super.computeLowerBound(context);
		}

		@Override
		protected boolean computePossiblyNegative() {
			counts[2]++;
			return super.computePossiblyNegative();
		}
	}
}
