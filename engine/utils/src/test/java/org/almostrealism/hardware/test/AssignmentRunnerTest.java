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
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataProviderProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Validates {@link Assignment.Runner}: an {@link Assignment} whose destination and source are both
 * {@link io.almostrealism.relation.Provider}s compiles to a {@code Runner} that, at run time, lets
 * the {@link io.almostrealism.code.ComputeContext} choose between a direct memory copy and the
 * compiled kernel. The same test must pass on both the Metal and the native (JNI) backends without
 * any backend-specific code: on native the context performs a direct copy, while on Metal it queues
 * the copy kernel.
 */
public class AssignmentRunnerTest extends TestSuiteBase {

	/**
	 * Assigns one provider-backed collection into another and verifies both that the operation is a
	 * {@link Assignment.Runner} and that it produces a correct copy on whichever backend is active.
	 */
	@Test(timeout = 60000)
	public void providerToProviderCopy() {
		int n = 256;

		PackedCollection src = new PackedCollection(n);
		PackedCollection dst = new PackedCollection(n);
		src.fill(pos -> Math.random() + 1.0);

		Runnable op = a(cp(dst), cp(src)).get();

		// Both operands are Providers, so get() must return the runtime-dispatching Runner rather
		// than a DestinationEvaluable or a bare compiled kernel.
		Assert.assertTrue("expected Assignment.Runner, got " + op.getClass().getName(),
				op instanceof Assignment.Runner);

		op.run();

		for (int i = 0; i < n; i++) {
			assertEquals(src.toDouble(i), dst.toDouble(i));
		}
	}

	/**
	 * A provider-to-provider copy (the form {@code MemoryDataArgumentMap} builds for aggregate
	 * copy-in/copy-out) must be a single-statement assignment: {@code memLength == 1}, with the entire
	 * size carried by the {@code count}. This guards against sizing the copy with
	 * {@code memLength == length}, which would unroll one statement per element — a distinct, larger
	 * {@link io.almostrealism.scope.Scope} per length (capped by {@link io.almostrealism.scope.ScopeSettings})
	 * instead of one reused program. Because {@code memLength} is always {@code 1}, the
	 * {@link Assignment#signature() signature} is size-independent, so a single compiled program serves
	 * copies of every length.
	 */
	@Test(timeout = 60000)
	public void providerCopyIsSingleStatement() {
		int[] sizes = {1, 17, 256, 4096};
		String signature = null;

		for (int n : sizes) {
			PackedCollection src = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);

			Assignment<MemoryData> copy = new Assignment<>(1,
					new MemoryDataProviderProducer(dst),
					new MemoryDataProviderProducer(src));

			// describe() is "{shortDescription} ({count}x{memLength})": memLength must be 1 (a single
			// statement) and the count must carry the full element count.
			Assert.assertTrue("expected a single-statement copy (count " + n + ", memLength 1), got "
					+ copy.describe(), copy.describe().endsWith("(" + n + "x1)"));

			// The signature must not depend on the length, so one compiled program is reused.
			if (signature == null) signature = copy.signature();
			Assert.assertEquals("provider-to-provider copy signature must be size-independent",
					signature, copy.signature());
		}

		Assert.assertEquals("assign1->memoryDataProvider", signature);
	}
}
