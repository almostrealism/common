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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Computation;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.IndexMaskTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.TraversableDeltaComputation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.OptionalInt;

/**
 * Pins the policy that decides whether a kernel folds its small arguments into an aggregate
 * argument, against compute contexts with an explicit argument limit.
 *
 * <p>A kernel that provably fits its context's argument limit folds nothing, so it pays no
 * aggregate copies; a kernel that may not fit, or one compiled for a context with no known limit,
 * folds every eligible argument exactly as before. The limit is supplied by wrapping the local
 * compute context, never by changing global state, so every case runs on any backend.</p>
 */
public class ArgumentLimitFoldTest extends TestSuiteBase implements ExpressionFeatures {
	/** Number of elements in each input; well under the aggregation size limit. */
	private static final int SIZE = 8;

	/** Number of small inputs summed by the probe computation. */
	private static final int INPUTS = 6;

	/**
	 * Number of terms in the masked sum, and of elements it is assigned to; past the width at
	 * which the traversal generator reorders a sum.
	 */
	private static final int MASKED_SUM_WIDTH = 24;

	/** Room left under the limit, beyond the bound, for the masked sum kernel's structure arguments. */
	private static final int MASKED_SUM_ROOM = 4;

	/**
	 * The decision itself: with no known limit every kernel folds, and with a known limit only a
	 * kernel whose bound exceeds it does.
	 */
	@Test(timeout = 10000)
	public void foldingRequiredOnlyPastKnownLimit() {
		ComputeContext<MemoryData> unknown = new LimitedComputeContext(OptionalInt.empty());
		Assert.assertFalse(unknown.fitsArgumentLimit(1));
		Assert.assertFalse(unknown.fitsArgumentLimit(Integer.MAX_VALUE));

		ComputeContext<MemoryData> limited = new LimitedComputeContext(OptionalInt.of(29));
		Assert.assertTrue(limited.fitsArgumentLimit(1));
		Assert.assertTrue(limited.fitsArgumentLimit(29));
		Assert.assertFalse(limited.fitsArgumentLimit(30));
		Assert.assertFalse(limited.fitsArgumentLimit(Integer.MAX_VALUE));
	}

	/**
	 * Metal's limit is its buffer table less the two entries bound after the last argument.
	 */
	@Test(timeout = 10000)
	public void metalLimitIsBufferTableLessTrailingEntries() {
		Assert.assertEquals(29, MetalOperator.MAX_ARGUMENTS);
		Assert.assertEquals(MetalOperator.BUFFER_TABLE_ENTRIES - MetalOperator.TRAILING_BUFFER_ENTRIES,
				MetalOperator.MAX_ARGUMENTS);
	}

	/**
	 * Leaves are counted by position, so one input used twice counts twice, and counting stops
	 * once the cap is passed.
	 */
	@Test(timeout = 30000)
	public void leavesCountedByPositionAndCapped() {
		PackedCollection a = new PackedCollection(SIZE).fill(1.0);
		PackedCollection b = new PackedCollection(SIZE).fill(2.0);

		int distinct = leaves(cp(a).add(cp(b)), 100);
		int shared = leaves(cp(a).add(cp(a)), 100);
		Assert.assertEquals(distinct, shared);

		int full = leaves(sumOfInputs(inputs(INPUTS)), 1000);
		Assert.assertTrue("full=" + full, full >= INPUTS);
		Assert.assertEquals(full, leaves(sumOfInputs(inputs(INPUTS)), full));

		int capped = leaves(sumOfInputs(inputs(INPUTS)), 2);
		Assert.assertTrue("capped=" + capped, capped > 2);
		Assert.assertTrue("capped=" + capped + " full=" + full, capped <= full);
	}

	/**
	 * Under a limit the kernel fits, nothing is folded; one argument below its bound, every
	 * eligible argument is folded, exactly as with no known limit. The result is correct in
	 * every case.
	 */
	@Test(timeout = 60000)
	public void foldsNothingUnderLimitAndEverythingOverIt() {
		FoldProbe unlimited = run(new LimitedComputeContext(OptionalInt.empty()));
		Assert.assertTrue("folded=" + unlimited.getFolded(), unlimited.getFolded() >= INPUTS);

		int bound = unlimited.getBound(Integer.MAX_VALUE - 1);
		Assert.assertTrue("bound=" + bound, bound > INPUTS);
		Assert.assertTrue("bound=" + bound, bound < MetalOperator.MAX_ARGUMENTS);

		FoldProbe fits = run(new LimitedComputeContext(OptionalInt.of(bound)));
		Assert.assertEquals(0, fits.getFolded());

		FoldProbe overflows = run(new LimitedComputeContext(OptionalInt.of(bound - 1)));
		Assert.assertEquals(unlimited.getFolded(), overflows.getFolded());
	}

