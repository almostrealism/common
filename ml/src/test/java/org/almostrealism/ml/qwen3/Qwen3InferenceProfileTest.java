package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Profiling test for Qwen3 inference performance analysis.
 *
 * <p>This test builds a Qwen3 model with synthetic (random) weights using
 * a representative 0.6B-scale configuration and profiles multiple forward
 * passes. The resulting profile XML can be analyzed with the
 * ar-profile-analyzer MCP tool to identify performance bottlenecks.</p>
 *
 * <p>The forward pass operations (attention projections, RoPE, RMSNorm,
 * SwiGLU FFN, etc.) have the same computational cost regardless of weight
 * values, making synthetic weights valid for performance profiling.</p>
 *
 * @see Qwen3
 * @see Qwen3Config
 */
public class Qwen3InferenceProfileTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String RESULTS_DIR = "ml/results";
	private static final String PROFILE_PATH = RESULTS_DIR + "/qwen3_inference_profile.xml";
	private static final String LOG_PATH = RESULTS_DIR + "/qwen3_inference_profile.txt";

	/**
	 * Profile a representative Qwen3 forward pass using 0.6B-scale config.
	 *
	 * <p>Uses dim=896, hiddenDim=4864, 24 layers, 14 query heads, 2 KV heads.
	 * Vocab is reduced to 1000 to keep memory manageable; the final dense
	 * projection scaling can be extrapolated linearly.</p>
	 */
	@Test(timeout = 120000)
	public void profileInference() throws IOException {
		new File(RESULTS_DIR).mkdirs();
		Console.root().addListener(OutputFeatures.fileOutput(LOG_PATH));

		log("=== Qwen3 Inference Profile Test ===");

		// Representative 0.6B config with reduced vocab for memory efficiency.
		// The 24 transformer layers at dim=896 with GQA (14:2) match the
		// benchmark configuration used for real-weight inference.
		Qwen3Config config = new Qwen3Config(
				896,    // dim
				4864,   // hiddenDim
				24,     // layerCount
				14,     // headCount
				2,      // kvHeadCount
				1000,   // vocabSize (reduced from 151936)
				128,    // seqLen (reduced context)
				true,   // sharedWeights
				1000000.0  // ropeTheta
		);
		config.validate();
		log("Config: " + config);

		// Create synthetic weights
		log("Creating synthetic weights...");
		long weightStart = System.currentTimeMillis();
		StateDictionary stateDict = createRandomWeights(config, 42L);
		log("Weights created in " + (System.currentTimeMillis() - weightStart) + " ms");

		// Build model (compilation is profiled internally by Qwen3)
		log("Building and compiling model...");
		long compileStart = System.currentTimeMillis();
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();
		Qwen3 model = new Qwen3(config, stateDict, tokenizer);
		long compileTime = System.currentTimeMillis() - compileStart;
		log("Model compiled in " + compileTime + " ms");

		OperationProfileNode profile = model.getProfile();
		org.almostrealism.model.CompiledModel compiledModel = model.getCompiledModel();
		PackedCollection embeddings = model.getTokenEmbeddings();
		PackedCollection position = model.getPosition();

		// Assign profile to Hardware for runtime execution timing
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			// Warm-up passes (JIT, cache priming)
			int warmupPasses = 3;
			log("Running " + warmupPasses + " warm-up forward passes...");
			for (int w = 0; w < warmupPasses; w++) {
				position.setMem(0, (double) w);
				PackedCollection input = createInput(embeddings, w % config.vocabSize, config.dim);
				compiledModel.forward(input);
			}

			// Profiled forward passes
			int profiledPasses = 10;
			log("Running " + profiledPasses + " profiled forward passes...");
			long totalNanos = 0;

			for (int p = 0; p < profiledPasses; p++) {
				int step = warmupPasses + p;
				position.setMem(0, (double) step);
				PackedCollection input = createInput(embeddings, step % config.vocabSize, config.dim);

				long start = System.nanoTime();
				PackedCollection output = compiledModel.forward(input);
				long elapsed = System.nanoTime() - start;
				totalNanos += elapsed;

				log(String.format("  Pass %d: %.2f ms (output size: %d)",
						p, elapsed / 1_000_000.0, output.getShape().getTotalSize()));
			}

			double avgMs = (totalNanos / (double) profiledPasses) / 1_000_000.0;
			log(String.format("Average forward pass: %.2f ms", avgMs));
			log(String.format("Tokens per second: %.1f", 1000.0 / avgMs));
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}

		// Save profile XML
		log("Saving profile to: " + PROFILE_PATH);
		profile.save(PROFILE_PATH);
		log("Profile saved successfully");

		// Print summary
		log("\n--- Profile Summary ---");
		profile.print();

		stateDict.destroy();
		log("\n=== Profile Test Complete ===");
	}

	/**
	 * Create input tensor from a token embedding.
	 */
	private PackedCollection createInput(PackedCollection embeddings, int token, int dim) {
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, embeddings.toDouble(token * dim + i));
		}
		return input;
	}

	/**
	 * Create random weights with correct shapes for profiling.
	 *
	 * <p>Generates all weight tensors expected by Qwen3 with small random
	 * values. The weight values do not affect operation timing.</p>
	 */
	private static StateDictionary createRandomWeights(Qwen3Config config, long seed) {
		Random random = new Random(seed);
		Map<String, PackedCollection> weights = new HashMap<>();

		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		// Token embeddings
		weights.put("model.embed_tokens.weight",
				randomCollection(random, config.vocabSize, config.dim));

		// Final RMS norm
		weights.put("model.norm.weight",
				randomCollection(random, config.dim));

		for (int i = 0; i < config.layerCount; i++) {
			String prefix = String.format("model.layers.%d", i);

			// RMS norm weights
			weights.put(prefix + ".input_layernorm.weight",
					randomCollection(random, config.dim));
			weights.put(prefix + ".post_attention_layernorm.weight",
					randomCollection(random, config.dim));

			// Attention projection weights
			weights.put(prefix + ".self_attn.q_proj.weight",
					randomCollection(random, config.dim, config.dim));
			weights.put(prefix + ".self_attn.k_proj.weight",
					randomCollection(random, kvDim, config.dim));
			weights.put(prefix + ".self_attn.v_proj.weight",
					randomCollection(random, kvDim, config.dim));
			weights.put(prefix + ".self_attn.o_proj.weight",
					randomCollection(random, config.dim, config.dim));

			// QK-Norm weights
			weights.put(prefix + ".self_attn.q_norm.weight",
					randomCollection(random, config.headCount, config.headSize));
			weights.put(prefix + ".self_attn.k_norm.weight",
					randomCollection(random, config.kvHeadCount, config.headSize));

			// FFN weights (SwiGLU)
			weights.put(prefix + ".mlp.gate_proj.weight",
					randomCollection(random, config.hiddenDim, config.dim));
			weights.put(prefix + ".mlp.down_proj.weight",
					randomCollection(random, config.dim, config.hiddenDim));
			weights.put(prefix + ".mlp.up_proj.weight",
					randomCollection(random, config.hiddenDim, config.dim));
		}

		return new StateDictionary(weights);
	}

	private static PackedCollection randomCollection(Random random, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		PackedCollection collection = new PackedCollection(shape);
		int size = shape.getTotalSize();
		for (int i = 0; i < size; i++) {
			collection.setMem(i, (random.nextDouble() - 0.5) * 0.2);
		}
		return collection;
	}
}
