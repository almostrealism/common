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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Minimal reproduction of the aggregate copy-out defect: a single assignment whose destination is a
 * small (aggregatable) provider-backed collection. The destination is folded into the aggregate
 * buffer, so after the kernel writes its slice the result must be de-aggregated back into the
 * destination. On a batching context (Metal) that de-aggregation consumes the aggregate before the
 * kernel's command buffer has run, so the destination reads back as zero.
 */
public class AggregateOutputCopyTest extends TestSuiteBase {

	/**
	 * Assigns {@code x = y * z} where the destination is written as a plain provider. The Assignment
	 * short-circuits to write {@code x} as the kernel's explicit destination, so {@code x} is never
	 * folded into the aggregate and there is no de-aggregation copy-out — this form does NOT reproduce
	 * the defect (kept as a control).
	 */
	@Test(timeout = 60000)
	public void directDestinationMultiply() {
		int n = 16;

		PackedCollection x = new PackedCollection(n);
		PackedCollection y = new PackedCollection(n).randFill();
		PackedCollection z = new PackedCollection(n).randFill();

		a(cp(x), cp(y).multiply(cp(z))).get().run();

		for (int i = 0; i < n; i++) {
			assertEquals(y.toDouble(i) * z.toDouble(i), x.toDouble(i));
		}
	}

	/**
	 * Assigns {@code x = y * z} with a traversed destination, exactly as a layer writes its output
	 * (see {@code LayerFeatures.into}: {@code a(traverse(axis, out), traverse(axis, in))}). The
	 * traversed destination is not a plain provider, so the Assignment takes the general kernel path
	 * and {@code x} is folded into the aggregate buffer. The kernel writes {@code x}'s aggregate slice
	 * and the result must be de-aggregated back into {@code x}. On Metal that de-aggregation reads the
	 * aggregate before the kernel's command buffer has run, so {@code x} reads back as zero.
	 */
	@Test(timeout = 60000)
	public void aggregatedDestinationMultiply() {
		int n = 16;

		PackedCollection x = new PackedCollection(n);
		PackedCollection y = new PackedCollection(n).randFill();
		PackedCollection z = new PackedCollection(n).randFill();

		a(cp(x).each(), cp(y).multiply(cp(z)).each()).get().run();

		for (int i = 0; i < n; i++) {
			assertEquals(y.toDouble(i) * z.toDouble(i), x.toDouble(i));
		}
	}
}
