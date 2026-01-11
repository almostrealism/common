package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compare Java's position 1 logits with PyTorch reference.
 * PyTorch exports logits from processing [9707, 271] at position 1.
 */
public class Position1LogitsComparisonTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
	private static final String PYTORCH_LOGITS_PATH = "/workspace/project/common/ml/qwen3_reference/position1_debug/logits_pos1.bin";

	@Test
	public void comparePosition1Logits() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/position1_logits_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Position 1 Logits Comparison: Java vs PyTorch");
		log("===================================================\n");

		// Load PyTorch reference logits
		log("Loading PyTorch reference logits...");
		double[] pytorchLogits = loadBinaryLogits(PYTORCH_LOGITS_PATH);
		log("  Loaded " + pytorchLogits.length + " logits from PyTorch");

		// Load and run Java model
		log("\nLoading Qwen3 model...");
		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 qwen3 = new Qwen3(config, stateDict, tokenizer);

		org.almostrealism.model.CompiledModel compiledModel = qwen3.getCompiledModel();
		PackedCollection embeddings = qwen3.getTokenEmbeddings();
		PackedCollection position = qwen3.getPosition();

		log("Model loaded.\n");

		// Run position 0 first to populate cache
		log("=== Position 0: Token 9707 (Hello) ===");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input0.setMem(i, embeddings.toDouble(9707 * config.dim + i));
		}
		PackedCollection logits0 = compiledModel.forward(input0);

		int[] top5_pos0 = findTopK(logits0, 5);
		log("Position 0 Top 5:");
		for (int i = 0; i < 5; i++) {
			int idx = top5_pos0[i];
			log(String.format("  %d. Token %d (logit: %.4f)", i + 1, idx, logits0.toDouble(idx)));
		}

		// Run position 1
		log("\n=== Position 1: Token 271 (\\n\\n) ===");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input1.setMem(i, embeddings.toDouble(271 * config.dim + i));
		}
		PackedCollection logits1 = compiledModel.forward(input1);

		// Compare with PyTorch
		log("\n=== Comparison ===");

		// Java top 5
		int[] javaTop5 = findTopK(logits1, 5);
		log("\nJava Top 5:");
		for (int i = 0; i < 5; i++) {
			int idx = javaTop5[i];
			log(String.format("  %d. Token %d (logit: %.4f, PyTorch logit: %.4f)",
					i + 1, idx, logits1.toDouble(idx), pytorchLogits[idx]));
		}

		// PyTorch top 5
		int[] pytorchTop5 = findTopK(pytorchLogits, 5);
		log("\nPyTorch Top 5:");
		for (int i = 0; i < 5; i++) {
			int idx = pytorchTop5[i];
			log(String.format("  %d. Token %d (logit: %.4f, Java logit: %.4f)",
					i + 1, idx, pytorchLogits[idx], logits1.toDouble(idx)));
		}

		// Statistics
		log("\n=== Error Statistics ===");
		double sumAbsDiff = 0;
		double maxAbsDiff = 0;
		int maxDiffIdx = 0;
		double sumSqDiff = 0;

		int count = Math.min(logits1.getShape().getTotalSize(), pytorchLogits.length);
		for (int i = 0; i < count; i++) {
			double diff = logits1.toDouble(i) - pytorchLogits[i];
			double absDiff = Math.abs(diff);
			sumAbsDiff += absDiff;
			sumSqDiff += diff * diff;
			if (absDiff > maxAbsDiff) {
				maxAbsDiff = absDiff;
				maxDiffIdx = i;
			}
		}

		double meanAbsDiff = sumAbsDiff / count;
		double rmse = Math.sqrt(sumSqDiff / count);

		log(String.format("Mean Absolute Difference: %.6f", meanAbsDiff));
		log(String.format("RMSE: %.6f", rmse));
		log(String.format("Max Absolute Difference: %.6f at token %d", maxAbsDiff, maxDiffIdx));
		log(String.format("  Java: %.6f, PyTorch: %.6f", logits1.toDouble(maxDiffIdx), pytorchLogits[maxDiffIdx]));

		// Check key tokens
		log("\n=== Key Token Comparison ===");
		int tokenI = 40;  // PyTorch's top prediction ("I")
		log(String.format("Token 40 ('I'): Java=%.4f, PyTorch=%.4f, diff=%.4f",
				logits1.toDouble(tokenI), pytorchLogits[tokenI],
				logits1.toDouble(tokenI) - pytorchLogits[tokenI]));

		int tokenJava = javaTop5[0];  // Java's top prediction
		log(String.format("Token %d (Java top): Java=%.4f, PyTorch=%.4f, diff=%.4f",
				tokenJava, logits1.toDouble(tokenJava), pytorchLogits[tokenJava],
				logits1.toDouble(tokenJava) - pytorchLogits[tokenJava]));

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private double[] loadBinaryLogits(String path) throws Exception {
		try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
			byte[] sizeBytes = new byte[4];
			dis.readFully(sizeBytes);
			int size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();

			double[] logits = new double[size];
			byte[] floatBytes = new byte[4];
			for (int i = 0; i < size; i++) {
				dis.readFully(floatBytes);
				logits[i] = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
			}
			return logits;
		}
	}

	private int[] findTopK(PackedCollection logits, int k) {
		int size = logits.getShape().getTotalSize();
		int[] indices = new int[k];
		double[] values = new double[k];
		java.util.Arrays.fill(values, Double.NEGATIVE_INFINITY);

		for (int i = 0; i < size; i++) {
			double val = logits.toDouble(i);
			for (int j = 0; j < k; j++) {
				if (val > values[j]) {
					for (int m = k - 1; m > j; m--) {
						indices[m] = indices[m - 1];
						values[m] = values[m - 1];
					}
					indices[j] = i;
					values[j] = val;
					break;
				}
			}
		}
		return indices;
	}

	private int[] findTopK(double[] logits, int k) {
		int[] indices = new int[k];
		double[] values = new double[k];
		java.util.Arrays.fill(values, Double.NEGATIVE_INFINITY);

		for (int i = 0; i < logits.length; i++) {
			double val = logits[i];
			for (int j = 0; j < k; j++) {
				if (val > values[j]) {
					for (int m = k - 1; m > j; m--) {
						indices[m] = indices[m - 1];
						values[m] = values[m - 1];
					}
					indices[j] = i;
					values[j] = val;
					break;
				}
			}
		}
		return indices;
	}
}
