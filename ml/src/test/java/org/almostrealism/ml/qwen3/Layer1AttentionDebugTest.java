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
 * Debug layer 1's attention by comparing each step:
 * 1. Q projection
 * 2. K projection
 * 3. V projection
 * 4. RoPE
 * etc.
 */
public class Layer1AttentionDebugTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void testLayer1AttentionProjections() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer1_attention_debug.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 1 Attention Debug Test ===\n");

		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		int dim = config.dim;
		int heads = config.headCount;
		int kvHeads = config.kvHeadCount;
		int headSize = dim / heads;
		int kvDim = kvHeads * headSize;

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load layer 1 input norm output as input for projections
		float[] pytorchInputNorm = loadReferenceOutput("layer1_input_norm.bin");
		PackedCollection inputNorm = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			inputNorm.setMem(i, pytorchInputNorm[i]);
		}

		// Load layer 1 projection weights
		PackedCollection wq = stateDict.get("model.layers.1.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.1.self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get("model.layers.1.self_attn.v_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.1.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.1.self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get("model.layers.1.self_attn.v_proj.bias");

		log("Weight shapes:");
		log("  wq: " + wq.getShape());
		log("  wk: " + wk.getShape());
		log("  wv: " + wv.getShape());
		log("  bq: " + (bq != null ? bq.getShape() : "null"));
		log("  bk: " + (bk != null ? bk.getShape() : "null"));
		log("  bv: " + (bv != null ? bv.getShape() : "null"));

		// Load PyTorch reference projections
		float[] pytorchQ = loadReferenceOutput("layer1_q_proj.bin");
		float[] pytorchK = loadReferenceOutput("layer1_k_proj.bin");
		float[] pytorchV = loadReferenceOutput("layer1_v_proj.bin");

		log("\nPyTorch projection sizes:");
		log("  Q: " + pytorchQ.length + " (expected " + dim + ")");
		log("  K: " + pytorchK.length + " (expected " + kvDim + ")");
		log("  V: " + pytorchV.length + " (expected " + kvDim + ")");

		// Test Q projection with dense layer
		log("\n=== Step 1: Q Projection ===");
		Model qModel = new Model(shape(1, dim));
		qModel.add(dense(wq, bq));
		PackedCollection javaQ = qModel.compile().forward(inputNorm);

		log("Q projection comparison:");
		compareOutputs(pytorchQ, javaQ, 10);

		// Test K projection with dense layer
		log("\n=== Step 2: K Projection ===");
		Model kModel = new Model(shape(1, dim));
		kModel.add(dense(wk, bk));
		PackedCollection javaK = kModel.compile().forward(inputNorm);

		log("K projection comparison:");
		compareOutputs(pytorchK, javaK, 10);

		// Test V projection with dense layer
		log("\n=== Step 3: V Projection ===");
		Model vModel = new Model(shape(1, dim));
		vModel.add(dense(wv, bv));
		PackedCollection javaV = vModel.compile().forward(inputNorm);

		log("V projection comparison:");
		compareOutputs(pytorchV, javaV, 10);

		// Now test the full attention with correct input
		log("\n=== Step 4: Full Attention ===");
		float[] pytorchOProj = loadReferenceOutput("layer1_o_proj.bin");

		PackedCollection rmsAttWeight = stateDict.get("model.layers.1.input_layernorm.weight");
		PackedCollection wo = stateDict.get("model.layers.1.self_attn.o_proj.weight");
		PackedCollection qkNormQ = stateDict.get("model.layers.1.self_attn.q_norm.weight");
		PackedCollection qkNormK = stateDict.get("model.layers.1.self_attn.k_norm.weight");

		PackedCollection freqCis = computeRopeFreqs(config);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Use the layer 0 output as input (which goes through RMSNorm first)
		float[] pytorchLayer0Output = loadReferenceOutput("after_layer_0.bin");
		PackedCollection layer1Input = new PackedCollection(shape(1, config.dim));
		for (int i = 0; i < config.dim; i++) {
			layer1Input.setMem(i, pytorchLayer0Output[i]);
		}

		Model attModel = new Model(shape(1, dim));
		attModel.add(attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK, freqCis, p(position), 1e-6));
		PackedCollection javaAtt = attModel.compile().forward(layer1Input);

		log("Full attention comparison:");
		compareOutputs(pytorchOProj, javaAtt, 10);

		// Also compare layer 0's attention to establish baseline
		log("\n=== Step 5: Layer 0 Attention (baseline) ===");
		float[] pytorchLayer0OProj = loadReferenceOutput("layer1_input_norm_input.bin");
		// Actually we need layer 0's o_proj output... let me check

		// Just compare the stats
		log("\nLayer 1 attention statistics:");
		double sumJava = 0, maxAbsJava = 0;
		for (int i = 0; i < dim; i++) {
			sumJava += javaAtt.toDouble(i);
			maxAbsJava = Math.max(maxAbsJava, Math.abs(javaAtt.toDouble(i)));
		}
		log(String.format("  Java mean: %.6f, max abs: %.6f", sumJava / dim, maxAbsJava));

		double sumPy = 0, maxAbsPy = 0;
		for (int i = 0; i < pytorchOProj.length; i++) {
			sumPy += pytorchOProj[i];
			maxAbsPy = Math.max(maxAbsPy, Math.abs(pytorchOProj[i]));
		}
		log(String.format("  PyTorch mean: %.6f, max abs: %.6f", sumPy / pytorchOProj.length, maxAbsPy));

		stateDict.destroy();
	}

	private void compareOutputs(float[] expected, PackedCollection actual, int showN) {
		int minLen = (int) Math.min(expected.length, actual.getShape().getTotalSize());

		double maxDiff = 0;
		int maxDiffIdx = -1;
		double sumDiff = 0;

		for (int i = 0; i < minLen; i++) {
			double diff = Math.abs(actual.toDouble(i) - expected[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
			sumDiff += diff;
		}

		double meanDiff = sumDiff / minLen;
		log(String.format("  Mean diff: %.6f, Max diff: %.6f at index %d", meanDiff, maxDiff, maxDiffIdx));
		log(String.format("  Compared %d values (expected %d, actual %d)", minLen, expected.length, (int) actual.getShape().getTotalSize()));

		log(String.format("  First %d values:", showN));
		for (int i = 0; i < Math.min(showN, minLen); i++) {
			log(String.format("    [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, expected[i], actual.toDouble(i), actual.toDouble(i) - expected[i]));
		}

		if (maxDiff < 1e-4) {
			log("  [EXCELLENT] Match within 1e-4");
		} else if (maxDiff < 0.01) {
			log("  [GOOD] Match within 0.01");
		} else if (maxDiff < 0.1) {
			log("  [WARNING] Divergence < 0.1");
		} else {
			log("  [FAIL] Significant divergence: " + maxDiff);
		}
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

	private float[] loadReferenceOutput(String filename) throws Exception {
		try (FileChannel channel = FileChannel.open(
				Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
			ByteBuffer sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			channel.read(sizeBuffer);
			sizeBuffer.flip();
			int size = sizeBuffer.getInt();

			ByteBuffer dataBuffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN);
			channel.read(dataBuffer);
			dataBuffer.flip();

			float[] data = new float[size];
			for (int i = 0; i < size; i++) {
				data[i] = dataBuffer.getFloat();
			}
			return data;
		}
	}
}
