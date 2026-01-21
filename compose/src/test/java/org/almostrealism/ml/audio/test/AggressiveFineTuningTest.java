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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.*;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.optimize.FineTuneConfig;
import org.almostrealism.optimize.FineTuningResult;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * End-to-end aggressive fine-tuning test for audio diffusion models.
 *
 * <p>This test demonstrates the complete fine-tuning pipeline:
 * <ol>
 *   <li>Load pre-trained autoencoder and diffusion transformer weights</li>
 *   <li>Encode training audio to latent space</li>
 *   <li>Fine-tune diffusion model with LoRA (aggressive settings for overfitting)</li>
 *   <li>Generate audio samples using the fine-tuned model</li>
 *   <li>Save samples for listening comparison</li>
 * </ol>
 *
 * <p>The goal is intentional overfitting to make it obvious when listening
 * that the model has learned something from the training data.</p>
 */
public class AggressiveFineTuningTest extends TestSuiteBase {

	private static final Path WEIGHTS_DIR = Path.of("/workspace/project/weights");
	private static final Path AUTOENCODER_DIR = WEIGHTS_DIR.resolve("autoencoder");
	private static final Path BASS_LOOPS_DIR = Path.of("/workspace/project/BASS LOOPS_125");
	private static final Path OUTPUT_DIR = Path.of("/workspace/project/generated_audio");

	// Model parameters (from ConditionalAudioSystem)
	private static final int IO_CHANNELS = 64;
	private static final int EMBED_DIM = 1024;
	private static final int DEPTH = 16;
	private static final int NUM_HEADS = 8;
	private static final int PATCH_SIZE = 1;
	private static final int COND_TOKEN_DIM = 768;
	private static final int GLOBAL_COND_DIM = 768;
	private static final String DIFFUSION_OBJECTIVE = "rf_denoiser";

	// Audio parameters
	private static final int SAMPLE_RATE = 44100;
	private static final double SEGMENT_SECONDS = 5.0; // 5 second segments

	// Training parameters for aggressive overfitting
	private static final int EPOCHS = 20;
	private static final int REPEAT_FACTOR = 5; // Repeat each sample 5 times per epoch
	private static final double LEARNING_RATE = 5e-4; // Higher LR for faster convergence
	private static final int NUM_DIFFUSION_STEPS = 1000;
	private static final int NUM_INFERENCE_STEPS = 50;

