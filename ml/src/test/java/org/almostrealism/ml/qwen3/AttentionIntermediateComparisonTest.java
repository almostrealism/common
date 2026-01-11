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
 * Compare Java's attention intermediates with PyTorch reference at position 1.
 *
 * This test helps identify where the attention computation diverges.
 */
public class AttentionIntermediateComparisonTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void compareAttentionIntermediates() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/attention_intermediate_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Intermediate Comparison: Java vs PyTorch");
		log("===================================================\n");

		// Load PyTorch reference data
		log("Loading PyTorch reference intermediates...");

		// Attention scores shape: [1, 14, 2, 2] = 56 values
		// But we only care about position 1 query (last 2 values per head)
		double[] pytorchAttnScoresBeforeMask = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_before_mask.bin");
		double[] pytorchAttnScoresAfterMask = loadBinaryLogits(REFERENCE_DIR + "/attn_scores_after_mask.bin");
		double[] pytorchAttnWeightsSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");

		log("  attn_scores_before_mask: " + pytorchAttnScoresBeforeMask.length + " values");
		log("  attn_scores_after_mask: " + pytorchAttnScoresAfterMask.length + " values");
		log("  attn_weights_softmax: " + pytorchAttnWeightsSoftmax.length + " values");

		// Print PyTorch attention scores for position 1 query
		// Shape is [batch=1, heads=14, query_positions=2, key_positions=2]
		// Position 1 query is at index [0, head, 1, :]
		log("\n=== PyTorch Attention Scores (Position 1 Query) ===");
		int heads = 14;
		for (int h = 0; h < heads; h++) {
			// Index: h * 4 + 2 (pos1 query, pos0 key), h * 4 + 3 (pos1 query, pos1 key)
			int idx0 = h * 4 + 2;
			int idx1 = h * 4 + 3;
			double score0 = pytorchAttnScoresBeforeMask[idx0];
			double score1 = pytorchAttnScoresBeforeMask[idx1];
			log(String.format("  Head %2d: pos0=%.4f, pos1=%.4f", h, score0, score1));
		}

		log("\n=== PyTorch Attention Weights After Softmax (Position 1 Query) ===");
		for (int h = 0; h < heads; h++) {
			int idx0 = h * 4 + 2;
			int idx1 = h * 4 + 3;
			double w0 = pytorchAttnWeightsSoftmax[idx0];
			double w1 = pytorchAttnWeightsSoftmax[idx1];
			log(String.format("  Head %2d: w0=%.6f, w1=%.6f", h, w0, w1));
		}

		// Load Java model and run
		log("\n=== Loading Java Model ===");
		Qwen3Config config = new Qwen3Config(
				896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
		);
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
		Qwen3 qwen3 = new Qwen3(config, stateDict, tokenizer);

		org.almostrealism.model.CompiledModel compiledModel = qwen3.getCompiledModel();
		PackedCollection embeddings = qwen3.getTokenEmbeddings();
		PackedCollection position = qwen3.getPosition();

		// Run position 0 first
		log("\nRunning position 0...");
		position.setMem(0, 0.0);
		PackedCollection input0 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input0.setMem(i, embeddings.toDouble(9707 * config.dim + i));
		}
		PackedCollection logits0 = compiledModel.forward(input0);

		int top0 = findTop1(logits0);
		log("Position 0 top token: " + top0 + " (logit: " + logits0.toDouble(top0) + ")");

		// Run position 1
		log("\nRunning position 1...");
		position.setMem(0, 1.0);
		PackedCollection input1 = new PackedCollection(compiledModel.getInputShape());
		for (int i = 0; i < config.dim; i++) {
			input1.setMem(i, embeddings.toDouble(271 * config.dim + i));
		}
		PackedCollection logits1 = compiledModel.forward(input1);

		int javaTop = findTop1(logits1);
		log("Position 1 top token: " + javaTop + " (logit: " + logits1.toDouble(javaTop) + ")");

		// Load PyTorch final logits for comparison
		double[] pytorchLogits = loadBinaryLogits(REFERENCE_DIR + "/logits_pos1.bin");
		int pytorchTop = findTop1(pytorchLogits);
		log("PyTorch position 1 top token: " + pytorchTop + " (logit: " + pytorchLogits[pytorchTop] + ")");

		// Compare token 40 (PyTorch's top)
		log("\n=== Key Token Comparison ===");
		log(String.format("Token 40 ('I'): Java=%.4f, PyTorch=%.4f, diff=%.4f",
				logits1.toDouble(40), pytorchLogits[40],
				logits1.toDouble(40) - pytorchLogits[40]));

		// Compare token 14374 (Java's top)
		log(String.format("Token %d (Java top): Java=%.4f, PyTorch=%.4f, diff=%.4f",
				javaTop, logits1.toDouble(javaTop), pytorchLogits[javaTop],
				logits1.toDouble(javaTop) - pytorchLogits[javaTop]));

		log("\n=== Analysis ===");
		if (javaTop == pytorchTop) {
			log("SUCCESS: Java and PyTorch agree on top token!");
		} else {
			log("MISMATCH: Java top=" + javaTop + ", PyTorch top=" + pytorchTop);
			log("\nPossible causes:");
			log("1. Attention score computation differs");
			log("2. RoPE encoding differs at position 1");
			log("3. KV cache read/write issue");
			log("4. Softmax numerical differences");
		}

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

	private int findTop1(PackedCollection logits) {
		int size = logits.getShape().getTotalSize();
		int maxIdx = 0;
		double maxVal = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < size; i++) {
			double val = logits.toDouble(i);
			if (val > maxVal) {
				maxVal = val;
				maxIdx = i;
			}
		}
		return maxIdx;
	}

	private int findTop1(double[] logits) {
		int maxIdx = 0;
		double maxVal = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < logits.length; i++) {
			if (logits[i] > maxVal) {
				maxVal = logits[i];
				maxIdx = i;
			}
		}
		return maxIdx;
	}
}
