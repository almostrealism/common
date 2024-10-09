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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
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
		log("Total = " + input.doubleStream().map(Math::abs).sum());

		FourierTransform ft = fft(bins, cp(input), requirement);
		PackedCollection<?> out = ft.get().evaluate();
		log(out.getShape());
		log("Total = " + input.doubleStream().map(Math::abs).sum());

		FourierTransform ift = fft(bins, true, cp(out), requirement);
		PackedCollection<?> reversed = ift.get().evaluate();
		log(reversed.getShape());
		log("Total = " + input.doubleStream().map(Math::abs).sum());

		for (int i = 0; i < bins; i++) {
			double expected = input.valueAt(0, i);
			double actual = reversed.toDouble(i);
			log(expected + " vs " + actual);
			assertSimilar(expected, actual, 0.0001);
		}
	}

	protected PackedCollection<?> generateWaves(int sampleRate, int frames, TraversalPolicy shape) {
		Frequency f1 = new Frequency(440.00);
		Frequency f2 = new Frequency(587.33);

		PackedCollection<?> input = new PackedCollection<>(shape);
		a(cp(input.range(shape(frames)).each()),
				add(
						sinw(integers(0, frames).divide(sampleRate), c(f1.getWaveLength()), c(0.9)),
						sinw(integers(0, frames).divide(sampleRate), c(f2.getWaveLength()), c(0.6))))
				.get().run();
		return input;
	}

	@Test
	public void multiBatchTransform1() {
		int sampleRate = 44100;
		int bins = 1024;
		int totalSlices = 2;
		int comparisonSlice = 1;

		PackedCollection<?> input = generateWaves(sampleRate, bins, shape(2, bins));
		input = cp(input).repeat(totalSlices).evaluate();

		// Confirm that the input slices are identical
		for (int i = 0; i < 2 * bins; i++) {
			double expected = input.toDouble(i);
			double actual = input.toDouble(2 * bins * comparisonSlice + i);
			assertSimilar(expected, actual, 0.0001);
		}

		// Apply the transform to the batches
		FourierTransform ft = fft(bins, (Producer) cp(input).traverse(1),
									ComputeRequirement.CPU);
		PackedCollection<?> out = ft.get().evaluate();
		log(out.getShape());

		int total = 0;

		// Confirm that the output slices are identical
		for (int i = 0; i < bins; i++) {
			double expected = out.toDouble(i);
			double actual = out.toDouble(2 * bins * comparisonSlice + i);
			// log(expected + " vs " + actual);
			assertSimilar(expected, actual, 0.0001);
			if (expected > 0) total++;
		}

		Assert.assertTrue(total > 300);
	}

	@Test
	public void multiBatchTransform2() {
		int sampleRate = 44100;
		int bins = 1024;

		multiBatchTransformAndReverse(sampleRate, bins, 4, 1, false);
	}

	@Test
	public void multiBatchTransform3() {
		int sampleRate = 44100;
		int bins = 1024;

		multiBatchTransformAndReverse(sampleRate, bins, 4, 1, true);
	}

	@Test
	public void multiBatchTransform4() {
		int sampleRate = 44100;
		int bins = 1024;

		multiBatchTransformAndReverse(sampleRate, bins, 8, 3, true);
	}

	protected void multiBatchTransformAndReverse(int sampleRate, int bins,
												 int totalSlices, int comparisonSlice,
												  boolean embedRepeat) {
		Frequency f1 = new Frequency(440.00);
		Frequency f2 = new Frequency(587.33);

		int frames = totalSlices * bins;
		PackedCollection<?> input = new PackedCollection<>(frames / bins, bins);

		a(cp(input.range(shape(frames)).each()),
				add(
						sinw(integers(0, frames).divide(sampleRate), c(f1.getWaveLength()), c(0.9)),
						sinw(integers(0, frames).divide(sampleRate), c(f2.getWaveLength()), c(0.6))))
				.get().run();

		if (!embedRepeat) {
			input = cp(input).traverse(1).repeat(2).evaluate();

			for (int i = 0; i < totalSlices; i++) {
				for (int j = 0; j < bins; j++) {
					input.range(shape(bins), bins * (1 + 2 * i)).each().set(j, 0.0);
				}
			}
		}

		FourierTransform ft = fft(bins,
				(Producer) (embedRepeat ? cp(input).traverse(1).repeat(2) : cp(input).traverse(1)),
				ComputeRequirement.CPU);
		PackedCollection<?> out = ft.get().evaluate();
		log(out.getShape());

		FourierTransform ift = ifft(bins,
				cp(out.range(shape(2, bins), comparisonSlice * 2 * bins)),
				ComputeRequirement.CPU);
		PackedCollection<?> reversed = ift.get().evaluate();
		log(reversed.getShape());

		PackedCollection<?> range = embedRepeat ?
				input.range(shape(bins), comparisonSlice * bins) :
				input.range(shape(bins), comparisonSlice * 2 * bins);

		for (int i = 0; i < bins; i++) {
			double expected = range.valueAt(i);
			double actual = reversed.toDouble(i);
			// log(expected + " vs " + actual);
			assertSimilar(expected, actual, 0.0001);
		}
	}
}
