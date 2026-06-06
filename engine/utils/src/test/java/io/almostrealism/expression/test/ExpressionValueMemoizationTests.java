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
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for the optional DAG-aware memoization in {@link Expression#value(IndexValues)}.
 *
 * <p>Expression graphs are DAGs: a single subexpression instance is frequently reachable by
 * many paths from the root (for example the sparse Jacobians produced by convolution
 * gradients). The recursive evaluation in {@link Expression#computeValue(IndexValues)} visits
 * children directly, so without memoization a shared node is recomputed once per path to it —
 * a cost that is exponential in tree depth. The memoization that collapses this is gated behind
 * {@link ScopeSettings#enableValueMemoization} (off by default, since the gather-analysis gate
 * now bounds the deep-DAG case and the cache otherwise slows the common shallow case). These
 * tests pin both behaviours: each distinct composite node is evaluated exactly once when the
 * flag is enabled, and the flag genuinely toggles that memoization.</p>
 */
public class ExpressionValueMemoizationTests extends TestSuiteBase implements ExpressionFeatures {

	/**
	 * Builds a balanced binary DAG of depth 24 in which every internal node shares a single
	 * child instance with itself, so the graph has 25 distinct nodes but a tree-node count of
	 * roughly {@code 2^25}. With memoization enabled, verifies that evaluating it invokes
	 * {@code computeValue} exactly once per distinct node (25 times) rather than once per path
	 * (millions of times), and that the computed value is correct.
	 */
	@Test(timeout = 30000)
	public void sharedSubexpressionEvaluatedOncePerNode() {
		boolean previous = ScopeSettings.enableValueMemoization;
		ScopeSettings.enableValueMemoization = true;
		try {
			int[] evaluations = { 0 };

			Expression<Integer> node = new CountingExpression(e(2), e(3), evaluations);
			for (int i = 0; i < 24; i++) {
				node = new CountingExpression(node, node, evaluations);
			}

			// 25 distinct nodes, but the tree-node count (counted per path) is exponential.
			Assert.assertTrue("expected an exponential tree-node count",
					node.countNodes() > 1_000_000);

			Number result = node.value(new IndexValues());

			// Memoization collapses the per-path recompute to one evaluation per distinct node.
			Assert.assertEquals(25, evaluations[0]);

			// Leaf value 2 + 3 = 5, doubled once per level for 24 levels.
			Assert.assertEquals(5L << 24, result.longValue());
		} finally {
			ScopeSettings.enableValueMemoization = previous;
		}
	}

	/**
	 * Verifies that the memoization is genuinely controlled by the flag: with it disabled (the
	 * default), a node shared across both children of its parent is recomputed once per path,
	 * so a small shared DAG evaluates more times than it has distinct nodes.
	 */
	@Test(timeout = 30000)
	public void memoizationDisabledRecomputesSharedNodesPerPath() {
		boolean previous = ScopeSettings.enableValueMemoization;
		ScopeSettings.enableValueMemoization = false;
		try {
			int[] evaluations = { 0 };

			// Three distinct composite nodes; the depth-1 node is shared by both children of
			// the root, so without memoization the leaf-summing node is visited twice.
			Expression<Integer> shared = new CountingExpression(e(2), e(3), evaluations);
			Expression<Integer> root = new CountingExpression(shared, shared, evaluations);

			Number result = root.value(new IndexValues());

			Assert.assertTrue("without memoization the shared node is recomputed per path",
					evaluations[0] > 2);
			// Value is independent of caching: (2 + 3) + (2 + 3) = 10.
			Assert.assertEquals(10L, result.longValue());
		} finally {
			ScopeSettings.enableValueMemoization = previous;
		}
	}

	/**
	 * A composite {@link Expression} that sums its children and records how many times it is
	 * evaluated, so a test can observe whether shared nodes are recomputed.
	 */
	private static final class CountingExpression extends Expression<Integer> {
		/** Single-element accumulator incremented once per {@link #computeValue(IndexValues)} call. */
		private final int[] counter;

		/**
		 * @param a       the first child
		 * @param b       the second child
		 * @param counter a shared accumulator incremented on each evaluation
		 */
		CountingExpression(Expression<?> a, Expression<?> b, int[] counter) {
			super(Integer.class, a, b);
			this.counter = counter;
		}

		@Override
		protected Number computeValue(IndexValues indexValues) {
			counter[0]++;

			long sum = 0;
			for (Expression<?> child : getChildren()) {
				sum += child.value(indexValues).longValue();
			}
			return sum;
		}

		@Override
		public Number evaluate(Number... children) {
			long sum = 0;
			for (Number child : children) {
				sum += child.longValue();
			}
			return sum;
		}

		@Override
		public String getExpression(LanguageOperations lang) {
			return "counting";
		}
	}
}
