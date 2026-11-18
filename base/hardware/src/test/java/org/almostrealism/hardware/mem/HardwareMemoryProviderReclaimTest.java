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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates that a {@link HardwareMemoryProvider} asks for finished kernel work to be released
 * before each garbage collection it requests while reclaiming room for an allocation.
 *
 * <p>Memory that finished work still holds through its kernel memory reservations is reachable,
 * so a collection alone can never free it; an allocation needing it was rejected however many
 * collections were requested.</p>
 */
public class HardwareMemoryProviderReclaimTest {

	/**
	 * A provider that never registers an allocation and records the order of the steps of each
	 * collection pass.
	 */
	private abstract static class StepProvider extends HardwareMemoryProvider<RAM> {
		/** The steps taken, in order. */
		protected final List<String> steps = new ArrayList<>();

		@Override
		protected void collectGarbage() {
			steps.add("collect");
		}

		@Override
		protected void deallocate(NativeRef<RAM> ref) {
			throw new UnsupportedOperationException("No allocation is ever registered by these tests");
		}

		@Override
		public void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
			throw new UnsupportedOperationException("No allocation is ever registered by these tests");
		}

		@Override
		public int getNumberSize() {
			throw new UnsupportedOperationException("No allocation is ever registered by these tests");
		}

		@Override
		public String getName() { return "recording-provider"; }
	}

	/**
	 * A provider whose release of finished work gives back the bytes such work held.
	 */
	private static class RecordingProvider extends StepProvider {

		/** The provider's usage counter, which a release of finished work reduces. */
		private final AtomicLong used;

		/** The bytes a release of finished work gives back. */
		private final long heldByFinishedWork;

		/**
		 * Creates a provider whose finished work holds the given number of bytes of its usage.
		 *
		 * @param used               the usage counter the test reserves against
		 * @param heldByFinishedWork the bytes released by releasing finished work
		 */
		RecordingProvider(AtomicLong used, long heldByFinishedWork) {
			this.used = used;
			this.heldByFinishedWork = heldByFinishedWork;
		}

		@Override
		protected void releaseFinishedWork() {
			steps.add("release");
			used.addAndGet(-heldByFinishedWork);
		}
	}

	/**
	 * A provider owned by a data context that is not registered with {@link Hardware}, as the
	 * context {@link Hardware#dataContext} runs a callable in is not. Its release of finished work
	 * is the one {@link HardwareMemoryProvider} provides.
	 */
	private static class OwnedProvider extends StepProvider {
		/** The data context that owns this provider. */
		private final DataContext<MemoryData> owner;

		/**
		 * Creates a provider owned by the given data context.
		 *
		 * @param owner the owning data context
		 */
		OwnedProvider(DataContext<MemoryData> owner) {
			this.owner = owner;
		}

		@Override
		public DataContext<MemoryData> getContext() { return owner; }
	}

	/**
	 * A data context that is never registered with {@link Hardware}, counts the releases of its
	 * finished work, and supports nothing else.
	 */
	private static class UnregisteredDataContext implements DataContext<MemoryData> {
		/** The number of times finished work was released. */
		private final AtomicInteger releases = new AtomicInteger();

		/** The usage counter of the provider this context owns. */
		private final AtomicLong used;

		/**
		 * Creates a context whose finished work holds all of the given usage.
		 *
		 * @param used the usage counter a release of finished work clears
		 */
		UnregisteredDataContext(AtomicLong used) {
			this.used = used;
		}

		@Override
		public void releaseFinishedWork() {
			releases.incrementAndGet();
			used.set(0);
		}

		@Override
		public String getName() { return "unregistered"; }

		@Override
		public Precision getPrecision() { return Precision.FP32; }

		@Override
		public void init() { }

		@Override
		public List<ComputeContext<MemoryData>> getComputeContexts() {
			throw new UnsupportedOperationException("Reclaim must not ask for compute contexts");
		}

		@Override
		public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
			throw new UnsupportedOperationException();
		}

		@Override
		public MemoryProvider<? extends Memory> getMemoryProvider(int size) {
			throw new UnsupportedOperationException();
		}

		@Override
		public MemoryProvider<? extends Memory> getKernelMemoryProvider() {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T deviceMemory(Callable<T> exec) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void destroy() { }
	}

	/**
	 * An allocation that does not fit because finished work holds the room it needs succeeds,
	 * because the provider releases that work before collecting garbage.
	 */
	@Test(timeout = 30000)
	public void reclaimReleasesFinishedWorkBeforeCollecting() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		RecordingProvider provider = new RecordingProvider(used, max);

		Assert.assertTrue("The allocation must fit once finished work is released",
				provider.reserve(used, max, 40));
		Assert.assertEquals("Finished work must be released before the collection",
				List.of("release", "collect"), provider.steps);
		Assert.assertEquals("The reservation must be counted", 40, used.get());
	}

	/**
	 * A registered ceiling release runs before each collection a provider requests while
	 * reclaiming, after finished work has been released, and a release removed again no longer
	 * runs.
	 */
	@Test(timeout = 30000)
	public void ceilingReleasesRunBeforeCollecting() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		RecordingProvider provider = new RecordingProvider(used, 0) {
			@Override
			protected void collectGarbage() {
				super.collectGarbage();
				used.set(0);
			}
		};

		Runnable release = () -> provider.steps.add("cache");
		HardwareMemoryProvider.addCeilingRelease(release);

		try {
			Assert.assertTrue("The allocation must fit after the collection",
					provider.reserve(used, max, 40));
			Assert.assertEquals("Caches must be released after finished work and before the collection",
					List.of("release", "cache", "collect"), provider.steps);
		} finally {
			HardwareMemoryProvider.removeCeilingRelease(release);
		}

		provider.steps.clear();
		used.set(max);
		Assert.assertTrue("The allocation must fit after the collection", provider.reserve(used, max, 40));
		Assert.assertEquals("A removed release must no longer run", List.of("release", "collect"), provider.steps);
	}

	/**
	 * A provider whose owning data context is not registered with {@link Hardware} still releases
	 * that context's finished work while reclaiming, exactly once per collection pass, so memory
	 * held by finished work of an isolated context is freed before the allocation is rejected.
	 */
	@Test(timeout = 60000)
	public void reclaimReleasesUnregisteredOwnerWork() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		UnregisteredDataContext owner = new UnregisteredDataContext(used);
		OwnedProvider provider = new OwnedProvider(owner);

		Hardware hw = Hardware.getLocalHardware();
		Assert.assertFalse("The owner must not be a registered data context",
				hw != null && hw.getAllDataContexts().contains(owner));

		Assert.assertTrue("The allocation must fit once the owner's finished work is released",
				provider.reserve(used, max, 40));
		Assert.assertEquals("The owner's finished work must be released once, in one pass",
				1, owner.releases.get());
		Assert.assertEquals("Only one collection must be needed", List.of("collect"), provider.steps);
		Assert.assertEquals("The reservation must be counted", 40, used.get());
	}

	/**
	 * A release of finished work that throws does not stop the collection pass: the failure is
	 * logged, the garbage collection still runs, and the allocation succeeds on memory grounds
	 * rather than the release failure surfacing in the allocating thread.
	 */
	@Test(timeout = 30000)
	public void reclaimCollectsWhenFinishedWorkReleaseThrows() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		StepProvider provider = new StepProvider() {
			@Override
			protected void releaseFinishedWork() {
				steps.add("release");
				throw new IllegalStateException("release failure");
			}

			@Override
			protected void collectGarbage() {
				super.collectGarbage();
				used.set(0);
			}
		};

		Assert.assertTrue("The collection must still free room after a release failure",
				provider.reserve(used, max, 40));
		Assert.assertEquals("The collection must run after the failed release",
				List.of("release", "collect"), provider.steps);
		Assert.assertEquals("The reservation must be counted", 40, used.get());
	}

	/**
	 * A data context whose release of finished work throws does not stop the release of the
	 * contexts after it: every context is asked, the failure is logged, and the allocation fits
	 * using the room the later context gave back.
	 */
	@Test(timeout = 30000)
	public void reclaimReleasesEveryContextWhenOneThrows() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		AtomicInteger failedReleases = new AtomicInteger();
		UnregisteredDataContext failing = new UnregisteredDataContext(new AtomicLong()) {
			@Override
			public void releaseFinishedWork() {
				failedReleases.incrementAndGet();
				throw new IllegalStateException("context release failure");
			}
		};
		UnregisteredDataContext owner = new UnregisteredDataContext(used);
		OwnedProvider provider = new OwnedProvider(owner) {
			@Override
			protected List<DataContext<MemoryData>> getReclaimContexts() {
				return List.of(failing, owner);
			}
		};

		Assert.assertTrue("The allocation must fit once the later context's work is released",
				provider.reserve(used, max, 40));
		Assert.assertEquals("The failing context must be asked once", 1, failedReleases.get());
		Assert.assertEquals("The context after the failing one must still be released",
				1, owner.releases.get());
		Assert.assertEquals("Only one collection must be needed", List.of("collect"), provider.steps);
		Assert.assertEquals("The reservation must be counted", 40, used.get());
	}

	/**
	 * Releasing finished work asks every reclaim context, in order, even when several fail, and
	 * rethrows the first failure with the later ones suppressed.
	 */
	@Test(timeout = 30000)
	public void releaseFinishedWorkAttemptsAllContextsAndRethrowsFirstFailure() {
		List<String> asked = new ArrayList<>();
		IllegalStateException first = new IllegalStateException("first");
		IllegalStateException second = new IllegalStateException("second");
		UnregisteredDataContext a = new UnregisteredDataContext(new AtomicLong()) {
			@Override
			public void releaseFinishedWork() {
				asked.add("a");
				throw first;
			}
		};
		UnregisteredDataContext b = new UnregisteredDataContext(new AtomicLong()) {
			@Override
			public void releaseFinishedWork() {
				asked.add("b");
			}
		};
		UnregisteredDataContext c = new UnregisteredDataContext(new AtomicLong()) {
			@Override
			public void releaseFinishedWork() {
				asked.add("c");
				throw second;
			}
		};
		OwnedProvider provider = new OwnedProvider(null) {
			@Override
			protected List<DataContext<MemoryData>> getReclaimContexts() {
				return List.of(a, b, c);
			}
		};

		try {
			provider.releaseFinishedWork();
			Assert.fail("The first release failure must be rethrown");
		} catch (IllegalStateException e) {
			Assert.assertSame("The first failure must be rethrown", first, e);
			Assert.assertArrayEquals("Later failures must be suppressed onto the first",
					new Throwable[] { second }, e.getSuppressed());
		}

		Assert.assertEquals("Every context must be asked, in order", List.of("a", "b", "c"), asked);
	}

	/**
	 * The reclaim contexts include the provider's own data context exactly once, after the
	 * contexts registered with {@link Hardware}, and include no owner when the provider has none.
	 */
	@Test(timeout = 30000)
	public void reclaimContextsIncludeOwnerOnce() {
		UnregisteredDataContext owner = new UnregisteredDataContext(new AtomicLong());
		Hardware hw = Hardware.getLocalHardware();
		List<DataContext<MemoryData>> registered = hw == null ? List.of() : hw.getAllDataContexts();

		List<DataContext<MemoryData>> owned = new OwnedProvider(owner).getReclaimContexts();
		Assert.assertEquals("The owner must follow the registered contexts",
				registered.size() + 1, owned.size());
		Assert.assertSame("The owner must be last", owner, owned.get(owned.size() - 1));

		Assert.assertEquals("A provider with no owner must release only the registered contexts",
				registered, new OwnedProvider(null).getReclaimContexts());
	}

	/**
	 * A registered ceiling release that throws does not stop the collection pass: the failure is
	 * logged, the garbage collection still runs, and the allocation succeeds on memory grounds.
	 */
	@Test(timeout = 30000)
	public void reclaimCollectsWhenCeilingReleaseThrows() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		RecordingProvider provider = new RecordingProvider(used, 0) {
			@Override
			protected void collectGarbage() {
				super.collectGarbage();
				used.set(0);
			}
		};

		Runnable release = () -> {
			provider.steps.add("cache");
			throw new IllegalStateException("cache release failure");
		};
		HardwareMemoryProvider.addCeilingRelease(release);

		try {
			Assert.assertTrue("The collection must still free room after a cache release failure",
					provider.reserve(used, max, 40));
			Assert.assertEquals("The collection must run after the failed cache release",
					List.of("release", "cache", "collect"), provider.steps);
		} finally {
			HardwareMemoryProvider.removeCeilingRelease(release);
		}

		Assert.assertEquals("The reservation must be counted", 40, used.get());
	}

	/**
	 * A provider that no data context owns has no owner to release, so a reclaim that frees nothing
	 * requests every collection it is allowed and then rejects the allocation.
	 */
	@Test(timeout = 60000)
	public void reclaimWithoutOwnerRejectsWhenNothingIsFreed() {
		long max = 100;
		AtomicLong used = new AtomicLong(max);
		OwnedProvider provider = new OwnedProvider(null);

		Assert.assertNull("A provider with no owner must report none", provider.getContext());
		Assert.assertFalse("The allocation must be rejected when nothing is freed",
				provider.reserve(used, max, 40));
		Assert.assertEquals("Every allowed collection must be requested",
				HardwareMemoryProvider.reclaimAttempts, provider.steps.size());
		Assert.assertEquals("Nothing must be reserved", max, used.get());
	}
}
