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

package org.almostrealism.ml.audio.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.ml.audio.DiffusionTransformerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;

/**
 * Tests for DiffusionTransformer architecture and components.
 */
public class DiffusionTransformerTests extends TestSuiteBase implements DiffusionTransformerFeatures {

	/**
	 * Tests that the transformerBlock can be built and compiled with proper shapes.
	 */
	@Test(timeout = 120000)
	public void transformerBlockShapes() {
		int batchSize = 1;
		int seqLen = 8;
		int dim = 32;
		int heads = 2;
		int dimHead = dim / heads;
		int hiddenDim = dim * 4;

		log("Testing transformerBlock with shapes:");
		log("  batchSize=" + batchSize + ", seqLen=" + seqLen + ", dim=" + dim + ", heads=" + heads);

		// Create minimal weights for a single transformer block
		PackedCollection preNormWeight = new PackedCollection(shape(dim)).fill(1.0);
		PackedCollection preNormBias = new PackedCollection(shape(dim));
		PackedCollection qkv = new PackedCollection(shape(dim * 3, dim));
		PackedCollection wo = new PackedCollection(shape(dim, dim));
		PackedCollection qNormWeight = new PackedCollection(shape(dimHead)).fill(1.0);
		PackedCollection qNormBias = new PackedCollection(shape(dimHead));
		PackedCollection kNormWeight = new PackedCollection(shape(dimHead)).fill(1.0);
		PackedCollection kNormBias = new PackedCollection(shape(dimHead));
		PackedCollection invFreq = new PackedCollection(shape(dimHead / 4));

		PackedCollection ffnNormWeight = new PackedCollection(shape(dim)).fill(1.0);
		PackedCollection ffnNormBias = new PackedCollection(shape(dim));
		PackedCollection w1 = new PackedCollection(shape(2 * hiddenDim, dim));
		PackedCollection ffW1Bias = new PackedCollection(shape(2 * hiddenDim));
		PackedCollection w2 = new PackedCollection(shape(dim, hiddenDim));
		PackedCollection ffW2Bias = new PackedCollection(shape(dim));

		log("Building transformerBlock...");
		Block block = transformerBlock(
				batchSize, dim, seqLen, heads,
				false,  // No cross-attention
				0, null,  // No context
				preNormWeight, preNormBias,
				qkv, wo,
				qNormWeight, qNormBias,
				kNormWeight, kNormBias,
				invFreq,
				null, null,  // No cross-attention weights
				null, null, null,
				null, null,
				null, null,
				ffnNormWeight, ffnNormBias,
				w1, w2, ffW1Bias, ffW2Bias
		);

		log("TransformerBlock built successfully");
		log("Block input shape: " + block.getInputShape());
		log("Block output shape: " + block.getOutputShape());

		// Create a model with this block and compile it
		Model model = new Model(shape(batchSize, seqLen, dim));
		model.sequential().add(block);

		log("Compiling model...");
		CompiledModel compiled = model.compile(false);

		log("Model compiled successfully");

		// Run forward pass
		PackedCollection input = new PackedCollection(shape(batchSize, seqLen, dim));
		input.fill(pos -> Math.random());

		log("Running forward pass...");
		PackedCollection output = compiled.forward(input);

		log("Forward pass completed");
		log("Input shape: " + input.getShape());
		log("Output shape: " + output.getShape());

		assertEquals("Output should have same size as input",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());

		model.destroy();
		log("Test completed successfully");
	}

	/**
	 * Tests that DiffusionTransformer can build and compile with random weights.
	 * This verifies that shape validation passes for all layers without needing
	 * external reference data.
	 */
	@Test(timeout = 120000)
	public void forwardPassWithRandomWeights() {
		// Use minimal sizes for fast testing while still exercising the full architecture
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;  // Single transformer block
		int numHeads = 2;
		int patchSize = 1;
		int condTokenDim = 0;  // Disable cross-attention for simplicity
		int globalCondDim = 0; // Disable global conditioning for simplicity
		int audioSeqLen = 8;
		int condSeqLen = 4;    // Not used when condTokenDim = 0

		log("Creating DiffusionTransformer with random weights:");
		log("  ioChannels=" + ioChannels + ", embedDim=" + embedDim + ", depth=" + depth);
		log("  numHeads=" + numHeads + ", patchSize=" + patchSize + ", audioSeqLen=" + audioSeqLen);

		// Create transformer with null StateDictionary (creates empty weights)
		DiffusionTransformer transformer = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen,
				null,  // null StateDictionary creates empty weights
				false);

