package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Compare weight shapes and values between layer 0 and layer 1.
 */
public class WeightShapeComparisonTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

	@Test
	public void compareWeightShapes() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/weight_shape_comparison.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Weight Shape Comparison: Layer 0 vs Layer 1 ===\n");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		String[] weightNames = {
			".input_layernorm.weight",
			".self_attn.q_proj.weight",
			".self_attn.k_proj.weight",
			".self_attn.v_proj.weight",
			".self_attn.o_proj.weight",
			".self_attn.q_proj.bias",
			".self_attn.k_proj.bias",
			".self_attn.v_proj.bias",
			".post_attention_layernorm.weight",
			".mlp.gate_proj.weight",
			".mlp.up_proj.weight",
			".mlp.down_proj.weight"
		};

		for (String weightName : weightNames) {
			String key0 = "model.layers.0" + weightName;
			String key1 = "model.layers.1" + weightName;

			PackedCollection w0 = stateDict.get(key0);
			PackedCollection w1 = stateDict.get(key1);

			log("=== " + weightName + " ===");

			if (w0 == null) {
				log("  Layer 0: NULL");
			} else {
				log("  Layer 0: " + w0.getShape() + " (size=" + w0.getShape().getTotalSize() + ")");
			}

			if (w1 == null) {
				log("  Layer 1: NULL");
			} else {
				log("  Layer 1: " + w1.getShape() + " (size=" + w1.getShape().getTotalSize() + ")");
			}

			if (w0 != null && w1 != null) {
				if (w0.getShape().getTotalSize() == w1.getShape().getTotalSize()) {
					log("  [OK] Same shape");

					// Compare first few values
					double sumDiff = 0;
					double maxDiff = 0;
					int n = (int) Math.min(100, w0.getShape().getTotalSize());
					for (int i = 0; i < n; i++) {
						double diff = Math.abs(w0.toDouble(i) - w1.toDouble(i));
						sumDiff += diff;
						maxDiff = Math.max(maxDiff, diff);
					}
					log(String.format("  Value diff (first %d): mean=%.6f, max=%.6f", n, sumDiff/n, maxDiff));

					// Check for any zero values that might indicate missing data
					int zeros0 = 0, zeros1 = 0;
					for (int i = 0; i < n; i++) {
						if (w0.toDouble(i) == 0) zeros0++;
						if (w1.toDouble(i) == 0) zeros1++;
					}
					log(String.format("  Zeros in first %d: layer0=%d, layer1=%d", n, zeros0, zeros1));
				} else {
					log("  [MISMATCH] Different shapes!");
				}
			}
			log("");
		}

		stateDict.destroy();
	}
}
