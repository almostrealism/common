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

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
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
	 * A provider that records the order of the steps of each collection pass, and whose release
	 * of finished work gives back the bytes such work held.
	 */
	private static class RecordingProvider extends HardwareMemoryProvider<RAM> {
		/** The steps taken, in order. */
		private final List<String> steps = new ArrayList<>();

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
}
