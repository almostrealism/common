/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.mem.KernelMemoryGuard;
import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Validates {@link MemoryData#detachedView()}, {@link MemoryData#deferredReference()} and the
 * deferred copy that relies on them. A copy or dispatch scheduled behind a dependency runs after
 * the scheduling call has returned, and its caller may destroy an argument in the meantime. The
 * scheduling lease keeps the memory alive across that window, but destroying a
 * {@link MemoryData} clears its reference to that memory, so the deferred work must still reach
 * the memory it was scheduled against.
 *
 * <p>Every test that destroys data while a view of it is still read holds a
 * {@link KernelMemoryGuard} scheduling lease over that data first, exactly as the deferred
 * operations do, so the destroyed memory is held back rather than freed until the lease is
 * released.</p>
 */
public class DeferredMemoryReferenceTest extends TestSuiteBase {

	/**
	 * A detached view of a delegated region describes exactly the same memory, offset, length
	 * and atomic length as the region itself, and reads the same values.
	 */
	@Test(timeout = 30000)
	public void detachedViewDescribesTheSameRegion() {
		PackedCollection base = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
		PackedCollection region = base.range(shape(3), 2);

		MemoryData view = region.detachedView();

		Assert.assertNotSame(region, view);
		Assert.assertSame(region.getMem(), view.getMem());
		Assert.assertEquals(region.getOffset(), view.getOffset());
		Assert.assertEquals(region.getMemLength(), view.getMemLength());
		Assert.assertEquals(region.getAtomicMemLength(), view.getAtomicMemLength());
		assertValues(view, 3.0, 4.0, 5.0);

		base.destroy();
	}

	/**
	 * Destroying the data a view was detached from leaves the original reporting destroyed,
	 * while the view still names the captured memory and reads the values it held.
	 */
	@Test(timeout = 30000)
	public void detachedViewSurvivesDestroyOfItsSource() {
		PackedCollection base = pack(10.0, 20.0, 30.0, 40.0);
		PackedCollection region = base.range(shape(2), 1);
		MemoryData view = region.detachedView();

		KernelMemoryGuard.Reservation lease = KernelMemoryGuard.acquireScheduledFor(new MemoryData[] { base });

		try {
			base.destroy();

			Assert.assertTrue(base.isDestroyed());
			Assert.assertTrue(region.isDestroyed());
			Assert.assertFalse(view.isDestroyed());
			Assert.assertEquals(1, view.getOffset());
			Assert.assertEquals(2, view.getMemLength());
			assertValues(view, 20.0, 30.0);
		} finally {
			KernelMemoryGuard.releaseFor(lease);
		}
	}

	/**
	 * Regression: the root backing a detached view does not own the memory it wraps, so destroying
	 * that root must not deallocate the memory the source still owns. Destroying the root is what the
	 * finalizer does when {@link MemoryDataAdapter#enableFinalizer} is enabled, so this holds across
	 * both the explicit and finalizer destroy paths. Before the view used a non-owning root, this
	 * freed the shared allocation out from under the source.
	 */
	@Test(timeout = 30000)
	public void detachedViewRootDoesNotFreeSharedMemory() {
		PackedCollection source = pack(2.0, 4.0, 6.0, 8.0);
		MemoryData view = source.detachedView();
		MemoryData storage = view.getRootDelegate();

		Assert.assertNotSame(source, storage);
		Assert.assertSame(source.getMem(), storage.getMem());

		boolean finalizer = MemoryDataAdapter.enableFinalizer;
		MemoryDataAdapter.enableFinalizer = true;

		try {
			storage.destroy();

			Assert.assertFalse("The source must still own the shared memory", source.isDestroyed());
			Assert.assertSame(source.getMem(), storage.getMem());
			assertValues(source, 2.0, 4.0, 6.0, 8.0);
			assertValues(view, 2.0, 4.0, 6.0, 8.0);
		} finally {
			MemoryDataAdapter.enableFinalizer = finalizer;
		}

		source.destroy();
	}

	/**
	 * Regression: argument preparation moves an argument backed by an unsupported provider by
	 * reallocating its root, and the root of a detached view is non-owning. Moving it must neither
	 * free the borrowed memory (which, with memory versions disabled, reassignment used to
	 * deallocate) nor keep it as a cached version, and the replacement the root receives is its
	 * own: destroying the root frees it, where the root used to ignore destroy entirely and retain
	 * the replacement. Checked with memory versions both enabled and disabled.
	 */
	@Test(timeout = 30000)
	public void detachedViewRootOwnsOnlyItsReplacementAfterMigration() {
		boolean versions = MemoryDataAdapter.enableMemVersions;

		try {
			for (boolean enabled : new boolean[] { true, false }) {
				MemoryDataAdapter.enableMemVersions = enabled;

				PackedCollection source = pack(1.0, 3.0, 5.0, 7.0);
				MemoryData view = source.detachedView();
				MemoryData storage = view.getRootDelegate();

				storage.reallocate(source.getMem().getProvider());

				Assert.assertNotSame("The root must hold a replacement after migration",
						source.getMem(), storage.getMem());
				assertValues(view, 1.0, 3.0, 5.0, 7.0);

				storage.destroy();

				Assert.assertTrue("Destroying the root must free its replacement", storage.isDestroyed());
				Assert.assertFalse("The source must still own its memory", source.isDestroyed());
				assertValues(source, 1.0, 3.0, 5.0, 7.0);

				source.destroy();
			}
		} finally {
			MemoryDataAdapter.enableMemVersions = versions;
		}
	}

	/**
	 * Releasing resolved deferred arguments frees the replacement a destroyed argument's view was
	 * migrated to, leaves an unmigrated view's captured memory alone, and never touches arguments
	 * that resolved to themselves. A supplier that was never invoked has nothing to release.
	 */
	@Test(timeout = 30000)
	public void releasingDeferredArgumentsFreesOnlyMigratedViews() {
		PackedCollection live = pack(1.0, 2.0);
		PackedCollection migrated = pack(3.0, 4.0);
		PackedCollection unmigrated = pack(5.0, 6.0);
		Object[] args = { live, migrated, unmigrated, "not memory" };

		Supplier<Object[]> deferred = ArgumentCapture.capture(args);
		KernelMemoryGuard.Reservation lease =
				KernelMemoryGuard.acquireScheduledFor(new MemoryData[] { migrated, unmigrated });

		try {
			migrated.destroy();
			unmigrated.destroy();

			Object[] resolved = deferred.get();
			MemoryData migratedView = (MemoryData) resolved[1];
			MemoryData unmigratedView = (MemoryData) resolved[2];
			migratedView.getRootDelegate().reallocate(migratedView.getMem().getProvider());

			ArgumentCapture.release(args, resolved);

			Assert.assertTrue("The migrated view's replacement must be freed",
					migratedView.getRootDelegate().isDestroyed());
			Assert.assertFalse("An unmigrated view still reads its captured memory",
					unmigratedView.isDestroyed());
			assertValues(unmigratedView, 5.0, 6.0);
			Assert.assertFalse("A live argument must not be released", live.isDestroyed());
			assertValues(live, 1.0, 2.0);

			ArgumentCapture.release(args, null);
			Assert.assertFalse(live.isDestroyed());
		} finally {
			KernelMemoryGuard.releaseFor(lease);
			live.destroy();
		}
	}

	/**
	 * Data that has already been destroyed has no memory to bind to, so its detached view is the
	 * data itself, and a deferred reference to it yields the data itself.
	 */
	@Test(timeout = 30000)
	public void destroyedDataHasNothingToDetach() {
		PackedCollection data = pack(1.0, 2.0);
		data.destroy();

		Assert.assertSame(data, data.detachedView());
		Assert.assertSame(data, data.deferredReference().get());
	}

	/**
	 * A deferred reference yields the data itself for as long as it is backed, so deferred work
	 * observes the live object; once the data is destroyed it yields a view of the memory the
	 * data described when the reference was taken.
	 */
	@Test(timeout = 30000)
	public void deferredReferenceSwitchesToCapturedViewOnlyAfterDestroy() {
		PackedCollection base = pack(5.0, 6.0, 7.0);
		Supplier<MemoryData> reference = base.deferredReference();

		Assert.assertSame(base, reference.get());
		Assert.assertSame("The live object must be yielded on every call while it is backed",
				base, reference.get());

		KernelMemoryGuard.Reservation lease = KernelMemoryGuard.acquireScheduledFor(new MemoryData[] { base });

		try {
			base.destroy();

			MemoryData resolved = reference.get();
			Assert.assertNotSame(base, resolved);
			Assert.assertFalse(resolved.isDestroyed());
			Assert.assertEquals(3, resolved.getMemLength());
			assertValues(resolved, 5.0, 6.0, 7.0);
		} finally {
			KernelMemoryGuard.releaseFor(lease);
		}
	}

	/**
	 * Deferred operator arguments substitute a captured view only for a {@link MemoryData}
	 * argument that has been destroyed; live arguments are passed through as the same object
	 * (so a migration in the meantime is observed and the argument cache still matches), and
	 * entries that are not {@link MemoryData} &mdash; including {@code null} &mdash; are passed
	 * through untouched for argument preparation to report.
	 */
	@Test(timeout = 30000)
	public void deferredArgumentsReplaceOnlyDestroyedMemoryData() {
		PackedCollection live = pack(1.0, 2.0);
		PackedCollection destroyed = pack(3.0, 4.0);
		Object[] args = { live, destroyed, "not memory", null };

		Supplier<Object[]> deferred = ArgumentCapture.capture(args);
		KernelMemoryGuard.Reservation lease = KernelMemoryGuard.acquireScheduledFor(new MemoryData[] { destroyed });

		try {
			destroyed.destroy();

			Object[] resolved = deferred.get();
			Assert.assertEquals(4, resolved.length);
			Assert.assertSame(live, resolved[0]);
			Assert.assertNotSame(destroyed, resolved[1]);
			Assert.assertFalse(((MemoryData) resolved[1]).isDestroyed());
			assertValues((MemoryData) resolved[1], 3.0, 4.0);
			Assert.assertEquals("not memory", resolved[2]);
			Assert.assertNull(resolved[3]);
		} finally {
			KernelMemoryGuard.releaseFor(lease);
			live.destroy();
		}

		Assert.assertNull(ArgumentCapture.capture(null).get());
	}

	/**
	 * Regression: a fallback copy scheduled behind a pending dependency must still copy the
	 * source's values when the caller destroys the source before the dependency completes. The
	 * copy's own scheduling lease keeps the memory alive; before the copy reached it through a
	 * deferred reference, the destroyed source's cleared memory reference made the scheduled copy
	 * fail.
	 */
	@Test(timeout = 30000)
	public void deferredCopyCompletesAfterSourceIsDestroyed() {
		ComputeContext<MemoryData> context = fallbackCopyContext();
		if (context == null) {
			log("No compute context uses the fallback copy; nothing to verify");
			return;
		}

		PackedCollection source = pack(1.5, 2.5, 3.5);
		PackedCollection destination = new PackedCollection(3);
		LatchSemaphore dependency = new LatchSemaphore(1);

		Semaphore copied = context.copy(source, destination, dependency);
		Assert.assertNotNull("A copy behind a pending dependency must be deferred", copied);

		source.destroy();
		dependency.countDown();
		copied.waitFor();

		assertValues(destination, 1.5, 2.5, 3.5);
		destination.destroy();
	}

	/**
	 * Destroying the destination of a fallback copy before its dependency completes must not make
	 * the scheduled copy fail: it completes against the memory the destination described when
	 * the copy was scheduled, which its lease held back from being freed.
	 */
	@Test(timeout = 30000)
	public void deferredCopyCompletesAfterDestinationIsDestroyed() {
		ComputeContext<MemoryData> context = fallbackCopyContext();
		if (context == null) {
			log("No compute context uses the fallback copy; nothing to verify");
			return;
		}

		PackedCollection source = pack(4.0, 5.0);
		PackedCollection destination = new PackedCollection(2);
		LatchSemaphore dependency = new LatchSemaphore(1);

		Semaphore copied = context.copy(source, destination, dependency);
		destination.destroy();
		dependency.countDown();
		copied.waitFor();

		Assert.assertTrue(destination.isDestroyed());
		assertValues(source, 4.0, 5.0);
		source.destroy();
	}

	/**
	 * Asserts the values held by the given data, reading them through a copy into a fresh
	 * collection so that the read goes through the data's memory, offset and length.
	 *
	 * @param data     the data to read
	 * @param expected the values it must hold
	 */
	private void assertValues(MemoryData data, double... expected) {
		PackedCollection out = new PackedCollection(expected.length);

		try {
			out.setFrom(0, data, 0, expected.length);
			for (int i = 0; i < expected.length; i++) {
				Assert.assertEquals("value " + i, expected[i], out.toDouble(i), 1e-6);
			}
		} finally {
			out.destroy();
		}
	}

	/**
	 * Returns a compute context whose {@link ComputeContext#copy copy} is the
	 * {@link AbstractComputeContext} fallback, which schedules the copy behind a pending
	 * dependency, or {@code null} if no available context uses it.
	 *
	 * @return a context using the fallback copy, or {@code null}
	 */
	private static ComputeContext<MemoryData> fallbackCopyContext() {
		Hardware hw = Hardware.getLocalHardware();

		return Stream.of(hw.getComputeContexts(true, false), hw.getComputeContexts(false, false))
				.flatMap(List::stream)
				.filter(DeferredMemoryReferenceTest::usesFallbackCopy)
				.findFirst().orElse(null);
	}

	/**
	 * Returns whether the given context inherits the {@link AbstractComputeContext} copy rather
	 * than overriding it with a backend-specific one.
	 *
	 * @param context the context to inspect
	 * @return {@code true} if its copy is the fallback
	 */
	private static boolean usesFallbackCopy(ComputeContext<MemoryData> context) {
		if (!(context instanceof AbstractComputeContext)) return false;

		try {
			return context.getClass().getMethod("copy",
					MemoryData.class, MemoryData.class, Semaphore.class)
					.getDeclaringClass() == AbstractComputeContext.class;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	/**
	 * Exposes {@link HardwareOperator}'s protected deferred-argument capture to this test. It is
	 * never instantiated.
	 */
	private abstract static class ArgumentCapture extends HardwareOperator {
		/**
		 * Captures the given raw arguments for a deferred operation.
		 *
		 * @param args the raw arguments
		 * @return a supplier of the arguments to run with
		 */
		static Supplier<Object[]> capture(Object[] args) {
			return deferredArguments(args);
		}

		/**
		 * Releases what resolving captured arguments produced.
		 *
		 * @param args     the raw arguments that were captured
		 * @param resolved what the capture's supplier returned
		 */
		static void release(Object[] args, Object[] resolved) {
			releaseDeferredArguments(args, resolved);
		}
	}
}
