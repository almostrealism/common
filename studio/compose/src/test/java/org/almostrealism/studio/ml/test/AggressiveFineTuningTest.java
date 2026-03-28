/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.audio.WavFile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.ml.DiffusionTrainingDataset;
import org.almostrealism.ml.audio.DiffusionNoiseScheduler;
import org.almostrealism.ml.audio.DiffusionSampler;
import org.almostrealism.ml.audio.LoRADiffusionTransformer;
import org.almostrealism.ml.audio.PingPongSamplingStrategy;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.TrainingResult;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * End-to-end LoRA fine-tuning pipeline test for diffusion transformers.
 *
 * <p>This test validates the complete training and inference pipeline using
 * synthetic latents so that no external audio files or pre-trained weights
 * are required.  The pipeline exercised is:
 * <ol>
 *   <li>Create a LoRA-wrapped DiffusionTransformer (random init)</li>
 *   <li>Compile for training (forward + backward pass)</li>
 *   <li>Train with {@link ModelOptimizer} on synthetic diffusion data</li>
 *   <li>Save LoRA adapter weights</li>
 *   <li>Run inference via {@link DiffusionSampler} and write WAV output</li>
 * </ol>
 *
 * <p>{@link #testAggressiveFineTuning()} uses production-scale parameters
 * (depth=16, embed=1024) and is expected to require significant compilation
 * time.  {@link #testCompilationScaling()} systematically measures forward
 * and backward pass compilation time and memory across increasing model
 * sizes to identify where the practical limits are.
 *
 * @see ModelOptimizer
 * @see DiffusionSampler
 * @see LoRADiffusionTransformer
 */
public class AggressiveFineTuningTest extends TestSuiteBase {

	private static final Path OUTPUT_DIR = Path.of("/workspace/project/generated_audio");

	// Production model parameters (from Stable Audio Open)
	private static final int PROD_IO_CHANNELS = 64;
	private static final int PROD_EMBED_DIM = 1024;
	private static final int PROD_DEPTH = 16;
	private static final int PROD_NUM_HEADS = 8;
	private static final int PROD_COND_TOKEN_DIM = 768;
	private static final int PROD_GLOBAL_COND_DIM = 768;

	private static final int PATCH_SIZE = 1;
	private static final String DIFFUSION_OBJECTIVE = "rf_denoiser";
	private static final int LATENT_LENGTH = 4;

	/**
	 * Production-scale fine-tuning pipeline test.
	 *
	 * <p>Uses the same model dimensions as the Stable Audio Open
	 * DiffusionTransformer (depth=16, embed=1024, heads=8).  This test
	 * exercises the full pipeline: create model, compile for training,
	 * train, save adapters, run inference, and write WAV output.
	 *
	 * <p>Backward-pass expression-tree compilation at this scale is
	 * expected to take a significant amount of time.
	 */
	@Test(timeout = 5 * 60000)
	@TestProperties(knownIssue = true)
	@TestDepth(1)
	public void testAggressiveFineTuning() throws IOException {
		Files.createDirectories(OUTPUT_DIR);

		runFineTuningPipeline(
				PROD_IO_CHANNELS, PROD_EMBED_DIM, PROD_DEPTH, PROD_NUM_HEADS,
				PROD_COND_TOKEN_DIM, PROD_GLOBAL_COND_DIM,
				LATENT_LENGTH, 3, 5, 2,
				OUTPUT_DIR.resolve("production_lora.pb"),
				OUTPUT_DIR.resolve("production_generated.wav")
		);
	}

	/**
	 * Measures forward-pass compilation time, backward-pass compilation
	 * time, and JVM heap usage across increasing model sizes.
	 *
	 * <p>Each configuration creates a LoRA DiffusionTransformer with
	 * synthetic random weights, compiles the forward pass, then compiles
	 * the training (backward) pass, and reports elapsed time and memory
	 * at each stage.  The results are logged as a table so that scaling
	 * trends are easy to identify.
	 *
	 * <p>Configurations tested (all with depth=1, heads=1, latent_len=2):
	 * <ul>
	 *   <li>embed=8, io=4</li>
	 *   <li>embed=16, io=8</li>
	 *   <li>embed=32, io=16</li>
	 *   <li>embed=64, io=32</li>
	 *   <li>embed=128, io=64</li>
	 *   <li>embed=256, io=64</li>
	 * </ul>
	 */
	@Test(timeout = 5 * 60000)
	@TestProperties(knownIssue = true)
	@TestDepth(2)
	public void testCompilationScaling() {
		int[][] configs = {
				// {embedDim, ioChannels, depth, numHeads, condTokenDim, globalCondDim}
				{8,   4,  1, 1, 0, 8},
				{16,  8,  1, 1, 0, 16},
				{32,  16, 1, 1, 0, 32},
				{64,  32, 1, 2, 0, 64},
				{128, 64, 1, 2, 0, 128},
				{256, 64, 1, 4, 0, 256},
		};

		log("=== Compilation Scaling Test ===");
		log("");
		log(String.format("%-8s %-6s %-6s %-6s %-14s %-14s %-14s %-14s %-14s",
				"Embed", "IO", "Depth", "Heads",
				"Fwd(ms)", "Bwd(ms)", "Train1(ms)",
				"HeapUsed(MB)", "HeapMax(MB)"));
		log(String.format("%-8s %-6s %-6s %-6s %-14s %-14s %-14s %-14s %-14s",
				"--------", "------", "------", "------",
				"--------------", "--------------", "--------------",
				"--------------", "--------------"));

		for (int[] cfg : configs) {
			int embedDim = cfg[0];
			int ioChannels = cfg[1];
			int depth = cfg[2];
			int numHeads = cfg[3];
			int condTokenDim = cfg[4];
			int globalCondDim = cfg[5];

			log("");
			log("--- Testing embed=" + embedDim + " io=" + ioChannels
					+ " depth=" + depth + " heads=" + numHeads + " ---");

			try {
				measureCompilation(embedDim, ioChannels, depth, numHeads,
						condTokenDim, globalCondDim);
			} catch (Exception e) {
				log(String.format("%-8d %-6d %-6d %-6d FAILED: %s",
						embedDim, ioChannels, depth, numHeads, e.getMessage()));
			}
		}

		log("");
		log("=== Scaling Test Complete ===");
	}

	/**
	 * Profiled fine-tuning run at embed=64 to capture detailed performance
	 * data for analysis with the ar-profile-analyzer MCP tools.
	 *
	 * <p>This test creates an XML profile file that can be analyzed to
	 * identify which operations dominate backward pass compilation time.
	 * The profile is saved to {@code utils/results/finetune_profile_embed64.xml}.
	 */
	@Test(timeout = 5 * 60000)
	@TestProperties(knownIssue = true)
	@TestDepth(2)
	public void testProfiledFineTuning() throws IOException {
		Files.createDirectories(Path.of("/workspace/project/common/utils/results"));

		int embedDim = 64;
		int ioChannels = 32;
		int depth = 1;
		int numHeads = 2;
		int condTokenDim = 0;
		int globalCondDim = 64;
		int latentLen = 2;

		log("=== Profiled Fine-Tuning (embed=" + embedDim + ") ===");
		log("");

		OperationProfileNode profile = new OperationProfileNode("finetune_embed64");

		profile(profile, () -> {
			try {
				runProfiledFineTuning(embedDim, ioChannels, depth, numHeads,
						condTokenDim, globalCondDim, latentLen);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});

		String profilePath = "/workspace/project/common/utils/results/finetune_profile_embed64.xml";
		profile.save(profilePath);
		log("");
		log("Profile saved to: " + profilePath);
	}

	/**
	 * Runs the profiled fine-tuning pipeline with detailed timing logs.
	 */
	private void runProfiledFineTuning(int embedDim, int ioChannels, int depth,
									   int numHeads, int condTokenDim,
									   int globalCondDim, int latentLen) throws IOException {
		AdapterConfig adapterConfig = AdapterConfig.forAudioDiffusion();

		// Step 1: Create model
		log("Step 1: Creating LoRA model...");
		long start = System.currentTimeMillis();
		LoRADiffusionTransformer model = LoRADiffusionTransformer.create(
				ioChannels, embedDim, depth, numHeads, PATCH_SIZE,
				condTokenDim, globalCondDim, DIFFUSION_OBJECTIVE,
				latentLen, 0, null, adapterConfig, false
		);
		log("  Model created in " + (System.currentTimeMillis() - start) + " ms");

		// Step 2: Compile for training (forward + backward)
		log("");
		log("Step 2: Compiling for training (forward + backward)...");
		start = System.currentTimeMillis();
		CompiledModel compiled = model.compileForTraining();
		long compileMs = System.currentTimeMillis() - start;
		log("  Compiled in " + compileMs + " ms");
		log("  LoRA layers: " + model.getLoraLayers().size());

		// Step 3: Create training data
		log("");
		log("Step 3: Creating training data...");
		TraversalPolicy latentShape = new TraversalPolicy(1, ioChannels, latentLen);
		DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(100);

		List<PackedCollection> latents = new ArrayList<>();
		Random rng = new Random(42);
		for (int i = 0; i < 3; i++) {
			PackedCollection latent = new PackedCollection(1, ioChannels, latentLen);
			latent.randnFill(rng);
			latents.add(latent);
		}

		DiffusionTrainingDataset dataset = new DiffusionTrainingDataset(latents, scheduler, 1);
		if (globalCondDim > 0) {
			dataset.setExtraArguments(new PackedCollection(globalCondDim));
		}
		log("  Created " + latents.size() + " training samples");

		// Step 4: Train for 3 epochs
		log("");
		log("Step 4: Training (3 epochs)...");
		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> dataset);
		optimizer.setLossFunction(new MeanSquaredError(latentShape.traverseEach()));
		optimizer.setLogFrequency(1);
		optimizer.setLogConsumer(this::log);

		start = System.currentTimeMillis();
		TrainingResult result = optimizer.optimize(3);
		long trainMs = System.currentTimeMillis() - start;
		log("  Training completed in " + trainMs + " ms");
		log("  Final loss: " + result.getBestValidationLoss());

		// Clean up
		model.releaseCompiledModel();

		log("");
		log("=== Profiled Run Summary ===");
		log("  Compile time: " + compileMs + " ms");
		log("  Train time: " + trainMs + " ms");
	}

	/**
	 * Measures compilation and first training step timing for a single
	 * model configuration.
	 */
	private void measureCompilation(int embedDim, int ioChannels, int depth,
									int numHeads, int condTokenDim,
									int globalCondDim) {
		Runtime runtime = Runtime.getRuntime();

		AdapterConfig adapterConfig = AdapterConfig.forAudioDiffusion();
		int latentLen = 2;

		// Create model
		LoRADiffusionTransformer model = LoRADiffusionTransformer.create(
				ioChannels, embedDim, depth, numHeads, PATCH_SIZE,
				condTokenDim, globalCondDim, DIFFUSION_OBJECTIVE,
				latentLen, 0, null, adapterConfig, false
		);

		// Forward pass compilation (compileForTraining includes forward)
		runtime.gc();
		long heapBefore = runtime.totalMemory() - runtime.freeMemory();
		long startFwd = System.nanoTime();
		CompiledModel compiled = model.compileForTraining();
		long fwdMs = (System.nanoTime() - startFwd) / 1_000_000;

		runtime.gc();
		long heapAfterCompile = runtime.totalMemory() - runtime.freeMemory();

		// First training step (triggers backward pass compilation)
		TraversalPolicy latentShape = new TraversalPolicy(1, ioChannels, latentLen);
		DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(100);

		List<PackedCollection> latents = new ArrayList<>();
		Random rng = new Random(42);
		PackedCollection latent = new PackedCollection(1, ioChannels, latentLen);
		latent.randnFill(rng);
		latents.add(latent);

		DiffusionTrainingDataset dataset = new DiffusionTrainingDataset(
				latents, scheduler, 1
		);
		if (globalCondDim > 0) {
			dataset.setExtraArguments(new PackedCollection(globalCondDim));
		}

		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> dataset);
		optimizer.setLossFunction(new MeanSquaredError(latentShape.traverseEach()));
		optimizer.setLogFrequency(1);
		optimizer.setLogConsumer(this::log);

		long startBwd = System.nanoTime();
		TrainingResult result = optimizer.optimize(1);
		long bwdMs = (System.nanoTime() - startBwd) / 1_000_000;

		// Second training step (no recompilation, measures pure runtime)
		long startTrain = System.nanoTime();
		optimizer.optimize(1);
		long trainMs = (System.nanoTime() - startTrain) / 1_000_000;

		runtime.gc();
		long heapAfterTrain = runtime.totalMemory() - runtime.freeMemory();
		long heapUsedMb = heapAfterTrain / (1024 * 1024);
		long heapMaxMb = runtime.maxMemory() / (1024 * 1024);

		log(String.format("%-8d %-6d %-6d %-6d %-14d %-14d %-14d %-14d %-14d",
				embedDim, ioChannels, depth, numHeads,
				fwdMs, bwdMs, trainMs, heapUsedMb, heapMaxMb));

		model.releaseCompiledModel();
	}

	/**
	 * Runs the full fine-tuning and inference pipeline for the given
	 * model configuration.
	 *
	 * @param ioChannels     latent channel count
	 * @param embedDim       transformer embedding dimension
	 * @param depth          number of transformer blocks
	 * @param numHeads       number of attention heads
	 * @param condTokenDim   cross-attention conditioning dimension (0 to disable)
	 * @param globalCondDim  global conditioning dimension
	 * @param latentLen      latent sequence length
	 * @param numSamples     number of synthetic training samples
	 * @param epochs         training epochs
	 * @param repeatFactor   dataset repeat factor
	 * @param adaptersPath   output path for LoRA adapter bundle
	 * @param wavPath        output path for generated WAV
	 */
	private void runFineTuningPipeline(int ioChannels, int embedDim, int depth,
									   int numHeads, int condTokenDim,
									   int globalCondDim, int latentLen,
									   int numSamples, int epochs,
									   int repeatFactor, Path adaptersPath,
									   Path wavPath) throws IOException {
		int diffusionSteps = 1000;

		log("=== LoRA Fine-Tuning Pipeline ===");
		log("  embed=" + embedDim + " depth=" + depth + " heads=" + numHeads
				+ " io=" + ioChannels + " latentLen=" + latentLen);
		log("");

		// Step 1: Create synthetic training data
		log("Step 1: Creating synthetic latent training data...");
		List<PackedCollection> syntheticLatents = new ArrayList<>();
		Random rng = new Random(42);
		for (int i = 0; i < numSamples; i++) {
			PackedCollection latent = new PackedCollection(1, ioChannels, latentLen);
			latent.randnFill(rng);
			syntheticLatents.add(latent);
		}
		log("  Created " + numSamples + " synthetic latents, shape (1, "
				+ ioChannels + ", " + latentLen + ")");

		// Step 2: Create LoRA model
		log("");
		log("Step 2: Creating LoRA diffusion model (random init)...");
		AdapterConfig adapterConfig = AdapterConfig.forAudioDiffusion();
		log("  LoRA rank: " + adapterConfig.getRank());
		log("  LoRA alpha: " + adapterConfig.getAlpha());

		LoRADiffusionTransformer loraModel = LoRADiffusionTransformer.create(
				ioChannels, embedDim, depth, numHeads, PATCH_SIZE,
				condTokenDim, globalCondDim, DIFFUSION_OBJECTIVE,
				latentLen, 0, null, adapterConfig, false
		);
		log("  Model object created");

		// Step 3: Compile for training
		log("");
		log("Step 3: Compiling model for training...");
		long compileStart = System.nanoTime();
		CompiledModel compiledModel = loraModel.compileForTraining();
		long compileMs = (System.nanoTime() - compileStart) / 1_000_000;
		log("  Model compiled in " + compileMs + " ms");
		log("  LoRA layers created: " + loraModel.getLoraLayers().size());

		long trainableParams = 0;
		for (LoRALinear layer : loraModel.getLoraLayers()) {
			for (PackedCollection weight : layer.getWeights()) {
				trainableParams += weight.getMemLength();
			}
		}
		log("  Trainable parameters: " + trainableParams);

		// Step 4: Train
		log("");
		log("Step 4: Starting training...");
		log("  Epochs: " + epochs + ", repeat factor: " + repeatFactor);

		DiffusionNoiseScheduler scheduler = new DiffusionNoiseScheduler(diffusionSteps);
		TraversalPolicy latentShape = new TraversalPolicy(1, ioChannels, latentLen);

		DiffusionTrainingDataset diffusionDataset = new DiffusionTrainingDataset(
				syntheticLatents, scheduler, repeatFactor
		);
		if (globalCondDim > 0) {
			diffusionDataset.setExtraArguments(new PackedCollection(globalCondDim));
		}

		ModelOptimizer optimizer = new ModelOptimizer(compiledModel, () -> {
			diffusionDataset.shuffle();
			return diffusionDataset;
		});
		optimizer.setLossFunction(new MeanSquaredError(latentShape.traverseEach()));
		optimizer.setLogFrequency(1);
		optimizer.setLogConsumer(this::log);

		long trainStart = System.nanoTime();
		TrainingResult result = optimizer.optimize(epochs);
		long trainMs = (System.nanoTime() - trainStart) / 1_000_000;

		log("");
		log("Training completed in " + trainMs + " ms:");
		log("  Total steps: " + result.getTotalSteps());
		for (int i = 0; i < result.getTrainLossHistory().size(); i++) {
			log(String.format("  Epoch %d: %.6f", i + 1,
					result.getTrainLossHistory().get(i)));
		}

		// Step 5: Save LoRA adapters
		log("");
		log("Step 5: Saving LoRA adapters...");
		loraModel.saveAdaptersBundle(
				adaptersPath, "test-model",
				Map.of(
						"final_loss", result.getBestValidationLoss(),
						"epochs", (double) epochs,
						"repeat_factor", (double) repeatFactor
				),
				"Pipeline validation test with synthetic data"
		);
		log("  Saved to: " + adaptersPath);

		// Step 6: Run inference
		log("");
		log("Step 6: Running inference...");
		loraModel.releaseCompiledModel();

		DiffusionSampler sampler = new DiffusionSampler(
				loraModel, new PingPongSamplingStrategy(),
				diffusionSteps, latentShape
		);
		sampler.setNumInferenceSteps(10);

		PackedCollection globalCond = globalCondDim > 0
				? new PackedCollection(globalCondDim) : null;
		long inferStart = System.nanoTime();
		PackedCollection generatedLatent = sampler.sample(42L, null, globalCond);
		long inferMs = (System.nanoTime() - inferStart) / 1_000_000;
		log("  Inference completed in " + inferMs + " ms");
		log("  Generated latent size: " + generatedLatent.getMemLength());

		// Step 7: Write WAV output
		log("");
		log("Step 7: Writing WAV output...");
		int totalSamples = generatedLatent.getMemLength();
		File wavFile = wavPath.toFile();
		WavFile wav = WavFile.newWavFile(wavFile, 1, totalSamples, 16, 44100);
		double[][] buffer = new double[1][totalSamples];
		for (int i = 0; i < totalSamples; i++) {
			buffer[0][i] = Math.max(-1.0, Math.min(1.0,
					generatedLatent.toDouble(i)));
		}
		wav.writeFrames(buffer, totalSamples);
		wav.close();
		log("  Saved WAV to: " + wavPath);

		// Summary
		log("");
		log("=== Pipeline Complete ===");
		log("  Compile: " + compileMs + " ms");
		log("  Train: " + trainMs + " ms");
		log("  Infer: " + inferMs + " ms");
		log("  Adapter file: " + adaptersPath);
		log("  WAV file: " + wavPath);
	}
}
