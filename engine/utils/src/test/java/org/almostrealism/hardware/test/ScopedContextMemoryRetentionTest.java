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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Memory allocated under a scoped data context and still referenced when the scope
 * ends stays valid. The scope's provider retires rather than freeing what it still
 * tracks, and each block is released when its holder lets go of it.
 *
 * <p>Before this was enforced, the scope's provider freed every remaining block, and a
 * holder that outlived the scope, such as a process-wide cache the scope had filled,
 * went on reading and writing freed memory until the heap was corrupted and the
 * process aborted.</p>
 */
public class ScopedContextMemoryRetentionTest extends TestSuiteBase {
	/**
	 * A collection allocated inside a scope is read, used by a kernel, and destroyed
	 * after the scope has ended.
	 */
	@Test(timeout = 60_000)
	public void memoryAllocatedInScopeSurvivesIt() {
		int length = 1024;
		double[] expected = new double[1];

		PackedCollection kept = dc(() -> {
			PackedCollection c = new PackedCollection(shape(length)).randFill();
			expected[0] = sum(cp(c)).evaluate().toDouble(0);
			return c;
		});

		MemoryProvider<?> provider = kept.getMem().getProvider();
		assertTrue("Retained memory is not reported as released", !provider.isReleased(kept.getMem()));

		double first = kept.toDouble(0);
		double total = sum(cp(kept)).evaluate().toDouble(0);
		log("expected=" + expected[0] + " total=" + total + " first=" + first);

		assertEquals("Sum after the scope", expected[0], total, 1e-6);
		assertTrue("Retained values are not zero", first != 0.0 || total != 0.0);

		kept.destroy();
	}
}
