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
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the description of live allocations that a memory provider reports when an
 * allocation is rejected at its ceiling, which is what lets the memory holding the
 * ceiling be attributed to the code that allocated it.
 */
public class MemoryAllocationReportTest extends TestSuiteBase {
	/** Elements per allocated block. */
	private static final int BLOCK_ELEMENTS = 1024;

	/** Bytes per allocated block, for 4-byte elements. */
	private static final long BLOCK_BYTES = 4L * BLOCK_ELEMENTS;

	/** Memory ceiling of the provider under test, in bytes. */
	private static final long CEILING = 4 * BLOCK_BYTES;

	/** Stack frames recorded per allocation; enough to reach the allocating test method. */
	private static final int TRACE_FRAMES = 32;

	/**
	 * Blocks allocated from two sites are reported as two groups, the site holding more
	 * bytes first, each naming its block count and bytes and the frames that allocated it.
	 */
	@Test(timeout = 60000)
	public void allocationsAreGroupedBySite() {
		int frames = RAM.allocationTraceFrames;
		RAM.allocationTraceFrames = TRACE_FRAMES;
		NativeMemoryProvider provider = provider();
		List<RAM> held = new ArrayList<>();

		try {
			for (int i = 0; i < 2; i++) {
				held.add(provider.allocate(BLOCK_ELEMENTS));
			}

			held.add(provider.allocate(BLOCK_ELEMENTS));

			String report = provider.describeAllocations(10);
			String[] lines = report.split("\n");

			Assert.assertEquals("3 blocks holding " + 3 * BLOCK_BYTES + " bytes from 2 allocation sites", lines[0]);
			Assert.assertEquals(2 * BLOCK_BYTES + " bytes in 2 blocks", lines[1]);
			Assert.assertTrue("the allocating method is not named in\n" + report,
					report.contains("allocationsAreGroupedBySite"));
			Assert.assertTrue("the smaller site is not listed second in\n" + report,
					report.contains("\n" + BLOCK_BYTES + " bytes in 1 blocks\n"));
			Assert.assertFalse(report.contains("AR_HARDWARE_ALLOCATION_TRACE_FRAMES"));
		} finally {
			RAM.allocationTraceFrames = frames;
			held.forEach(block -> provider.deallocate(BLOCK_ELEMENTS, block));
			provider.destroy();
		}
	}

	/**
	 * Blocks allocated without a recorded site are reported together, with the setting
	 * that records sites named, and an allocation beyond the ceiling is still rejected with
	 * the provider's own message.
	 */
	@Test(timeout = 60000)
	public void unrecordedSitesNameTheTraceSetting() {
		int frames = RAM.allocationTraceFrames;
		RAM.allocationTraceFrames = 0;
		NativeMemoryProvider provider = provider();
		List<RAM> held = new ArrayList<>();

		try {
			for (int i = 0; i < 4; i++) {
				held.add(provider.allocate(BLOCK_ELEMENTS));
			}

			String report = provider.describeAllocations(10);
			Assert.assertTrue(report, report.startsWith("4 blocks holding " + CEILING + " bytes from 1 allocation sites\n" +
					CEILING + " bytes in 4 blocks allocated where no site was recorded"));
			Assert.assertTrue(report, report.endsWith("Set AR_HARDWARE_ALLOCATION_TRACE_FRAMES to record allocation sites"));

			try {
				held.add(provider.allocate(BLOCK_ELEMENTS));
				Assert.fail("an allocation beyond the ceiling was accepted");
			} catch (HardwareException expected) {
				Assert.assertEquals("Memory max reached", expected.getMessage());
			}
		} finally {
			RAM.allocationTraceFrames = frames;
			held.forEach(block -> provider.deallocate(BLOCK_ELEMENTS, block));
			provider.destroy();
		}
	}

	/**
	 * Creates a direct-buffer provider with the test ceiling.
	 *
	 * @return the provider
	 */
	private static NativeMemoryProvider provider() {
		return new NativeMemoryProvider(Precision.FP32, CEILING, false, null, true);
	}
}