		log("DiffusionTransformer model built successfully");

		// Create test inputs with appropriate shapes
		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen));
		input.fill(pos -> Math.random());

		PackedCollection timestep = new PackedCollection(shape(batchSize, 1));
		timestep.fill(pos -> Math.random());

		log("Running forward pass...");
		PackedCollection output = transformer.forward(input, timestep, null, null);

		log("Forward pass completed successfully");
		log("Input shape: " + input.getShape());
		log("Output shape: " + output.getShape());

		// Verify output shape matches input shape
		assertEquals("Output should have same shape as input",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());

		// Cleanup
		transformer.destroy();
		log("Test completed successfully");
	}

	/**
	 * Tests DiffusionTransformer with cross-attention conditioning.
	 */
	@Test(timeout = 120000)
	public void forwardPassWithCrossAttention() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;
		int numHeads = 2;
		int patchSize = 1;
		int condTokenDim = 16;  // Enable cross-attention
		int globalCondDim = 0;
		int audioSeqLen = 8;
		int condSeqLen = 4;

		log("Creating DiffusionTransformer with cross-attention:");
		log("  condTokenDim=" + condTokenDim + ", condSeqLen=" + condSeqLen);

		DiffusionTransformer transformer = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen,
				null, false);

		log("DiffusionTransformer model built successfully");

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen));
		input.fill(pos -> Math.random());

		PackedCollection timestep = new PackedCollection(shape(batchSize, 1));
		timestep.fill(pos -> Math.random());

		// Cross-attention conditioning expects [condSeqLen, condTokenDim] shape
		PackedCollection crossAttnCond = new PackedCollection(shape(batchSize, condSeqLen, condTokenDim));
		crossAttnCond.fill(pos -> Math.random());

		log("Running forward pass with cross-attention...");
		PackedCollection output = transformer.forward(input, timestep, crossAttnCond, null);

		log("Forward pass completed successfully");
		log("Output shape: " + output.getShape());

		assertEquals("Output should have same shape as input",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());

		transformer.destroy();
		log("Test completed successfully");
	}

	/**
	 * Tests DiffusionTransformer with global conditioning.
	 */
	@Test(timeout = 120000)
	public void forwardPassWithGlobalConditioning() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;
		int numHeads = 2;
		int patchSize = 1;
		int condTokenDim = 0;
		int globalCondDim = 16;  // Enable global conditioning
		int audioSeqLen = 8;
		int condSeqLen = 4;

		log("Creating DiffusionTransformer with global conditioning:");
		log("  globalCondDim=" + globalCondDim);

		DiffusionTransformer transformer = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen,
				null, false);

		log("DiffusionTransformer model built successfully");

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen));
		input.fill(pos -> Math.random());

		PackedCollection timestep = new PackedCollection(shape(batchSize, 1));
		timestep.fill(pos -> Math.random());

		// Global conditioning expects [globalCondDim] shape
		PackedCollection globalCond = new PackedCollection(shape(batchSize, globalCondDim));
		globalCond.fill(pos -> Math.random());

		log("Running forward pass with global conditioning...");
		PackedCollection output = transformer.forward(input, timestep, null, globalCond);

		log("Forward pass completed successfully");
		log("Output shape: " + output.getShape());

		assertEquals("Output should have same shape as input",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());

		transformer.destroy();
		log("Test completed successfully");
	}

	/**
	 * Tests DiffusionTransformer with BOTH cross-attention and global conditioning
	 * enabled simultaneously. This is the configuration used in production
	 * (see ConditionalAudioSystem: condTokenDim and globalCondDim both > 0), and
	 * is the only path that exercises the {@code hasCrossAttn && globalCondDim > 0}
	 * branch of {@link DiffusionTransformer#forward}.
	 */
	@Test(timeout = 120000)
	public void forwardPassWithCrossAttentionAndGlobalConditioning() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;
		int numHeads = 2;
		int patchSize = 1;
		int condTokenDim = 16;  // Enable cross-attention
		int globalCondDim = 16; // Enable global conditioning
		int audioSeqLen = 8;
		int condSeqLen = 4;

		log("Creating DiffusionTransformer with cross-attention AND global conditioning");

		DiffusionTransformer transformer = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen,
				null, false);

		log("DiffusionTransformer model built successfully");

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen));
		input.fill(pos -> Math.random());

		PackedCollection timestep = new PackedCollection(shape(batchSize, 1));
		timestep.fill(pos -> Math.random());

		PackedCollection crossAttnCond = new PackedCollection(shape(batchSize, condSeqLen, condTokenDim));
		crossAttnCond.fill(pos -> Math.random());

		PackedCollection globalCond = new PackedCollection(shape(batchSize, globalCondDim));
		globalCond.fill(pos -> Math.random());

		log("Running forward pass with cross-attention and global conditioning...");
		PackedCollection output = transformer.forward(input, timestep, crossAttnCond, globalCond);

		log("Forward pass completed successfully");
		log("Output shape: " + output.getShape());

		assertEquals("Output should have same shape as input",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());

		transformer.destroy();
		log("Test completed successfully");
	}

	/**
	 * Smoke test of the full production DiffusionTransformer using the real
	 * downloaded DiT weights.
	 *
	 * <p>Loads the {@code stable-audio-dit} StateDictionary from a local directory
	 * (set via the {@code ditWeightsDir} system property, default
	 * {@code ~/dit-weights}) and runs a single forward pass with the production
	 * configuration from {@code ConditionalAudioSystem}. The test is skipped when
	 * the weights directory is not present.</p>
	 */
	@Test(timeout = 600000)
	public void forwardWithRealWeights() throws Exception {
		String dir = System.getProperty("ditWeightsDir",
				System.getProperty("user.home") + "/dit-weights");
		File weightsDir = new File(dir);
		if (!weightsDir.isDirectory() || weightsDir.listFiles() == null
				|| weightsDir.listFiles().length < 17) {
			log("Skipping forwardWithRealWeights: weights directory not present at " + dir);
			return;
		}

		StateDictionary stateDictionary = new StateDictionary(dir);
		log("Loaded StateDictionary with " + stateDictionary.keySet().size() + " tensors");

		// Production configuration (see ConditionalAudioSystem): 10-arg constructor
		// defaults audioSeqLen=256, condSeqLen=65.
		DiffusionTransformer transformer = new DiffusionTransformer(
				64, 1024, 16, 8, 1, 768, 768, "rf_denoiser",
				stateDictionary, false);

		int batchSize = DiffusionTransformer.batchSize;
		int audioSeqLen = 256;
		int condSeqLen = 65;

		// Every model input is supplied in the model's declared (batched) shape, which
		// matches the real pipeline once the latent, timestep, and conditioning tensors
		// are produced consistently:
		//   latent   (batch, ioChannels, audioSeqLen)
		//   timestep (batch, 1)
		//   cross    (batch, condSeqLen, condTokenDim)   <- real ONNX conditioner rank
		//   global   (batch, globalCondDim)              <- real ONNX conditioner rank
		PackedCollection input = new PackedCollection(shape(batchSize, 64, audioSeqLen));
		input.fill(pos -> Math.random());

		PackedCollection timestep = new PackedCollection(shape(batchSize, 1));
		timestep.fill(pos -> Math.random());

		PackedCollection crossAttnCond = new PackedCollection(shape(batchSize, condSeqLen, 768));
		crossAttnCond.fill(pos -> Math.random());

		PackedCollection globalCond = new PackedCollection(shape(batchSize, 768));
		globalCond.fill(pos -> Math.random());

		log("Running forward pass with real DiT weights...");
		PackedCollection output = transformer.forward(input, timestep, crossAttnCond, globalCond);

		log("Forward pass completed, output shape: " + output.getShape());
		assertEquals("Output should have same size as input",
				input.getShape().getTotalSize(), output.getShape().getTotalSize());

		transformer.destroy();
	}

	/**
	 * Verifies that supplying an input whose shape (here, number of dimensions) does
	 * not match the model's declared input shape fails fast with a clear, descriptive
	 * error rather than a cryptic failure deep in the computation graph. This is the
	 * exact mistake that produced the production "Axis 3 is greater than the number of
	 * dimensions (2)" crash (a 2-D latent supplied where a 3-D input was expected).
	 */
	@Test(timeout = 120000)
	public void rejectsWrongShapedInput() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 1;
		int numHeads = 2;
		int patchSize = 1;
		int audioSeqLen = 8;
		int condSeqLen = 4;

		DiffusionTransformer transformer = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				0, 0, "rf_denoiser", audioSeqLen, condSeqLen, null, false);

		int batchSize = DiffusionTransformer.batchSize;

		// Model declares its primary input as (batchSize, ioChannels, audioSeqLen);
		// supply a rank-2 tensor of the same total size to exercise the validation.
		PackedCollection wrongShaped = new PackedCollection(shape(ioChannels, audioSeqLen));
		wrongShaped.fill(pos -> Math.random());

		PackedCollection timestep = new PackedCollection(shape(batchSize, 1));
		timestep.fill(pos -> Math.random());

		try {
			transformer.forward(wrongShaped, timestep, null, null);
			throw new AssertionError("Expected an IllegalArgumentException for a wrong-shaped input");
		} catch (IllegalArgumentException e) {
			log("Got expected validation error: " + e.getMessage());
			String message = e.getMessage();
			if (message == null || !message.contains("expected") || !message.contains("received")) {
				throw new AssertionError("Validation error should report the expected and " +
						"received shapes, but was: " + message);
			}
		} finally {
			transformer.destroy();
		}
	}

	/**
	 * Tests fourierFeatures with simple known values to verify basic mathematical operations.
	 * This is a sanity check to ensure the 2*pi factor, matrix multiplication, and
	 * concatenation order are working correctly.
	 */
	@Test(timeout = 120000)
	public void fourierFeaturesBasic() {
		int batchSize = 1;
		int inFeatures = 1;
		int outFeatures = 4;  // Simple case

		// Create simple test inputs
		PackedCollection input = new PackedCollection(shape(batchSize, inFeatures));
		input.setValueAt(0.5, 0, 0);  // Simple input value

		// Create simple weight matrix [outFeatures/2, inFeatures] = [2, 1]
		PackedCollection weight = new PackedCollection(shape(outFeatures / 2, inFeatures));
		weight.setValueAt(1.0, 0, 0);  // First frequency
		weight.setValueAt(2.0, 1, 0);  // Second frequency

		// Create test model
		Model model = new Model(shape(batchSize, inFeatures));
		SequentialBlock main = model.sequential();
		main.add(fourierFeatures(batchSize, inFeatures, outFeatures, weight));

		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);

		// Manual calculation for verification
		// f = 2 * PI * input @ weight.T
		// f[0] = 2 * PI * 0.5 * 1.0 = PI
		// f[1] = 2 * PI * 0.5 * 2.0 = 2*PI
		// output = [cos(PI), cos(2*PI), sin(PI), sin(2*PI)] = [-1, 1, 0, 0] (approximately)

		double expectedCos1 = Math.cos(Math.PI);        // ~= -1
		double expectedCos2 = Math.cos(2 * Math.PI);    // ~= 1
		double expectedSin1 = Math.sin(Math.PI);        // ~= 0
		double expectedSin2 = Math.sin(2 * Math.PI);    // ~= 0

		log("FourierFeatures basic test:");
		log("Input: " + input.valueAt(0, 0));
		log("Weight[0]: " + weight.valueAt(0, 0) + ", Weight[1]: " + weight.valueAt(1, 0));
		log("Expected: [" + expectedCos1 + ", " + expectedCos2 + ", " + expectedSin1 + ", " + expectedSin2 + "]");
		log("Actual: [" + output.valueAt(0, 0) + ", " + output.valueAt(0, 1) +
				", " + output.valueAt(0, 2) + ", " + output.valueAt(0, 3) + "]");

		assertEquals("First cos value", expectedCos1, output.valueAt(0, 0));
		assertEquals("Second cos value", expectedCos2, output.valueAt(0, 1));
		assertEquals("First sin value", expectedSin1, output.valueAt(0, 2));
		assertEquals("Second sin value", expectedSin2, output.valueAt(0, 3));
	}
}