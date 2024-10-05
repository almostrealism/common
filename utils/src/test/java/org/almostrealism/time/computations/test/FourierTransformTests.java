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
import org.almostrealism.time.Frequency;
import org.almostrealism.time.computations.FourierTransform;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
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

	@Test
	public void forwardAndInverse() {
		forwardAndInverse(ComputeRequirement.CPU);
	}

	public void forwardAndInverse(ComputeRequirement requirement) {
		int bins = 1024;
		Frequency f1 = new Frequency(440.00);
		Frequency f2 = new Frequency(587.33);

		PackedCollection<?> input = new PackedCollection<>(2, bins);

		a(cp(input.range(shape(bins)).each()),
				add(
						sinw(integers(0, bins), c(f1.getWaveLength()), c(0.9)),
						sinw(integers(0, bins), c(f2.getWaveLength()), c(0.6))))
				.get().run();

		FourierTransform ft = fft(bins, cp(input), requirement);
		PackedCollection<?> out = ft.get().evaluate();
		log(out.getShape());

		FourierTransform ift = fft(bins, true, cp(out), requirement);
		PackedCollection<?> reversed = ift.get().evaluate();
		log(reversed.getShape());
		
		int total = 0;

		for (int i = 0; i < bins; i++) {
			double expected = bins * input.valueAt(0, i);

			if (expected != 0) {
				double actual = reversed.toDouble(i);
				log(expected + " vs " + actual);
				assertSimilar(expected, actual, 0.0001);
				total++;
			}
		}

		Assert.assertTrue(total > (bins * 0.9));
	}
}
