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

package org.almostrealism.ml.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests for Rotary Position Embedding (RoPE) gradient performance.
 *
 * <p>These tests verify gradient computation efficiency for the RoPE formula:
 * {@code output = input * cos(freqs) + rotate_half(input) * sin(freqs)}
 *
 * <p>The operations are implemented directly to avoid ml module dependency,
 * but the pattern matches exactly what RotationFeatures.applyRotaryTransform does.
 *
 * <p>This is a common bottleneck in transformer training because:
 * <ul>
 *   <li>RoPE involves sin/cos computations</li>
 *   <li>The rotateHalf operation uses subset and concat</li>
 *   <li>Multiple multiply operations combine these</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class RotaryEmbeddingGradientTests extends TestSuiteBase {

	// =========================================================================
	// Helper Methods - Replicate RoPE operations without ml dependency
	// =========================================================================

	/**
	 * Rotates the input tensor by swapping and negating halves.
	 * For input [..., d], returns [..., -x2, x1] where:
	 * - x1 is the first half: input[..., 0:d/2]
	 * - x2 is the second half: input[..., d/2:d]
	 */
	private CollectionProducer rotateHalf(CollectionProducer input,
										  int batchSize, int heads, int seqLen, int rotaryDim) {
		int halfDim = rotaryDim / 2;

		// Extract first half (x1)
		CollectionProducer x1 = input.subset(shape(batchSize, heads, seqLen, halfDim), 0, 0, 0, 0);

		// Extract second half (x2)
		CollectionProducer x2 = input.subset(shape(batchSize, heads, seqLen, halfDim), 0, 0, 0, halfDim);

		// Return concatenation of [-x2, x1] along dimension 3
		return concat(3, x2.minus(), x1);
	}

	/**
	 * Applies the rotary transform to an input tensor.
	 * output = input * cos(freqs) + rotate_half(input) * sin(freqs)
	 */
	private CollectionProducer applyRotaryTransform(CollectionProducer input,
													CollectionProducer freqs,
													int batchSize, int heads, int seqLen, int rotaryDim) {
		// Expand freqs from (seqLen, rotaryDim) to (batchSize, heads, seqLen, rotaryDim)
		CollectionProducer expandedFreqs = freqs
				.repeat(0, batchSize)    // (batchSize, seqLen, rotaryDim)
				.repeat(1, heads);       // (batchSize, heads, seqLen, rotaryDim)

		CollectionProducer cosFreqs = cos(expandedFreqs);
		CollectionProducer sinFreqs = sin(expandedFreqs);

		CollectionProducer rotateHalfInput = rotateHalf(input, batchSize, heads, seqLen, rotaryDim);

		// input * cos(freqs) + rotate_half(input) * sin(freqs)
		return input.multiply(cosFreqs).add(rotateHalfInput.multiply(sinFreqs));
	}

	// =========================================================================
	// Core RoPE Gradient Tests
	// =========================================================================

	/**
	 * Tests the core RoPE formula gradient at minimal scale.
	 * Formula: input * cos(freqs) + rotate_half(input) * sin(freqs)
	 */
	@Test(timeout = 120000)
	public void ropeFormulaGradientTiny() throws IOException {
		ropeFormulaGradient("ropeTiny", 1, 1, 4, 8);
	}

	/**
	 * Tests the core RoPE formula gradient at small scale.
	 */
	@Test(timeout = 120000)
	public void ropeFormulaGradientSmall() throws IOException {
		ropeFormulaGradient("ropeSmall", 1, 2, 8, 16);
	}

	/**
	 * Tests the core RoPE formula gradient at medium scale.
	 * This is closer to typical model dimensions.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void ropeFormulaGradientMedium() throws IOException {
		ropeFormulaGradient("ropeMedium", 1, 4, 16, 32);
	}

	/**
	 * Tests the core RoPE formula gradient at larger scale.
	 * This matches realistic transformer attention head sizes.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void ropeFormulaGradientLarge() throws IOException {
		ropeFormulaGradient("ropeLarge", 1, 8, 32, 64);
	}

	/**
	 * Tests RoPE gradient with realistic DiffusionTransformer dimensions.
	 * batch=1, heads=6, seqLen=24, rotaryDim=64
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void ropeFormulaGradientRealistic() throws IOException {
		ropeFormulaGradient("ropeRealistic", 1, 6, 24, 64);
	}

	private void ropeFormulaGradient(String name, int batchSize, int heads, int seqLen, int rotaryDim)
			throws IOException {
		log("Testing RoPE gradient: batch=" + batchSize + ", heads=" + heads +
				", seqLen=" + seqLen + ", rotaryDim=" + rotaryDim);

		// Create input tensor (batchSize, heads, seqLen, rotaryDim)
		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim)).randnFill();

		// Create frequency tensor (seqLen, rotaryDim)
		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim)).randnFill();

		CollectionProducer x = cp(input);
		CollectionProducer f = cp(freqs);

		OperationProfileNode profile = kernelTest(name, () -> {
			// Apply the RoPE formula
			CollectionProducer result = applyRotaryTransform(x, f, batchSize, heads, seqLen, rotaryDim);
			// Compute gradient with respect to input
			return result.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());

			// The gradient should have shape (output_size, input_size)
			int outputSize = batchSize * heads * seqLen * rotaryDim;
			int inputSize = batchSize * heads * seqLen * rotaryDim;
			log("Expected: (" + outputSize + ", " + inputSize + ")");
			assertEquals(outputSize * inputSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	// =========================================================================
	// Isolated Component Tests
	// =========================================================================

	/**
	 * Tests the rotateHalf operation gradient in isolation.
	 * rotateHalf(input) = concat(3, -x2, x1) where x1=first half, x2=second half
	 */
	@Test(timeout = 120000)
	public void rotateHalfGradientTiny() throws IOException {
		rotateHalfGradient("rotateHalfTiny", 1, 1, 4, 8);
	}

	/**
	 * Tests the rotateHalf operation gradient at medium scale.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void rotateHalfGradientMedium() throws IOException {
		rotateHalfGradient("rotateHalfMedium", 1, 4, 16, 32);
	}

	private void rotateHalfGradient(String name, int batchSize, int heads, int seqLen, int rotaryDim)
			throws IOException {
		log("Testing rotateHalf gradient: batch=" + batchSize + ", heads=" + heads +
				", seqLen=" + seqLen + ", rotaryDim=" + rotaryDim);

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim)).randnFill();
		CollectionProducer x = cp(input);

		OperationProfileNode profile = kernelTest(name, () -> {
			// rotateHalf operation
			CollectionProducer result = rotateHalf(x, batchSize, heads, seqLen, rotaryDim);
			return result.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			int totalSize = batchSize * heads * seqLen * rotaryDim;
			assertEquals(totalSize * totalSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	/**
	 * Tests input*cos(freqs) gradient pattern in isolation.
	 */
	@Test(timeout = 120000)
	public void inputTimesCosGradientTiny() throws IOException {
		inputTimesCosGradient("inputCosGradTiny", 1, 1, 4, 8);
	}

	@Test(timeout = 300000)
	@TestDepth(1)
	public void inputTimesCosGradientMedium() throws IOException {
		inputTimesCosGradient("inputCosGradMedium", 1, 4, 16, 32);
	}

	private void inputTimesCosGradient(String name, int batchSize, int heads, int seqLen, int rotaryDim)
			throws IOException {
		log("Testing input*cos(freqs) gradient: batch=" + batchSize + ", heads=" + heads +
				", seqLen=" + seqLen + ", rotaryDim=" + rotaryDim);

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim)).randnFill();
		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim)).randnFill();

		CollectionProducer x = cp(input);
		CollectionProducer f = cp(freqs);

		OperationProfileNode profile = kernelTest(name, () -> {
			// Expand freqs to match input shape
			CollectionProducer expandedFreqs = f
					.repeat(0, batchSize)
					.repeat(1, heads);

			// input * cos(freqs)
			CollectionProducer result = x.multiply(cos(expandedFreqs));
			return result.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			int totalSize = batchSize * heads * seqLen * rotaryDim;
			assertEquals(totalSize * totalSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	/**
	 * Tests rotateHalf(input)*sin(freqs) gradient pattern in isolation.
	 */
	@Test(timeout = 120000)
	public void rotateHalfTimesSinGradientTiny() throws IOException {
		rotateHalfTimesSinGradient("rotateHalfSinGradTiny", 1, 1, 4, 8);
	}

	@Test(timeout = 300000)
	@TestDepth(1)
	public void rotateHalfTimesSinGradientMedium() throws IOException {
		rotateHalfTimesSinGradient("rotateHalfSinGradMedium", 1, 4, 16, 32);
	}

	private void rotateHalfTimesSinGradient(String name, int batchSize, int heads, int seqLen, int rotaryDim)
			throws IOException {
		log("Testing rotateHalf(input)*sin(freqs) gradient: batch=" + batchSize + ", heads=" + heads +
				", seqLen=" + seqLen + ", rotaryDim=" + rotaryDim);

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim)).randnFill();
		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim)).randnFill();

		CollectionProducer x = cp(input);
		CollectionProducer f = cp(freqs);

		OperationProfileNode profile = kernelTest(name, () -> {
			// Expand freqs to match input shape
			CollectionProducer expandedFreqs = f
					.repeat(0, batchSize)
					.repeat(1, heads);

			// rotateHalf(input) * sin(freqs)
			CollectionProducer rotated = rotateHalf(x, batchSize, heads, seqLen, rotaryDim);
			CollectionProducer result = rotated.multiply(sin(expandedFreqs));
			return result.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			int totalSize = batchSize * heads * seqLen * rotaryDim;
			assertEquals(totalSize * totalSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	// =========================================================================
	// Subset/Concat Gradient Tests
	// =========================================================================

	/**
	 * Tests subset operation gradient in isolation.
	 */
	@Test(timeout = 120000)
	public void subsetGradientTiny() throws IOException {
		subsetGradient("subsetGradTiny", 1, 2, 4, 16);
	}

	@Test(timeout = 300000)
	@TestDepth(1)
	public void subsetGradientMedium() throws IOException {
		subsetGradient("subsetGradMedium", 1, 4, 16, 64);
	}

	private void subsetGradient(String name, int batchSize, int heads, int seqLen, int dim)
			throws IOException {
		int halfDim = dim / 2;
		log("Testing subset gradient: shape (" + batchSize + "," + heads + "," + seqLen + "," + dim +
				") -> subset half = " + halfDim);

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, dim)).randnFill();
		CollectionProducer x = cp(input);

		OperationProfileNode profile = kernelTest(name, () -> {
			// Extract first half - similar to what rotateHalf does
			CollectionProducer subset = x.subset(shape(batchSize, heads, seqLen, halfDim), 0, 0, 0, 0);
			return subset.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			int outputSize = batchSize * heads * seqLen * halfDim;
			int inputSize = batchSize * heads * seqLen * dim;
			assertEquals(outputSize * inputSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}

	/**
	 * Tests concat operation gradient in isolation.
	 */
	@Test(timeout = 120000)
	public void concatGradientTiny() throws IOException {
		concatGradient("concatGradTiny", 1, 2, 4, 8);
	}

	@Test(timeout = 300000)
	@TestDepth(1)
	public void concatGradientMedium() throws IOException {
		concatGradient("concatGradMedium", 1, 4, 16, 16);
	}

	private void concatGradient(String name, int batchSize, int heads, int seqLen, int halfDim)
			throws IOException {
		int fullDim = halfDim * 2;
		log("Testing concat gradient: 2 x (" + batchSize + "," + heads + "," + seqLen + "," + halfDim +
				") -> (" + batchSize + "," + heads + "," + seqLen + "," + fullDim + ")");

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, fullDim)).randnFill();
		CollectionProducer x = cp(input);

		OperationProfileNode profile = kernelTest(name, () -> {
			// Split and concat - similar to rotateHalf pattern
			CollectionProducer x1 = x.subset(shape(batchSize, heads, seqLen, halfDim), 0, 0, 0, 0);
			CollectionProducer x2 = x.subset(shape(batchSize, heads, seqLen, halfDim), 0, 0, 0, halfDim);

			// concat(3, x2, x1) - the swapped version
			CollectionProducer result = concat(3, x2, x1);
			return result.delta(x);
		}, output -> {
			log("Output shape: " + output.getShape());
			int totalSize = batchSize * heads * seqLen * fullDim;
			assertEquals(totalSize * totalSize, output.getMemLength());
		}, true, true, true);

		profile.save("results/" + name + ".xml");
		log("Profile saved to results/" + name + ".xml");
	}
}
