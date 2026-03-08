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

package org.almostrealism.time.computations.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

/**
 * Tests that {@link MultiOrderFilter} produces correct convolution results
 * when coefficients are provided via expression trees (e.g., from
 * {@code lowPassCoefficients}) rather than pre-computed constants.
 *
 * <p>These tests exercise the direct buffer reference path in
 * {@link MultiOrderFilter#getScope}, which forces the framework to
 * evaluate coefficient expressions into a buffer argument before
 * kernel dispatch, preventing sin/cos from being inlined into the
 * per-sample convolution loop.</p>
 */
public class MultiOrderFilterConvolutionTest extends TestSuiteBase implements FirFilterTestFeatures {

	/**
	 * Verifies that MultiOrderFilter with pre-computed constant
	 * coefficients produces correct convolution results.
	 */
	@Test(timeout = 15000)
	public void convolutionWithConstantCoefficients() {
		int signalSize = 256;
		int filterOrder = 10;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));

		double[] coeffs = referenceLowPassCoefficients(5000, 44100, filterOrder);
		PackedCollection coefficients = new PackedCollection(filterOrder + 1);
		coefficients.setMem(coeffs);

		MultiOrderFilter filter = MultiOrderFilter.create(
				traverseEach(cp(signal)), p(coefficients));
		PackedCollection result = filter.get().evaluate();

		double[] expected = referenceConvolve(signal.toArray(0, signalSize), coeffs);
		assertConvolutionEquals(expected, result, signalSize);
	}

	/**
	 * Verifies that MultiOrderFilter produces correct results when
	 * coefficients come from the {@code lowPassCoefficients()} expression
	 * tree, which contains sin/cos computations. This exercises the
	 * direct buffer reference path that prevents coefficient expression
	 * inlining.
	 */
	@Test(timeout = 30000)
	public void convolutionWithExpressionCoefficients() {
		int signalSize = 256;
		int filterOrder = 20;
		double cutoff = 5000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 16.0)
						+ 0.5 * Math.sin(2.0 * Math.PI * i / 4.0));

		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoff), sampleRate, filterOrder);
		PackedCollection result = filter.get().evaluate();

		double[] coeffs = referenceLowPassCoefficients(cutoff, sampleRate, filterOrder);
		double[] expected = referenceConvolve(signal.toArray(0, signalSize), coeffs);
		assertConvolutionEquals(expected, result, signalSize);
	}

	/**
	 * Verifies that the filter produces correct results with a larger
	 * signal size matching realistic audio buffer sizes, and with the
	 * same filter order used in the real-time audio pipeline.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void convolutionWithRealisticParameters() {
		int signalSize = 4096;
		int filterOrder = 40;
		double cutoff = 8000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)
						+ 0.3 * Math.sin(2.0 * Math.PI * 12000.0 * i / sampleRate));

		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoff), sampleRate, filterOrder);
		PackedCollection result = filter.get().evaluate();

		double[] coeffs = referenceLowPassCoefficients(cutoff, sampleRate, filterOrder);
		double[] expected = referenceConvolve(signal.toArray(0, signalSize), coeffs);
		assertConvolutionEquals(expected, result, signalSize);
	}

	/**
	 * Verifies that the filter choice mechanism (selecting between
	 * low-pass and high-pass based on a runtime decision variable)
	 * produces correct results when coefficients are pre-computed
	 * into a buffer via the two-kernel approach used by
	 * {@link org.almostrealism.audio.arrange.EfxManager}.
	 *
	 * <p>The choice expression tree is evaluated into a buffer in a
	 * separate operation, then the buffer is passed to MultiOrderFilter
	 * via {@code p()} (plain buffer reference). This ensures the
	 * convolution kernel contains only multiply-accumulate operations.</p>
	 */
	@Test(timeout = 30000)
	public void convolutionWithChosenCoefficients() {
		int signalSize = 128;
		int filterOrder = 20;
		double cutoff = 5000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 16.0));

		Producer<PackedCollection> decision = cp(pack(0.9));

		CollectionProducer lpCoefficients =
				lowPassCoefficients(c(cutoff), sampleRate, filterOrder)
						.reshape(1, filterOrder + 1);
		CollectionProducer hpCoefficients =
				highPassCoefficients(c(cutoff), sampleRate, filterOrder)
						.reshape(1, filterOrder + 1);

		Producer<PackedCollection> coefficients = choice(2,
				shape(filterOrder + 1), decision,
				concat(shape(2, filterOrder + 1), hpCoefficients, lpCoefficients));

		// Pre-compute chosen coefficients into a buffer (two-kernel approach)
		PackedCollection coeffBuffer = new PackedCollection(filterOrder + 1);
		a("coeffs", cp(coeffBuffer.each()), coefficients).get().run();

		// Convolution reads from the pre-computed buffer
		MultiOrderFilter filter = MultiOrderFilter.create(
				traverseEach(cp(signal)), p(coeffBuffer));
		PackedCollection result = filter.get().evaluate();

		double[] coeffs = referenceLowPassCoefficients(cutoff, sampleRate, filterOrder);
		double[] expected = referenceConvolve(signal.toArray(0, signalSize), coeffs);
		assertConvolutionEquals(expected, result, signalSize);
	}
}
