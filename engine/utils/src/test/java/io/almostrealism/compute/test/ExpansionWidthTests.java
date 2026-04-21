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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.ParallelProcessContext;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.PackedCollectionPad;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies that {@link Process#getExpansionWidth()} returns the expected value
 * on producers whose emitted expressions carry multiple branches, and that
 * {@link ParallelProcessContext#getExpansionWidth()} accumulates those values
 * along the tree under both max and product propagation semantics.
 *
 * <p>The tests stay at the Process/context level and do not compile anything —
 * they exercise the hook methods and the factory propagator directly.</p>
 *
 * @see Process#getExpansionWidth()
 * @see ParallelProcessContext
 */
public class ExpansionWidthTests extends TestSuiteBase {

	/**
	 * A multi-operand pointwise operation like {@code multiply(x, y)} emits
	 * the concatenation of both operands' full expressions (as {@code x · y}),
	 * so its expansion width is the operand count &mdash; {@code 2} for a
	 * binary multiply. This test documents that corrected semantic; earlier
	 * (pre-faithful-ew) iterations expected the default {@code 1}, which
	 * under-measured the fan-out.
	 */
	@Test(timeout = 15000)
	public void binaryMultiplyReportsWidthTwo() {
		PackedCollection input = new PackedCollection(shape(8)).randFill();
		CollectionProducer producer = cp(input).multiply(c(2.0));

		long width = Process.expansionWidth(producer);
		assertEquals("binary multiply emits x · y — two operand emissions",
				2L, width);
	}

	/**
	 * {@code greaterThan(a, b, trueValue, falseValue)} emits a ternary whose two
	 * branches both remain in the expression tree. Its expansion width is {@code 2}.
	 */
	@Test(timeout = 15000)
	public void greaterThanReportsWidthTwo() {
		PackedCollection a = new PackedCollection(shape(8)).randFill();
		PackedCollection b = new PackedCollection(shape(8)).randFill();
		PackedCollection t = new PackedCollection(shape(8)).randFill();
		PackedCollection f = new PackedCollection(shape(8)).randFill();

		Producer<PackedCollection> gt = greaterThan(cp(a), cp(b), cp(t), cp(f));
		long width = Process.expansionWidth(gt);
		assertEquals("greaterThan emits a 2-branch Conditional", 2L, width);
	}

	/**
	 * {@code lessThan} is symmetric with {@code greaterThan}: also a 2-branch
	 * ternary.
	 */
	@Test(timeout = 15000)
	public void lessThanReportsWidthTwo() {
		PackedCollection a = new PackedCollection(shape(8)).randFill();
		PackedCollection b = new PackedCollection(shape(8)).randFill();
		PackedCollection t = new PackedCollection(shape(8)).randFill();
		PackedCollection f = new PackedCollection(shape(8)).randFill();

		Producer<PackedCollection> lt = lessThan(cp(a), cp(b), cp(t), cp(f));
		long width = Process.expansionWidth(lt);
		assertEquals("lessThan emits a 2-branch Conditional", 2L, width);
	}

	/**
	 * A concatenation is built as a sum of pads; each pad emits
	 * {@code Conditional(inBounds, value, 0)} with expansion width {@code 2}.
	 * The concat itself materialises each piece's pad, so the overall structure
	 * carries a 2-branch expansion under every pad child.
	 */
	@Test(timeout = 15000)
	public void padReportsWidthTwo() {
		PackedCollection input = new PackedCollection(shape(4)).randFill();
		// Use the direct PackedCollectionPad constructor to avoid the reshape
		// wrapper that CollectionFeatures.pad introduces for traversal-axis
		// handling. getExpansionWidth is a per-node property, not a rollup;
		// the reshape's own width is 1 (it does not amplify), so the
		// underlying pad must be inspected directly.
		Producer<PackedCollection> padded = new PackedCollectionPad(
				shape(8), new TraversalPolicy(true, 2), cp(input));

		long width = Process.expansionWidth(padded);
		assertEquals("pad emits a 2-branch Conditional for the boundary check", 2L, width);
	}

	/**
	 * Under default (max) semantics, the context accumulator retains the largest
	 * expansion width seen on any ancestor. A chain of two conditional producers
	 * each of width 2 yields accumulated width 2, not 4.
	 */
	@Test(timeout = 15000)
	public void contextMaxSemanticsAccumulatesWithMax() {
		boolean prior = ParallelProcessContext.enableProductExpansionWidth;
		ParallelProcessContext.enableProductExpansionWidth = false;
		try {
			PackedCollection a = new PackedCollection(shape(8)).randFill();
			PackedCollection b = new PackedCollection(shape(8)).randFill();
			PackedCollection t = new PackedCollection(shape(8)).randFill();
			PackedCollection f = new PackedCollection(shape(8)).randFill();

			Producer<PackedCollection> inner = greaterThan(cp(a), cp(b), cp(t), cp(f));
			Producer<PackedCollection> outer = greaterThan(cp(a), cp(b), inner, cp(f));

			ParallelProcessContext base = ParallelProcessContext.of(ProcessContext.base());
			ParallelProcessContext outerCtx = ParallelProcessContext.of(base, (ParallelProcess) outer);
			ParallelProcessContext innerCtx = ParallelProcessContext.of(outerCtx, (ParallelProcess) inner);

			assertEquals("outer context reflects outer greaterThan", 2L, outerCtx.getExpansionWidth());
			assertEquals("nested greaterThans accumulate as max, not product",
					2L, innerCtx.getExpansionWidth());
		} finally {
			ParallelProcessContext.enableProductExpansionWidth = prior;
		}
	}

	/**
	 * When product semantics are enabled via
	 * {@link ParallelProcessContext#enableProductExpansionWidth}, nested conditional
	 * producers compound: two stacked ternaries of width 2 yield accumulated width 4.
	 */
	@Test(timeout = 15000)
	public void contextProductSemanticsCompoundsStackedConditionals() {
		boolean prior = ParallelProcessContext.enableProductExpansionWidth;
		ParallelProcessContext.enableProductExpansionWidth = true;
		try {
			PackedCollection a = new PackedCollection(shape(8)).randFill();
			PackedCollection b = new PackedCollection(shape(8)).randFill();
			PackedCollection t = new PackedCollection(shape(8)).randFill();
			PackedCollection f = new PackedCollection(shape(8)).randFill();

			Producer<PackedCollection> inner = greaterThan(cp(a), cp(b), cp(t), cp(f));
			Producer<PackedCollection> outer = greaterThan(cp(a), cp(b), inner, cp(f));

			ParallelProcessContext base = ParallelProcessContext.of(ProcessContext.base());
			ParallelProcessContext outerCtx = ParallelProcessContext.of(base, (ParallelProcess) outer);
			ParallelProcessContext innerCtx = ParallelProcessContext.of(outerCtx, (ParallelProcess) inner);

			assertEquals("outer context reflects outer greaterThan", 2L, outerCtx.getExpansionWidth());
			assertEquals("nested greaterThans compound to 4 under product semantics",
					4L, innerCtx.getExpansionWidth());
		} finally {
			ParallelProcessContext.enableProductExpansionWidth = prior;
		}
	}

	/**
	 * Product-semantic propagation compounds each ancestor's expansion width
	 * into the child context. With faithful per-node widths, a binary multiply
	 * (ew 2) nested inside a greater-than (ew 2) yields accumulated width 4 at
	 * the multiply's level.
	 */
	@Test(timeout = 15000)
	public void multiplyNestedUnderGreaterThanCompoundsToFour() {
		boolean prior = ParallelProcessContext.enableProductExpansionWidth;
		ParallelProcessContext.enableProductExpansionWidth = true;
		try {
			PackedCollection a = new PackedCollection(shape(8)).randFill();
			PackedCollection b = new PackedCollection(shape(8)).randFill();
			PackedCollection f = new PackedCollection(shape(8)).randFill();

			// outer: 2-branch conditional; inner: binary multiply (ew 2)
			Producer<PackedCollection> inner = cp(a).multiply(c(2.0));
			Producer<PackedCollection> outer = greaterThan(cp(a), cp(b), (CollectionProducer) inner, cp(f));

			ParallelProcessContext base = ParallelProcessContext.of(ProcessContext.base());
			ParallelProcessContext outerCtx = ParallelProcessContext.of(base, (ParallelProcess) outer);
			assertEquals(2L, outerCtx.getExpansionWidth());

			ParallelProcessContext innerCtx = ParallelProcessContext.of(outerCtx, (ParallelProcess) inner);
			assertEquals("binary multiply (ew 2) under greaterThan (ew 2) = 4",
					4L, innerCtx.getExpansionWidth());
		} finally {
			ParallelProcessContext.enableProductExpansionWidth = prior;
		}
	}
}