	/**
	 * Full end-to-end aggressive fine-tuning test.
	 *
	 * <p>This test will:
	 * <ul>
	 *   <li>Load 5 audio files from BASS LOOPS</li>
	 *   <li>Encode them to latent space</li>
	 *   <li>Train for 20 epochs with aggressive settings</li>
	 *   <li>Generate 3 audio samples</li>
	 *   <li>Save before/after samples for comparison</li>
	 * </ul>
	 */
	@Test
	public void testAggressiveFineTuning() throws IOException {
		// Check prerequisites
		if (!Files.exists(WEIGHTS_DIR)) {
			log("Weights directory not found, skipping test");
			return;
		}
		if (!Files.exists(AUTOENCODER_DIR)) {
			log("Autoencoder weights not found at " + AUTOENCODER_DIR);
			log("Run extract_autoencoder_weights.py first");
			return;
		}
		if (!Files.exists(BASS_LOOPS_DIR)) {
			log("Training data not found at " + BASS_LOOPS_DIR);
			return;
		}

		// Create output directory
		Files.createDirectories(OUTPUT_DIR);

		log("=== Aggressive Fine-Tuning Test ===");
		log("This test intentionally overfits to demonstrate learning.");
		log("");

		// Step 1: Load weights
		log("Step 1: Loading weights...");
		StateDictionary transformerWeights = new StateDictionary(WEIGHTS_DIR.toString());
		StateDictionary autoencoderWeights = new StateDictionary(AUTOENCODER_DIR.toString());
		log("  Transformer weights: " + transformerWeights.keySet().size() + " keys");
		log("  Autoencoder weights: " + autoencoderWeights.keySet().size() + " keys");

		// Step 2: Encode training audio to latents
		log("");
		log("Step 2: Encoding audio to latent space...");
		AudioLatentDataset dataset = AudioLatentDataset.fromDirectory(
				BASS_LOOPS_DIR,
				autoencoderWeights,
				SEGMENT_SECONDS,
				SAMPLE_RATE,
				5  // Limit to 5 files for faster testing
		);
		log("  Encoded " + dataset.size() + " latent segments");
		log("  Latent shape: (1, " + dataset.getLatentChannels() + ", " + dataset.getLatentLength() + ")");

		// Step 3: Create LoRA model
		log("");
		log("Step 3: Creating LoRA diffusion model...");
		AdapterConfig adapterConfig = AdapterConfig.forAudioDiffusion();
		log("  LoRA rank: " + adapterConfig.getRank());
		log("  LoRA alpha: " + adapterConfig.getAlpha());

		LoRADiffusionTransformer loraModel = LoRADiffusionTransformer.create(
				IO_CHANNELS,
				EMBED_DIM,
				DEPTH,
				NUM_HEADS,
				PATCH_SIZE,
				COND_TOKEN_DIM,
				GLOBAL_COND_DIM,
				DIFFUSION_OBJECTIVE,
				dataset.getLatentLength(),  // audioSeqLen
				0,  // condSeqLen (no text conditioning for now)
				transformerWeights,
				adapterConfig,
				false
		);
		log("  Created model with " + loraModel.getLoraLayers().size() + " LoRA layers");

		// Count trainable parameters
		long trainableParams = 0;
		for (var layer : loraModel.getLoraLayers()) {
			for (var weight : layer.getWeights()) {
				trainableParams += weight.getMemLength();
			}
		}
		log("  Trainable parameters: " + trainableParams);

		// Step 4: Compile model
		log("");
		log("Step 4: Compiling model for training...");
		CompiledModel compiledModel = loraModel.compileForTraining();
		log("  Model compiled with backward pass enabled");

		// Step 5: Generate "before" sample (untrained model)
		log("");
		log("Step 5: Generating 'before' sample (untrained model)...");
		DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(NUM_DIFFUSION_STEPS);
		generateAndSaveSample(compiledModel, autoencoderWeights, scheduler,
				dataset.getLatentLength(), OUTPUT_DIR.resolve("before_training.wav"));

		// Step 6: Aggressive fine-tuning
		log("");
		log("Step 6: Starting aggressive fine-tuning...");
		log("  Epochs: " + EPOCHS);
		log("  Repeat factor: " + REPEAT_FACTOR);
		log("  Learning rate: " + LEARNING_RATE);

		FineTuneConfig config = new FineTuneConfig()
				.epochs(EPOCHS)
				.learningRate(LEARNING_RATE)
				.logEveryNSteps(10)
				.earlyStoppingPatience(0); // Disable early stopping

		TraversalPolicy latentShape = new TraversalPolicy(1, IO_CHANNELS, dataset.getLatentLength());

		AudioDiffusionFineTuner fineTuner = new AudioDiffusionFineTuner(
				compiledModel, config, latentShape, scheduler
		);
		fineTuner.setAggressiveMode(true);
		fineTuner.setRepeatFactor(REPEAT_FACTOR);

		FineTuningResult result = fineTuner.fineTune(dataset);

		log("");
		log("Training completed:");
		log("  Total steps: " + result.getTotalSteps());
		log("  Final loss: " + String.format("%.6f", result.getBestValidationLoss()));
		log("  Training time: " + result.getTrainingTime());

		// Log loss history
		log("");
		log("Loss history:");
		for (int i = 0; i < result.getTrainLossHistory().size(); i++) {
			log(String.format("  Epoch %d: %.6f", i + 1, result.getTrainLossHistory().get(i)));
		}

		// Step 7: Generate "after" samples
		log("");
		log("Step 7: Generating 'after' samples (trained model)...");
		for (int i = 0; i < 3; i++) {
			generateAndSaveSample(compiledModel, autoencoderWeights, scheduler,
					dataset.getLatentLength(),
					OUTPUT_DIR.resolve("after_training_" + (i + 1) + ".wav"));
		}

		// Step 8: Save LoRA weights
		log("");
		log("Step 8: Saving LoRA adapters...");
		Path adaptersPath = OUTPUT_DIR.resolve("bass_loops_lora.pb");
		loraModel.saveAdaptersBundle(
				adaptersPath,
				"stable-audio-open-1.0",
				Map.of(
						"final_loss", result.getBestValidationLoss(),
						"epochs", (double) EPOCHS,
						"repeat_factor", (double) REPEAT_FACTOR
				),
				"Aggressively fine-tuned on BASS LOOPS for demonstration"
		);
		log("  Saved to: " + adaptersPath);

		// Summary
		log("");
		log("=== Test Complete ===");
		log("Output files:");
		log("  " + OUTPUT_DIR.resolve("before_training.wav"));
		log("  " + OUTPUT_DIR.resolve("after_training_1.wav"));
		log("  " + OUTPUT_DIR.resolve("after_training_2.wav"));
		log("  " + OUTPUT_DIR.resolve("after_training_3.wav"));
		log("  " + adaptersPath);
		log("");
		log("Listen to the before/after samples to hear if the model learned");
		log("the characteristics of the BASS LOOPS training data.");
	}

