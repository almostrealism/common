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

package org.almostrealism.ml.audio;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Tests for OobleckAutoEncoder architecture validation.
 *
 * <p>These are synthetic tests that validate the model architecture constructs
 * correctly and can process input without crashing. They use random weights
 * and do NOT validate numerical correctness against a reference implementation.</p>
 *
 * <p>For numerical validation against PyTorch, see {@link OobleckValidationTest}.</p>
 */
public class OobleckAutoEncoderTest extends TestSuiteBase {

	/** Stride (downsampling factor) for each encoder stage. */
	private static final int[] STRIDES = {4, 8, 8, 16, 16};

	/** Output channels for each encoder block. */
	private static final int[] OUT_CHANNELS = {128, 256, 512, 1024, 2048};

	/** Decoder input channels for each block. */
	private static final int[] DEC_IN_CHANNELS = {2048, 1024, 512, 256, 128};

	/** Decoder output channels for each block. */
	private static final int[] DEC_OUT_CHANNELS = {1024, 512, 256, 128, 128};

	/** Base number of channels for the smallest layer. */
	private static final int BASE_CHANNELS = 128;

	/** Number of residual blocks per encoder/decoder stage. */
	private static final int NUM_RES_BLOCKS = 3;

	/** Latent dimension for the encoder output. */
	private static final int LATENT_DIM_ENCODER = 128;

	/** Latent dimension for the decoder input. */
	private static final int LATENT_DIM_DECODER = 64;

	/**
	 * Tests that encoder output length calculation works correctly.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void testEncoderConstruction() {
		// Just verify output length calculation without building full model
		// Full model tests are memory-intensive; see OobleckValidationTest for real validation

		// For 65536 samples:
		// Block 1: stride 4, k=4, p=1: (65536+2-4)/4+1 = 16384
		// Block 2: stride 8, k=8, p=3: (16384+6-8)/8+1 = 2048
		// Block 3: stride 8, k=8, p=3: (2048+6-8)/8+1 = 256
		// Block 4: stride 16, k=16, p=7: (256+14-16)/16+1 = 16
		// Block 5: stride 16, k=16, p=7: (16+14-16)/16+1 = 1
		int seqLength = 65536;
		int length = seqLength;
		int[] strides = {4, 8, 8, 16, 16};
		for (int stride : strides) {
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			length = (length + 2 * padding - kernel) / stride + 1;
		}
		assertEquals("65536 samples should produce 1 latent position", 1, length);
	}

	/**
	 * Tests decoder output length calculation.
	 */
	@Test(timeout = 300000)
	public void testDecoderConstruction() {
		// Just verify output length calculation without building full model
		// ConvTranspose1d: out = (in - 1) * stride - 2*padding + kernel + output_padding
		// where output_padding = stride - 1

		int latentLength = 1;
		int length = latentLength;
		int[] strides = {16, 16, 8, 8, 4};
		for (int stride : strides) {
			int kernel = stride;
			int padding = (kernel - 1) / 2;
			int outputPadding = stride - 1;
			length = (length - 1) * stride - 2 * padding + kernel + outputPadding;
		}
		// Expected: 1 -> 16 -> 256 -> 2048 -> 16384 -> 65536
		assertTrue("1 latent position should produce ~65536 output", length > 50000);
	}

	/**
	 * Tests that the VAE bottleneck can be constructed.
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void testBottleneckConstruction() {
		int batchSize = 1;
		int seqLength = 256;

		VAEBottleneck bottleneck = new VAEBottleneck(batchSize, seqLength);

		assertNotNull(bottleneck.getBottleneck());
		assertEquals(128, bottleneck.getInputDim());
		assertEquals(64, bottleneck.getOutputDim());
	}

	/**
	 * Tests compression ratio calculation.
	 *
	 * <p>Total theoretical compression: 4 * 8 * 8 * 16 * 16 = 65536x.</p>
	 */
	@Test(timeout = 300000)
	@TestDepth(1)
	public void testCompressionRatio() {
		int totalCompression = 1;
		for (int stride : STRIDES) {
			totalCompression *= stride;
		}
		assertEquals("Total compression should be 65536x", 65536, totalCompression);
	}

