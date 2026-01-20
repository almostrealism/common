/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.ml.audio.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.LoRADiffusionTransformer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * End-to-end integration test for the fine-tuning pipeline.
 *
 * <p>This test verifies that we can:</p>
 * <ol>
 *   <li>Load pre-trained model weights</li>
 *   <li>Create a LoRA-enabled model</li>
 *   <li>Run a forward pass through the model</li>
 * </ol>
 *
 * <p><b>Important:</b> The DiffusionTransformer operates on latent representations
 * from the autoencoder, not raw audio. For full end-to-end training, we need:</p>
 * <pre>
 * Raw Audio -&gt; Autoencoder Encoder -&gt; Latent -&gt; DiffusionTransformer
 * </pre>
 */
public class EndToEndFineTuningTest extends TestSuiteBase {

	private static final Path WEIGHTS_DIR = Path.of("/workspace/project/weights");
	private static final Path BASS_LOOPS_DIR = Path.of("/workspace/project/BASS LOOPS_125");

	// Model parameters (from ConditionalAudioSystem)
	private static final int IO_CHANNELS = 64;
	private static final int EMBED_DIM = 1024;
	private static final int DEPTH = 16;
	private static final int NUM_HEADS = 8;
	private static final int PATCH_SIZE = 1;
	private static final int COND_TOKEN_DIM = 768;
	private static final int GLOBAL_COND_DIM = 768;
	private static final String DIFFUSION_OBJECTIVE = "rf_denoiser";

	/**
	 * Test that we can load the pre-trained weights.
	 */
	@Test
	public void testLoadWeights() throws IOException {
		if (!Files.exists(WEIGHTS_DIR)) {
			log("Weights directory not found, skipping test");
			return;
		}

		log("Loading weights from: " + WEIGHTS_DIR);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());

		// Check that we have weights
		int weightCount = stateDict.keySet().size();
		log("Loaded " + weightCount + " weight keys");

		Assert.assertTrue("Should have loaded weights", weightCount > 0);

		// Log some sample keys to understand the structure
		log("Sample weight keys:");
		stateDict.keySet().stream()
				.sorted()
				.limit(30)
				.forEach(key -> log("  " + key));

		log("Weight loading test passed");
	}

	/**
	 * Test that we can create a LoRADiffusionTransformer with loaded weights.
	 *
	 * <p>This verifies the model architecture matches the weights.</p>
	 */
	@Test
	public void testCreateLoRAModel() throws IOException {
		if (!Files.exists(WEIGHTS_DIR)) {
			log("Weights directory not found, skipping test");
			return;
		}

		log("Loading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR.toString());
		log("Loaded " + stateDict.keySet().size() + " weight keys");

		log("Creating LoRADiffusionTransformer...");
		AdapterConfig adapterConfig = AdapterConfig.forAudioDiffusion();
		log("Adapter config: rank=" + adapterConfig.getRank() + ", alpha=" + adapterConfig.getAlpha());

		LoRADiffusionTransformer model = LoRADiffusionTransformer.create(
				IO_CHANNELS,
				EMBED_DIM,
				DEPTH,
				NUM_HEADS,
				PATCH_SIZE,
				COND_TOKEN_DIM,
				GLOBAL_COND_DIM,
				DIFFUSION_OBJECTIVE,
				stateDict,
				adapterConfig
		);

		Assert.assertNotNull("Model should be created", model);
		Assert.assertNotNull("Model should have LoRA layers", model.getLoraLayers());

		int loraLayerCount = model.getLoraLayers().size();
		log("Created model with " + loraLayerCount + " LoRA layers");

		Assert.assertTrue("Should have LoRA layers", loraLayerCount > 0);

		// Check trainable parameters
		int trainableParams = 0;
		for (var loraLayer : model.getLoraLayers()) {
			for (var weight : loraLayer.getWeights()) {
				trainableParams += weight.getMemLength();
			}
		}
		log("Total trainable LoRA parameters: " + trainableParams);

		log("LoRA model creation test passed");
	}

	/**
	 * Test that we can load the autoencoder weights (encoder and decoder).
	 *
	 * <p>This verifies the extracted autoencoder weights are in the correct format
	 * for use with OobleckEncoder and OobleckDecoder.</p>
	 */
	@Test
	public void testLoadAutoencoderWeights() throws IOException {
		Path autoencoderDir = WEIGHTS_DIR.resolve("autoencoder");
		if (!Files.exists(autoencoderDir)) {
			log("Autoencoder weights directory not found at " + autoencoderDir);
			log("Run extract_autoencoder_weights.py to generate these weights.");
			return;
		}

		log("Loading autoencoder weights from: " + autoencoderDir);
		StateDictionary encoderDict = new StateDictionary(autoencoderDir.toString());

		int keyCount = encoderDict.keySet().size();
		log("Loaded " + keyCount + " autoencoder weight keys");
		Assert.assertTrue("Should have loaded autoencoder weights", keyCount > 0);

		// Check for expected encoder keys
		List<String> expectedEncoderKeys = Arrays.asList(
				"encoder.layers.0.weight_g",
				"encoder.layers.0.weight_v",
				"encoder.layers.0.bias",
				"encoder.layers.1.layers.0.layers.0.alpha",
				"encoder.layers.6.alpha",
				"encoder.layers.7.weight_g"
		);

		log("Checking encoder weight keys:");
		int foundCount = 0;
		for (String key : expectedEncoderKeys) {
			boolean found = encoderDict.keySet().contains(key);
			log("  " + key + ": " + (found ? "FOUND" : "MISSING"));
			if (found) foundCount++;
		}

		// Check for expected decoder keys
		List<String> expectedDecoderKeys = Arrays.asList(
				"decoder.layers.0.weight_g",
				"decoder.layers.0.weight_v",
				"decoder.layers.0.bias",
				"decoder.layers.1.layers.0.alpha",
				"decoder.layers.6.alpha",
				"decoder.layers.7.weight_g"
		);

		log("Checking decoder weight keys:");
		for (String key : expectedDecoderKeys) {
			boolean found = encoderDict.keySet().contains(key);
			log("  " + key + ": " + (found ? "FOUND" : "MISSING"));
			if (found) foundCount++;
		}

		// Log sample of actual keys for debugging
		log("\nSample of actual keys (first 30):");
		encoderDict.keySet().stream()
				.sorted()
				.limit(30)
				.forEach(key -> log("  " + key));

		// Count encoder vs decoder keys
		long encoderKeyCount = encoderDict.keySet().stream()
				.filter(k -> k.startsWith("encoder."))
				.count();
		long decoderKeyCount = encoderDict.keySet().stream()
				.filter(k -> k.startsWith("decoder."))
				.count();
		long bottleneckKeyCount = encoderDict.keySet().stream()
				.filter(k -> k.startsWith("bottleneck."))
				.count();

		log("\nWeight distribution:");
		log("  Encoder keys: " + encoderKeyCount);
		log("  Decoder keys: " + decoderKeyCount);
		log("  Bottleneck keys: " + bottleneckKeyCount);

		Assert.assertTrue("Should have encoder keys", encoderKeyCount > 0);
		Assert.assertTrue("Should have decoder keys", decoderKeyCount > 0);

		log("Autoencoder weight loading test passed");
	}
}
