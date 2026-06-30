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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.io.Console;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Verifies that {@code AcceleratedOperation} detects and warns when an aggregated operation's
 * copy-in/kernel/copy-out dispatch group is split across more than one compute command buffer.
 *
 * <p>The aggregated copies are submitted with null dependencies and rely on the provider's
 * <em>in-buffer</em> hazard tracking to order their read-after-write dependency on the aggregate
 * buffer. That ordering does not cross a command-buffer boundary, so if the provider forces a commit
 * partway through the group the result may be wrong. That split is a known, currently-unmitigated
 * limitation; this test confirms it surfaces as a warning rather than as silent corruption.</p>
 *
 * <p>The split is provoked deterministically: an operation is given more aggregated inputs than the
 * Metal backend's per-buffer dispatch limit, so the copy-in group alone spans two buffers.</p>
 */
public class AggregateBatchSplitWarningTest extends TestSuiteBase {

	/**
	 * Builds a single operation that aggregates more inputs than fit in one command buffer and
	 * asserts that evaluating it emits the batch-split warning.
	 */
	@Test(timeout = 120000)
	public void copyInStraddleWarns() {
		// The split, and therefore the warning, only arises on a backend that batches dispatches into
		// command buffers (Metal). On a synchronous backend there is nothing to split, so skip.
		if (!(Hardware.getLocalHardware().getComputeContext() instanceof MetalComputeContext)) {
			return;
		}

		// More aggregated inputs than the Metal per-buffer dispatch limit (MetalCommandRunner.MAX_OPEN
		// = 256), so the copy-in group alone is forced to commit mid-group and span two buffers.
		int count = 300;

		List<CollectionProducer> terms = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			PackedCollection input = new PackedCollection(1);
			input.fill(pos -> 1.0);
			terms.add(cp(input));
		}

		// Balanced reduction keeps the expression shallow while leaving all `count` inputs as
		// distinct aggregated arguments (each contributes one copy-in dispatch).
		while (terms.size() > 1) {
			List<CollectionProducer> next = new ArrayList<>();
			for (int i = 0; i < terms.size(); i += 2) {
				if (i + 1 < terms.size()) {
					next.add(add(terms.get(i), terms.get(i + 1)));
				} else {
					next.add(terms.get(i));
				}
			}
			terms = next;
		}

		CollectionProducer sum = terms.get(0);

		List<String> captured = Collections.synchronizedList(new ArrayList<>());
		Consumer<String> listener = captured::add;
		Console.root().addListener(listener);

		try {
			sum.evaluate();
		} finally {
			Console.root().removeListener(listener);
		}

		boolean warned = captured.stream()
				.anyMatch(s -> s.contains("spanned more than one compute command buffer"));
		Assert.assertTrue("Expected a batch-split warning for an operation aggregating " + count
				+ " inputs across the per-buffer dispatch limit", warned);
	}
}