	/**
	 * A kernel that fits is allowed exactly the room its bound leaves under the limit for the
	 * arguments compilation adds; a kernel that folds, or one compiled with no known limit, is
	 * not limited.
	 */
	@Test(timeout = 60000)
	public void allowanceIsTheRoomUnderTheLimit() {
		int bound = run(new LimitedComputeContext(OptionalInt.empty())).getBound(Integer.MAX_VALUE - 1);
		Computation<Void> computation = a(cp(new PackedCollection(SIZE)).each(), sumOfInputs(inputs(INPUTS)).each());

		Assert.assertEquals(Integer.MAX_VALUE,
				new FoldProbe(new LimitedComputeContext(OptionalInt.empty()), computation).getAllowance());
		Assert.assertEquals(Integer.MAX_VALUE,
				new FoldProbe(new LimitedComputeContext(OptionalInt.of(bound - 1)), computation).getAllowance());
		Assert.assertEquals(0,
				new FoldProbe(new LimitedComputeContext(OptionalInt.of(bound)), computation).getAllowance());
		Assert.assertEquals(3,
				new FoldProbe(new LimitedComputeContext(OptionalInt.of(bound + 3)), computation).getAllowance());
	}

	/**
	 * A kernel compiled with no room left under the limit folds nothing and is allowed nothing
	 * further, and still evaluates correctly.
	 */
	@Test(timeout = 60000)
	public void kernelWithNoAllowanceEvaluatesCorrectly() {
		int bound = run(new LimitedComputeContext(OptionalInt.empty())).getBound(Integer.MAX_VALUE - 1);

		FoldProbe exact = run(new LimitedComputeContext(OptionalInt.of(bound)));
		Assert.assertEquals(0, exact.getFolded());
		Assert.assertEquals(0, exact.getAllowance());
	}

	/**
	 * End to end through the real compile pipeline, with a kernel whose expression is a masked sum
	 * wide enough that the traversal generator reorders it into a lookup table, which is a kernel
	 * argument that is not a leaf of the computation. With no known limit, or with room under the
	 * limit, the table is created, so the kernel declares more arguments than with no room; with no
	 * room it is not, the sum is compiled inline, and the kernel stays within its bound. Every
	 * kernel under a limit stays within it and folds nothing, and every kernel computes the same
	 * values.
	 */
	@Test(timeout = 120000)
	public void structureArgumentsStayWithinTheLimit() {
		boolean previousReorder = Sum.enableGenerateReordering;
		Sum.enableGenerateReordering = true;

		try {
			MaskedSumProbe unlimited = runMaskedSum("unlimited", new LimitedComputeContext(OptionalInt.empty()));
			int bound = unlimited.getBound(Integer.MAX_VALUE - 1);
			Assert.assertTrue("bound=" + bound, bound < MetalOperator.MAX_ARGUMENTS);

			MaskedSumProbe none = runMaskedSum("none", new LimitedComputeContext(OptionalInt.of(bound)));
			Assert.assertEquals(0, none.getAllowance());
			Assert.assertEquals(0, none.getFolded());
			Assert.assertTrue("arguments=" + none.getArgumentCount() + " bound=" + bound,
					none.getArgumentCount() <= bound);
			Assert.assertTrue("arguments=" + unlimited.getArgumentCount() + " without room=" + none.getArgumentCount(),
					unlimited.getArgumentCount() > none.getArgumentCount());

			int limit = bound + MASKED_SUM_ROOM;
			MaskedSumProbe room = runMaskedSum("room", new LimitedComputeContext(OptionalInt.of(limit)));
			Assert.assertEquals(MASKED_SUM_ROOM, room.getAllowance());
			Assert.assertEquals(0, room.getFolded());
			Assert.assertTrue("arguments=" + room.getArgumentCount() + " limit=" + limit,
					room.getArgumentCount() <= limit);
			Assert.assertTrue("arguments=" + room.getArgumentCount() + " without room=" + none.getArgumentCount(),
					room.getArgumentCount() > none.getArgumentCount());
		} finally {
			Sum.enableGenerateReordering = previousReorder;
		}
	}

