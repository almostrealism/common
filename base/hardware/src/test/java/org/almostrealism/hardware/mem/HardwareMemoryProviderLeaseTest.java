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

package org.almostrealism.hardware.mem;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link HardwareMemoryProvider#beginAllocation()}/{@link HardwareMemoryProvider#endAllocation()} must hold
 * back the {@link HardwareMemoryProvider#onFullyReleased(Runnable)} callback while an allocation that has
 * reserved a lease is still creating its backend resource (e.g. an OpenCL or Metal buffer) outside of any
 * lock this provider holds, even though {@link HardwareMemoryProvider#destroy()} itself is free to run to
 * completion during that window.
 *
 * <p>Unlike most tests in this project, this one does not extend {@code TestSuiteBase}: that class lives in
 * the engine layer, which sits above this module, so depending on it would invert the module graph. This
 * needs no real CL/Metal hardware: the lease bookkeeping is exercised directly on a minimal concrete
 * subclass whose {@link HardwareMemoryProvider#deallocate(NativeRef)} is never invoked, since no allocation
 * is ever registered.</p>
 */
public class HardwareMemoryProviderLeaseTest {

	/** Minimal concrete provider used only to reach the protected lease methods under test. */
	private static class TestProvider extends HardwareMemoryProvider<RAM> {
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
		public String getName() { return "test-provider"; }
	}

	/** With nothing tracked and no lease outstanding, the callback runs synchronously. */
	@Test(timeout = 30000)
	public void onFullyReleasedRunsImmediatelyWhenNothingPending() {
		TestProvider provider = new TestProvider();
		AtomicBoolean ran = new AtomicBoolean(false);

		provider.onFullyReleased(() -> ran.set(true));

		Assert.assertTrue("The callback should run synchronously when nothing is tracked or pending", ran.get());
	}

	/** A lease reserved by beginAllocation() must hold the callback back until endAllocation() releases it. */
	@Test(timeout = 30000)
	public void onFullyReleasedWaitsForPendingAllocation() {
		TestProvider provider = new TestProvider();
		AtomicBoolean ran = new AtomicBoolean(false);

		provider.beginAllocation();
		provider.onFullyReleased(() -> ran.set(true));
		Assert.assertFalse("The callback must not run while an allocation lease is outstanding", ran.get());

		provider.endAllocation();
		Assert.assertTrue("The callback should run once the last outstanding lease is released", ran.get());
	}

	/**
	 * destroy() must not block on an in-flight allocation lease, but the completion callback it
	 * registers afterward (mirroring CLDataContext.destroy()/MetalDataContext.destroy()) must still
	 * wait for that lease, so a backend resource an in-flight allocation depends on is not released
	 * underneath it.
	 */
	@Test(timeout = 30000)
	public void destroyCompletesWhileAllocationIsPendingButReleaseIsDeferred() {
		TestProvider provider = new TestProvider();
		AtomicBoolean ran = new AtomicBoolean(false);

		provider.beginAllocation();
		provider.destroy();
		Assert.assertTrue("destroy() must complete even while an allocation lease is outstanding",
				provider.isDestroyed());

		provider.onFullyReleased(() -> ran.set(true));
		Assert.assertFalse("The completion callback must stay pending until the in-flight allocation ends",
				ran.get());

		provider.endAllocation();
		Assert.assertTrue("The completion callback should fire once the in-flight allocation ends", ran.get());
	}

	/** Once destroyed, a new allocation cannot reserve a lease. */
	@Test(timeout = 30000)
	public void beginAllocationFailsFastAfterDestroy() {
		TestProvider provider = new TestProvider();
		provider.destroy();

		try {
			provider.beginAllocation();
			Assert.fail("beginAllocation() should fail fast once the provider is destroyed");
		} catch (IllegalStateException expected) {
			Assert.assertTrue("The message should name the reason",
					expected.getMessage().contains("destroyed"));
		}
	}

	/** Two outstanding leases must both be released before the completion callback fires. */
	@Test(timeout = 30000)
	public void onFullyReleasedWaitsForAllOutstandingLeases() {
		TestProvider provider = new TestProvider();
		AtomicBoolean ran = new AtomicBoolean(false);

		provider.beginAllocation();
		provider.beginAllocation();
		provider.onFullyReleased(() -> ran.set(true));

		provider.endAllocation();
		Assert.assertFalse("The callback must wait for every outstanding lease, not just the first release",
				ran.get());

		provider.endAllocation();
		Assert.assertTrue("The callback should run once every outstanding lease has been released", ran.get());
	}
}
