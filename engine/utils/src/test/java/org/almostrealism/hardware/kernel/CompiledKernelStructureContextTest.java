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

package org.almostrealism.hardware.kernel;

import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests what a {@link CompiledKernelStructureContext} offers the kernel compiled from a
 * computation: the kernel structure resources, which it creates only for a computation that
 * reports it may refer to them (see {@link io.almostrealism.code.Computation#isKernelStructureSupported()}),
 * and the lanes of the compute context the kernel is compiled for.
 */
public class CompiledKernelStructureContextTest extends TestSuiteBase {

	/**
	 * A traversal operation is itself a kernel structure resource, generated for another kernel,
	 * so the structure context of its own kernel offers neither a series nor a traversal provider,
	 * which keeps compiling it from generating further traversal operations.
	 */
	@Test(timeout = 30000)
	public void traversalOperationHasNoKernelStructureOfItsOwn() {
		KernelTraversalOperation<?> traversal = new KernelTraversalOperation<>();
		CompiledKernelStructureContext context = new CompiledKernelStructureContext(traversal, 1);

		Assert.assertFalse(traversal.isKernelStructureSupported());
		Assert.assertFalse(context.isKernelStructureSupported());
		Assert.assertNull(context.getSeriesProvider());
		Assert.assertNull(context.getTraversalProvider());
	}

	/**
	 * Any other computation may refer to kernel structure resources, and the structure context of
	 * its kernel reports the lanes of the compute context it was created for.
	 */
	@Test(timeout = 30000)
	public void computationSupportsKernelStructure() {
		try (PackedCollection input = new PackedCollection(shape(2));
			 PackedCollection weights = new PackedCollection(shape(2))) {
			WeightedSumComputation sum = new WeightedSumComputation(shape(2), shape(2), shape(2),
					shape(1), shape(1), cp(input), cp(weights));
			CompiledKernelStructureContext context = new CompiledKernelStructureContext(sum, 32);

			Assert.assertTrue(sum.isKernelStructureSupported());
			Assert.assertTrue(context.isKernelStructureSupported());
			Assert.assertEquals(32, context.getLaneCount());
		}
	}
}
