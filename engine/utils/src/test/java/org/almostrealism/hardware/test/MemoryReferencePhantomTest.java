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

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.mem.MemoryReference;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

/**
 * Tests verifying that {@link MemoryReference} correctly extends {@link PhantomReference}.
 *
 * <p>These tests validate the Phase 4 migration from {@code WeakReference} to
 * {@code PhantomReference}, ensuring that:</p>
 * <ul>
 *   <li>{@code get()} always returns {@code null} (PhantomReference contract)</li>
 *   <li>{@link NativeRef} caches address and size for post-GC deallocation</li>
 *   <li>Allocation stack trace is preserved independently of the referent</li>
 *   <li>Equality and hashing work correctly using cached fields</li>
 * </ul>
 */
public class MemoryReferencePhantomTest extends TestSuiteBase {

	/**
	 * Verifies that {@link MemoryReference} is a {@link PhantomReference}.
	 */
	@Test
	public void memoryReferenceExtendsPhantomReference() {
		ReferenceQueue<RAM> queue = new ReferenceQueue<>();
		TestRAM ram = new TestRAM(1000L, 4096L);
		NativeRef<RAM> ref = new NativeRef<>(ram, queue);

		Assert.assertTrue("MemoryReference should extend PhantomReference",
				ref instanceof PhantomReference);
	}

	/**
	 * Verifies that {@link NativeRef#get()} always returns {@code null},
	 * as required by the {@link PhantomReference} contract.
	 */
	@Test
	public void getAlwaysReturnsNull() {
		ReferenceQueue<RAM> queue = new ReferenceQueue<>();
		TestRAM ram = new TestRAM(2000L, 8192L);
		NativeRef<RAM> ref = new NativeRef<>(ram, queue);

		Assert.assertNull("PhantomReference.get() must always return null", ref.get());
	}

	/**
	 * Verifies that {@link NativeRef} caches address and size at construction time,
	 * making them available for post-GC deallocation even though {@code get()} returns null.
	 */
	@Test
	public void nativeRefCachesAddressAndSize() {
		ReferenceQueue<RAM> queue = new ReferenceQueue<>();
		long expectedAddress = 42000L;
		long expectedSize = 16384L;
		TestRAM ram = new TestRAM(expectedAddress, expectedSize);
		NativeRef<RAM> ref = new NativeRef<>(ram, queue);

		Assert.assertEquals("Cached address should match", expectedAddress, ref.getAddress());
		Assert.assertEquals("Cached size should match", expectedSize, ref.getSize());
	}

	/**
	 * Verifies that the allocation stack trace is preserved in the reference
	 * independently of the referent object.
	 */
	@Test
	public void allocationStackTracePreserved() {
		ReferenceQueue<RAM> queue = new ReferenceQueue<>();
		TrackedRAM ram = new TrackedRAM(3000L, 1024L);
		NativeRef<RAM> ref = new NativeRef<>(ram, queue);

		Assert.assertNotNull("Allocation stack trace should be preserved",
				ref.getAllocationStackTrace());
		Assert.assertTrue("Stack trace should have frames",
				ref.getAllocationStackTrace().length > 0);
	}

	/**
	 * Verifies that {@link NativeRef} equality is based on address and size,
	 * not on the referent identity (which is inaccessible via PhantomReference).
	 */
	@Test
	public void equalityBasedOnCachedFields() {
		ReferenceQueue<RAM> queue = new ReferenceQueue<>();
		TestRAM ram1 = new TestRAM(5000L, 2048L);
		TestRAM ram2 = new TestRAM(5000L, 2048L);
		NativeRef<RAM> ref1 = new NativeRef<>(ram1, queue);
		NativeRef<RAM> ref2 = new NativeRef<>(ram2, queue);

		Assert.assertEquals("Refs with same address and size should be equal", ref1, ref2);
		Assert.assertEquals("Hash codes should match", ref1.hashCode(), ref2.hashCode());
	}

	/**
	 * Verifies that {@link NativeRef} instances with different addresses are not equal.
	 */
	@Test
	public void inequalityForDifferentAddresses() {
		ReferenceQueue<RAM> queue = new ReferenceQueue<>();
		TestRAM ram1 = new TestRAM(6000L, 2048L);
		TestRAM ram2 = new TestRAM(7000L, 2048L);
		NativeRef<RAM> ref1 = new NativeRef<>(ram1, queue);
		NativeRef<RAM> ref2 = new NativeRef<>(ram2, queue);

		Assert.assertNotEquals("Refs with different addresses should not be equal", ref1, ref2);
	}

	/**
	 * {@link RAM} with allocation tracking enabled for stack trace tests.
	 */
	private static class TrackedRAM extends TestRAM {
		TrackedRAM(long address, long size) {
			super(address, size, 16);
		}
	}

	/**
	 * Minimal {@link RAM} implementation for testing {@link NativeRef} without
	 * requiring native memory allocation.
	 */
	private static class TestRAM extends RAM {
		private final long address;
		private final long size;

		TestRAM(long address, long size) {
			this(address, size, 0);
		}

		TestRAM(long address, long size, int traceFrames) {
			super(traceFrames);
			this.address = address;
			this.size = size;
		}

		@Override
		public MemoryProvider getProvider() { return null; }

		@Override
		public long getContentPointer() { return address; }

		@Override
		public long getContainerPointer() { return address; }

		@Override
		public long getSize() { return size; }
	}
}