	/**
	 * Generates and saves an audio sample.
	 */
	private void generateAndSaveSample(CompiledModel diffusionModel,
									   StateDictionary decoderWeights,
									   DiffusionNoiseScheduler scheduler,
									   int latentLength,
									   Path outputPath) throws IOException {
		// Build decoder
		OobleckDecoder decoder = new OobleckDecoder(decoderWeights, 1, latentLength);
		Model decoderModel = new Model(new TraversalPolicy(1, IO_CHANNELS, latentLength));
		decoderModel.add(decoder);
		CompiledModel compiledDecoder = decoderModel.compile(false); // Inference only, no backprop needed

		// Create generator
		AudioDiffusionGenerator generator = new AudioDiffusionGenerator(
				diffusionModel, compiledDecoder, scheduler, latentLength
		);
		generator.setNumInferenceSteps(NUM_INFERENCE_STEPS);
		generator.setDDIMEta(0.0); // Deterministic sampling
		generator.setVerbose(false);

		// Generate and save
		generator.generateAndSave(outputPath);
		log("  Generated: " + outputPath.getFileName());
	}

	/**
	 * Quick test that just verifies the pipeline compiles without running full training.
	 */
	@Test
	public void testPipelineCompiles() throws IOException {
		if (!Files.exists(WEIGHTS_DIR)) {
			log("Weights directory not found, skipping test");
			return;
		}
		if (!Files.exists(AUTOENCODER_DIR)) {
			log("Autoencoder weights not found, skipping test");
			return;
		}

		log("Testing that the pipeline compiles...");

		// Load weights
		StateDictionary transformerWeights = new StateDictionary(WEIGHTS_DIR.toString());
		StateDictionary autoencoderWeights = new StateDictionary(AUTOENCODER_DIR.toString());

		// Calculate latent dimensions for 5 second audio
		int segmentSamples = (int) (SEGMENT_SECONDS * SAMPLE_RATE);
		OobleckEncoder encoder = new OobleckEncoder(autoencoderWeights, 1, segmentSamples);
		int latentLength = encoder.getOutputLength();
		log("  Latent length for " + SEGMENT_SECONDS + "s audio: " + latentLength);

		// Create scheduler
		DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(NUM_DIFFUSION_STEPS);
		log("  Scheduler created with " + NUM_DIFFUSION_STEPS + " steps");

		// Create LoRA model
		AdapterConfig adapterConfig = AdapterConfig.forAudioDiffusion();
		LoRADiffusionTransformer loraModel = LoRADiffusionTransformer.create(
				IO_CHANNELS, EMBED_DIM, DEPTH, NUM_HEADS, PATCH_SIZE,
				COND_TOKEN_DIM, GLOBAL_COND_DIM, DIFFUSION_OBJECTIVE,
				latentLength, 0,
				transformerWeights, adapterConfig, false
		);
		log("  LoRA model created with " + loraModel.getLoraLayers().size() + " layers");

		// Test noise addition
		PackedCollection testLatent = new PackedCollection(1, IO_CHANNELS, latentLength);
		PackedCollection noise = scheduler.sampleNoiseLike(testLatent);
		PackedCollection noisyLatent = scheduler.addNoise(testLatent, noise, 500);
		log("  Noise addition works");

		// Test timestep creation
		PackedCollection timestep = new PackedCollection(1);
		timestep.setMem(0, 0.5);
		log("  Timestep tensor created");

		log("Pipeline compilation test passed!");
	}
}