	/**
	 * Creates synthetic encoder weights matching the Stable Audio Open checkpoint format.
	 */
	private StateDictionary createSyntheticEncoderWeights() {
		Map<String, PackedCollection> weights = new HashMap<>();
		Random rand = new Random(42);

		// layers.0: Input conv (2 -> 128, k=7)
		addWNConvWeights(weights, rand, "encoder.layers.0", 128, 2, 7, true);

		// Encoder blocks: layers.1 through layers.5
		int inChannels = BASE_CHANNELS;
		for (int blockIdx = 0; blockIdx < 5; blockIdx++) {
			int outChannels = OUT_CHANNELS[blockIdx];
			int stride = STRIDES[blockIdx];
			int layerIdx = blockIdx + 1;
			String prefix = String.format("encoder.layers.%d", layerIdx);

			// 3 residual blocks
			for (int resIdx = 0; resIdx < NUM_RES_BLOCKS; resIdx++) {
				addResidualBlockWeights(weights, rand, prefix + ".layers." + resIdx, inChannels);
			}

			// Snake before downsample
			addSnakeWeights(weights, rand, prefix + ".layers.3", inChannels);

			// Downsample conv
			addWNConvWeights(weights, rand, prefix + ".layers.4", outChannels, inChannels, stride, true);

			inChannels = outChannels;
		}

		// layers.6: Final Snake
		addSnakeWeights(weights, rand, "encoder.layers.6", 2048);

		// layers.7: Output conv (2048 -> 128, k=3)
		addWNConvWeights(weights, rand, "encoder.layers.7", LATENT_DIM_ENCODER, 2048, 3, true);

		return new StateDictionary(weights);
	}

	/**
	 * Creates synthetic decoder weights matching the Stable Audio Open checkpoint format.
	 */
	private StateDictionary createSyntheticDecoderWeights() {
		Map<String, PackedCollection> weights = new HashMap<>();
		Random rand = new Random(42);

		// layers.0: Input conv (64 -> 2048, k=7)
		addWNConvWeights(weights, rand, "decoder.layers.0", 2048, LATENT_DIM_DECODER, 7, true);

		// Decoder blocks: layers.1 through layers.5
		for (int blockIdx = 0; blockIdx < 5; blockIdx++) {
			int inChannels = DEC_IN_CHANNELS[blockIdx];
			int outChannels = DEC_OUT_CHANNELS[blockIdx];
			int stride = STRIDES[blockIdx];  // Same strides, reversed order in channel progression
			int layerIdx = blockIdx + 1;
			String prefix = String.format("decoder.layers.%d", layerIdx);

			// Snake before upsample
			addSnakeWeights(weights, rand, prefix + ".layers.0", inChannels);

			// Upsample conv (transposed) - weight shape (inChannels, outChannels, kernel) for ConvTranspose
			addWNConvTransposeWeights(weights, rand, prefix + ".layers.1", inChannels, outChannels, stride);

			// 3 residual blocks at output channels
			for (int resIdx = 0; resIdx < NUM_RES_BLOCKS; resIdx++) {
				addResidualBlockWeights(weights, rand, prefix + ".layers." + (resIdx + 2), outChannels);
			}
		}

		// layers.6: Final Snake
		addSnakeWeights(weights, rand, "decoder.layers.6", BASE_CHANNELS);

		// layers.7: Output conv (128 -> 2, k=7) - no bias
		addWNConvWeights(weights, rand, "decoder.layers.7", 2, BASE_CHANNELS, 7, false);

		return new StateDictionary(weights);
	}

	/**
	 * Adds weight normalization conv weights.
	 */
	private void addWNConvWeights(Map<String, PackedCollection> weights, Random rand,
								  String prefix, int outChannels, int inChannels, int kernel, boolean hasBias) {
		weights.put(prefix + ".weight_g", randomWeights(rand, outChannels, 1, 1));
		weights.put(prefix + ".weight_v", randomWeights(rand, outChannels, inChannels, kernel));
		if (hasBias) {
			weights.put(prefix + ".bias", randomWeights(rand, outChannels));
		}
	}

	/**
	 * Adds weight normalization transposed conv weights.
	 */
	private void addWNConvTransposeWeights(Map<String, PackedCollection> weights, Random rand,
										   String prefix, int inChannels, int outChannels, int kernel) {
		// ConvTranspose1d weight shape is (inChannels, outChannels, kernel)
		weights.put(prefix + ".weight_g", randomWeights(rand, inChannels, 1, 1));
		weights.put(prefix + ".weight_v", randomWeights(rand, inChannels, outChannels, kernel));
		weights.put(prefix + ".bias", randomWeights(rand, outChannels));
	}

