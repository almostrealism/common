package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionFeatures;
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
 * Test the actual attention method from AttentionFeatures to see where it diverges.
 */
public class FullAttentionMethodTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	/** Directory containing exported model weights. */
	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

	/** Directory containing PyTorch reference outputs for comparison. */
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	/**
	 * Test the attention method for layer 0.
	 */
	@Test(timeout = 120000)
	public void testLayer0AttentionMethod() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/results/layer0_attention_method.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 0 FULL ATTENTION METHOD TEST");
		log("=".repeat(70) + "\n");

		runAttentionMethodTest(0);
	}

	/**
	 * Test the attention method for layer 1.
	 */
	@Test(timeout = 120000)
	public void testLayer1AttentionMethod() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/results/layer1_attention_method.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  LAYER 1 FULL ATTENTION METHOD TEST");
		log("=".repeat(70) + "\n");

		runAttentionMethodTest(1);
	}

	/**
	 * Runs the attention method test for a specified layer.
	 *
	 * @param layerNum the layer number to test
	 * @throws Exception if test execution fails
	 */
	private void runAttentionMethodTest(int layerNum) throws Exception {
		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);

		int dim = config.dim;
		int heads = config.headCount;
		int kvHeads = config.kvHeadCount;
		int headSize = dim / heads;

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load weights
		String prefix = "model.layers." + layerNum + ".";
		PackedCollection rmsAttWeight = stateDict.get(prefix + "input_layernorm.weight");
		PackedCollection wq = stateDict.get(prefix + "self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get(prefix + "self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get(prefix + "self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get(prefix + "self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get(prefix + "self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get(prefix + "self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get(prefix + "self_attn.v_proj.bias");
		PackedCollection qkNormQ = stateDict.get(prefix + "self_attn.q_norm.weight");
		PackedCollection qkNormK = stateDict.get(prefix + "self_attn.k_norm.weight");

		log(String.format("Layer %d weights loaded", layerNum));
		log(String.format("  wq shape: %s", wq.getShape()));
		log(String.format("  qkNormQ: %s", qkNormQ != null ? qkNormQ.getShape().toString() : "null"));

		// Load input
		String inputFile = layerNum == 0 ? "embedding_output.bin" : "after_layer_" + (layerNum - 1) + ".bin";
		float[] pytorchInput = loadReferenceOutput(inputFile);
		PackedCollection input = new PackedCollection(shape(1, dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, pytorchInput[i]);
		}

		log(String.format("\nInput (from %s):", inputFile));
		log("  First 5: " + formatFirst(input, 5));

		// Create RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(config.seqLen, headSize, config.ropeTheta);
		PackedCollection position = new PackedCollection(shape(1));
		position.setMem(0, 0.0);

		// Create the attention block using the actual method
		Model attModel = new Model(shape(1, dim));
		attModel.add(attention(heads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
				bk, bv, bq, qkNormQ, qkNormK, cp(freqCis), p(position), 1e-6));

		log("\nCompiling and running attention...");
		PackedCollection output = attModel.compile().forward(input);

		log("\nAttention output:");
		log("  Shape: " + output.getShape());
		log("  First 10: " + formatFirst(output, 10));

		// Compare with PyTorch reference
		String refFile = "layer" + layerNum + "_o_proj.bin";
		float[] pytorchOProj = loadReferenceOutput(refFile);

		log("\n=== Comparison with PyTorch ===");
		double maxDiff = 0;
		int maxDiffIdx = -1;
		for (int i = 0; i < Math.min(pytorchOProj.length, (int) output.getShape().getTotalSize()); i++) {
			double diff = Math.abs(output.toDouble(i) - pytorchOProj[i]);
			if (diff > maxDiff) {
				maxDiff = diff;
				maxDiffIdx = i;
			}
		}

		log(String.format("  Max diff: %.6f at index %d", maxDiff, maxDiffIdx));
		log("\n  First 10 comparison:");
		for (int i = 0; i < Math.min(10, pytorchOProj.length); i++) {
			log(String.format("    [%d] PyTorch=%.6f, Java=%.6f, diff=%.6f",
					i, pytorchOProj[i], output.toDouble(i), output.toDouble(i) - pytorchOProj[i]));
		}

		String status;
		if (maxDiff < 1e-4) status = "[EXCELLENT]";
		else if (maxDiff < 0.01) status = "[GOOD]";
		else if (maxDiff < 0.1) status = "[WARNING]";
		else status = "[FAIL]";
		log(String.format("  Status: %s", status));

		stateDict.destroy();
	}

	/**
	 * Computes RoPE frequency tensor for the given sequence length and head size.
	 *
	 * @param seqLen the sequence length
	 * @param headSize the head size
	 * @param theta the theta parameter for RoPE
	 * @return the frequency tensor with shape (seqLen, headSize/2, 2)
	 */
	private PackedCollection computeRopeFreqs(int seqLen, int headSize, double theta) {
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
		}

		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				CollectionFeatures.getInstance().a(CollectionFeatures.getInstance().cp(freqCis.range(new TraversalPolicy(1), idx)), CollectionFeatures.getInstance().c(Math.cos(angle))).get().run();
				CollectionFeatures.getInstance().a(CollectionFeatures.getInstance().cp(freqCis.range(new TraversalPolicy(1), idx + 1)), CollectionFeatures.getInstance().c(Math.sin(angle))).get().run();
			}
		}
		return freqCis;
	}

	/**
	 * Formats the first n elements of a collection as a string.
	 *
	 * @param c the collection to format
	 * @param n the number of elements to include
	 * @return formatted string representation
	 */
	private String formatFirst(PackedCollection c, int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(n, c.getShape().getTotalSize()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Loads reference output data from a binary file.
	 *
	 * @param filename the name of the reference file
	 * @return array of float values from the file
	 * @throws Exception if file reading fails
	 */
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
