package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to verify position is being used correctly in RoPE.
 */
public class PositionVerificationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

	@Test
	public void testPositionAffectsOutput() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/position_verification.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  POSITION VERIFICATION TEST");
		log("=".repeat(70) + "\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		PackedCollection tokenEmbeddings = stateDict.get("model.embed_tokens.weight");

		// Build a single-layer model with position control
		PackedCollection position = new PackedCollection(1);
		PackedCollection freqCis = computeRopeFreqs(config);

		// Get just layer 0 weights
		String prefix = "model.layers.0";
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

		// Use DynamicCollectionProducer instead of p(position) so the position
		// is read at runtime, not embedded at compile time
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		Model model = new Model(shape(1, config.dim));
		model.add(transformer(
			config.headCount, config.kvHeadCount,
			layerRmsAtt, layerWk, layerWv, layerWq, layerWo,
			layerBk, layerBv, layerBq, layerQkNormQ, layerQkNormK,
			freqCis, layerRmsFfn, layerW1, layerW2, layerW3,
			dynamicPosition, 1e-6));

		CompiledModel compiled = model.compile();

		// Get Hello token embedding
		int helloToken = 9707;
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, tokenEmbeddings.toDouble(helloToken * config.dim + i));
		}

		// Test at position 0
		position.setMem(0, 0.0);
		PackedCollection outPos0 = compiled.forward(input);
		log("Output at position 0:");
		logStats("  ", outPos0);

		// Save first 5 values
		float[] pos0Values = new float[5];
		for (int i = 0; i < 5; i++) {
			pos0Values[i] = (float) outPos0.toDouble(i);
		}

		// Test at position 1
		position.setMem(0, 1.0);
		PackedCollection outPos1 = compiled.forward(input);
		log("\nOutput at position 1:");
		logStats("  ", outPos1);

		// Compare
		log("\n--- Comparison ---");
		log(String.format("%-5s %-15s %-15s %-15s", "Idx", "Pos 0", "Pos 1", "Diff"));
		boolean different = false;
		for (int i = 0; i < 5; i++) {
			double v0 = outPos0.toDouble(i);
			double v1 = outPos1.toDouble(i);
			double diff = v0 - v1;
			log(String.format("%-5d %-15.6f %-15.6f %-15.6f", i, v0, v1, diff));
			if (Math.abs(diff) > 0.001) {
				different = true;
			}
		}

		if (different) {
			log("\nSUCCESS: Output differs between position 0 and position 1!");
			log("This means position IS affecting the computation.");
		} else {
			log("\nFAILURE: Output is the SAME at both positions!");
			log("This means position is NOT being used correctly.");
		}

		stateDict.destroy();
		log("\n" + "=".repeat(70));

		Assert.assertTrue("Position should affect output - outputs at position 0 and 1 should differ", different);
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = 10;
		double theta = config.ropeTheta;
		int freqDim = headSize / 2;

		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(theta, (double) (2 * i) / headSize);
				double val = pos * freq;
				freqCis.setMem(pos * freqDim * 2 + i * 2, Math.cos(val));
				freqCis.setMem(pos * freqDim * 2 + i * 2 + 1, Math.sin(val));
			}
		}
		return freqCis;
	}

	private void logStats(String prefix, PackedCollection c) {
		int size = c.getShape().getTotalSize();
		double sum = 0, sumSq = 0;
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (int i = 0; i < size; i++) {
			double v = c.toDouble(i);
			sum += v;
			sumSq += v * v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double mean = sum / size;
		double variance = (sumSq / size) - (mean * mean);
		double std = Math.sqrt(Math.max(0, variance));
		log(String.format("%s mean=%.6f, std=%.6f, range=[%.4f, %.4f]", prefix, mean, std, min, max));
	}
}
