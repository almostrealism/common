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
import org.almostrealism.hardware.computations.Assignment;
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
	 * A provider-to-provider copy must copy correctly across a range of sizes. Since the copy is
	 * performed via the {@link io.almostrealism.code.ComputeContext}'s
	 * {@link io.almostrealism.code.ComputeContext#copy(io.almostrealism.code.Memory, int, io.almostrealism.code.Memory, int, int) copy}
	 * (no compiled kernel), there is no per-size program to reuse; this guards that the copy moves the
	 * full contents regardless of length.
	 */
	@Test(timeout = 60000)
	public void providerCopyAcrossSizes() {
		int[] sizes = {1, 17, 256, 4096};

		for (int n : sizes) {
			PackedCollection src = new PackedCollection(n);
			PackedCollection dst = new PackedCollection(n);
			src.fill(pos -> Math.random() + 1.0);

			Runnable op = a(cp(dst), cp(src)).get();
			Assert.assertTrue("expected Assignment.Runner, got " + op.getClass().getName(),
					op instanceof Assignment.Runner);
			op.run();

			for (int i = 0; i < n; i++) {
				assertEquals(src.toDouble(i), dst.toDouble(i));
			}
		}
	}
}
