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

import io.almostrealism.code.Precision;
import org.almostrealism.nio.NativeBuffer;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.nio.TestNativeBuffer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link NativeMemoryProvider} in direct mode requests a collection once enough
 * released direct-buffer memory awaits the JVM's cleaner, instead of letting it stay committed
 * until {@code -XX:MaxDirectMemorySize} forces one.
 *
 * <p>Releases are driven through the provider's own deallocation path with
 * {@link TestNativeBuffer}s, each of which reports a size of 1024 bytes, so no native operation
 * has to be compiled.</p>
 */
public class NativeMemoryProviderDirectReleaseTest extends TestSuiteBase {
	/** The value of {@link NativeMemoryProvider#directCollectionThreshold} before each test. */
	private long threshold;

	/** Lowers the collection threshold to two released test buffers. */
	@Before
	public void lowerThreshold() {
		threshold = NativeMemoryProvider.directCollectionThreshold;
		NativeMemoryProvider.directCollectionThreshold = 2048;
	}

	/** Restores the collection threshold. */
	@After
	public void restoreThreshold() {
		NativeMemoryProvider.directCollectionThreshold = threshold;
	}

	/**
	 * Verifies that no collection is requested below the threshold, that one is requested once
	 * released bytes reach it, and that the count starts again afterwards.
	 */
	@Test(timeout = 30_000)
	public void collectsOnceReleasedBytesReachThreshold() {
		CountingProvider provider = new CountingProvider();

		provider.release(TestNativeBuffer.create(null, 1000L, 128));
		Assert.assertEquals(1024, provider.getReleasedDirectBytes());
		Assert.assertFalse(provider.collect());
		Assert.assertEquals(0, provider.collections);

		provider.release(TestNativeBuffer.create(null, 2000L, 128));
		Assert.assertTrue(provider.collect());
		Assert.assertEquals(1, provider.collections);
		Assert.assertEquals(0, provider.getReleasedDirectBytes());

		Assert.assertFalse(provider.collect());
		Assert.assertEquals(1, provider.collections);
	}

	/**
	 * Verifies that a provider whose allocation ceiling is below
	 * {@link NativeMemoryProvider#directCollectionThreshold} requests a collection once released
	 * bytes reach that ceiling, and that a provider with a higher ceiling keeps the global value.
	 */
	@Test(timeout = 30_000)
	public void thresholdIsBoundedByMemoryMax() {
		NativeMemoryProvider.directCollectionThreshold = Long.MAX_VALUE;

		CountingProvider bounded = new CountingProvider(1024);
		Assert.assertEquals(1024, bounded.getDirectCollectionThreshold());

		bounded.release(TestNativeBuffer.create(null, 3000L, 128));
		Assert.assertTrue(bounded.collect());
		Assert.assertEquals(1, bounded.collections);
		Assert.assertEquals(0, bounded.getReleasedDirectBytes());

		NativeMemoryProvider.directCollectionThreshold = 4096;
		CountingProvider unbounded = new CountingProvider(Long.MAX_VALUE);
		Assert.assertEquals(4096, unbounded.getDirectCollectionThreshold());

		unbounded.release(TestNativeBuffer.create(null, 4000L, 128));
		Assert.assertFalse(unbounded.collect());
		Assert.assertEquals(0, unbounded.collections);
		Assert.assertEquals(1024, unbounded.getReleasedDirectBytes());
	}

	/**
	 * Verifies that released bytes are kept when the collection pass is interrupted after the
	 * garbage collection was requested, that the still-interrupted thread then requests no
	 * further collection however often it allocates, and that the bytes are collected once the
	 * interrupt is cleared.
	 */
	@Test(timeout = 30_000)
	public void interruptedCollectionKeepsReleasedBytes() {
		CountingProvider provider = new CountingProvider();
		provider.release(TestNativeBuffer.create(null, 1000L, 128));
		provider.release(TestNativeBuffer.create(null, 2000L, 128));
		Assert.assertEquals(2048, provider.getReleasedDirectBytes());

		provider.interruptOnCollect = true;
		boolean collected;
		boolean retried = false;
		boolean interrupted;

		try {
			collected = provider.collect();
			for (int i = 0; i < 3; i++) {
				retried |= provider.collect();
			}
		} finally {
			interrupted = Thread.interrupted();
		}

		Assert.assertTrue(interrupted);
		Assert.assertFalse(collected);
		Assert.assertFalse(retried);
		Assert.assertEquals(1, provider.collections);
		Assert.assertEquals(2048, provider.getReleasedDirectBytes());

		provider.interruptOnCollect = false;
		Assert.assertTrue(provider.collect());
		Assert.assertEquals(2, provider.collections);
		Assert.assertEquals(0, provider.getReleasedDirectBytes());
	}

