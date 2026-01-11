package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

/**
 * Test to verify the memory layout transformation in attentionValues.
 *
 * The issue: enumerate(1, 1, v).reshape(heads, headSize, seqLength) does NOT
 * actually transpose the data - it just reinterprets the memory layout.
 *
 * Expected: v[pos, h*headSize+d] should map to new[h, d, pos]
 * Actual: The reshape just reinterprets linear memory, getting wrong values.
 */
public class AttentionValuesLayoutTest extends TestSuiteBase implements LayerFeatures, ConsoleFeatures {

	@Test
	public void testEnumerateReshapeLayout() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/attention_values_layout.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n===================================================");
		log("  Attention Values Layout Analysis");
		log("===================================================\n");

		// Use small dimensions for easy tracing
		int seqLength = 2;
		int heads = 3;
		int headSize = 4;
		int dim = heads * headSize;  // 12

		// Create input with known values: v[pos, h*headSize+d] = pos * 100 + h * 10 + d
		// This makes it easy to trace where each value ends up
		PackedCollection v = new PackedCollection(shape(seqLength, dim));
		log("Input v[pos, h*headSize+d] = pos*100 + h*10 + d:");
		for (int pos = 0; pos < seqLength; pos++) {
			StringBuilder sb = new StringBuilder();
			sb.append("  pos=").append(pos).append(": [");
			for (int h = 0; h < heads; h++) {
				for (int d = 0; d < headSize; d++) {
					double val = pos * 100 + h * 10 + d;
					v.setMem(pos * dim + h * headSize + d, val);
					if (h > 0 || d > 0) sb.append(", ");
					sb.append(String.format("%.0f", val));
				}
			}
			sb.append("]");
			log(sb.toString());
		}

		// What we expect after correct transformation to [heads, headSize, seqLength]:
		// new[h, d, pos] = v[pos, h*headSize+d] = pos*100 + h*10 + d
		log("\nExpected layout after correct transpose to [heads, headSize, seqLength]:");
		log("  new[h, d, pos] = v[pos, h*headSize+d]");
		for (int h = 0; h < heads; h++) {
			log("  Head " + h + ":");
			for (int d = 0; d < headSize; d++) {
				StringBuilder sb = new StringBuilder();
				sb.append("    d=").append(d).append(": [");
				for (int pos = 0; pos < seqLength; pos++) {
					if (pos > 0) sb.append(", ");
					double expected = pos * 100 + h * 10 + d;
					sb.append(String.format("%.0f", expected));
				}
				sb.append("]");
				log(sb.toString());
			}
		}

		// Now test what enumerate(1,1,v).reshape actually produces
		log("\n=== Testing enumerate(1,1,v).reshape(heads, headSize, seqLength) ===");

		CollectionProducer vp = c(v);
		CollectionProducer enumerated = enumerate(1, 1, vp);
		log("After enumerate(1, 1, v): shape = " + shape(enumerated));

		CollectionProducer reshaped = enumerated.reshape(shape(heads, headSize, seqLength));
		log("After reshape to (heads, headSize, seqLength): shape = " + shape(reshaped));

		// Evaluate
		PackedCollection result = reshaped.get().evaluate();
		log("\nActual values in result[h, d, pos]:");
		int mismatchCount = 0;
		for (int h = 0; h < heads; h++) {
			log("  Head " + h + ":");
			for (int d = 0; d < headSize; d++) {
				StringBuilder sb = new StringBuilder();
				sb.append("    d=").append(d).append(": [");
				for (int pos = 0; pos < seqLength; pos++) {
					if (pos > 0) sb.append(", ");
					int idx = h * (headSize * seqLength) + d * seqLength + pos;
					double actual = result.toDouble(idx);
					double expected = pos * 100 + h * 10 + d;
					sb.append(String.format("%.0f", actual));
					if (Math.abs(actual - expected) > 0.01) {
						sb.append("(WRONG!)");
						mismatchCount++;
					}
				}
				sb.append("]");
				log(sb.toString());
			}
		}

		log("\n=== Analysis ===");
		if (mismatchCount == 0) {
			log("[OK] enumerate/reshape produces correct layout");
		} else {
			log("[MISMATCH] " + mismatchCount + " values are in wrong positions!");
			log("\nRoot cause: enumerate(1,1,v).reshape(...) does NOT transpose data.");
			log("It just reinterprets the linear memory layout differently.");
			log("\nThe correct fix: use permute() to actually transpose the dimensions.");
		}

		// Test the correct approach using reshape + permute
		log("\n=== Testing correct approach: reshape then permute ===");

		// First reshape [seqLength, dim] to [seqLength, heads, headSize]
		CollectionProducer step1 = vp.reshape(shape(seqLength, heads, headSize));
		log("Step 1 - reshape to (seqLength, heads, headSize): shape = " + shape(step1));

		// Then permute to [heads, headSize, seqLength]  (0,1,2) -> (1,2,0)
		CollectionProducer step2 = permute(step1, 1, 2, 0);
		log("Step 2 - permute to (heads, headSize, seqLength): shape = " + shape(step2));

		PackedCollection correctResult = step2.get().evaluate();
		log("\nCorrect result values:");
		int correctMismatch = 0;
		for (int h = 0; h < heads; h++) {
			log("  Head " + h + ":");
			for (int d = 0; d < headSize; d++) {
				StringBuilder sb = new StringBuilder();
				sb.append("    d=").append(d).append(": [");
				for (int pos = 0; pos < seqLength; pos++) {
					if (pos > 0) sb.append(", ");
					int idx = h * (headSize * seqLength) + d * seqLength + pos;
					double actual = correctResult.toDouble(idx);
					double expected = pos * 100 + h * 10 + d;
					sb.append(String.format("%.0f", actual));
					if (Math.abs(actual - expected) > 0.01) {
						sb.append("(WRONG!)");
						correctMismatch++;
					}
				}
				sb.append("]");
				log(sb.toString());
			}
		}

		if (correctMismatch == 0) {
			log("\n[OK] reshape + permute produces correct layout!");
		} else {
			log("\n[STILL WRONG] " + correctMismatch + " mismatches. Need different permutation.");
		}

		log("\n=== Test Complete ===");
	}
}
