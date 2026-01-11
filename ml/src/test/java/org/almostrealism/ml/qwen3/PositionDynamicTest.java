package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Minimal test to verify position updates affect the compiled model.
 *
 * This test verifies that:
 * 1. Position changes are reflected in RoPE rotation
 * 2. Position changes are reflected in KV cache writes
 * 3. Position changes are reflected in causal mask
 */
public class PositionDynamicTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

	@Test
	public void testPositionAffectsOutput() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/position_dynamic_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Position Dynamic Update Test");
		log("===================================================\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		log("Loading weights...");
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection tokenEmbeddings = stateDict.get("model.embed_tokens.weight");

		// Create position collection
		PackedCollection position = new PackedCollection(1);

		log("Building model with dynamic position...");
		Model transformer = buildMinimalTransformer(config, stateDict, position);

		log("Compiling model...");
		CompiledModel compiled = transformer.compile();

		// Get embedding for token 9707 ("Hello")
		int token = 9707;
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, tokenEmbeddings.toDouble(token * config.dim + i));
		}

		// Run forward pass at different positions
		double[][] outputs = new double[5][10];

		log("\n=== Running Forward Pass at Different Positions ===\n");

		for (int step = 0; step < 5; step++) {
			// UPDATE POSITION BEFORE FORWARD PASS
			position.setMem(0, (double) step);
			log("Step " + step + ": position.toDouble(0) = " + position.toDouble(0));

			// Forward pass
			PackedCollection output = compiled.forward(input);

			// Store first 10 output values
			log("  Output (first 10 values):");
			for (int i = 0; i < 10; i++) {
				outputs[step][i] = output.toDouble(i);
			}
			log("    " + formatOutput(outputs[step]));
		}

		// Compare outputs
		log("\n=== Comparing Outputs ===\n");

		boolean allIdentical = true;
		for (int step = 1; step < 5; step++) {
			boolean identical = true;
			double maxDiff = 0;
			for (int i = 0; i < 10; i++) {
				double diff = Math.abs(outputs[step][i] - outputs[step-1][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) {
					identical = false;
					allIdentical = false;
				}
			}

			if (identical) {
				log("Step " + (step-1) + " vs Step " + step + ": IDENTICAL (max diff: " + maxDiff + ")");
			} else {
				log("Step " + (step-1) + " vs Step " + step + ": DIFFERENT (max diff: " + maxDiff + ")");
			}
		}

		log("\n=== Result ===\n");
		if (allIdentical) {
			log("[FAIL] All outputs are identical - position is NOT affecting computation!");
			log("This indicates the position producer is being evaluated at compile time,");
			log("not dynamically at each forward pass.");
		} else {
			log("[PASS] Outputs differ between positions - position IS affecting computation.");
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private String formatOutput(double[] values) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", values[i]));
		}
		sb.append("]");
		return sb.toString();
	}

	private Model buildMinimalTransformer(Qwen3Config config, StateDictionary stateDict,
										  PackedCollection position) {
		Model transformer = new Model(shape(1, config.dim));

		PackedCollection rmsFinalWeight = stateDict.get("model.norm.weight");
		PackedCollection freqCis = computeRopeFreqs(config);

		// Build only first 1 layer for debugging
		int maxLayers = 1;
		for (int i = 0; i < Math.min(config.layerCount, maxLayers); i++) {
			String prefix = String.format("model.layers.%d", i);

			PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
			PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");

			PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
			PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
			PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
			PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

			PackedCollection layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
			PackedCollection layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
			PackedCollection layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");

			PackedCollection layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
			PackedCollection layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

			PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
			PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
			PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

			transformer.add(transformer(
					config.headCount,
					config.kvHeadCount,
					layerRmsAtt,
					layerWk, layerWv, layerWq, layerWo,
					layerBk, layerBv, layerBq,
					layerQkNormQ, layerQkNormK,
					freqCis,
					layerRmsFfn,
					layerW1, layerW2, layerW3,
					p(position),
					1e-6));
		}

		transformer.add(rmsnorm(shape(1, config.dim), rmsFinalWeight, 1e-6));
		return transformer;
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = 10;
		double theta = config.ropeTheta;

		int freqDim = headSize / 2;
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));

		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
				double angle = pos * freq;
				freqCis.setMem((pos * freqDim + i) * 2, Math.cos(angle));
				freqCis.setMem((pos * freqDim + i) * 2 + 1, Math.sin(angle));
			}
		}

		return freqCis;
	}
}
