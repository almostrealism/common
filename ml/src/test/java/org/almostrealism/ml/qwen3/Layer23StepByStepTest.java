package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Step-by-step breakdown of layer 23 computation.
 * Tests each component in sequence to find exact point of divergence.
 */
public class Layer23StepByStepTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer23StepByStep() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer23_step_by_step.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(80));
		log("  LAYER 23 STEP-BY-STEP ANALYSIS");
		log("=".repeat(80) + "\n");

		Qwen3Config config = new Qwen3Config(
			896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load input (PyTorch's layer 22 output)
		float[] pytorchInput = loadReferenceOutput("after_layer_22.bin");
		PackedCollection input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			input.setMem(i, pytorchInput[i]);
		}

		log("Input (PyTorch layer 22 output):");
		logStats("  ", input);

		// Load expected output
		float[] pytorchOutput = loadReferenceOutput("after_layer_23.bin");
		log("\nExpected output (PyTorch layer 23 output):");
		logFloatStats("  ", pytorchOutput);

		// Get weights
		String prefix = "model.layers.23";
		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		PackedCollection rmsAttWeight = stateDict.get(prefix + ".input_layernorm.weight");
		PackedCollection wq = stateDict.get(prefix + ".self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get(prefix + ".self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get(prefix + ".self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get(prefix + ".self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get(prefix + ".self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get(prefix + ".self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get(prefix + ".self_attn.v_proj.bias");
		PackedCollection rmsFfnWeight = stateDict.get(prefix + ".post_attention_layernorm.weight");
		PackedCollection w1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
		PackedCollection w2 = stateDict.get(prefix + ".mlp.down_proj.weight");
		PackedCollection w3 = stateDict.get(prefix + ".mlp.up_proj.weight");

		// Step 1: RMSNorm (input_layernorm)
		log("\n--- Step 1: Input RMSNorm ---");
		Model rmsModel = new Model(shape(1, config.dim));
		rmsModel.add(rmsnorm(shape(1, config.dim), rmsAttWeight, 1e-6));
		PackedCollection afterRmsNorm = rmsModel.compile().forward(input);
		logStats("  After RMSNorm:", afterRmsNorm);

		// Step 2: Q projection
		log("\n--- Step 2: Q Projection ---");
		Model qProjModel = new Model(shape(1, config.dim));
		qProjModel.add(rmsnorm(shape(1, config.dim), rmsAttWeight, 1e-6));
		qProjModel.add(dense(wq, bq));
		PackedCollection afterQProj = qProjModel.compile().forward(input);
		logStats("  After Q proj:", afterQProj);

		// Step 3: K projection
		log("\n--- Step 3: K Projection ---");
		Model kProjModel = new Model(shape(1, config.dim));
		kProjModel.add(rmsnorm(shape(1, config.dim), rmsAttWeight, 1e-6));
		kProjModel.add(dense(wk, bk));
		PackedCollection afterKProj = kProjModel.compile().forward(input);
		logStats("  After K proj:", afterKProj);

		// Step 4: Full attention block (without residual)
		log("\n--- Step 4: Attention Block (no residual) ---");
		Block attentionBlock = attention(
			config.headCount, config.kvHeadCount,
			rmsAttWeight, wk, wv, wq, wo,
			bk, bv, bq, null, null,
			freqCis, p(position), 1e-6);
		Model attModel = new Model(shape(1, config.dim));
		attModel.add(attentionBlock);
		PackedCollection attentionOut = attModel.compile().forward(input);
		logStats("  Attention output:", attentionOut);

		// Step 5: After attention residual (input + attention)
		log("\n--- Step 5: After Attention Residual ---");
		PackedCollection afterAttResidual = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			afterAttResidual.setMem(i, input.toDouble(i) + attentionOut.toDouble(i));
		}
		logStats("  Input + Attention:", afterAttResidual);

		// Step 6: FFN block (without residual)
		log("\n--- Step 6: FFN Block (no residual) ---");
		Block ffnBlock = feedForward(rmsFfnWeight, w1, w2, w3, 1e-6);
		Model ffnModel = new Model(shape(1, config.dim));
		ffnModel.add(ffnBlock);
		PackedCollection ffnOut = ffnModel.compile().forward(afterAttResidual);
		logStats("  FFN output:", ffnOut);

		// Step 7: After FFN residual (afterAttResidual + ffn)
		log("\n--- Step 7: After FFN Residual ---");
		PackedCollection afterFfnResidual = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			afterFfnResidual.setMem(i, afterAttResidual.toDouble(i) + ffnOut.toDouble(i));
		}
		logStats("  AfterAtt + FFN:", afterFfnResidual);

		// Step 8: Full transformer layer
		log("\n--- Step 8: Full Transformer Layer ---");
		Model fullModel = buildSingleLayer(config, stateDict, 23);
		PackedCollection fullOut = fullModel.compile().forward(input);
		logStats("  Full layer output:", fullOut);

		// Compare with expected
		log("\n--- Comparison with PyTorch ---");
		float[] arOutput = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			arOutput[i] = (float) fullOut.toDouble(i);
		}
		double mae = computeMAE(pytorchOutput, arOutput);
		log(String.format("  Full layer MAE: %.6f", mae));

		// Manual computation vs transformer method
		float[] manualOutput = new float[config.dim];
		for (int i = 0; i < config.dim; i++) {
			manualOutput[i] = (float) afterFfnResidual.toDouble(i);
		}
		double manualMae = computeMAE(pytorchOutput, manualOutput);
		log(String.format("  Manual step-by-step MAE: %.6f", manualMae));

		// Check if full transformer output matches manual computation
		double fullVsManualMae = computeMAE(arOutput, manualOutput);
		log(String.format("  Full vs Manual MAE: %.6f", fullVsManualMae));

		if (fullVsManualMae > 0.001) {
			log("\n  *** WARNING: Full transformer output differs from manual step-by-step! ***");
			log("  This suggests accum() is not working correctly.");
		}

		// Print first 10 values comparison
		log("\n--- First 10 Values ---");
		log(String.format("%-5s %-12s %-12s %-12s %-12s", "Idx", "PyTorch", "Full AR", "Manual AR", "Diff"));
		for (int i = 0; i < 10; i++) {
			log(String.format("%-5d %-12.4f %-12.4f %-12.4f %-12.4f",
				i, pytorchOutput[i], arOutput[i], manualOutput[i], pytorchOutput[i] - arOutput[i]));
		}

		stateDict.destroy();
		log("\n" + "=".repeat(80));
	}

	private Model buildSingleLayer(Qwen3Config config, StateDictionary stateDict, int layerIndex) {
		Model model = new Model(shape(1, config.dim));

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		String prefix = String.format("model.layers.%d", layerIndex);

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

		model.add(transformer(
			config.headCount, config.kvHeadCount,
			layerRmsAtt, layerWk, layerWv, layerWq, layerWo,
			layerBk, layerBv, layerBq, layerQkNormQ, layerQkNormK,
			freqCis, layerRmsFfn, layerW1, layerW2, layerW3,
			p(position), 1e-6));

		return model;
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

	private void logFloatStats(String prefix, float[] arr) {
		double sum = 0, sumSq = 0;
		double min = arr[0], max = arr[0];
		for (float v : arr) {
			sum += v;
			sumSq += v * v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double mean = sum / arr.length;
		double variance = (sumSq / arr.length) - (mean * mean);
		double std = Math.sqrt(Math.max(0, variance));
		log(String.format("%s mean=%.6f, std=%.6f, range=[%.4f, %.4f]", prefix, mean, std, min, max));
	}

	private float[] loadReferenceOutput(String filename) throws IOException {
		try (FileChannel channel = FileChannel.open(
				Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			channel.read(buffer);
			buffer.flip();

			int size = buffer.getInt();
			float[] output = new float[size];
			for (int i = 0; i < size; i++) {
				output[i] = buffer.getFloat();
			}
			return output;
		}
	}

	private double computeMAE(float[] expected, float[] actual) {
		double sum = 0;
		for (int i = 0; i < expected.length; i++) {
			sum += Math.abs(expected[i] - actual[i]);
		}
		return sum / expected.length;
	}
}
