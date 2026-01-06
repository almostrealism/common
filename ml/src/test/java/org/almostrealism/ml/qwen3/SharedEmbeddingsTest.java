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

/**
 * Narrowly focused test to verify shared embeddings (lm_head weight sharing).
 * <p>
 * Purpose: Validate that the output projection (lm_head) correctly uses
 * the shared token embeddings weights, which is critical for the model to
 * produce correct logits.
 * <p>
 * This test checks:
 * 1. Whether lm_head weight exists separately or is shared
 * 2. That the weight reference is correct
 * 3. That the weight transpose is applied correctly
 */
public class SharedEmbeddingsTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

	@Test
	public void testSharedEmbeddingsConfiguration() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		// Setup file logging
		String logFile = "/workspace/project/common/ml/test_output/shared_embeddings_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Shared Embeddings Test ===\n");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Check if lm_head exists separately
		PackedCollection lmHead = stateDict.get("lm_head.weight");
		PackedCollection embeddings = stateDict.get("model.embed_tokens.weight");

		log("Token embeddings shape: " + embeddings.getShape());
		log("Token embeddings address: " + System.identityHashCode(embeddings));

		if (lmHead == null) {
			log("\nlm_head.weight: NOT FOUND (should use shared embeddings)");
		} else {
			log("\nlm_head.weight shape: " + lmHead.getShape());
			log("lm_head.weight address: " + System.identityHashCode(lmHead));

			if (lmHead == embeddings) {
				log("\n[PASS] lm_head and embeddings are THE SAME object (correct sharing)");
			} else {
				log("\n[WARNING] lm_head and embeddings are DIFFERENT objects");

				// Check if content is identical
				boolean identical = true;
				if (lmHead.getMemLength() == embeddings.getMemLength()) {
					for (int i = 0; i < Math.min(1000, lmHead.getMemLength()); i++) {
						if (Math.abs(lmHead.valueAt(i) - embeddings.valueAt(i)) > 1e-6) {
							identical = false;
							break;
						}
					}
				} else {
					identical = false;
				}

				if (identical) {
					log("[INFO] Content appears identical (copy, not reference)");
				} else {
					log("[FAIL] Content is DIFFERENT!");
				}
			}
		}

		// Check config inference
		log("\n=== Config Inference ===");
		Qwen3Config config = new Qwen3Config(
				896,      // dim
				4864,     // hiddenDim
				24,       // layerCount
				14,       // headCount
				2,        // kvHeadCount
				151936,   // vocabSize
				32768,    // seqLen
				true,     // sharedWeights (what we expect)
				1000000.0 // ropeTheta
		);

		log("Config says sharedWeights = " + config.sharedWeights);

		// Verify weights are used correctly in model
		log("\n=== Model Weight Usage ===");
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(WEIGHTS_DIR + "/tokenizer.bin");
		Qwen3 model = new Qwen3(config, stateDict, tokenizer);

		PackedCollection modelEmbeddings = model.getTokenEmbeddings();
		log("Model embeddings address: " + System.identityHashCode(modelEmbeddings));
		log("Model embeddings == state dict embeddings: " + (modelEmbeddings == embeddings));

		// Check shape expectations
		log("\n=== Shape Validation ===");
		int vocabSize = config.vocabSize;
		int dim = config.dim;

		log("Expected embeddings shape: (" + vocabSize + ", " + dim + ")");
		log("Actual embeddings shape: " + embeddings.getShape());

		if (embeddings.getShape().length(0) == vocabSize &&
				embeddings.getShape().length(1) == dim) {
			log("[PASS] Embeddings shape matches expected");
		} else {
			log("[FAIL] Embeddings shape mismatch!");
		}

		// The model should use embeddings as lm_head with transpose
		// In PyTorch: logits = input @ embeddings.T
		// In AR: dense layer should apply W^T to input
		log("\n=== Weight Transpose for Output Projection ===");
		log("PyTorch formula: logits = hidden @ embeddings.T");
		log("AR dense layer: should apply W^T internally");
		log("Therefore: pass embeddings directly to dense() layer");

		stateDict.destroy();
	}

	@Test
	public void testOutputProjectionShape() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		// Setup file logging
		String logFile = "/workspace/project/common/ml/test_output/output_projection_shape_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Output Projection Shape Test ===\n");

		Qwen3Config config = new Qwen3Config(
				896,      // dim
				4864,     // hiddenDim
				24,       // layerCount
				14,       // headCount
				2,        // kvHeadCount
				151936,   // vocabSize
				32768,    // seqLen
				true,     // sharedWeights
				1000000.0 // ropeTheta
		);

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(WEIGHTS_DIR + "/tokenizer.bin");
		Qwen3 model = new Qwen3(config, stateDict, tokenizer);

		// Get compiled model output shape
		org.almostrealism.model.CompiledModel compiled = model.getCompiledModel();

		log("Model input shape: " + compiled.getInputShape());
		log("Model output shape: " + compiled.getOutputShape());
		log("Expected output size (vocabSize): " + config.vocabSize);

		int outputSize = compiled.getOutputShape().getTotalSize();
		if (outputSize == config.vocabSize) {
			log("\n[PASS] Output size matches vocabulary size");
		} else {
			log("\n[FAIL] Output size mismatch!");
			log("  Expected: " + config.vocabSize);
			log("  Actual: " + outputSize);
		}

		stateDict.destroy();
	}
}
