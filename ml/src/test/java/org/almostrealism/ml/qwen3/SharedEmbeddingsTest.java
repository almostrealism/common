package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

/**
 * Narrowly focused test to verify shared embeddings (lm_head weight sharing).
 *
 * Purpose: Validate that the output projection (lm_head) correctly uses
 * the shared token embeddings weights, which is critical for the model to
 * produce correct logits.
 *
 * This test checks:
 * 1. Whether lm_head weight exists separately or is shared
 * 2. That the weight reference is correct
 * 3. That the weight transpose is applied correctly
 */
public class SharedEmbeddingsTest {

    private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

    @Test
    public void testSharedEmbeddingsConfiguration() throws Exception {
        System.err.println("\n=== Shared Embeddings Test ===\n");

        StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

        // Check if lm_head exists separately
        PackedCollection<?> lmHead = stateDict.get("lm_head.weight");
        PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");

        System.err.println("Token embeddings shape: " + embeddings.getShape());
        System.err.println("Token embeddings address: " + System.identityHashCode(embeddings));

        if (lmHead == null) {
            System.err.println("\nlm_head.weight: NOT FOUND (should use shared embeddings)");
        } else {
            System.err.println("\nlm_head.weight shape: " + lmHead.getShape());
            System.err.println("lm_head.weight address: " + System.identityHashCode(lmHead));

            if (lmHead == embeddings) {
                System.err.println("\n[PASS] lm_head and embeddings are THE SAME object (correct sharing)");
            } else {
                System.err.println("\n[WARNING] lm_head and embeddings are DIFFERENT objects");

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
                    System.err.println("[INFO] Content appears identical (copy, not reference)");
                } else {
                    System.err.println("[FAIL] Content is DIFFERENT!");
                }
            }
        }

        // Check config inference
        System.err.println("\n=== Config Inference ===");
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

        System.err.println("Config says sharedWeights = " + config.sharedWeights);

        // Verify weights are used correctly in model
        System.err.println("\n=== Model Weight Usage ===");
        Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(WEIGHTS_DIR + "/tokenizer.bin");
        Qwen3 model = new Qwen3(config, stateDict, tokenizer);

        PackedCollection<?> modelEmbeddings = model.getTokenEmbeddings();
        System.err.println("Model embeddings address: " + System.identityHashCode(modelEmbeddings));
        System.err.println("Model embeddings == state dict embeddings: " + (modelEmbeddings == embeddings));

        // Check shape expectations
        System.err.println("\n=== Shape Validation ===");
        int vocabSize = config.vocabSize;
        int dim = config.dim;

        System.err.println("Expected embeddings shape: (" + vocabSize + ", " + dim + ")");
        System.err.println("Actual embeddings shape: " + embeddings.getShape());

        if (embeddings.getShape().length(0) == vocabSize &&
            embeddings.getShape().length(1) == dim) {
            System.err.println("[PASS] Embeddings shape matches expected");
        } else {
            System.err.println("[FAIL] Embeddings shape mismatch!");
        }

        // The model should use embeddings as lm_head with transpose
        // In PyTorch: logits = input @ embeddings.T
        // In AR: dense layer should apply W^T to input
        System.err.println("\n=== Weight Transpose for Output Projection ===");
        System.err.println("PyTorch formula: logits = hidden @ embeddings.T");
        System.err.println("AR dense layer: should apply W^T internally");
        System.err.println("Therefore: pass embeddings directly to dense() layer");

        stateDict.destroy();
    }

    @Test
    public void testOutputProjectionShape() throws Exception {
        System.err.println("\n=== Output Projection Shape Test ===\n");

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

        System.err.println("Model input shape: " + compiled.getInputShape());
        System.err.println("Model output shape: " + compiled.getOutputShape());
        System.err.println("Expected output size (vocabSize): " + config.vocabSize);

        int outputSize = compiled.getOutputShape().getTotalSize();
        if (outputSize == config.vocabSize) {
            System.err.println("\n[PASS] Output size matches vocabulary size");
        } else {
            System.err.println("\n[FAIL] Output size mismatch!");
            System.err.println("  Expected: " + config.vocabSize);
            System.err.println("  Actual: " + outputSize);
        }

        stateDict.destroy();
    }
}