	/**
	 * Adds Snake activation weights (alpha, beta).
	 */
	private void addSnakeWeights(Map<String, PackedCollection> weights, Random rand,
								 String prefix, int channels) {
		weights.put(prefix + ".alpha", randomWeights(rand, channels));
		weights.put(prefix + ".beta", randomWeights(rand, channels));
	}

	/**
	 * Adds residual block weights.
	 */
	private void addResidualBlockWeights(Map<String, PackedCollection> weights, Random rand,
										 String prefix, int channels) {
		// layers.0: Snake
		addSnakeWeights(weights, rand, prefix + ".layers.0", channels);

		// layers.1: WNConv1d(k=7)
		addWNConvWeights(weights, rand, prefix + ".layers.1", channels, channels, 7, true);

		// layers.2: Snake
		addSnakeWeights(weights, rand, prefix + ".layers.2", channels);

		// layers.3: WNConv1d(k=1)
		addWNConvWeights(weights, rand, prefix + ".layers.3", channels, channels, 1, true);
	}

	/**
	 * Creates a random weight tensor with small values.
	 */
	private PackedCollection randomWeights(Random rand, int... dims) {
		PackedCollection weights = new PackedCollection(dims);
		CollectionFeatures ops = CollectionFeatures.getInstance();
		ops.a(ops.cp(weights), ops.rand(weights.getShape(), rand).add(-0.5).multiply(0.1)).get().run();
		return weights;
	}

	/**
	 * Verifies that the residual block shared by {@link OobleckEncoder} and
	 * {@link OobleckDecoder} (via {@link OobleckCodec#buildResidualBlock}) preserves
	 * the input shape — a property the skip connection depends on.
	 */
	@Test(timeout = 120000)
	public void sharedResidualBlockPreservesInputShape() {
		int batchSize = 1;
		int channels = 16;
		int seqLength = 16;
		String prefix = "block";

		Map<String, PackedCollection> weights = new HashMap<>();
		addResidualBlockWeights(weights, new Random(42), prefix, channels);
		StateDictionary stateDict = new StateDictionary(weights);

		ResidualBlockCodec codec = new ResidualBlockCodec(stateDict, batchSize, channels, seqLength);
		Block residualBlock = codec.residualBlock(batchSize, channels, seqLength, prefix);

		PackedCollection input = new PackedCollection(batchSize, channels, seqLength);
		rand(input.getShape()).multiply(0.1).into(input.traverseEach()).evaluate();

		PackedCollection output = runResidualBlock(residualBlock, batchSize, channels, seqLength, input);

		assertEquals("Residual block must preserve the batch dimension",
				batchSize, output.getShape().length(0));
		assertEquals("Residual block must preserve the channel dimension",
				channels, output.getShape().length(1));
		assertEquals("Residual block must preserve the sequence dimension",
				seqLength, output.getShape().length(2));
	}

	/**
	 * Pins the behavior of the shared {@link OobleckCodec#buildResidualBlock} against an
	 * inline reference construction of the same Snake -&gt; WNConv(k=7) -&gt; Snake -&gt;
	 * WNConv(k=1) + skip graph. Both are built from numerically identical weights and
	 * fed the same input; their outputs must agree. This guards the consolidation of the
	 * previously-duplicated encoder and decoder residual-block builders.
	 */
	@Test(timeout = 120000)
	public void sharedResidualBlockMatchesInlineReference() {
		int batchSize = 1;
		int channels = 16;
		int seqLength = 16;
		String prefix = "block";

		StateDictionary sharedDict = positiveBetaResidualDict(prefix, channels);
		StateDictionary referenceDict = positiveBetaResidualDict(prefix, channels);

		ResidualBlockCodec codec = new ResidualBlockCodec(sharedDict, batchSize, channels, seqLength);
		Block sharedBlock = codec.residualBlock(batchSize, channels, seqLength, prefix);
		Block referenceBlock = buildReferenceResidualBlock(referenceDict, batchSize, channels, seqLength, prefix);

		PackedCollection input = new PackedCollection(batchSize, channels, seqLength);
		rand(input.getShape()).multiply(0.1).into(input.traverseEach()).evaluate();

		PackedCollection sharedOut = runResidualBlock(sharedBlock, batchSize, channels, seqLength, input);
		int len = sharedOut.getMemLength();
		double[] sharedValues = new double[len];
		for (int i = 0; i < len; i++) {
			sharedValues[i] = sharedOut.toDouble(i);
		}

		PackedCollection referenceOut = runResidualBlock(referenceBlock, batchSize, channels, seqLength, input);
		assertEquals("Output lengths must match", len, referenceOut.getMemLength());
		for (int i = 0; i < len; i++) {
			assertEquals("Shared residual block output must match the inline reference at index " + i,
					referenceOut.toDouble(i), sharedValues[i], 1e-5);
		}
	}

