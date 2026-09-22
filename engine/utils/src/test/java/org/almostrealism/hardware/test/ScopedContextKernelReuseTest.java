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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * An evaluable compiles its kernel under whatever data context is current the first
 * time it runs. When that was a scoped context, the kernel is bound to a compute
 * context and memory provider that are gone once the scope ends, so a later
 * evaluation must compile again rather than dispatch through the dead context.
 *
 * <p>Before this was enforced, the stale kernel reallocated its arguments into the
 * destroyed provider, which failed on the dispatch thread and left the evaluating
 * thread waiting on a readiness latch that never fired.</p>
 */
public class ScopedContextKernelReuseTest extends TestSuiteBase {
	/**
	 * Evaluates one evaluable in three successive scoped contexts. Each pass allocates
	 * its own input inside the scope, so only the kernel is shared between passes.
	 */
	@Test(timeout = 120_000)
	public void kernelIsRecompiledAfterScopedContextDestroyed() {
		int length = 1024;
		CollectionProducer doubled = c(new PassThroughProducer<>(shape(length), 0)).multiply(2.0);
		Evaluable<PackedCollection> ev = doubled.get();

		for (int pass = 0; pass < 3; pass++) {
			double difference = dc(() -> {
				PackedCollection input = new PackedCollection(shape(length)).randFill();
				PackedCollection out = ev.evaluate(input);
				double total = sum(cp(out)).evaluate().toDouble(0);
				double diff = sum(cp(out).subtract(cp(input).multiply(2.0)).abs()).evaluate().toDouble(0);
				log("total=" + total + " difference=" + diff);

				if (total == 0.0) {
					throw new AssertionError("Output is entirely zero");
				}

				return diff;
			});

			assertEquals("Difference on pass " + pass, 0.0, difference, 1e-6);
		}
	}
}
