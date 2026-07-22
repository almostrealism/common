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

import org.almostrealism.hardware.metal.MTLEvent;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the {@link MTLEvent} signal/release lifecycle contract that
 * {@code MetalCommandRunner}'s foreign-dependency bridging relies on.
 *
 * <p>A bridge signals its event from a completion callback that runs whenever the
 * foreign work finishes, while the event is released with its command buffer's
 * completion. On the success path the buffer's encoded wait orders the signal
 * before the release, but a buffer that finishes by an error or teardown path
 * releases the event first — previously the late callback then threw
 * {@code IllegalStateException} from {@code setSignaledValue} (observed
 * intermittently as {@code Exception in thread "Semaphore onComplete"}), and
 * because release and signal were unsynchronized, the same window could reach
 * freed native state. {@link MTLEvent#signal(long)} closes both: it skips a
 * released event and is mutually excluded with {@link MTLEvent#release()}.</p>
 */
public class MTLEventLifecycleTest extends TestSuiteBase {

	/**
	 * Returns a fresh shared event, or null when no Metal device is available.
	 */
	private MTLEvent newEvent() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) return null;
		return metal.getMtlDevice().newSharedEvent();
	}

	/**
	 * The normal bridge order: signal from the completion callback, then release
	 * with the buffer. Release is idempotent, matching the destroy paths that can
	 * run completion callbacks for a buffer more than one teardown route reaches.
	 */
	@Test(timeout = 60000)
	public void signalThenReleaseSucceeds() {
		MTLEvent event = newEvent();
		if (event == null) {
			log("Skipping signalThenReleaseSucceeds - no Metal device");
			return;
		}

		Assert.assertTrue("A live event must accept the signal", event.signal(1));
		event.release();
		Assert.assertTrue(event.isReleased());
		event.release();
		Assert.assertTrue(event.isReleased());
	}

	/**
	 * The race order this contract exists for: the buffer finished by another path
	 * and released the event before the foreign completion callback ran. The late
	 * signal must report false without throwing.
	 */
	@Test(timeout = 60000)
	public void lateSignalAfterReleaseIsSkipped() {
		MTLEvent event = newEvent();
		if (event == null) {
			log("Skipping lateSignalAfterReleaseIsSkipped - no Metal device");
			return;
		}

		event.release();
		Assert.assertFalse("A released event must skip the signal", event.signal(1));
	}

	/**
	 * The legacy unguarded form keeps its documented contract: signaling a
	 * released event through {@link MTLEvent#setSignaledValue(long)} throws.
	 */
	@Test(timeout = 60000)
	public void setSignaledValueAfterReleaseStillThrows() {
		MTLEvent event = newEvent();
		if (event == null) {
			log("Skipping setSignaledValueAfterReleaseStillThrows - no Metal device");
			return;
		}

		event.release();

		try {
			event.setSignaledValue(1);
			Assert.fail("setSignaledValue on a released event must throw");
		} catch (IllegalStateException e) {
			log("setSignaledValue correctly threw after release");
		}
	}
}