	/**
	 * An input whose ordering reads a collection binds that collection as a kernel argument with
	 * no position in the process tree, so the bound counts it on top of the leaves. A limit equal
	 * to the bound without it, which a leaf count alone would judge to fit, folds instead; a limit
	 * equal to the full bound folds nothing and the kernel stays within it. Both compute the same
	 * values.
	 */
	@Test(timeout = 60000)
	public void boundCountsArgumentsOrderedInputsBind() {
		LimitedComputeContext unknown = new LimitedComputeContext(OptionalInt.empty());
		int plainBound = new FoldProbe(unknown, orderedSum(new PackedCollection(shape(2, 2)), false))
				.getBound(Integer.MAX_VALUE - 1);
		int orderedBound = new FoldProbe(unknown, orderedSum(new PackedCollection(shape(2, 2)), true))
				.getBound(Integer.MAX_VALUE - 1);
		Assert.assertEquals(plainBound + 1, orderedBound);

		FoldProbe fits = runOrderedSum(new LimitedComputeContext(OptionalInt.of(orderedBound)));
		Assert.assertEquals(0, fits.getFolded());
		Assert.assertEquals(0, fits.getAllowance());
		Assert.assertTrue("arguments=" + fits.getArgumentCount() + " bound=" + orderedBound,
				fits.getArgumentCount() <= orderedBound);

		FoldProbe leavesOnly = runOrderedSum(new LimitedComputeContext(OptionalInt.of(plainBound)));
		Assert.assertEquals(Integer.MAX_VALUE, leavesOnly.getAllowance());
		Assert.assertTrue("folded=" + leavesOnly.getFolded(), leavesOnly.getFolded() > 0);
	}

	/**
	 * The target of a derivative is bound as a kernel argument without being an input of the
	 * derivative, so the derivative reports it as a scope argument and its bound is one more
	 * than its output and leaves.
	 */
	@Test(timeout = 30000)
	public void boundCountsTheTargetOfADerivative() {
		PackedCollection source = new PackedCollection(SIZE).fill(1.0);
		PackedCollection target = new PackedCollection(SIZE).fill(2.0);

		TraversableDeltaComputation delta = TraversableDeltaComputation.create("argumentLimitDelta",
				shape(SIZE), shape(SIZE),
				args -> DefaultCollectionExpression.create(shape(SIZE), idx -> args[1].getValueAt(idx)),
				cp(target), cp(source));
		Assert.assertEquals(1, delta.getScopeArgumentCount());

		MaskedSumProbe probe = new MaskedSumProbe(new LimitedComputeContext(OptionalInt.empty()), delta);
		Assert.assertEquals(1 + delta.countLeaves(Integer.MAX_VALUE - 1) + 1,
				probe.getBound(Integer.MAX_VALUE - 1));
	}

	/**
	 * Weights are summed over every position of the tree, the root included, and the sum stops
	 * once it passes the cap.
	 */
	@Test(timeout = 30000)
	public void sumByPositionCountsEveryPositionAndIsCapped() {
		PackedCollection a = new PackedCollection(SIZE).fill(1.0);
		Process<?, ?> shared = (Process<?, ?>) cp(a).add(cp(a));

		int nodes = shared.sumByPosition(node -> 1, 1000);
		Assert.assertTrue("nodes=" + nodes, nodes >= 3);
		Assert.assertEquals(shared.countLeaves(1000), shared.sumByPosition(node ->
				node.getChildren() == null || node.getChildren().isEmpty() ? 1 : 0, 1000));
		Assert.assertEquals(0, shared.sumByPosition(node -> 0, 0));

		int capped = shared.sumByPosition(node -> 1, 1);
		Assert.assertTrue("capped=" + capped, capped > 1);
		Assert.assertTrue("capped=" + capped + " nodes=" + nodes, capped < nodes);
	}

	/**
	 * Assigns to the given output the sum of {@code [[1, 2], [3, 4]]} and {@code [[2, 0], [0, 3]]},
	 * the second either dense or stored compactly through a mask ordering.
	 *
	 * @param out     the 2 x 2 output
	 * @param ordered whether the second input is stored through a mask ordering
	 * @return the assignment
	 */
	private Computation<Void> orderedSum(PackedCollection out, boolean ordered) {
		PackedCollection plain = pack(1.0, 2.0, 3.0, 4.0).reshape(shape(2, 2));
		PackedCollection second = ordered
				? new PackedCollection(shape(2, 2), 1, pack(2.0, 3.0), 0, new IndexMaskTraversalOrdering(pack(0, 3)))
				: pack(2.0, 0.0, 0.0, 3.0).reshape(shape(2, 2));
		return a(cp(out).each(), cp(plain).add(cp(second)).each());
	}

