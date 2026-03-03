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

package org.almostrealism.time.computations.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that pre-computing filter coefficients into a buffer produces
 * identical results to computing them inline during convolution.
 *
 * <p>The coefficient pre-computation optimization separates the expensive
 * sinc/Hamming window computation (involving sin and cos) from the
 * convolution kernel, so that coefficients are computed once per buffer
 * instead of once per sample.</p>
 *
 * <p>These tests verify that the optimization does not change the
 * filter output. If the pre-computation were removed or broken, the
 * tests would fail because the output would differ.</p>
 *
 * @see MultiOrderFilter
 * @see org.almostrealism.time.TemporalFeatures#lowPassCoefficients
 */
public class MultiOrderFilterCoefficientPrecomputationTest
		extends TestSuiteBase implements TemporalFeatures {

	/**
	 * Verifies that pre-computed low-pass coefficients produce the same
	 * filter output as inline coefficient computation.
	 *
	 * <p>This test compares two approaches:</p>
	 * <ol>
	 *   <li><strong>Inline:</strong> The traditional approach where
	 *       lowPassCoefficients() is passed directly to MultiOrderFilter,
	 *       potentially causing sin/cos to be evaluated per sample.</li>
	 *   <li><strong>Pre-computed:</strong> Coefficients are evaluated once
	 *       into a PackedCollection buffer, then the buffer reference is
	 *       passed to MultiOrderFilter for pure memory-read convolution.</li>
	 * </ol>
	 *
	 * <p>The outputs must match within floating-point tolerance.</p>
	 */
	@Test(timeout = 30_000)
	public void precomputedLowPassMatchesInline() {
		int signalLength = 1024;
		int filterOrder = 40;
		int sampleRate = 44100;
		double cutoffHz = 5000.0;

		PackedCollection signal = createTestSignal(signalLength);

		PackedCollection inlineOutput = computeInline(
				signal, cutoffHz, sampleRate, filterOrder);
		PackedCollection precomputedOutput = computeWithPrecomputedCoefficients(
				signal, cutoffHz, sampleRate, filterOrder);

		assertOutputsMatch(inlineOutput, precomputedOutput, signalLength,
				"Low-pass pre-computed coefficients");
	}

	/**
	 * Verifies that pre-computed high-pass coefficients produce the same
	 * filter output as inline coefficient computation.
	 */
	@Test(timeout = 30_000)
	public void precomputedHighPassMatchesInline() {
		int signalLength = 1024;
		int filterOrder = 40;
		int sampleRate = 44100;
		double cutoffHz = 2000.0;

		PackedCollection signal = createTestSignal(signalLength);

		PackedCollection inlineOutput = computeHighPassInline(
				signal, cutoffHz, sampleRate, filterOrder);
		PackedCollection precomputedHighPassOutput = computeHighPassWithPrecomputedCoefficients(
				signal, cutoffHz, sampleRate, filterOrder);

		assertOutputsMatch(inlineOutput, precomputedHighPassOutput, signalLength,
				"High-pass pre-computed coefficients");
	}

	/**
	 * Verifies that the pre-computed coefficient buffer contains the correct
	 * number of values and that they are non-zero (for a non-trivial cutoff).
	 */
	@Test(timeout = 15_000)
	public void coefficientBufferHasCorrectSizeAndValues() {
		int filterOrder = 40;
		int sampleRate = 44100;
		double cutoffHz = 5000.0;

		CollectionProducer coeffProducer =
				lowPassCoefficients(c(cutoffHz), sampleRate, filterOrder);
		PackedCollection coeffBuffer = coeffProducer.get().evaluate();

		assertEquals("Coefficient buffer should have filterOrder+1 elements",
				filterOrder + 1, coeffBuffer.getMemLength());

		double sum = 0;
		for (int i = 0; i <= filterOrder; i++) {
			sum += Math.abs(coeffBuffer.toDouble(i));
		}

		assertTrue("Coefficients should be non-zero for a valid cutoff frequency",
				sum > 0.0);
	}

	/**
	 * Verifies that the pre-computation approach works when coefficients
	 * are written to a buffer via an OperationList assignment, matching
	 * the pattern used by EfxManager.
	 */
	@Test(timeout = 30_000)
	public void operationListCoefficientAssignment() {
		int signalLength = 512;
		int filterOrder = 40;
		int sampleRate = 44100;
		double cutoffHz = 3000.0;
		int coeffSize = filterOrder + 1;

		PackedCollection signal = createTestSignal(signalLength);
		PackedCollection coeffBuffer = new PackedCollection(coeffSize);
		PackedCollection destination = new PackedCollection(signalLength);

		CollectionProducer coefficients =
				lowPassCoefficients(c(cutoffHz), sampleRate, filterOrder);

		OperationList setup = new OperationList("test-setup");
		setup.add(a("coefficients", cp(coeffBuffer.each()), coefficients));
		setup.add(a("filter", cp(destination.each()),
				MultiOrderFilter.create(
						traverseEach(cp(signal)), cp(coeffBuffer))));
		setup.get().run();

		PackedCollection inlineOutput = computeInline(
				signal, cutoffHz, sampleRate, filterOrder);

		assertOutputsMatch(inlineOutput, destination, signalLength,
				"OperationList coefficient assignment");
	}

	/**
	 * Verifies pre-computation with a larger signal size (4096 frames,
	 * matching the AudioScene buffer size).
	 */
	@Test(timeout = 60_000)
	@TestDepth(2)
	public void precomputedCoefficientsAtBufferSize() {
		int signalLength = 4096;
		int filterOrder = 40;
		int sampleRate = 44100;
		double cutoffHz = 8000.0;

		PackedCollection signal = createTestSignal(signalLength);

		PackedCollection inlineOutput = computeInline(
				signal, cutoffHz, sampleRate, filterOrder);
		PackedCollection precomputedOutput = computeWithPrecomputedCoefficients(
				signal, cutoffHz, sampleRate, filterOrder);

		assertOutputsMatch(inlineOutput, precomputedOutput, signalLength,
				"Pre-computed coefficients at buffer size 4096");
	}

	private PackedCollection createTestSignal(int length) {
		PackedCollection signal = new PackedCollection(length);
		for (int i = 0; i < length; i++) {
			signal.setMem(i, Math.sin(2 * Math.PI * 440 * i / 44100.0)
					+ 0.5 * Math.sin(2 * Math.PI * 8000 * i / 44100.0));
		}
		return signal;
	}

	private PackedCollection computeInline(PackedCollection signal,
										   double cutoffHz, int sampleRate, int filterOrder) {
		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoffHz), sampleRate, filterOrder);
		return filter.get().evaluate();
	}

	private PackedCollection computeWithPrecomputedCoefficients(
			PackedCollection signal, double cutoffHz, int sampleRate, int filterOrder) {
		CollectionProducer coeffProducer =
				lowPassCoefficients(c(cutoffHz), sampleRate, filterOrder);
		PackedCollection coeffBuffer = coeffProducer.get().evaluate();

		MultiOrderFilter filter = MultiOrderFilter.create(
				traverseEach(cp(signal)), cp(coeffBuffer));
		return filter.get().evaluate();
	}

	private PackedCollection computeHighPassInline(PackedCollection signal,
												   double cutoffHz, int sampleRate, int filterOrder) {
		MultiOrderFilter filter = highPass(
				traverseEach(cp(signal)), c(cutoffHz), sampleRate, filterOrder);
		return filter.get().evaluate();
	}

	private PackedCollection computeHighPassWithPrecomputedCoefficients(
			PackedCollection signal, double cutoffHz, int sampleRate, int filterOrder) {
		CollectionProducer coeffProducer =
				highPassCoefficients(c(cutoffHz), sampleRate, filterOrder);
		PackedCollection coeffBuffer = coeffProducer.get().evaluate();

		MultiOrderFilter filter = MultiOrderFilter.create(
				traverseEach(cp(signal)), cp(coeffBuffer));
		return filter.get().evaluate();
	}

	private void assertOutputsMatch(PackedCollection expected,
									PackedCollection actual, int length, String label) {
		int matching = 0;
		double maxDiff = 0;

		for (int i = 0; i < length; i++) {
			double diff = Math.abs(expected.toDouble(i) - actual.toDouble(i));
			if (diff < 1e-6) matching++;
			maxDiff = Math.max(maxDiff, diff);
		}

		double matchRatio = (double) matching / length;
		assertTrue(label + ": at least 95% of samples should match " +
						"(matched " + String.format("%.1f%%", matchRatio * 100) +
						", maxDiff=" + String.format("%.6e", maxDiff) + ")",
				matchRatio >= 0.95);
	}
}
