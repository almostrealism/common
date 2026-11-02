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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Precision;
import io.almostrealism.concurrent.Submittable;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.scope.Scope;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.MemoryReplacementManager;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.protobuf.Collections;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for the protobuf-backed memory provider: collections produced by
 * {@link CollectionEncoder#decode(Collections.CollectionData, boolean)} read from the message on the host,
 * migrate to the compute device at first kernel use, and reject writes while
 * still message-backed. A collection too large to aggregate but too small to
 * migrate is staged in a temporary of its own for each dispatch, and since nothing
 * writes it, that staging is not ordered after the work the kernel depends on.
 */
public class CollectionDataMemoryProviderTest extends TestSuiteBase {

	/** Number of elements in the test tensors. */
	private static final int SIZE = 96;

	/** How many dispatches to read a deferred collection through. */
	private static final int DISPATCHES = 16;

	/** How many frames of a collected failure to name. */
	private static final int ORIGIN_FRAMES = 12;

	/** Produces a collection whose values are a fixed function of the index. */
	private PackedCollection testValues() {
		PackedCollection c = new PackedCollection(shape(SIZE));
		integers(0, SIZE).multiply(0.25).add(1.0)
				.into(c.traverseEach()).evaluate();
		return c;
	}

	/**
	 * Host reads from a deferred collection must match the encoded source,
	 * at both encoded precisions, without the data leaving the message.
	 */
	@Test(timeout = 60000)
	public void deferredHostReadsMatchSource() {
		PackedCollection source = testValues();

		for (Precision precision : new Precision[] { Precision.FP64, Precision.FP32 }) {
			Collections.CollectionData data = CollectionEncoder.encode(source, precision);
			PackedCollection deferred = CollectionEncoder.decode(data, false);

			Assert.assertEquals("PROTOBUF",
					deferred.getRootDelegate().getMem().getProvider().getName());
			Assert.assertEquals(SIZE, deferred.getShape().getTotalSize());

			double tolerance = precision == Precision.FP64 ? 0.0 : 1e-6;
			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("element " + i + " at " + precision,
						source.toDouble(i), deferred.toDouble(i), tolerance);
			}
		}
	}

	/**
	 * A kernel consuming a small deferred collection computes correctly while
	 * the collection stays message-backed: below the aggregation threshold the
	 * framework copies the values into the dispatch aggregate (reading through
	 * the provider) instead of migrating the root, so host-mostly data never
	 * permanently occupies device memory.
	 */
	@Test(timeout = 120000)
	public void smallDeferredComputesWithoutMigrating() {
		PackedCollection source = testValues();
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), false);

		Assert.assertEquals("PROTOBUF",
				deferred.getRootDelegate().getMem().getProvider().getName());

		PackedCollection doubled = cp(deferred).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					2.0 * source.toDouble(i), doubled.toDouble(i), 1e-9);
		}

		Assert.assertEquals("small arguments aggregate per dispatch rather than migrating",
				"PROTOBUF", deferred.getRootDelegate().getMem().getProvider().getName());
	}

	/**
	 * A kernel consuming a deferred collection larger than the aggregation
	 * threshold (1,048,576 elements) triggers migration of the root
	 * reservation to the compute device, computes correctly, and leaves the
	 * collection reading from device memory afterward.
	 */
	@Test(timeout = 120000)
	public void largeDeferredMigratesAtFirstKernelUse() {
		int size = 1100000;
		PackedCollection source = new PackedCollection(shape(size));
		integers(0, size).multiply(0.001).add(1.0)
				.into(source.traverseEach()).evaluate();

		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), false);
		Assert.assertEquals("PROTOBUF",
				deferred.getRootDelegate().getMem().getProvider().getName());

		double[] expected = source.toArray();
		double[] doubled = cp(deferred).multiply(2.0).evaluate().toArray();
		for (int i = 0; i < size; i++) {
			Assert.assertEquals("element " + i, 2.0 * expected[i], doubled[i], 1e-9);
		}

		Assert.assertNotEquals("kernel use should have migrated the root reservation",
				"PROTOBUF", deferred.getRootDelegate().getMem().getProvider().getName());

		double[] after = deferred.toArray();
		for (int i = 0; i < size; i++) {
			Assert.assertEquals("element " + i + " after migration",
					expected[i], after[i], 1e-9);
		}
	}

	/**
	 * Materialized decoding copies the message's values into freshly allocated
	 * memory: the result matches the source, is not message-backed, and is
	 * ordinary writable storage.
	 */
	@Test(timeout = 60000)
	public void materializedDecodeCopiesIntoAllocatedMemory() {
		PackedCollection source = testValues();
		PackedCollection materialized = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), true);

		Assert.assertNotEquals("materialized collections use ordinary storage",
				"PROTOBUF", materialized.getRootDelegate().getMem().getProvider().getName());

		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					source.toDouble(i), materialized.toDouble(i), 1e-9);
		}

		materialized.setMem(0, 42.0);
		Assert.assertEquals(42.0, materialized.toDouble(0), 1e-9);
	}

	/**
	 * Reading a deferred collection arranges no write back into it.
	 *
	 * <p>An argument small enough to aggregate is copied into the dispatch
	 * aggregate and, for writable memory, copied back out afterwards. Message
	 * backed memory cannot accept that second copy, and nothing could have
	 * written to it in between for the copy to carry.</p>
	 *
	 * <p>Arranged regardless, it fails where it runs rather than where it was
	 * decided: on a pool thread, after the operation the test is watching has
	 * already returned. So the failure is collected from the threads it lands
	 * on rather than expected from this one — a test that only evaluated and
	 * asserted the values would pass with the write-back still there, which is
	 * how this went unnoticed to begin with.</p>
	 *
	 * <p>Collecting them is added to what already happens to them rather than
	 * put in its place. Anything else failing on a background thread during
	 * this window is still reported by whatever was reporting it before, which
	 * is the behaviour a test should be borrowing rather than suspending.</p>
	 */
	@Test(timeout = 120000)
	public void readingDeferredValuesArrangesNoWriteBack() {
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(testValues(), Precision.FP64), false);

		List<Throwable> failures = new CopyOnWriteArrayList<>();
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

		Thread.setDefaultUncaughtExceptionHandler((thread, thrown) -> {
			failures.add(thrown);
			if (previous != null) previous.uncaughtException(thread, thrown);
		});

		try {
			for (int i = 0; i < DISPATCHES; i++) {
				cp(deferred).multiply(2.0).evaluate();
			}
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(previous);
		}

		Assert.assertTrue("Reading message-backed memory must not arrange a write into it: "
				+ describe(failures), failures.isEmpty());
	}

	/**
	 * Describes collected failures by where they were raised.
	 *
	 * <p>The message alone says only that a write was attempted, which is the
	 * part already known. What has to be named is the code that arranged it,
	 * and these were raised on threads no test is watching, so the stack goes
	 * into the failure or nowhere.</p>
	 *
	 * @param failures the collected failures
	 * @return a description naming the first one's origin
	 */
	private String describe(List<Throwable> failures) {
		if (failures.isEmpty()) return "none";

		StringBuilder description = new StringBuilder();
		description.append(failures.size()).append(" of them, the first at:");

		StackTraceElement[] stack = failures.get(0).getStackTrace();

		for (int i = 0; i < Math.min(stack.length, ORIGIN_FRAMES); i++) {
			description.append("\n    at ").append(stack[i]);
		}

		return description.toString();
	}

	/**
	 * Creates a replacement manager with no target provider, so that every argument small enough
	 * to stage is replaced by a temporary of its own, with every copy made through {@code context}.
	 *
	 * @param context the context whose copies move the staged data
	 * @return a manager replacing every eligible argument
	 */
	private MemoryReplacementManager replacementManager(ComputeContext<MemoryData> context) {
		return new MemoryReplacementManager(context, null, (length, atomic) -> new Bytes(length, atomic));
	}

	/**
	 * The replacement copy of a read-only argument depends on no prior work: nothing writes a
	 * read-only source, and the temporary it is copied into exists only for this invocation.
	 * Submitted behind work that has not completed, the copy is therefore made at once, and the
	 * completion it returns is that work's own, so a kernel chained on it is ordered after that
	 * work exactly as it would be without a replacement, and not also after a host copy, which a
	 * batching backend could not chain on the device.
	 */
	@Test(timeout = 60000)
	public void readOnlyReplacementDoesNotWaitForPendingWork() {
		PackedCollection source = testValues();
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), false);
		LatchSemaphore pending = new LatchSemaphore(1);
		MemoryReplacementManager manager = replacementManager(Hardware.getLocalHardware().getComputeContext());

		try {
			MemoryData temporary = (MemoryData) manager.processArguments(new Object[] { deferred })[0];
			Assert.assertNotSame("The read-only argument is staged in a temporary", deferred, temporary);
			Assert.assertSame("The copy must add nothing to wait for beyond the pending work",
					pending, Submittable.submit(manager.getPrepareOperations(), pending));
			Assert.assertTrue("A read-only source is never copied back",
					manager.getPostprocessOperations().isEmpty());

			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("element " + i, source.toDouble(i), temporary.toDouble(i), 1e-9);
			}
		} finally {
			pending.countDown();
			manager.releaseTemporaries();
			source.destroy();
		}
	}

	/**
	 * The completion of a read-only replacement covers the work the operation depends on as well
	 * as the copy, whatever the context reports for the copy. A context that queues copies reports
	 * one made without a dependency as still running; the prepared completion must not settle when
	 * that copy does, because a kernel chained on it would then run before the work it depends on.
	 */
	@Test(timeout = 60000)
	public void readOnlyReplacementCompletionCoversPendingWork() throws InterruptedException {
		PackedCollection source = testValues();
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), false);
		LatchSemaphore pending = new LatchSemaphore(1);
		LatchSemaphore copying = new LatchSemaphore(1);
		QueuingComputeContext context = new QueuingComputeContext(
				Hardware.getLocalHardware().getComputeContext().getDataContext(), copying);
		MemoryReplacementManager manager = replacementManager(context);

		try {
			MemoryData temporary = (MemoryData) manager.processArguments(new Object[] { deferred })[0];
			Semaphore prepared = Submittable.submit(manager.getPrepareOperations(), pending);
			Assert.assertNotNull("The copy and the pending work are both outstanding", prepared);

			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("element " + i, source.toDouble(i), temporary.toDouble(i), 1e-9);
			}

			AtomicBoolean released = new AtomicBoolean();
			Thread waiter = new Thread(() -> {
				prepared.waitFor();
				released.set(true);
			}, "CollectionDataMemoryProviderTest prepared waiter");
			waiter.setDaemon(true);
			waiter.start();

			copying.countDown();
			waiter.join(250);
			Assert.assertFalse("The prepared completion must wait for the pending work", released.get());

			pending.countDown();
			waiter.join(10000);
			Assert.assertTrue("The prepared completion settles once the copy and the pending work have",
					released.get());
		} finally {
			copying.countDown();
			pending.countDown();
			manager.releaseTemporaries();
			context.destroy();
			source.destroy();
		}
	}

	/**
	 * The replacement copy of writable memory, by contrast, remains ordered after the work it
	 * follows, since that work may still be writing the source: behind work that has not
	 * completed it is left outstanding, and it delivers the source once that work completes.
	 */
	@Test(timeout = 60000)
	public void writableReplacementWaitsForPendingWork() {
		PackedCollection source = testValues();
		LatchSemaphore pending = new LatchSemaphore(1);
		MemoryReplacementManager manager = replacementManager(Hardware.getLocalHardware().getComputeContext());

		try {
			MemoryData temporary = (MemoryData) manager.processArguments(new Object[] { source })[0];
			Semaphore copied = Submittable.submit(manager.getPrepareOperations(), pending);
			Assert.assertNotNull("The copy must be ordered after the pending work", copied);

			pending.countDown();
			copied.waitFor();

			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("element " + i, source.toDouble(i), temporary.toDouble(i), 1e-9);
			}
		} finally {
			pending.countDown();
			manager.releaseTemporaries();
			source.destroy();
		}
	}

	/**
	 * A kernel that reads a read-only collection too large to aggregate, yet below the size at
	 * which it would migrate, computes correctly on every dispatch when it is chained after work
	 * it depends on: the collection is staged for each dispatch without waiting for that work,
	 * the work is still complete before the kernel reads what it wrote, and the collection
	 * remains message-backed afterwards.
	 */
	@Test(timeout = 120000)
	public void chainedKernelReadsStagedDeferredValues() {
		int size = 4096;
		PackedCollection source = new PackedCollection(shape(size));
		integers(0, size).multiply(0.5).into(source.traverseEach()).evaluate();
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(source, Precision.FP64), false);
		PackedCollection counter = new PackedCollection(shape(size));
		PackedCollection result = new PackedCollection(shape(size));

		OperationList step = new OperationList("Deferred Step", false);
		step.add(a(cp(counter).each(), cp(counter).add(1.0)));
		step.add(a(cp(result).each(), cp(counter).add(cp(deferred))));
		Runnable run = step.get();

		try {
			for (int i = 0; i < DISPATCHES; i++) {
				run.run();
			}

			double[] expected = source.toArray();
			double[] actual = result.toArray();
			for (int i = 0; i < size; i++) {
				Assert.assertEquals("element " + i, DISPATCHES + expected[i], actual[i], 1e-9);
			}

			Assert.assertEquals("staged arguments are not migrated",
					"PROTOBUF", deferred.getRootDelegate().getMem().getProvider().getName());
		} finally {
			Destroyable.destroy(run);
			source.destroy();
			counter.destroy();
			result.destroy();
		}
	}

	/**
	 * Message-backed memory is a read-only source: writes are rejected rather
	 * than silently lost, since migration is one-way.
	 */
	@Test(timeout = 60000)
	public void deferredRejectsWritesBeforeMigration() {
		PackedCollection deferred = CollectionEncoder.decode(
				CollectionEncoder.encode(testValues(), Precision.FP64), false);

		try {
			deferred.setMem(0, 1.0);
			Assert.fail("Write into message-backed memory should be rejected");
		} catch (UnsupportedOperationException e) {
			// Expected: the provider is a read-only source
		}
	}

	/**
	 * A {@link StateDictionary} saved to disk and reloaded with deferred
	 * weights enabled serves identical values, and its weights work as
	 * kernel arguments.
	 */
	@Test(timeout = 120000)
	public void stateDictionaryRoundTripWithDeferredWeights() throws IOException {
		Map<String, PackedCollection> weights = new HashMap<>();
		weights.put("layer.alpha", testValues());
		weights.put("layer.beta", testValues());

		Path dir = Files.createTempDirectory("deferred-weights-test");
		new StateDictionary(weights).save(dir.resolve("weights.pb"), Precision.FP64);

		boolean previous = StateDictionary.enableMaterializeWeights;
		StateDictionary.enableMaterializeWeights = false;

		try {
			StateDictionary loaded = new StateDictionary(dir.toString());
			Assert.assertEquals(2, loaded.size());

			for (String key : weights.keySet()) {
				PackedCollection expected = weights.get(key);
				PackedCollection actual = loaded.get(key);
				Assert.assertEquals("PROTOBUF",
						actual.getRootDelegate().getMem().getProvider().getName());

				for (int i = 0; i < SIZE; i++) {
					Assert.assertEquals(key + " element " + i,
							expected.toDouble(i), actual.toDouble(i), 1e-9);
				}
			}

			PackedCollection shifted = cp(loaded.get("layer.alpha")).add(1.0).evaluate();
			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("shifted element " + i,
						weights.get("layer.alpha").toDouble(i) + 1.0,
						shifted.toDouble(i), 1e-9);
			}

			loaded.destroy();
		} finally {
			StateDictionary.enableMaterializeWeights = previous;
		}
	}

	/**
	 * A context that makes a copy it is not asked to order at once but reports it as still
	 * running until a given completion fires, as a backend that queues its copies behind its own
	 * work would. Only its copies are used.
	 */
	private static class QueuingComputeContext extends AbstractComputeContext<DataContext<MemoryData>> {
		/** The completion reported for every copy made without a dependency. */
		private final Semaphore queued;

		/**
		 * Creates a context over the given data context.
		 *
		 * @param dataContext the data context this context reports
		 * @param queued      the completion reported for every copy made without a dependency
		 */
		QueuingComputeContext(DataContext<MemoryData> dataContext, Semaphore queued) {
			super(dataContext);
			this.queued = queued;
		}

		@Override
		public LanguageOperations getLanguage() { throw new UnsupportedOperationException(); }

		@Override
		public InstructionSet deliver(Scope scope) { throw new UnsupportedOperationException(); }

		@Override
		public boolean isCPU() { return true; }

		/** Makes the copy as the host fallback does, reporting one made without a dependency as queued. */
		@Override
		public Semaphore copy(MemoryData source, MemoryData destination, Semaphore dependsOn) {
			Semaphore copied = super.copy(source, destination, dependsOn);
			return dependsOn == null ? queued : copied;
		}
	}
}
