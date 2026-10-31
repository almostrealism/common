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

package org.almostrealism.layers.test;

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests {@link Model#setComputeRequirements(ComputeRequirement...)}: the requirements a model
 * records, and that a model compiled under them computes the same forward and backward passes
 * as the same model compiled without them.
 */
public class ModelComputeRequirementsTest extends TestSuiteBase implements LayerFeatures {

	/**
	 * A new model has no requirements; setting some records them in order, and setting none
	 * clears them again so that each operation chooses its own context.
	 */
	@Test(timeout = 30000)
	public void requirementsAreRecordedAndCleared() {
		Model model = new Model(shape(3));
		Assert.assertNull(model.getComputeRequirements());

		model.setComputeRequirements(ComputeRequirement.CPU);
		Assert.assertEquals(List.of(ComputeRequirement.CPU), model.getComputeRequirements());

		model.setComputeRequirements(ComputeRequirement.GPU, ComputeRequirement.CPU);
		Assert.assertEquals(List.of(ComputeRequirement.GPU, ComputeRequirement.CPU),
				model.getComputeRequirements());

		model.setComputeRequirements();
		Assert.assertNull(model.getComputeRequirements());
	}

	/**
	 * A model compiled with backpropagation under a requirement produces the exact forward
	 * output of the matrix product and the same input gradient as an identical model compiled
	 * without requirements, so placing every pass on one context changes where it runs, not what
	 * it computes.
	 */
	@Test(timeout = 120000)
	public void requiredPassesComputeTheSameResults() {
		ComputeRequirement requirement = Hardware.getLocalHardware()
				.isAvailable(ComputeRequirement.GPU) ? ComputeRequirement.GPU : ComputeRequirement.CPU;

		try (PackedCollection freeWeights = integers(1, 7).reshape(shape(2, 3)).evaluate();
			 PackedCollection requiredWeights = integers(1, 7).reshape(shape(2, 3)).evaluate();
			 PackedCollection input = pack(1.0, -1.0, 2.0);
			 PackedCollection gradient = pack(1.0, 0.5)) {
			Model free = new Model(shape(3));
			free.add(dense(freeWeights));

			Model required = new Model(shape(3));
			required.add(dense(requiredWeights));
			required.setComputeRequirements(requirement);

			CompiledModel freeCompiled = free.compile(true, true);
			CompiledModel requiredCompiled = required.compile(true, true);

			try {
				double[] expected = { 1.0 - 2.0 + 6.0, 4.0 - 5.0 + 12.0 };
				Assert.assertArrayEquals(expected,
						freeCompiled.forward(input).toArray(), 1e-5);
				Assert.assertArrayEquals(expected,
						requiredCompiled.forward(input).toArray(), 1e-5);

				double[] freeGradient = freeCompiled.backward(gradient).toArray();
				double[] requiredGradient = requiredCompiled.backward(gradient).toArray();
				Assert.assertEquals(3, requiredGradient.length);
				Assert.assertArrayEquals(freeGradient, requiredGradient, 1e-5);
			} finally {
				freeCompiled.destroy();
				requiredCompiled.destroy();
			}
		}
	}
}
