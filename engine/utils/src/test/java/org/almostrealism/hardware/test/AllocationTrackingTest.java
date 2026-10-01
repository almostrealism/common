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
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeMemory;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that a memory provider keeps tracking a block whose address the backend reused while the
 * previous block at that address was still being released.
 *
 * <p>A release frees the backend block before it drops the block's tracking entry, so a
 * concurrent allocation can receive the same address in between. The release must then remove
 * only its own entry; removing whatever is stored under the address would leave the new, live
 * block untracked, so the provider would report it as already released.</p>
 */
public class AllocationTrackingTest extends TestSuiteBase {
	/** Fake address shared by the released block and the block that reuses it. */
	private static final long ADDRESS = 0x1000;

	/** Size in bytes of both blocks. */
	private static final long SIZE = 64;

	/**
	 * A block allocated at the address of a block whose release is in progress stays tracked
	 * after that release completes, so the provider does not report the live block as released.
	 */
	@Test(timeout = 60000)
	public void reusedAddressStaysTracked() {
		ReusingProvider provider = new ReusingProvider();
		NativeMemory released = provider.register();

		provider.deallocate((int) (SIZE / 4), released);

		Assert.assertNotNull("the address was not reused during the release", provider.reused);
		Assert.assertFalse("a live block at a reused address was untracked",
				provider.isReleased(provider.reused));
	}

	/**
	 * A provider over fake addresses that never touches native memory. Releasing its first block
	 * registers a second block at the same address, as a concurrent allocation receiving the
	 * address the backend had just freed would.
	 */
	private static class ReusingProvider extends NativeMemoryProvider {
		/** The block registered at the reused address, once the first release has happened. */
		private NativeMemory reused;

		/** Creates a provider with a ceiling far above the blocks it registers. */
		ReusingProvider() {
			super(Precision.FP32, 1024 * SIZE, false, null, false);
		}

		/**
		 * Registers a block at {@link #ADDRESS} as allocated by this provider.
		 *
		 * @return the registered block
		 */
		NativeMemory register() {
			NativeMemory mem = new NativeMemory(this, ADDRESS, SIZE);
			allocated(mem);
			return mem;
		}

		/**
		 * Frees nothing; the first release registers a new block at the address it releases.
		 *
		 * @param ref the reference being released
		 */
		@Override
		public synchronized void deallocate(NativeRef<RAM> ref) {
			if (reused == null) {
				reused = register();
			}
		}
	}
}
