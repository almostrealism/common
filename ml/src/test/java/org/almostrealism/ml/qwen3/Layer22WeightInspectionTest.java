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
 * Test to inspect layer 22's weights and compare them with surrounding layers.
 * Goal: Determine if layer 22 weights are corrupted or anomalous.
 */
public class Layer22WeightInspectionTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/workspace/project/common/ml/qwen3_weights";

	@Test
	public void inspectLayer22Weights() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/layer22_weight_inspection.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n=== Layer 22 Weight Inspection Test ===\n");

		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Compare layers 20, 21, 22, 23 to see if 22 is anomalous
		int[] layers = {20, 21, 22, 23};
		String[] weightNames = {
			"input_layernorm.weight",
			"post_attention_layernorm.weight",
			"self_attn.q_proj.weight",
			"self_attn.k_proj.weight",
			"self_attn.v_proj.weight",
			"self_attn.o_proj.weight",
			"self_attn.q_norm.weight",
			"self_attn.k_norm.weight",
			"mlp.gate_proj.weight",
			"mlp.up_proj.weight",
			"mlp.down_proj.weight"
		};

		log(String.format("%-35s", "Weight"));
		for (int layer : layers) {
			log(String.format("Layer %-4d", layer));
		}
		log("");
		log("-".repeat(80));

		for (String weightName : weightNames) {
			StringBuilder line = new StringBuilder();
			line.append(String.format("%-35s", weightName));

			for (int layer : layers) {
				String key = String.format("model.layers.%d.%s", layer, weightName);
				PackedCollection weight = stateDict.get(key);

				if (weight != null) {
					double[] stats = computeStats(weight);
					line.append(String.format("%.3f+/-%.3f  ", stats[0], stats[1]));
				} else {
					line.append("NULL       ");
				}
			}

			log(line.toString());
		}

		// Detailed comparison for Q projection (large weight matrix)
		log("\n=== Q Projection Weight Details ===");
		for (int layer : layers) {
			String key = String.format("model.layers.%d.self_attn.q_proj.weight", layer);
			PackedCollection weight = stateDict.get(key);
			if (weight != null) {
				double[] stats = computDetailedStats(weight);
				log(String.format("Layer %d: shape=%s, mean=%.6f, std=%.6f, min=%.6f, max=%.6f",
					layer, weight.getShape(), stats[0], stats[1], stats[2], stats[3]));

				// Check for NaN or Inf
				boolean hasNaN = false, hasInf = false;
				for (int i = 0; i < weight.getShape().getTotalSize(); i++) {
					double v = weight.toDouble(i);
					if (Double.isNaN(v)) hasNaN = true;
					if (Double.isInfinite(v)) hasInf = true;
				}
				if (hasNaN || hasInf) {
					log(String.format("  WARNING: NaN=%s, Inf=%s", hasNaN, hasInf));
				}
			}
		}

		// Check weight SHAPES for K/V projections
		log("\n=== Weight Shape Verification ===");
		for (int layer : layers) {
			String qKey = String.format("model.layers.%d.self_attn.q_proj.weight", layer);
			String kKey = String.format("model.layers.%d.self_attn.k_proj.weight", layer);
			String vKey = String.format("model.layers.%d.self_attn.v_proj.weight", layer);

			PackedCollection qw = stateDict.get(qKey);
			PackedCollection kw = stateDict.get(kKey);
			PackedCollection vw = stateDict.get(vKey);

			log(String.format("Layer %d: Q=%s, K=%s, V=%s",
				layer,
				qw != null ? qw.getShape().toString() : "NULL",
				kw != null ? kw.getShape().toString() : "NULL",
				vw != null ? vw.getShape().toString() : "NULL"));
		}

		stateDict.destroy();
		log("\n=== Test Complete ===");
	}

	private double[] computeStats(PackedCollection c) {
		int size = c.getShape().getTotalSize();
		double sum = 0, sumSq = 0;
		for (int i = 0; i < size; i++) {
			double v = c.toDouble(i);
			sum += v;
			sumSq += v * v;
		}
		double mean = sum / size;
		double variance = (sumSq / size) - (mean * mean);
		double std = Math.sqrt(Math.max(0, variance));
		return new double[]{mean, std};
	}

	private double[] computDetailedStats(PackedCollection c) {
		int size = c.getShape().getTotalSize();
		double sum = 0, sumSq = 0;
		double min = Double.MAX_VALUE, max = Double.MIN_VALUE;

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

		return new double[]{mean, std, min, max};
	}
}
