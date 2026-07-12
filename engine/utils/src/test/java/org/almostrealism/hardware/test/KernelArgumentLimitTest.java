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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Minimal reproduction of the kernel-argument-count limit that argument
 * aggregation exists to overcome.
 *
 * <p>A single computation that combines many distinct {@link PackedCollection}
 * inputs binds each input as its own kernel argument. Compute contexts cap the
 * number of arguments a kernel may take: Metal allows buffer indices {@code 0}
 * through {@code 30} (31 buffers total), so a kernel that references more than
 * ~31 distinct buffers fails to compile with
 * {@code 'buffer' attribute parameter is out of bounds: must be between 0 and 30}.
 * The JNI/native backend has no comparable limit, so the same graph compiles and
 * runs there.</p>
 *
 * <p>This is the compact analogue of the production failures in
 * {@code BatchedChainSeamTest} and {@code BatchedSssFromScalarsTest}, whose fused
 * scalar-driven render feeds dozens of small per-note scalar collections into one
 * kernel. Argument aggregation resolves it by packing many small buffers into a
 * single consolidated kernel argument (copied in before, and back out after,
 * evaluation), keeping the bound argument count well under the limit.</p>
 *
 * <p><strong>Success indicator:</strong> with aggregation in effect this test
 * passes on every backend (the {@value #ARG_COUNT} small inputs are packed into a
 * handful of arguments). Without it, {@link #manySmallInputsExceedKernelArgumentLimit}
 * compiles and evaluates correctly under JNI but fails to compile under Metal once
 * the distinct-buffer count crosses the per-kernel limit.</p>
 */
public class KernelArgumentLimitTest extends TestSuiteBase {

	/**
	 * Number of distinct single-element inputs summed in one kernel. Chosen to sit
	 * comfortably above the Metal per-kernel limit of 31 buffers so the unaggregated
	 * graph cannot bind them all as individual arguments.
	 */
	private static final int ARG_COUNT = 48;

	/**
	 * Creates a single-element {@link PackedCollection} holding the given scalar value.
	 */
	private PackedCollection single(double value) {
		PackedCollection c = new PackedCollection(1);
		a(cp(c), c(value)).get().run();
		return c;
	}

	/**
	 * Sums {@value #ARG_COUNT} distinct single-element collections in one computation.
	 * Each input is a separate buffer, so the compiled kernel needs more arguments than
	 * Metal permits unless they are aggregated. The assertion also verifies the summed
	 * result, so a correct-but-aggregated kernel is validated end to end.
	 */
	@Test(timeout = 120000)
	public void manySmallInputsExceedKernelArgumentLimit() {
		PackedCollection[] inputs = new PackedCollection[ARG_COUNT];

		CollectionProducer sum = null;
		double expected = 0.0;
		for (int i = 0; i < ARG_COUNT; i++) {
			double value = i + 1.0;
			expected += value;
			inputs[i] = single(value);
			sum = sum == null ? cp(inputs[i]) : sum.add(cp(inputs[i]));
		}

		PackedCollection out = sum.evaluate();

		assertEquals(expected, out.toDouble(0));
	}
}
