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

package io.almostrealism.code.test;

import io.almostrealism.code.ScopeLifecycle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pins the cost of the {@link ScopeLifecycle#resetArguments() argument-reset} traversal: a
 * deeply shared (diamond-shaped) graph must have each of its nodes reset exactly once per
 * pass, not once per path that reaches it.
 *
 * <p>Before the traversal was memoized, {@link ScopeLifecycle#resetArguments()} descended
 * into shared inputs unconditionally, so a node reachable along <em>k</em> distinct paths was
 * reset <em>k</em> times and a chain of diamonds made the reset exponential in its depth. These
 * tests build such a chain out of a counting {@link ScopeLifecycle} stub that recurses exactly
 * as {@link io.almostrealism.code.ComputationBase} does — through
 * {@link ScopeLifecycle#resetArguments(java.util.stream.Stream, Set)} — and assert the
 * once-per-node guarantee. The guarantee is structural (a visit count), not a wall-clock
 * threshold, so it holds on any backend.</p>
 */
public class ScopeLifecycleResetArgumentsTest extends TestSuiteBase {

	/**
	 * A {@link ScopeLifecycle} node that records how many times it is reset and descends into
	 * its inputs the same way a real computation does. It drives a reset pass from its no-arg
	 * {@link #resetArguments()} via {@link ScopeLifecycle#resetArguments(ScopeLifecycle)} and,
	 * once in a pass, threads the visited set through {@link #resetArguments(Set)}.
	 */
	private static final class CountingLifecycle implements ScopeLifecycle {
		/** The inputs this node descends into when reset, mirroring a computation's inputs. */
		private final List<ScopeLifecycle> inputs;

		/** When true, this node compares {@link #equals(Object) equal} to any other instance. */
		private final boolean alwaysEqual;

		/** The number of times this node has been reset across all passes. */
		private int resetCount;

		/**
		 * Constructs a node with the given inputs.
		 *
		 * @param alwaysEqual whether this node compares equal to every other instance
		 * @param inputs      the inputs to descend into when reset
		 */
		private CountingLifecycle(boolean alwaysEqual, ScopeLifecycle... inputs) {
			this.inputs = List.of(inputs);
			this.alwaysEqual = alwaysEqual;
		}

		@Override
		public void resetArguments() {
			ScopeLifecycle.resetArguments(this);
		}

		@Override
		public void resetArguments(Set<ScopeLifecycle> visited) {
			resetCount++;
			ScopeLifecycle.resetArguments(inputs.stream(), visited);
		}

		@Override
		public boolean equals(Object obj) {
			return alwaysEqual ? obj instanceof CountingLifecycle : super.equals(obj);
		}

		@Override
		public int hashCode() {
			return alwaysEqual ? 0 : super.hashCode();
		}
	}

	/**
	 * A chain of {@code depth} diamonds shares its deepest node along 2<sup>depth</sup> distinct
	 * paths. Resetting the root must still reset each of the chain's nodes exactly once, which an
	 * un-memoized descent could not do: at depth 24 it would attempt over sixteen million resets
	 * of the shared leaf alone.
	 */
	@Test(timeout = 60000)
	public void resetVisitsEachSharedNodeOnce() {
		int depth = 24;

		List<CountingLifecycle> nodes = new ArrayList<>();

		CountingLifecycle leaf = new CountingLifecycle(false);
		nodes.add(leaf);

		ScopeLifecycle shared = leaf;
		for (int level = 0; level < depth; level++) {
			CountingLifecycle left = new CountingLifecycle(false, shared);
			CountingLifecycle right = new CountingLifecycle(false, shared);
			CountingLifecycle top = new CountingLifecycle(false, left, right);
			nodes.add(left);
			nodes.add(right);
			nodes.add(top);
			shared = top;
		}

		shared.resetArguments();

		for (CountingLifecycle node : nodes) {
			Assert.assertEquals("Each node must be reset exactly once per pass", 1, node.resetCount);
		}

		// A second pass is independent of the first and resets every node once again.
		shared.resetArguments();

		for (CountingLifecycle node : nodes) {
			Assert.assertEquals("A fresh pass resets each node once more", 2, node.resetCount);
		}
	}

	/**
	 * The visited set is keyed by identity, so two distinct nodes that compare
	 * {@link Object#equals(Object) equal} are reset independently rather than one standing in for
	 * the other. Distinct graph nodes can carry independent argument state even when they compare
	 * equal, so collapsing them by {@code equals} would leave one unreset.
	 */
	@Test(timeout = 60000)
	public void equalButDistinctNodesAreResetIndependently() {
		CountingLifecycle first = new CountingLifecycle(true);
		CountingLifecycle second = new CountingLifecycle(true);

		Assert.assertEquals(first, second);

		CountingLifecycle root = new CountingLifecycle(false, first, second);
		root.resetArguments();

		Assert.assertEquals(1, first.resetCount);
		Assert.assertEquals(1, second.resetCount);
		Assert.assertEquals(1, root.resetCount);
	}
}
