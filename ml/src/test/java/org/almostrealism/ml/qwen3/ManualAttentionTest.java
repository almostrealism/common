package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Manually construct attention-like structure step by step,
 * following exactly what attention() does, to find where position breaks.
 */
public class ManualAttentionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testManualAttentionStructure() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/manual_attention_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  MANUAL ATTENTION STRUCTURE TEST");
		log("=".repeat(70) + "\n");

		// Same config as MinimalAttentionTest
		int dim = 8;
		int heads = 2;
		int kvHeads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int kvDim = dim * kvHeads / heads;

		log("Config: dim=" + dim + ", heads=" + heads + ", headSize=" + headSize);

		// Create weights
		PackedCollection rmsAttWeight = ones(dim);
		PackedCollection wq = createIdentity(dim, dim);
		PackedCollection wk = createIdentity(kvDim, dim);
		PackedCollection wv = createIdentity(kvDim, dim);
		PackedCollection wo = createIdentity(dim, dim);

		// Create RoPE frequencies
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		// Create caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, kvHeads, headSize));
		keyCache.clear();
		valueCache.clear();

		// Create position
		PackedCollection position = new PackedCollection(1);

		// Create DynamicCollectionProducer ONCE - like attention() receives it
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		log("\nBuilding MANUAL attention block...");

		// Build EXACTLY like attention() does:
		var inputShape = shape(1, dim);
		SequentialBlock attention = new SequentialBlock(inputShape);

		// 1. RMSNorm
		attention.add(rmsnorm(inputShape, rmsAttWeight, 1e-6));

		// 2. Keys branch
		var kvHeadShapeComplex = shape(kvHeads, headSize / 2, 2);
		SequentialBlock keys = attention.branch();
		keys.add(dense(wk));  // Linear projection
		keys.add(reshape(shape(kvDim), kvHeadShapeComplex));
		keys.add(ropeRotation(kvHeadShapeComplex, freqCis, dynamicPosition));
		keys.andThen(into(keyCache.reshape(shape(seqLen, kvDim)), dynamicPosition));

		// 3. Values branch
		SequentialBlock values = attention.branch();
		values.add(dense(wv));  // Linear projection
		values.andThen(into(valueCache.reshape(shape(seqLen, kvDim)), dynamicPosition));

		// 4. Queries (main path)
		var headShapeComplex = shape(heads, headSize / 2, 2);
		var headShape = shape(heads, headSize);
		var attentionShape = shape(heads, seqLen).traverseEach();

		attention.add(dense(wq));  // Q projection
		attention.add(reshape(shape(dim), headShapeComplex));
		attention.add(ropeRotation(headShapeComplex, freqCis, dynamicPosition));
		attention.add(reshape(headShapeComplex, headShape));
		attention.add(attentionKeys(headShape, p(keyCache)));

		// 5. Causal mask
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);

		attention.add(layer("causal_mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		// 6. Softmax, attention values, output projection
		attention.add(softmax(attentionShape, true));
		attention.add(attentionValues(attentionShape, p(valueCache)));
		attention.add(dense(wo));

		// Restore shape
		attention.reshape(inputShape);

		// Build and compile model
		Model model = new Model(inputShape);
		model.add(attention);

		log("Compiling model...");
		CompiledModel compiled = model.compile();

		// Test
		PackedCollection input = new PackedCollection(inputShape);
		for (int i = 0; i < dim; i++) {
			input.setMem(i, 0.1 * (i + 1));
		}
		log("Input: " + formatArray(input, 0, dim));

		log("\n=== Testing Positions ===");
		double[][] outputs = new double[5][dim];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = compiled.forward(input);

			for (int i = 0; i < dim; i++) {
				outputs[pos][i] = result.toDouble(i);
			}
			log("Position " + pos + ": " + formatArray(result, 0, dim));
		}

		// Compare
		log("\n=== Comparing Outputs ===");
		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			double maxDiff = 0;
			for (int i = 0; i < dim; i++) {
				double diff = Math.abs(outputs[pos][i] - outputs[0][i]);
				maxDiff = Math.max(maxDiff, diff);
				if (diff > 1e-6) allSame = false;
			}
			log(String.format("Position 0 vs %d: max diff = %.9f", pos, maxDiff));
		}

		if (allSame) {
			log("\n[FAIL] Manual attention - position NOT working!");
		} else {
			log("\n[PASS] Manual attention - position IS working!");
		}

		Assert.assertFalse("Position should affect manual attention output", allSame);
	}

	private PackedCollection ones(int size) {
		PackedCollection c = new PackedCollection(size);
		for (int i = 0; i < size; i++) c.setMem(i, 1.0);
		return c;
	}

	private PackedCollection createIdentity(int rows, int cols) {
		PackedCollection c = new PackedCollection(shape(rows, cols));
		for (int i = 0; i < rows; i++) {
			for (int j = 0; j < cols; j++) {
				c.setMem(i * cols + j, (i == j) ? 0.5 : 0.01);
			}
		}
		return c;
	}

	private PackedCollection createFreqCis(int seqLen, int headSize) {
		double theta = 1000000.0;
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

	private String formatArray(PackedCollection c, int start, int count) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = start; i < start + count && i < c.getShape().getTotalSize(); i++) {
			if (i > start) sb.append(", ");
			sb.append(String.format("%.6f", c.toDouble(i)));
		}
		sb.append("]");
		return sb.toString();
	}
}
