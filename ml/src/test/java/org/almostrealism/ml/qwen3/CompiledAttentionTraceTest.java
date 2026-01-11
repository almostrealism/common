package org.almostrealism.ml.qwen3;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
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

import io.almostrealism.collect.TraversalPolicy;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Trace through the compiled attention model and compare intermediate values with PyTorch.
 *
 * This test builds a minimal attention block and traces values at each step.
 */
public class CompiledAttentionTraceTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";
	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/position1_debug";

	@Test
	public void traceCompiledAttention() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/compiled_attention_trace.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Compiled Attention Trace");
		log("===================================================\n");

		// Config from Qwen2.5-0.5B
		int dim = 896;
		int heads = 14;
		int kvHeads = 2;
		int headSize = dim / heads;  // 64
		int kvDim = kvHeads * headSize;  // 128
		int seqLen = 32768;
		double ropeTheta = 1000000.0;
		double epsilon = 1e-6;

		// Load PyTorch reference data
		double[] pytorchVProj = loadBinaryLogits(REFERENCE_DIR + "/v_proj_both_positions.bin");
		double[] pytorchVExpanded = loadBinaryLogits(REFERENCE_DIR + "/v_expanded_gqa.bin");
		double[] pytorchSoftmax = loadBinaryLogits(REFERENCE_DIR + "/attn_weights_softmax.bin");
		double[] pytorchAttnOutput = loadBinaryLogits(REFERENCE_DIR + "/attn_output_pos1.bin");
		double[] pytorchOProj = loadBinaryLogits(REFERENCE_DIR + "/o_proj_pos1.bin");

		log("Loaded PyTorch reference data");

		// Load weights
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection rmsAttWeight = stateDict.get("model.layers.0.input_layernorm.weight");
		PackedCollection wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
		PackedCollection wk = stateDict.get("model.layers.0.self_attn.k_proj.weight");
		PackedCollection wv = stateDict.get("model.layers.0.self_attn.v_proj.weight");
		PackedCollection wo = stateDict.get("model.layers.0.self_attn.o_proj.weight");
		PackedCollection bq = stateDict.get("model.layers.0.self_attn.q_proj.bias");
		PackedCollection bk = stateDict.get("model.layers.0.self_attn.k_proj.bias");
		PackedCollection bv = stateDict.get("model.layers.0.self_attn.v_proj.bias");

		// Compute RoPE frequencies
		PackedCollection freqCis = computeRopeFreqs(seqLen, headSize, ropeTheta);

		// Position collection
		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> positionProducer = new DynamicCollectionProducer(position.getShape(), args -> position);

		// Build minimal test: just attentionValues with controlled inputs
		log("\n=== Building Isolated attentionValues Test ===");

		// We'll use PyTorch's softmax and V projection directly to isolate attentionValues
		// 1. Create a value cache with PyTorch's V projection values
		PackedCollection valueCache = new PackedCollection(seqLen, kvHeads, headSize);
		valueCache.clear();

		// Store PyTorch V projection values
		for (int pos = 0; pos < 2; pos++) {
			for (int kv = 0; kv < kvHeads; kv++) {
				for (int d = 0; d < headSize; d++) {
					int srcIdx = pos * kvDim + kv * headSize + d;
					int dstIdx = pos * (kvHeads * headSize) + kv * headSize + d;
					valueCache.setMem(dstIdx, pytorchVProj[srcIdx]);
				}
			}
		}

		log("Stored PyTorch V projection values in cache");
		log("  Cache[0,0,:5] = " + formatValues(valueCache, 0, 5));
		log("  Cache[1,0,:5] = " + formatValues(valueCache, kvHeads * headSize, 5));

		// Create softmax input with PyTorch's values
		// PyTorch softmax shape: [batch=1, heads=14, query_len=2, key_len=2]
		// For position 1 query: we need [heads, key_len=2]
		PackedCollection softmaxInput = new PackedCollection(shape(heads, 2));
		for (int h = 0; h < heads; h++) {
			int idx0 = h * 4 + 2;  // pos1 query, key0
			int idx1 = h * 4 + 3;  // pos1 query, key1
			softmaxInput.setMem(h * 2 + 0, pytorchSoftmax[idx0]);
			softmaxInput.setMem(h * 2 + 1, pytorchSoftmax[idx1]);
		}

		log("\nSoftmax input (PyTorch values):");
		log("  Head 0: [" + softmaxInput.toDouble(0) + ", " + softmaxInput.toDouble(1) + "]");
		log("  Head 1: [" + softmaxInput.toDouble(2) + ", " + softmaxInput.toDouble(3) + "]");

		// Build the attentionValues layer
		// Need to subset the cache to just the first 2 positions
		PackedCollection valueCacheSubset = new PackedCollection(shape(2, kvHeads, headSize));
		for (int i = 0; i < 2 * kvHeads * headSize; i++) {
			valueCacheSubset.setMem(i, valueCache.toDouble(i));
		}

		log("\n=== Testing attentionValues Computation ===");

		// Build the attentionValues layer with PyTorch softmax input
		CollectionProducer attnValuesComp = attentionValuesComputation(
				softmaxInput, p(valueCacheSubset), heads, kvHeads, headSize, 2);

		// Evaluate
		log("\nEvaluating attentionValues with PyTorch inputs...");
		PackedCollection attnValuesResult = attnValuesComp.get().evaluate();

		log("\nJava attentionValues output (first 10):");
		log("  " + formatValues(attnValuesResult, 0, 10));
		log("\nPyTorch attn_output_pos1 (first 10):");
		log("  " + formatPyTorchValues(pytorchAttnOutput, 0, 10));

		// Compare
		double maxAttnDiff = 0;
		for (int i = 0; i < dim; i++) {
			double diff = Math.abs(attnValuesResult.toDouble(i) - pytorchAttnOutput[i]);
			maxAttnDiff = Math.max(maxAttnDiff, diff);
		}
		log("\nMax attention output difference: " + String.format("%.6f", maxAttnDiff));

		if (maxAttnDiff < 0.001) {
			log("[OK] attentionValues matches PyTorch!");
		} else {
			log("[MISMATCH] attentionValues differs from PyTorch!");

			// Debug: trace through what attentionValues should produce
			log("\n=== Manual Calculation ===");
			double[] expected = new double[dim];
			for (int h = 0; h < heads; h++) {
				int kvHead = h / (heads / kvHeads);
				for (int d = 0; d < headSize; d++) {
					double sum = 0;
					for (int pos = 0; pos < 2; pos++) {
						double sw = softmaxInput.toDouble(h * 2 + pos);
						double v = valueCacheSubset.toDouble(pos * kvHeads * headSize + kvHead * headSize + d);
						sum += sw * v;
					}
					expected[h * headSize + d] = sum;
				}
			}
			log("Expected (first 10): " + formatDoubleArray(expected, 0, 10));
			log("Actual   (first 10): " + formatValues(attnValuesResult, 0, 10));
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	/**
	 * Build the attentionValues computation for testing with actual softmax input.
	 */
	private CollectionProducer attentionValuesComputation(
			PackedCollection softmaxInput, Producer<PackedCollection> values,
			int heads, int kvHeads, int headSize, int seqLength) {

		int dim = heads * headSize;
		int headsPerKvGroup = heads / kvHeads;

		// GQA expansion
		Producer<PackedCollection> expandedValues =
				expandValuesForGQA(values, seqLength, kvHeads, heads, headSize, headsPerKvGroup);

		// Same computation as attentionValues
		Producer<PackedCollection> v = reshape(shape(seqLength, dim), expandedValues);
		v = enumerate(1, 1, v).reshape(shape(heads, headSize, seqLength));

		// Input softmax weights with shape [heads, seqLength]
		CollectionProducer a = traverse(1, c(softmaxInput)).repeat(headSize);
		CollectionProducer o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum();
		return o.reshape(shape(1, dim).traverseEach());
	}

	private String formatValues(PackedCollection c, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", c.toDouble(offset + i)));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatPyTorchValues(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatDoubleArray(double[] arr, int offset, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.4f", arr[offset + i]));
		}
		sb.append("]");
		return sb.toString();
	}

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
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}
		return freqCis;
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

}
