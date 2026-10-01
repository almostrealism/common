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

import org.almostrealism.hardware.mem.HardwareMemoryProvider.SweepAction;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Validates {@link HardwareMemoryProvider#decideSweep(long, long, long, boolean, boolean)}, the
 * policy by which the deferred-release sweep decides the fate of each held-back release. The
 * decision is pulled out as a pure function so the scheduling-lease-to-execution-guard timeout
 * handoff can be exercised deterministically, without the background sweep thread or real GPU
 * memory.
 */
public class HardwareMemoryProviderSweepTest extends TestSuiteBase {

	/** The backstop window used throughout, matching the default {@code deferredReleaseTimeoutMs}. */
	private static final long TIMEOUT = 30_000L;

	/**
	 * A scheduling lease keeps the release held back, and its timeout is restarted on every sweep,
	 * regardless of how long it has already been held: a lease covers the scheduling-to-execution
	 * window, which may legitimately outlast the backstop a kernel execution never reaches.
	 */
	@Test(timeout = 10_000)
	public void schedulingLeaseIsHeldAndItsTimeoutRestarts() {
		Assert.assertEquals("A fresh lease must be held with its timeout restarted",
				SweepAction.RESET, HardwareMemoryProvider.decideSweep(0L, 10_000L, TIMEOUT, true, true));
		Assert.assertEquals("A lease held past the backstop must still be held, not expired",
				SweepAction.RESET, HardwareMemoryProvider.decideSweep(0L, 10 * TIMEOUT, TIMEOUT, true, true));
	}

	/**
	 * Regression: a dependency held past the timeout must not expose its execution guard to a
	 * freed block. The lease's last sweep (here at {@code t = 40_000}, past both the original
	 * deferral and the backstop window) restarts the timeout, so at the handoff — where the lease
	 * is gone ({@code scheduled == false}) and the execution guard is now active
	 * ({@code activelyReferenced == true}) — the block is held rather than freed.
	 *
	 * <p>The contrast case shows the defect this guards against: an entry still carrying its
	 * original {@code t = 0} stamp reports as expired at the same moment of handoff and would be
	 * released out from under the active guard.</p>
	 */
	@Test(timeout = 10_000)
	public void executionGuardSurvivesHandoffAfterALongSchedulingWait() {
		long handoffStamp = 40_000L;

		Assert.assertEquals("The execution guard must receive its own timeout window at handoff",
				SweepAction.HOLD,
				HardwareMemoryProvider.decideSweep(handoffStamp, handoffStamp + 5_000L, TIMEOUT, false, true));

		Assert.assertEquals("An un-restarted entry would be freed under the active guard",
				SweepAction.RELEASE,
				HardwareMemoryProvider.decideSweep(0L, handoffStamp + 5_000L, TIMEOUT, false, true));
	}

	/**
	 * The backstop still fires for a genuinely leaked execution guard: an actively referenced block
	 * with no scheduling lease whose window has elapsed is released anyway, so a count that never
	 * comes back cannot hold the block for the life of the process.
	 */
	@Test(timeout = 10_000)
	public void leakedExecutionGuardIsReleasedAfterTheTimeout() {
		Assert.assertEquals("A referenced block within its window is held",
				SweepAction.HOLD, HardwareMemoryProvider.decideSweep(0L, TIMEOUT - 1L, TIMEOUT, false, true));
		Assert.assertEquals("A referenced block past its window is released as a backstop",
				SweepAction.RELEASE, HardwareMemoryProvider.decideSweep(0L, TIMEOUT, TIMEOUT, false, true));
	}

	/**
	 * A block that is no longer referenced by any kernel and holds no lease is released at once,
	 * well before the timeout, since there is nothing left using it.
	 */
	@Test(timeout = 10_000)
	public void finishedBlockIsReleasedImmediately() {
		Assert.assertEquals("A block with no references and no lease is released immediately",
				SweepAction.RELEASE, HardwareMemoryProvider.decideSweep(0L, 1L, TIMEOUT, false, false));
	}
}