	/**
	 * Runs the {@link #orderedSum(PackedCollection, boolean) ordered sum} under the given context and checks its
	 * output.
	 *
	 * @param context the context to compile and run under
	 * @return the operation that ran
	 */
	private FoldProbe runOrderedSum(LimitedComputeContext context) {
		PackedCollection out = new PackedCollection(shape(2, 2)).fill(-1.0);

		FoldProbe op = new FoldProbe(context, orderedSum(out, true));
		op.run();

		double[] expected = { 3.0, 2.0, 3.0, 7.0 };
		double[] values = out.toArray();
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], values[i]);
		}

		return op;
	}

	/**
	 * Evaluates a computation whose element at each kernel index is the
	 * {@link #maskedSum(Expression, int) masked sum} under the given context, and checks that
	 * element {@code i} is {@code i + 1}.
	 *
	 * @param tag     a name suffix distinguishing this variant, so that no compiled kernel is
	 *                shared with another variant
	 * @param context the context to compile and run under
	 * @return the evaluable that ran
	 */
	private MaskedSumProbe runMaskedSum(String tag, LimitedComputeContext context) {
		TraversalPolicy shape = shape(MASKED_SUM_WIDTH);
		CollectionExpression expression =
				DefaultCollectionExpression.create(shape, idx -> maskedSum(new KernelIndex(), MASKED_SUM_WIDTH));

		MaskedSumProbe op = new MaskedSumProbe(context,
				new DefaultTraversableExpressionComputation("argumentLimitMaskedSum_" + tag, shape, expression));
		double[] values = op.evaluate().toArray();
		for (int i = 0; i < MASKED_SUM_WIDTH; i++) {
			assertEquals(i + 1.0, values[i]);
		}

		return op;
	}

	/**
	 * Builds a {@link Sum} of {@code n} single-index-masked terms: term {@code j} is {@code j + 1}
	 * where {@code index} is {@code j}, and {@code 0} elsewhere.
	 *
	 * @param index the index of the element whose value the sum is
	 * @param n     the number of masked terms
	 * @return the sum
	 */
	private Expression<?> maskedSum(Expression<?> index, int n) {
		Expression<?>[] terms = new Expression[n];
		for (int j = 0; j < n; j++) {
			terms[j] = Mask.of(index.eq(j), e((double) (j + 1)));
		}

		return Sum.of(terms);
	}

	/**
	 * Computations sharing a signature under the same limited context share one kernel: the
	 * reusing computation replays the fold, reaches the same decision, and reads its own inputs,
	 * whether the shared kernel folds nothing or folds everything.
	 */
	@Test(timeout = 60000)
	public void reuseReproducesTheDecision() {
		int bound = run(new LimitedComputeContext(OptionalInt.empty())).getBound(Integer.MAX_VALUE - 1);

		for (OptionalInt limit : new OptionalInt[] { OptionalInt.of(bound), OptionalInt.of(bound - 1) }) {
			LimitedComputeContext context = new LimitedComputeContext(limit);
			FoldProbe first = run(context);
			FoldProbe second = run(context);
			Assert.assertEquals("limit=" + limit, first.getFolded(), second.getFolded());
		}
	}

	/**
	 * Builds the probe assignment over fresh inputs, runs it under the given context, and checks
	 * that every output element is the sum of the inputs.
	 *
	 * @param context the context to compile and run under
	 * @return the operation that ran
	 */
	private FoldProbe run(LimitedComputeContext context) {
		PackedCollection[] inputs = inputs(INPUTS);
		PackedCollection out = new PackedCollection(SIZE).fill(-1.0);

		FoldProbe op = new FoldProbe(context, a(cp(out).each(), sumOfInputs(inputs).each()));
		op.run();

		double expected = INPUTS * (INPUTS + 1) / 2.0;
		double[] values = out.toArray();
		for (int i = 0; i < SIZE; i++) {
			assertEquals(expected, values[i]);
		}

		return op;
	}

	/**
	 * Creates the probe inputs, the {@code i}-th filled with {@code i + 1}.
	 *
	 * @param count the number of inputs
	 * @return the inputs
	 */
	private static PackedCollection[] inputs(int count) {
		PackedCollection[] inputs = new PackedCollection[count];
		for (int i = 0; i < count; i++) {
			inputs[i] = new PackedCollection(SIZE).fill(i + 1.0);
		}

		return inputs;
	}

	/**
	 * Describes the sum of the given inputs as one computation graph.
	 *
	 * @param inputs the inputs to sum
	 * @return the producer of their sum
	 */
	private CollectionProducer sumOfInputs(PackedCollection[] inputs) {
		CollectionProducer sum = cp(inputs[0]);
		for (int i = 1; i < inputs.length; i++) {
			sum = sum.add(cp(inputs[i]));
		}

		return sum;
	}

	/**
	 * Counts the leaves of the process tree of the given producer.
	 *
	 * @param producer the producer, which must be a {@link Process}
	 * @param cap      the cap passed to {@link Process#countLeaves(int)}
	 * @return the capped leaf count
	 */
	private static int leaves(CollectionProducer producer, int cap) {
		return ((Process<?, ?>) producer).countLeaves(cap);
	}

	/**
	 * An operation that reports how many arguments its kernel folded and its structural
	 * argument bound.
	 */
	private static class FoldProbe extends AcceleratedComputationOperation<Void> {
		/**
		 * Creates the probe for the given computation under the given context.
		 *
		 * @param context     the compute context
		 * @param computation the computation
		 */
		FoldProbe(ComputeContext<MemoryData> context, Computation<Void> computation) {
			super(context, computation);
		}

		/** Returns the number of arguments folded into this operation's aggregate. */
		int getFolded() {
			return argumentMap == null ? 0 : argumentMap.getFoldedArgumentCount();
		}

		/**
		 * Returns this operation's structural argument bound.
		 *
		 * @param cap the cap passed to the bound
		 * @return the bound
		 */
		int getBound(int cap) {
			return getArgumentBound(cap);
		}

		/** Returns the arguments compilation may add to this operation's kernel. */
		int getAllowance() {
			return getArgumentAllowance();
		}

		/** Returns the number of arguments this operation's compiled kernel declares. */
		int getArgumentCount() {
			return getArgumentVariables().size();
		}
	}

	/**
	 * An evaluable that reports how many arguments its kernel declares and folded, its structural
	 * argument bound, and the allowance that bound leaves under the limit.
	 */
	private static class MaskedSumProbe extends AcceleratedComputationEvaluable<PackedCollection> {
		/**
		 * Creates the probe for the given computation under the given context.
		 *
		 * @param context     the compute context
		 * @param computation the computation
		 */
		MaskedSumProbe(ComputeContext<MemoryData> context, Computation<PackedCollection> computation) {
			super(context, computation);
		}

		/** Returns the number of arguments folded into this operation's aggregate. */
		int getFolded() {
			return argumentMap == null ? 0 : argumentMap.getFoldedArgumentCount();
		}

		/**
		 * Returns this operation's structural argument bound.
		 *
		 * @param cap the cap passed to the bound
		 * @return the bound
		 */
		int getBound(int cap) {
			return getArgumentBound(cap);
		}

		/** Returns the number of arguments this operation's compiled kernel declares. */
		int getArgumentCount() {
			return getArgumentVariables().size();
		}

		/** Returns the arguments compilation may add to this operation's kernel. */
		int getAllowance() {
			return getArgumentAllowance();
		}
	}

	/**
	 * The local compute context, reporting an explicit argument limit in place of its own.
	 */
	private static class LimitedComputeContext implements ComputeContext<MemoryData> {
		/** The context every operation is delegated to. */
		private final ComputeContext<MemoryData> delegate;

		/** The limit this context reports. */
		private final OptionalInt limit;

		/**
		 * Wraps the local compute context.
		 *
		 * @param limit the limit to report
		 */
		LimitedComputeContext(OptionalInt limit) {
			this.delegate = Hardware.getLocalHardware().getComputeContext();
			this.limit = limit;
		}

		@Override
		public OptionalInt getArgumentLimit() { return limit; }

		@Override
		public DataContext<MemoryData> getDataContext() { return delegate.getDataContext(); }

		@Override
		public LanguageOperations getLanguage() { return delegate.getLanguage(); }

		@Override
		public InstructionSet deliver(Scope scope) { return delegate.deliver(scope); }

		@Override
		public void runLater(Runnable runnable) { delegate.runLater(runnable); }

		@Override
		public boolean isExecutorThread() { return delegate.isExecutorThread(); }

		@Override
		public boolean isCPU() { return delegate.isCPU(); }

		@Override
		public int getLaneCount() { return delegate.getLaneCount(); }

		@Override
		public Semaphore copy(MemoryData source, MemoryData destination, Semaphore dependsOn) {
			return delegate.copy(source, destination, dependsOn);
		}

		@Override
		public boolean isProfiling() { return delegate.isProfiling(); }

		@Override
		public void destroy() { }

		@Override
		public boolean isDestroyed() { return delegate.isDestroyed(); }
	}
}
