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

import org.almostrealism.nio.NativeBuffer;
import org.almostrealism.nio.NativeBufferRef;
import org.almostrealism.nio.TestNativeBuffer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.ref.ReferenceQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tests for {@link NativeBufferRef}, verifying that fields needed for post-GC
 * deallocation are correctly cached at construction time.
 *
 * <p>Because {@link org.almostrealism.hardware.mem.MemoryReference} extends
 * {@link java.lang.ref.PhantomReference}, {@code get()} always returns {@code null}.
 * {@link NativeBufferRef} must cache the root {@link java.nio.ByteBuffer}, shared
 * location, and deallocation listeners so they remain accessible after the referent
 * is garbage collected.</p>
 *
 * <p>Uses {@link TestNativeBuffer} to avoid the {@link org.almostrealism.nio.NIO}
 * native library dependency.</p>
 */
public class NativeBufferRefTest extends TestSuiteBase {

	/**
	 * Verifies that the root {@link java.nio.ByteBuffer} is cached from the
	 * {@link NativeBuffer} at construction time.
	 */
	@Test(timeout = 10_000)
	public void rootBufferIsCached() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 1000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertNotNull("Root buffer should be cached", ref.getRootBuffer());
		Assert.assertSame("Root buffer should be the same object",
				buffer.getRootBuffer(), ref.getRootBuffer());
		Assert.assertTrue("Root buffer should be direct",
				ref.getRootBuffer().isDirect());
	}

	/**
	 * Verifies that the shared location is cached as {@code null} for
	 * non-shared buffers.
	 */
	@Test(timeout = 10_000)
	public void sharedLocationIsNullForNonSharedBuffer() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 2000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertNull("Shared location should be null for non-shared buffer",
				ref.getSharedLocation());
	}

	/**
	 * Verifies that shared location is cached when set.
	 */
	@Test(timeout = 10_000)
	public void sharedLocationIsCachedWhenSet() {
		NativeBuffer buffer = TestNativeBuffer.create("/tmp/test-shared", 3000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertEquals("Shared location should be cached",
				"/tmp/test-shared", ref.getSharedLocation());
	}

	/**
	 * Verifies that deallocation listeners registered before {@link NativeBufferRef}
	 * construction are captured in the ref's cached list.
	 */
	@Test(timeout = 10_000)
	public void deallocationListenersAreCaptured() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 4000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		AtomicBoolean listenerCalled = new AtomicBoolean(false);
		Consumer<NativeBuffer> listener = nb -> listenerCalled.set(true);
		buffer.addDeallocationListener(listener);

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertEquals("Should have 1 cached listener",
				1, ref.getDeallocationListeners().size());

		ref.getDeallocationListeners().get(0).accept(null);
		Assert.assertTrue("Cached listener should be callable",
				listenerCalled.get());
	}

	/**
	 * Verifies that listeners added to the {@link NativeBuffer} after
	 * {@link NativeBufferRef} construction are NOT captured (the list is
	 * a snapshot at construction time).
	 */
	@Test(timeout = 10_000)
	public void listenersAddedAfterConstructionAreNotCaptured() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 5000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);
		buffer.addDeallocationListener(nb -> {});

		Assert.assertEquals("Listener added after construction should not be captured",
				0, ref.getDeallocationListeners().size());
		Assert.assertEquals("Original buffer should have the listener",
				1, buffer.getDeallocationListeners().size());
	}

	/**
	 * Verifies that the cached deallocation listener list is independent
	 * (a copy, not a shared reference) from the original buffer's list.
	 */
	@Test(timeout = 10_000)
	public void cachedListenerListIsIndependentCopy() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 6000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		buffer.addDeallocationListener(nb -> {});
		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertEquals("Initial size should be 1", 1, ref.getDeallocationListeners().size());

		buffer.addDeallocationListener(nb -> {});
		Assert.assertEquals("Cached list should not grow when original list grows",
				1, ref.getDeallocationListeners().size());
	}

	/**
	 * Verifies that {@link NativeBufferRef#get()} returns {@code null},
	 * consistent with the {@link java.lang.ref.PhantomReference} contract.
	 */
	@Test(timeout = 10_000)
	public void getReturnsNull() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 7000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertNull("PhantomReference.get() must return null", ref.get());
	}

	/**
	 * Verifies that address and size are inherited from
	 * {@link org.almostrealism.hardware.mem.NativeRef} and correctly cached.
	 */
	@Test(timeout = 10_000)
	public void addressAndSizeAreCached() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 8000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertEquals("Address should match buffer address",
				8000L, ref.getAddress());
		Assert.assertEquals("Size should match buffer size",
				buffer.getSize(), ref.getSize());
	}

	/**
	 * Verifies that an empty deallocation listener list is handled correctly.
	 */
	@Test(timeout = 10_000)
	public void emptyDeallocationListeners() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 9000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertNotNull("Listener list should not be null",
				ref.getDeallocationListeners());
		Assert.assertTrue("Listener list should be empty",
				ref.getDeallocationListeners().isEmpty());
	}

	/**
	 * Verifies that multiple deallocation listeners are all captured.
	 */
	@Test(timeout = 10_000)
	public void multipleListenersAreCaptured() {
		NativeBuffer buffer = TestNativeBuffer.create(null, 10000L, 128);
		ReferenceQueue<NativeBuffer> queue = new ReferenceQueue<>();

		AtomicBoolean first = new AtomicBoolean(false);
		AtomicBoolean second = new AtomicBoolean(false);
		AtomicBoolean third = new AtomicBoolean(false);

		buffer.addDeallocationListener(nb -> first.set(true));
		buffer.addDeallocationListener(nb -> second.set(true));
		buffer.addDeallocationListener(nb -> third.set(true));

		NativeBufferRef ref = new NativeBufferRef(buffer, queue);

		Assert.assertEquals("Should have 3 cached listeners",
				3, ref.getDeallocationListeners().size());

		ref.getDeallocationListeners().forEach(l -> l.accept(null));
		Assert.assertTrue("First listener should have been called", first.get());
		Assert.assertTrue("Second listener should have been called", second.get());
		Assert.assertTrue("Third listener should have been called", third.get());
	}
}