	/**
	 * Verifies that a caller whose thread is already interrupted requests no collection and
	 * leaves the released bytes counted, and that a caller below the threshold is unaffected by
	 * the interrupt.
	 */
	@Test(timeout = 30_000)
	public void interruptedCallerRequestsNoCollection() {
		CountingProvider provider = new CountingProvider();
		provider.release(TestNativeBuffer.create(null, 1000L, 128));
		provider.release(TestNativeBuffer.create(null, 2000L, 128));

		boolean collected;

		try {
			Thread.currentThread().interrupt();
			collected = provider.collect();
		} finally {
			Thread.interrupted();
		}

		Assert.assertFalse(collected);
		Assert.assertEquals(0, provider.collections);
		Assert.assertEquals(2048, provider.getReleasedDirectBytes());

		Assert.assertTrue(provider.collect());
		Assert.assertEquals(1, provider.collections);
		Assert.assertEquals(0, provider.getReleasedDirectBytes());

		provider.release(TestNativeBuffer.create(null, 3000L, 128));

		try {
			Thread.currentThread().interrupt();
			collected = provider.collect();
		} finally {
			Thread.interrupted();
		}

		Assert.assertFalse(collected);
		Assert.assertEquals(1, provider.collections);
		Assert.assertEquals(1024, provider.getReleasedDirectBytes());
	}

	/**
	 * Verifies that a release which changes the count while a caller is taking it does not make
	 * that caller give up: the caller still requests the collection, and every released byte,
	 * including the concurrent release, is taken off the count by it.
	 */
	@Test(timeout = 30_000)
	public void concurrentReleaseDoesNotCancelCollection() {
		CountingProvider provider = new CountingProvider();
		provider.release(TestNativeBuffer.create(null, 1000L, 128));
		provider.release(TestNativeBuffer.create(null, 2000L, 128));
		provider.releaseOnThresholdQuery = TestNativeBuffer.create(null, 3000L, 128);

		Assert.assertTrue(provider.collect());
		Assert.assertNull(provider.releaseOnThresholdQuery);
		Assert.assertEquals(1, provider.collections);
		Assert.assertEquals(0, provider.getReleasedDirectBytes());
	}

	/**
	 * Verifies that a provider destroyed while an allocation is running its collection pass,
	 * which runs outside the provider's monitor, does not hand out the block that allocation then
	 * creates: the allocation fails, and nothing is left registered with the destroyed provider.
	 */
	@Test(timeout = 30_000)
	public void destroyDuringCollectionRejectsAllocation() {
		CountingProvider provider = new CountingProvider();
		provider.release(TestNativeBuffer.create(null, 1000L, 128));
		provider.release(TestNativeBuffer.create(null, 2000L, 128));
		provider.destroyOnCollect = true;

		boolean rejected = false;

		try {
			provider.allocate(16);
		} catch (IllegalStateException e) {
			rejected = true;
		}

		Assert.assertTrue(rejected);
		Assert.assertEquals(1, provider.collections);
		Assert.assertTrue(provider.isDestroyed());
		Assert.assertEquals(0, provider.getAllocatedCount());
	}

	/**
	 * A direct-mode provider that counts the collections it requests instead of running them,
	 * and can release a buffer through its own deallocation path.
	 */
	private static class CountingProvider extends NativeMemoryProvider {
		/** Number of collections requested. */
		private int collections;

		/**
		 * A buffer to release the next time the collection threshold is queried, which stands in
		 * for a release on another thread between the caller reading the count and taking it.
		 */
		private NativeBuffer releaseOnThresholdQuery;

		/**
		 * If true, each requested collection interrupts the calling thread, so that the wait
		 * which follows it is cut short.
		 */
		private boolean interruptOnCollect;

		/**
		 * If true, each requested collection destroys this provider, which stands in for a
		 * {@link #destroy()} on another thread while an allocation is collecting.
		 */
		private boolean destroyOnCollect;

		/** Creates a private (non-shared) direct-mode provider with no practical ceiling. */
		CountingProvider() {
			this(Long.MAX_VALUE);
		}

		/**
		 * Creates a private (non-shared) direct-mode provider.
		 *
		 * @param memoryMax the provider's allocation ceiling in bytes
		 */
		CountingProvider(long memoryMax) {
			super(Precision.FP32, memoryMax, false, null, true);
		}

		/**
		 * Releases a buffer as its deallocation path would, without any shared-memory mapping.
		 *
		 * @param buffer the buffer to release
		 */
		void release(NativeBuffer buffer) {
			deallocate(nativeRef(buffer));
		}

		/**
		 * Runs the collection check.
		 *
		 * @return whether a collection was requested
		 */
		boolean collect() {
			return collectReleasedDirectMemory();
		}

		@Override
		public long getDirectCollectionThreshold() {
			NativeBuffer buffer = releaseOnThresholdQuery;

			if (buffer != null) {
				releaseOnThresholdQuery = null;
				release(buffer);
			}

			return super.getDirectCollectionThreshold();
		}

		@Override
		protected void collectGarbage() {
			collections++;
			if (interruptOnCollect) Thread.currentThread().interrupt();
			if (destroyOnCollect) destroy();
		}
	}
}
