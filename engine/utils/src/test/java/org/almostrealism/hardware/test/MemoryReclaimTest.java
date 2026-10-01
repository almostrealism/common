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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalDataContext;
import org.almostrealism.hardware.metal.MetalMemory;
import org.almostrealism.hardware.metal.MetalMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * Tests that a GPU memory provider reclaims unreachable allocations when an allocation would
 * exceed its ceiling, instead of rejecting the allocation while the ceiling is filled with
 * garbage that has simply not been collected yet.
 */
public class MemoryReclaimTest extends TestSuiteBase {
	/**
	 * Allocating and discarding many blocks whose total far exceeds a small ceiling succeeds,
	 * because each allocation that would exceed the ceiling first reclaims the discarded blocks.
	 */
	@Test(timeout = 120000)
	public void discardedAllocationsAreReclaimed() {
		MetalDataContext context = metalContext();
		Assume.assumeTrue("requires the Metal backend", context != null);

		int blockElements = 4 * 1024 * 1024;
		long ceiling = 4L * blockElements * 4;
		MetalMemoryProvider provider = new MetalMemoryProvider(context, 4, ceiling);

		for (int i = 0; i < 24; i++) {
			MetalMemory block = provider.allocate(blockElements);
			Assert.assertTrue(provider.getAllocatedMemory() <= ceiling);
			Assert.assertNotNull(block);
		}

		log("allocatedBytes=" + provider.getAllocatedMemory() + " ceiling=" + ceiling);
	}

	/**
	 * Returns the Metal data context of the active hardware configuration, or null when no
	 * Metal backend is available.
	 *
	 * @return the Metal data context, or null
	 */
	private MetalDataContext metalContext() {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, ComputeRequirement.MTL).stream()
					.map(c -> c.getDataContext())
					.filter(MetalDataContext.class::isInstance)
					.map(MetalDataContext.class::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
