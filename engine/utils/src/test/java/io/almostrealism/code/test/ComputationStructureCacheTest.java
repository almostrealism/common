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

import io.almostrealism.code.ComputationBase;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pins how {@link ComputationBase} answers the two structural questions that optimization
 * and every traversal of a process tree ask of each node: whether its count is fixed, and
 * which processes are its children. Both follow from the inputs alone, so each is determined
 * once for a set of inputs and determined again only when the inputs are replaced. Before
 * this, a question about one node walked the whole graph below it every time it was asked,
 * which made optimizing a graph quadratic in its size. The tests count how often the inputs
 * are consulted, so they hold on any backend.
 */
public class ComputationStructureCacheTest extends TestSuiteBase {

	/** An input that is not a {@link Process} and records how often its count is examined. */
	private static final class CountingInput implements Producer<PackedCollection>, Countable {
		/** Whether the count of this input is fixed. */
		private final boolean fixed;

		/** The number of times {@link #isFixedCount()} has been called. */
		private int fixedCountQueries;

		/**
		 * Creates an input.
		 *
		 * @param fixed whether the count of the input is fixed
		 */
		private CountingInput(boolean fixed) {
			this.fixed = fixed;
		}

		@Override
		public Evaluable<PackedCollection> get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getCountLong() { return 1; }

		@Override
		public boolean isFixedCount() {
			fixedCountQueries++;
			return fixed;
		}
	}

	/** An input that is itself a {@link Process}, with no children of its own. */
	private static final class ProcessInput implements Producer<PackedCollection>,
			Process<Process<?, ?>, Evaluable<? extends PackedCollection>> {
		@Override
		public Evaluable<PackedCollection> get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }
	}

	/** A computation whose inputs a test can replace. */
	private static final class Node extends ComputationBase<PackedCollection, PackedCollection, Evaluable<PackedCollection>> {
		/**
		 * Creates a computation over the given inputs.
		 *
		 * @param inputs the inputs
		 */
		private Node(List<Producer<PackedCollection>> inputs) {
			setInputs(inputs);
		}

		/**
		 * Replaces the inputs of this computation.
		 *
		 * @param inputs the new inputs
		 */
		private void replaceInputs(List<Producer<PackedCollection>> inputs) {
			setInputs(inputs);
		}

		@Override
		public Evaluable<PackedCollection> get() {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * The count of a computation is fixed when the count of every input is fixed, and the
	 * inputs are consulted once however often the computation is asked.
	 */
	@Test(timeout = 10000)
	public void fixedCountIsDeterminedOnceForTheInputs() {
		CountingInput a = new CountingInput(true);
		CountingInput b = new CountingInput(true);
		Node node = new Node(List.of(a, b));

		for (int i = 0; i < 5; i++) {
			assertTrue(node.isFixedCount());
		}

		Assert.assertEquals(1, a.fixedCountQueries);
		Assert.assertEquals(1, b.fixedCountQueries);
	}

	/** One input whose count is variable makes the count of the computation variable. */
	@Test(timeout = 10000)
	public void variableInputMakesTheCountVariable() {
		CountingInput variable = new CountingInput(false);
		Node node = new Node(List.of(new CountingInput(true), variable));

		assertFalse(node.isFixedCount());
		assertFalse(node.isFixedCount());
		Assert.assertEquals(1, variable.fixedCountQueries);
	}

	/** Replacing the inputs makes the computation consult the new inputs. */
	@Test(timeout = 10000)
	public void replacingInputsRedeterminesFixedCount() {
		CountingInput fixed = new CountingInput(true);
		Node node = new Node(List.of(fixed));
		assertTrue(node.isFixedCount());

		CountingInput variable = new CountingInput(false);
		node.replaceInputs(List.of(new CountingInput(true), variable));
		assertFalse(node.isFixedCount());
		Assert.assertEquals(1, variable.fixedCountQueries);

		node.replaceInputs(List.of(fixed));
		assertTrue(node.isFixedCount());
		Assert.assertEquals(2, fixed.fixedCountQueries);
	}

	/**
	 * The children of a computation are its inputs, with an input that is a {@link Process}
	 * presented as itself and any other input wrapped in a process, and the same children (the
	 * same wrappers included) are presented every time.
	 */
	@Test(timeout = 10000)
	public void childrenAreBuiltOnceForTheInputs() {
		ProcessInput process = new ProcessInput();
		CountingInput plain = new CountingInput(true);
		Node node = new Node(List.of(process, plain));

		Collection<Process<?, ?>> children = node.getChildren();
		List<Process<?, ?>> listed = new ArrayList<>(children);
		Assert.assertEquals(2, listed.size());
		Assert.assertSame(process, listed.get(0));
		assertNotNull(listed.get(1));

		Assert.assertSame(children, node.getChildren());
		Assert.assertSame(listed.get(1), new ArrayList<>(node.getChildren()).get(1));
	}

	/** The children cannot be changed through the collection that presents them. */
	@Test(timeout = 10000)
	public void childrenCannotBeModified() {
		Node node = new Node(List.of(new ProcessInput()));
		Collection<Process<?, ?>> children = node.getChildren();

		try {
			children.clear();
			Assert.fail("The children of a computation should not be modifiable");
		} catch (UnsupportedOperationException expected) {
			Assert.assertEquals(1, node.getChildren().size());
		}
	}

	/**
	 * The inputs are a snapshot that cannot be changed in place, either through
	 * {@link ComputationBase#getInputs()} or through the list they were given in, so the
	 * answers determined from them cannot go stale without the inputs being set again.
	 */
	@Test(timeout = 10000)
	public void inputsCannotBeReplacedInPlace() {
		ProcessInput original = new ProcessInput();
		List<Producer<PackedCollection>> given = new ArrayList<>(List.of(original));
		Node node = new Node(given);
		assertTrue(node.isFixedCount());
		Collection<Process<?, ?>> children = node.getChildren();

		given.set(0, new CountingInput(false));
		Assert.assertSame(original, node.getInputs().get(0));

		try {
			node.getInputs().set(0, new CountingInput(false));
			Assert.fail("The inputs of a computation should not be replaceable in place");
		} catch (UnsupportedOperationException expected) {
			Assert.assertSame(original, node.getInputs().get(0));
		}

		assertTrue(node.isFixedCount());
		Assert.assertSame(children, node.getChildren());
		Assert.assertSame(original, node.getChildren().iterator().next());
	}

	/** Replacing the inputs replaces the children. */
	@Test(timeout = 10000)
	public void replacingInputsRebuildsChildren() {
		ProcessInput first = new ProcessInput();
		ProcessInput second = new ProcessInput();
		Node node = new Node(List.of(first));
		Collection<Process<?, ?>> before = node.getChildren();

		node.replaceInputs(List.of(second));
		Collection<Process<?, ?>> after = node.getChildren();

		Assert.assertNotSame(before, after);
		Assert.assertEquals(1, after.size());
		Assert.assertSame(second, after.iterator().next());
	}
}
