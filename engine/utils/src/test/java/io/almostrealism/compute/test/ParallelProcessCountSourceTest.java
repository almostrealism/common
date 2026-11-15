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

package io.almostrealism.compute.test;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Parent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Tests for {@link ParallelProcess#isCountFrom(Object, Predicate)}, using plain graph nodes
 * so that which nodes are fixed, which are variable, and which are reported as sources of
 * variable count is fully determined by each test.
 */
public class ParallelProcessCountSourceTest extends TestSuiteBase {

	/** A graph node with a chosen count kind and chosen children. */
	private static final class Node implements Countable, Parent<Object> {
		/** The label used to recognise this node in a predicate. */
		private final String label;
		/** Whether this node's count is fixed. */
		private final boolean fixed;
		/** The children of this node. */
		private final List<Object> children;

		/**
		 * Creates a node.
		 *
		 * @param label     the label of the node
		 * @param fixed     whether the node's count is fixed
		 * @param children  the children of the node
		 */
		private Node(String label, boolean fixed, Object... children) {
			this.label = label;
			this.fixed = fixed;
			this.children = List.of(children);
		}

		@Override
		public long getCountLong() { return fixed ? 4 : 1; }

		@Override
		public boolean isFixedCount() { return fixed; }

		@Override
		public List<Object> getChildren() { return children; }
	}

	/** A predicate that accepts nodes whose label starts with "ok", recording every test. */
	private static final class RecordingSource implements Predicate<Object> {
		/** Every node the predicate was asked about, in order. */
		private final List<Object> tested = new ArrayList<>();

		@Override
		public boolean test(Object node) {
			tested.add(node);
			return node instanceof Node && ((Node) node).label.startsWith("ok");
		}
	}

	/** A fixed-count node has no source of variable count, so the predicate is never asked. */
	@Test(timeout = 10000)
	public void fixedCountRootIsAccepted() {
		RecordingSource source = new RecordingSource();
		Node root = new Node("bad", true, new Node("bad-child", false));

		assertTrue(ParallelProcess.isCountFrom(root, source));
		assertEquals(0, source.tested.size());
	}

	/** An object that is not {@link Countable} is fixed, so it is accepted without a test. */
	@Test(timeout = 10000)
	public void nonCountableRootIsAccepted() {
		RecordingSource source = new RecordingSource();

		assertTrue(ParallelProcess.isCountFrom("not a node", source));
		assertEquals(0, source.tested.size());
	}

	/** A variable-count leaf is itself the source, so the predicate decides. */
	@Test(timeout = 10000)
	public void variableLeafIsTested() {
		RecordingSource accepted = new RecordingSource();
		Node ok = new Node("ok", false);
		assertTrue(ParallelProcess.isCountFrom(ok, accepted));
		assertEquals(List.of(ok), accepted.tested);

		RecordingSource rejected = new RecordingSource();
		Node bad = new Node("bad", false);
		assertFalse(ParallelProcess.isCountFrom(bad, rejected));
		assertEquals(List.of(bad), rejected.tested);
	}

	/**
	 * A variable-count node whose children are all fixed is intrinsically variable: it is the
	 * source, and its fixed children are never tested.
	 */
	@Test(timeout = 10000)
	public void variableNodeWithFixedChildrenIsTheSource() {
		RecordingSource source = new RecordingSource();
		Node root = new Node("bad", false, new Node("ok-a", true), new Node("ok-b", true));

		assertFalse(ParallelProcess.isCountFrom(root, source));
		assertEquals(List.of(root), source.tested);
	}

	/**
	 * A variable-count node with variable children is traced through them: the node itself
	 * is not tested, and the result holds only when every variable child's sources pass.
	 */
	@Test(timeout = 10000)
	public void variableChildrenDecideTheResult() {
		Node okA = new Node("ok-a", false);
		Node okB = new Node("ok-b", false);
		Node fixed = new Node("bad-fixed", true);

		RecordingSource allPass = new RecordingSource();
		Node passing = new Node("bad-root", false, okA, fixed, new Node("bad-mid", false, okB));
		assertTrue(ParallelProcess.isCountFrom(passing, allPass));
		assertEquals(List.of(okA, okB), allPass.tested);

		RecordingSource oneFails = new RecordingSource();
		Node bad = new Node("bad", false);
		Node failing = new Node("ok-root", false, okA, new Node("ok-mid", false, bad));
		assertFalse(ParallelProcess.isCountFrom(failing, oneFails));
		assertTrue(oneFails.tested.contains(bad));
		assertFalse(oneFails.tested.contains(failing));
	}

	/** A source shared by several paths of the graph is tested only once. */
	@Test(timeout = 10000)
	public void sharedSourceIsTestedOnce() {
		RecordingSource source = new RecordingSource();
		Node shared = new Node("ok-shared", false);
		Node left = new Node("bad-left", false, shared);
		Node right = new Node("bad-right", false, shared);
		Node root = new Node("bad-root", false, left, right, shared);

		assertTrue(ParallelProcess.isCountFrom(root, source));
		assertEquals(List.of(shared), source.tested);
	}
}
