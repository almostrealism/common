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
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that a {@link HardwareMemoryProvider} keeps tracking a block whose address the
 * native allocator hands out again while the previous block at that address is still
 * being released.
 *
 * <p>A release frees the native block before it removes the block's entry from the
 * provider's allocation map. In that interval another thread's allocation can receive
 * the same address and be registered under the same key. The release must then remove
 * only its own entry: removing the key would leave the new, live block untracked, so it
 * could never be freed through the provider, and {@link HardwareMemoryProvider#isReleased}
 * would report it as released, failing every dispatch that uses it.</p>
 */
public class AddressReuseDuringReleaseTest extends TestSuiteBase {

	/** Address shared by the released block and the block allocated in its place. */
	private static final long ADDRESS = 0x7f00_0000_1000L;

	/** Size, in bytes, shared by both blocks, so their references are equal by value. */
	private static final long SIZE = 64;

	/**
	 * A block allocated at the address of a block that is being released stays tracked
	 * once that release completes.
	 */
	@Test(timeout = 30000)
	public void blockAllocatedDuringReleaseRemainsTracked() {
		ReusingProvider provider = new ReusingProvider();
		FakeRam released = new FakeRam(provider);
		provider.register(released);
		NativeRef<RAM> releasedRef = provider.trackedRef(released);

		provider.deallocate((int) SIZE, released);

		Assert.assertNotNull("the release should have reused the address for a new block",
				provider.reused);
		NativeRef<RAM> tracked = provider.trackedRef(provider.reused);
		Assert.assertNotNull("the block allocated at the reused address must still be tracked", tracked);
		Assert.assertNotSame("the entry at the address must be the new block's, not the released one's",
				releasedRef, tracked);
		Assert.assertFalse("a dispatch must not see the new block as released",
				provider.isReleased(provider.reused));
	}

	/**
	 * A handle to a released block stays released once its address has been handed to a new
	 * block, and releasing it again does not free the new block that now occupies the address.
	 */
	@Test(timeout = 30000)
	public void staleHandleDoesNotResolveToReusedBlock() {
		ReusingProvider provider = new ReusingProvider();
		FakeRam released = new FakeRam(provider);
		provider.register(released);

		provider.deallocate((int) SIZE, released);
		Assert.assertNotNull("the release should have reused the address for a new block",
				provider.reused);
		Assert.assertEquals(1, provider.releases);

		Assert.assertTrue("the released block must not appear live because its address was reused",
				provider.isReleased(released));

		provider.deallocate((int) SIZE, released);
		Assert.assertEquals("releasing the stale handle must not free the block at the reused address",
				1, provider.releases);
		Assert.assertNotNull("the block at the reused address must still be tracked",
				provider.trackedRef(provider.reused));
		Assert.assertFalse("the block at the reused address must not be reported as released",
				provider.isReleased(provider.reused));
	}

	/**
	 * A provider whose native release simulates the allocator handing the freed address
	 * straight to a new allocation, before the release has removed the old entry.
	 */
	private static class ReusingProvider extends HardwareMemoryProvider<RAM> {
		/** The block registered at the freed address during the release, once it has been. */
		private FakeRam reused;

		/** How many blocks the native release has freed. */
		private int releases;

		/**
		 * Registers a block as allocated.
		 *
		 * @param ram the block to track
		 */
		void register(RAM ram) {
			allocated(ram);
		}

		/**
		 * Returns the reference tracked for the given block's address, if it is this block's.
		 *
		 * @param ram the block to look up
		 * @return its tracked reference, or {@code null} if it is not tracked
		 */
		NativeRef<RAM> trackedRef(RAM ram) {
			NativeRef<RAM> ref = getNativeRef(ram);
			return ref == null || ref.isFreed() ? null : ref;
		}

		@Override
		protected void deallocate(NativeRef<RAM> ref) {
			releases++;

			if (reused == null) {
				reused = new FakeRam(this);
				register(reused);
			}
		}

		@Override
		public void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int getNumberSize() { return 8; }

		@Override
		public String getName() { return "address-reuse-test"; }
	}

	/** A block at {@link #ADDRESS} with no memory behind it. */
	private static class FakeRam extends RAM {
		/** The provider this block is registered with. */
		private final MemoryProvider<RAM> provider;

		/**
		 * Creates a block owned by the given provider.
		 *
		 * @param provider the owning provider
		 */
		FakeRam(MemoryProvider<RAM> provider) {
			this.provider = provider;
		}

		@Override
		public long getContainerPointer() { return ADDRESS; }

		@Override
		public long getSize() { return SIZE; }

		@Override
		public MemoryProvider getProvider() { return provider; }
	}
}
