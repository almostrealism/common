/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.time.computations.test;

import io.almostrealism.code.ComputeRequirement;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.FourierTransform;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.List;

public class FourierTransformTests implements TestFeatures {
	@Test
	public void compileCpu() {
		compile(ComputeRequirement.CPU);
	}

	@Test
	public void compileGpu() {
		if (FourierTransform.enableRecursion) {
			if (skipKnownIssues) return;
			throw new UnsupportedOperationException("Recursion is not supported on the GPU");
		}

		compile(ComputeRequirement.GPU);
	}

	public void compile(ComputeRequirement requirement) {
		int bins = 256;

		PackedCollection<?> input = new PackedCollection<>(bins, 2);
		FourierTransform ft = new FourierTransform(bins, cp(input));
		ft.setComputeRequirements(List.of(requirement));
		ft.get().evaluate();
	}
}
