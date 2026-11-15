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
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Computation;
import io.almostrealism.compute.Process;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
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
public class ArgumentLimitFoldTest extends TestSuiteBase {
	/** Number of elements in each input; well under the aggregation size limit. */
	private static final int SIZE = 8;

	/** Number of small inputs summed by the probe computation. */
	private static final int INPUTS = 6;

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
