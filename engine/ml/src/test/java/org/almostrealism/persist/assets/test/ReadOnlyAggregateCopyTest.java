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

package org.almostrealism.persist.assets.test;

import io.almostrealism.code.DataContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Precision;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that {@link MemoryDataArgumentMap} copies the aggregate slice of a read-only argument
 * that the kernel does not write in once, on the first dispatch, while every other slice is
 * still copied in before every dispatch, and that every dispatch, including one prepared
 * concurrently with the first, is ordered after that single copy.
 */
public class ReadOnlyAggregateCopyTest extends TestSuiteBase {

	/** Number of elements in each test tensor; small enough to be aggregated. */
	private static final int SIZE = 96;

	/** How many dispatches to prepare. */
	private static final int DISPATCHES = 4;

	/** How many threads prepare dispatches at once in the concurrent test. */
	private static final int THREADS = 8;

	/** Produces a collection whose values are a fixed function of the index. */
	private PackedCollection testValues() {
		PackedCollection c = new PackedCollection(shape(SIZE));
		integers(0, SIZE).multiply(0.25).add(1.0)
				.into(c.traverseEach()).evaluate();
		return c;
	}

	/**
	 * Returns a read-only (message-backed) collection holding the values of {@code source}.
	 *
	 * @param source the values
	 * @return a read-only collection holding them
	 */
	private PackedCollection readOnly(PackedCollection source) {
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), false);
		Assert.assertTrue("The collection must be read-only for this test to mean anything",
				deferred.isReadOnly());
		return deferred;
	}

	/**
	 * Creates an argument map whose copies run through {@code context} and whose aggregate buffers
	 * are recorded in {@code aggregates}.
	 *
	 * @param context    the context performing the copies
	 * @param aggregates receives every aggregate buffer created
	 * @return the argument map
	 */
	private MemoryDataArgumentMap map(CountingComputeContext context, List<PackedCollection> aggregates) {
		return MemoryDataArgumentMap.create(context, length -> {
			PackedCollection aggregate = new PackedCollection(length);
			aggregates.add(aggregate);
			return aggregate;
		});
	}

	/**
	 * Returns a kernel that assigns the first elements of {@code written} from those of
	 * {@code read}, so that it writes the slice of {@code written} and only reads that of
	 * {@code read}.
	 *
	 * @param written the argument the kernel writes
	 * @param read    the argument the kernel reads
	 * @return the kernel scope
	 */
	private Scope<?> assignKernel(ArrayVariable<?> written, ArrayVariable<?> read) {
		Scope<?> kernel = new Scope<>("assign");
		for (int i = 0; i < 2; i++) {
			kernel.assign(written.reference(new IntegerConstant(i)), read.reference(new IntegerConstant(i)));
		}
		return kernel;
	}

	/**
	 * Submits the map's copy-ins as a dispatch would, and waits for them.
	 *
	 * @param map the argument map
	 */
	private void dispatch(MemoryDataArgumentMap map) {
		Semaphore prepared = Submittable.submit(map.getPrepareOperations(), null);
		if (prepared != null) prepared.waitFor();
	}

	/**
	 * A read-only argument whose slice the kernel only reads is copied into the aggregate by the
	 * first dispatch and by no later one, while the writable argument is copied in by every
	 * dispatch, and the aggregate still holds the read-only values after every dispatch.
	 */
	@Test(timeout = 60000)
	public void readOnlySliceTheKernelDoesNotWriteIsCopiedOnce() {
		PackedCollection source = testValues();
		PackedCollection deferred = readOnly(source);
		List<PackedCollection> aggregates = new ArrayList<>();
		CountingComputeContext context = new CountingComputeContext(null);

		try (PackedCollection written = new PackedCollection(SIZE)) {
			MemoryDataArgumentMap map = map(context, aggregates);

			try {
				ArrayVariable<?> readArg = map.get(p(deferred));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.limitCopyBackTo(assignKernel(writtenArg, readArg));

				dispatch(map);
				Assert.assertEquals("The first dispatch copies the read-only slice", 1, context.readOnlyCopies.get());
				Assert.assertEquals(1, context.writableCopies.get());

				for (int d = 1; d < DISPATCHES; d++) {
					aggregates.get(0).setMem(SIZE, 99.0);
					dispatch(map);
				}

				Assert.assertEquals("Later dispatches do not copy the read-only slice again",
						1, context.readOnlyCopies.get());
				Assert.assertEquals("The writable slice is copied in by every dispatch",
						DISPATCHES, context.writableCopies.get());
				Assert.assertEquals(1, aggregates.size());

				double[] aggregate = aggregates.get(0).toArray();
				Assert.assertEquals("The writable slice was copied in again over the changed value",
						0.0, aggregate[SIZE], 0.0);
				for (int i = 0; i < SIZE; i++) {
					Assert.assertEquals("aggregate element " + i, source.toDouble(i), aggregate[i], 0.0);
				}
			} finally {
				map.destroy();
			}
		} finally {
			context.destroy();
			source.destroy();
		}
	}

	/**
	 * While the slices the kernel writes are unknown, a read-only slice is copied in by every
	 * dispatch, and so is a read-only slice the kernel is known to write: in either case the
	 * aggregate may no longer hold the source's values at the next dispatch.
	 */
	@Test(timeout = 60000)
	public void readOnlySliceIsCopiedEveryDispatchUnlessKnownUnwritten() {
		PackedCollection source = testValues();
		CountingComputeContext context = new CountingComputeContext(null);

		try (PackedCollection other = new PackedCollection(SIZE)) {
			MemoryDataArgumentMap unknown = map(context, new ArrayList<>());

			try {
				unknown.get(p(readOnly(source)));
				unknown.get(p(other));

				for (int d = 0; d < DISPATCHES; d++) dispatch(unknown);
				Assert.assertEquals("Unknown written slices keep the per-dispatch copy",
						DISPATCHES, context.readOnlyCopies.get());
			} finally {
				unknown.destroy();
			}

			context.readOnlyCopies.set(0);
			MemoryDataArgumentMap written = map(context, new ArrayList<>());

			try {
				ArrayVariable<?> readOnlyArg = written.get(p(readOnly(source)));
				ArrayVariable<?> otherArg = written.get(p(other));
				written.limitCopyBackTo(assignKernel(readOnlyArg, otherArg));

				for (int d = 0; d < DISPATCHES; d++) dispatch(written);
				Assert.assertEquals("A read-only slice the kernel writes is copied every dispatch",
						DISPATCHES, context.readOnlyCopies.get());
			} finally {
				written.destroy();
			}
		} finally {
			context.destroy();
			source.destroy();
		}
	}

	/**
	 * A dispatch prepared after the first one, while the first one's single copy of the read-only
	 * slice is still outstanding, is ordered after that copy even though it does not make it: its
	 * prepared completion does not settle until the copy has.
	 */
	@Test(timeout = 60000)
	public void laterDispatchIsOrderedAfterTheSingleCopy() throws InterruptedException {
		PackedCollection source = testValues();
		LatchSemaphore copying = new LatchSemaphore(1);
		CountingComputeContext context = new CountingComputeContext(copying);

		try (PackedCollection written = new PackedCollection(SIZE)) {
			MemoryDataArgumentMap map = map(context, new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(readOnly(source)));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.limitCopyBackTo(assignKernel(writtenArg, readArg));

				Semaphore first = Submittable.submit(map.getPrepareOperations(), null);
				Semaphore second = Submittable.submit(map.getPrepareOperations(), null);
				Assert.assertEquals(1, context.readOnlyCopies.get());
				Assert.assertNotNull("The first dispatch is ordered after the outstanding copy", first);
				Assert.assertNotNull("A later dispatch is ordered after the outstanding copy", second);

				AtomicBoolean released = new AtomicBoolean();
				Thread waiter = new Thread(() -> {
					second.waitFor();
					released.set(true);
				}, "ReadOnlyAggregateCopyTest waiter");
				waiter.setDaemon(true);
				waiter.start();

				waiter.join(250);
				Assert.assertFalse("A later dispatch must wait for the single copy", released.get());

				copying.countDown();
				waiter.join(10000);
				Assert.assertTrue("A later dispatch proceeds once the single copy completes", released.get());
				Assert.assertEquals(1, context.readOnlyCopies.get());
			} finally {
				map.destroy();
			}
		} finally {
			copying.countDown();
			context.destroy();
			source.destroy();
		}
	}

	/**
	 * Dispatches prepared concurrently on many threads make the copy of the read-only slice
	 * exactly once between them, and every one of them finds the aggregate holding its values.
	 */
	@Test(timeout = 60000)
	public void concurrentDispatchesCopyTheReadOnlySliceOnce() throws InterruptedException {
		PackedCollection source = testValues();
		List<PackedCollection> aggregates = new CopyOnWriteArrayList<>();
		CountingComputeContext context = new CountingComputeContext(null);
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		try (PackedCollection written = new PackedCollection(SIZE)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(context, length -> {
				PackedCollection aggregate = new PackedCollection(length);
				aggregates.add(aggregate);
				return aggregate;
			});

			try {
				ArrayVariable<?> readArg = map.get(p(readOnly(source)));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.limitCopyBackTo(assignKernel(writtenArg, readArg));

				CountDownLatch start = new CountDownLatch(1);
				List<Thread> threads = new ArrayList<>();

				for (int t = 0; t < THREADS; t++) {
					Thread thread = new Thread(() -> {
						try {
							start.await();
							dispatch(map);

							PackedCollection aggregate = aggregates.get(0);
							for (int i = 0; i < SIZE; i++) {
								Assert.assertEquals("aggregate element " + i,
										source.toDouble(i), aggregate.toDouble(i), 0.0);
							}
						} catch (Throwable e) {
							failures.add(e);
						}
					}, "ReadOnlyAggregateCopyTest dispatcher " + t);
					threads.add(thread);
					thread.start();
				}

				start.countDown();
				for (Thread thread : threads) {
					thread.join(TimeUnit.SECONDS.toMillis(30));
				}

				Assert.assertTrue("Concurrent dispatches failed: " + failures, failures.isEmpty());
				Assert.assertEquals("The read-only slice is copied exactly once", 1, context.readOnlyCopies.get());
				Assert.assertEquals(THREADS, context.writableCopies.get());
			} finally {
				map.destroy();
			}
		} finally {
			context.destroy();
			source.destroy();
		}
	}

	/**
	 * An operation reading a small read-only collection alongside a value it updates computes
	 * correctly on every one of many dispatches: the slice copied in once still holds the
	 * read-only values when later dispatches read it, and the collection is never migrated.
	 */
	@Test(timeout = 120000)
	public void repeatedDispatchReadsReadOnlyAggregatedValues() {
		PackedCollection source = testValues();
		PackedCollection deferred = readOnly(source);
		PackedCollection counter = new PackedCollection(shape(SIZE));
		PackedCollection result = new PackedCollection(shape(SIZE));

		OperationList step = new OperationList("Read-Only Aggregate Step", false);
		step.add(a(cp(counter).each(), cp(counter).add(1.0)));
		step.add(a(cp(result).each(), cp(counter).add(cp(deferred))));
		Runnable run = step.get();

		try {
			for (int d = 1; d <= DISPATCHES * 4; d++) {
				run.run();

				double[] actual = result.toArray();
				for (int i = 0; i < SIZE; i++) {
					Assert.assertEquals("dispatch " + d + " element " + i,
							d + source.toDouble(i), actual[i], 1e-9);
				}
			}

			Assert.assertEquals("Aggregated read-only arguments are not migrated",
					"PROTOBUF", deferred.getRootDelegate().getMem().getProvider().getName());
		} finally {
			Destroyable.destroy(run);
			source.destroy();
			counter.destroy();
			result.destroy();
		}
	}

	/**
	 * A context that makes copies as the host fallback does, counting the copies from read-only and
	 * from writable sources, and optionally reporting a copy from a read-only source made without
	 * a dependency as still running until a given completion fires, as a backend that queues its
	 * copies would. Only its copies are used.
	 */
	private static class CountingComputeContext extends AbstractComputeContext<DataContext<MemoryData>> {
		/** The number of copies made from read-only sources. */
		private final AtomicInteger readOnlyCopies = new AtomicInteger();
		/** The number of copies made from writable sources. */
		private final AtomicInteger writableCopies = new AtomicInteger();
		/** The completion reported for a read-only copy made without a dependency, or null. */
		private final Semaphore queued;

		/**
		 * Creates a context over the local hardware's data context.
		 *
		 * @param queued the completion reported for every read-only copy made without a
		 *               dependency, or null to report the copy's own completion
		 */
		CountingComputeContext(Semaphore queued) {
			super(Hardware.getLocalHardware().getComputeContext().getDataContext());
			this.queued = queued;
		}

		@Override
		public LanguageOperations getLanguage() { throw new UnsupportedOperationException(); }

		@Override
		public InstructionSet deliver(Scope scope) { throw new UnsupportedOperationException(); }

		@Override
		public boolean isCPU() { return true; }

		/** Makes the copy as the host fallback does, counting it by whether its source is read-only. */
		@Override
		public Semaphore copy(MemoryData source, MemoryData destination, Semaphore dependsOn) {
			boolean readOnly = source.isReadOnly();
			(readOnly ? readOnlyCopies : writableCopies).incrementAndGet();

			Semaphore copied = super.copy(source, destination, dependsOn);
			return readOnly && dependsOn == null && queued != null ? queued : copied;
		}
	}
}
