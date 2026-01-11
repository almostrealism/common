package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Test with 2 layers that both use layer 0's weights.
 * This tests if running two layers sequentially breaks something,
 * independent of different weight values.
 */
public class DuplicateLayer0Test extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testDuplicateLayer0() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/duplicate_layer0.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Duplicate Layer 0 Test ===\n");
		log("Purpose: Build 2 layers both using layer 0's weights");
		log("If this fails, the issue is with running 2 layers, not specific weights\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Build model with 2 layers, both using layer 0's weights
		log("Building 2-layer model (both layers use layer 0 weights)...");
		Model model = new Model(shape(1, config.dim));

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Load layer 0 weights
		String prefix = "model.layers.0";
		PackedCollection rmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
		PackedCollection rmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
		PackedCollection wq = stateDict.get(prefix + ".self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get(prefix + ".self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get(prefix + ".self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get(prefix + ".self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get(prefix + ".self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get(prefix + ".self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get(prefix + ".self_attn.v_proj.bias");
		PackedCollection qkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
		PackedCollection qkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");
		PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
		PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
		PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

		// Add first layer (layer 0 weights)
		model.add(transformer(config.headCount, config.kvHeadCount,
				rmsAtt, wk, wv, wq, wo, bk, bv, bq, qkNormQ, qkNormK,
				freqCis, rmsFfn, w1, w2, w3, p(position), 1e-6));

		// Add second layer (ALSO layer 0 weights)
		model.add(transformer(config.headCount, config.kvHeadCount,
				rmsAtt, wk, wv, wq, wo, bk, bv, bq, qkNormQ, qkNormK,
				freqCis, rmsFfn, w1, w2, w3, p(position), 1e-6));

		// Get embeddings input
		int tokenId = 9707;
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection input = embeddings.range(shape(config.dim), tokenId * config.dim);

		// Compile and run
		log("Compiling...");
		org.almostrealism.model.CompiledModel compiled = model.compile();

		log("Running forward pass...");
		PackedCollection output = compiled.forward(input);

		// Compare with expected
		// Expected = run layer0 twice on embeddings
		// We can compare against after_layer_0.bin processed by layer 0 again
		// But we don't have that exact reference, so just check if output is stable

		float[] arOutput = new float[config.dim];
		double maxAbs = 0, mean = 0;
		for (int i = 0; i < config.dim; i++) {
			arOutput[i] = (float) output.toDouble(i);
			maxAbs = Math.max(maxAbs, Math.abs(arOutput[i]));
			mean += arOutput[i];
		}
		mean /= config.dim;

		log("\n=== Output Statistics ===");
		log(String.format("Mean: %.6f", mean));
		log(String.format("Max absolute value: %.6f", maxAbs));

		log("\nFirst 10 output values:");
		for (int i = 0; i < 10; i++) {
			log(String.format("  [%d] = %.6f", i, arOutput[i]));
		}

		// Also run just layer 0 once and compare
		Model singleLayer = new Model(shape(1, config.dim));
		singleLayer.add(transformer(config.headCount, config.kvHeadCount,
				rmsAtt, wk, wv, wq, wo, bk, bv, bq, qkNormQ, qkNormK,
				freqCis, rmsFfn, w1, w2, w3, p(position), 1e-6));
		org.almostrealism.model.CompiledModel singleCompiled = singleLayer.compile();
		PackedCollection singleOutput = singleCompiled.forward(input);

		log("\n=== Single Layer Output (for reference) ===");
		log("First 10 single-layer output values:");
		for (int i = 0; i < 10; i++) {
			log(String.format("  [%d] = %.6f", i, singleOutput.toDouble(i)));
		}

		// The double layer output should be different from single layer
		// but should still be stable
		if (maxAbs > 100 || Double.isNaN(maxAbs) || Double.isInfinite(maxAbs)) {
			log("\n[FAIL] Output has numerical issues");
		} else {
			log("\n[OK] Output is numerically stable");
		}

		stateDict.destroy();
	}

	private PackedCollection computeRopeFreqs(Qwen3Config config) {
		int headSize = config.dim / config.headCount;
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(config.ropeTheta, (2.0 * i) / headSize);
		}

		PackedCollection freqCis = new PackedCollection(shape(config.seqLen, freqDim, 2));
		for (int pos = 0; pos < config.seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}
		return freqCis;
	}
}
