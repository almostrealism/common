/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.IOException;

/**
 * Delta computation tests for 2D convolution operations.
 * These tests are separated from {@link RepeatedDeltaComputationTests}
 * because they are significantly more expensive to run.
 */
public class Convolution2dDeltaComputationTests extends TestSuiteBase {

	@Test(timeout = 7 * 60000)
	@TestDepth(1)
	public void convSmallest() throws IOException {
		int dim = 10;
		int size = 3;
		int filters = 8;

		convolution2d("convSmallest", shape(dim, dim), size, filters);
	}

	@Test(timeout = 40 * 60000)
	@TestDepth(2)
	public void convSmall() throws IOException {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int dim = 16;
		int size = 3;
		int filters = 8;

		convolution2d("convSmall", shape(dim, dim), size, filters);
	}

	@Test(timeout = 60000)
	@TestProperties(knownIssue = true)
	public void convLarge() throws IOException {

		int dim = 64;
		int size = 3;
		int filters = 8;

		convolution2d("convLarge", shape(dim, dim), size, filters);
	}

	/**
	 * Constructs a 2D convolution and computes its delta with respect to the input.
	 *
	 * @param name profile name for metrics output
	 * @param inputShape spatial dimensions of the input (rows x cols)
	 * @param size convolution kernel size
	 * @param filterCount number of convolution filters
	 */
	public void convolution2d(String name, TraversalPolicy inputShape, int size, int filterCount) throws IOException {
		OperationProfileNode profile = new OperationProfileNode(name);

		try {
			initKernelMetrics();

			int pad = size - 1;
			TraversalPolicy outputShape = shape(inputShape.length(0) - pad, inputShape.length(1) - pad, filterCount);
			TraversalPolicy filterShape = shape(filterCount, size, size);
			PackedCollection filters = new PackedCollection(filterShape).randnFill();

			PackedCollection input = new PackedCollection(inputShape).randnFill();
			Process.optimized(cp(input).enumerate(1, size, 1)
					.enumerate(1, size, 1)
					.traverse(2)
					.repeat(filterCount)
					.traverse(2)
					.multiply(cp(filters)
							.repeat(outputShape.length(1)).traverse(0)
							.repeat(outputShape.length(0)).traverse(2))
					.traverse()
					.sum()
					.delta(cp(input)))
					.get().evaluate();
		} finally {
			logKernelMetrics();
			profile.save("results/" + name + ".xml");
		}
	}
}