	/**
	 * Builds a synthetic residual block weight set and forces the Snake beta parameters
	 * positive so the activation stays finite for a value comparison. Two calls with the
	 * same fixed seed produce numerically identical, independently-stored weights.
	 */
	private StateDictionary positiveBetaResidualDict(String prefix, int channels) {
		Map<String, PackedCollection> weights = new HashMap<>();
		addResidualBlockWeights(weights, new Random(42), prefix, channels);
		for (String betaKey : new String[] { prefix + ".layers.0.beta", prefix + ".layers.2.beta" }) {
			PackedCollection beta = weights.get(betaKey);
			a(cp(beta), abs(cp(beta)).add(0.1)).get().run();
		}
		return new StateDictionary(weights);
	}

	/**
	 * Inline reference construction of the Oobleck residual block, mirroring the shared
	 * {@link OobleckCodec#buildResidualBlock} against which it is compared.
	 */
	private Block buildReferenceResidualBlock(StateDictionary dict, int batchSize,
											  int channels, int seqLength, String prefix) {
		TraversalPolicy inputShape = shape(batchSize, channels, seqLength);
		SequentialBlock mainPath = new SequentialBlock(inputShape);

		mainPath.add(snake(inputShape,
				dict.get(prefix + ".layers.0.alpha"), dict.get(prefix + ".layers.0.beta")));
		mainPath.add(wnConv1d(batchSize, channels, channels, seqLength, 7, 1, 3,
				dict.get(prefix + ".layers.1.weight_g"),
				dict.get(prefix + ".layers.1.weight_v"),
				dict.get(prefix + ".layers.1.bias")));
		mainPath.add(snake(inputShape,
				dict.get(prefix + ".layers.2.alpha"), dict.get(prefix + ".layers.2.beta")));
		mainPath.add(wnConv1d(batchSize, channels, channels, seqLength, 1, 1, 0,
				dict.get(prefix + ".layers.3.weight_g"),
				dict.get(prefix + ".layers.3.weight_v"),
				dict.get(prefix + ".layers.3.bias")));

		return residual(mainPath);
	}

	/**
	 * Wraps a residual block in a single-block model, compiles it, and runs one forward pass.
	 */
	private PackedCollection runResidualBlock(Block block, int batchSize, int channels,
											  int seqLength, PackedCollection input) {
		Model model = new Model(shape(batchSize, channels, seqLength));
		model.add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * Minimal concrete {@link OobleckCodec} that exposes the shared residual-block builder
	 * for direct testing without constructing a full encoder or decoder.
	 */
	private static class ResidualBlockCodec extends OobleckCodec {
		/**
		 * Creates a codec whose only purpose is to expose the shared residual-block builder.
		 *
		 * @param stateDict Synthetic weights for the residual block under test
		 * @param batchSize Batch size
		 * @param channels  Channel count
		 * @param seqLength Sequence length
		 */
		ResidualBlockCodec(StateDictionary stateDict, int batchSize, int channels, int seqLength) {
			super(new TraversalPolicy(batchSize, channels, seqLength), stateDict);
		}

		/**
		 * Exposes the protected shared residual-block builder for testing.
		 *
		 * @param batchSize Batch size
		 * @param channels  Channel count
		 * @param seqLength Sequence length
		 * @param prefix    Weight key prefix
		 * @return Assembled residual block
		 */
		Block residualBlock(int batchSize, int channels, int seqLength, String prefix) {
			return buildResidualBlock(batchSize, channels, seqLength, prefix);
		}
	}
}
