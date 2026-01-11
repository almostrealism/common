package org.almostrealism.ml.qwen3;

import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Verify PyTorch reference data is consistent across all layers.
 */
public class ReferenceDataVerificationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	private static final String REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs";

	@Test
	public void verifyAllReferenceData() throws Exception {
		Assume.assumeTrue("Skipping comparison test in pipeline profile", TestUtils.isComparisonTestEnabled());

		String logFile = "/workspace/project/common/ml/test_output/reference_data_verification.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(80));
		log("  PYTORCH REFERENCE DATA VERIFICATION");
		log("=".repeat(80) + "\n");

		log(String.format("%-20s %-10s %-15s %-15s %-15s %-15s",
			"Layer", "Size", "Mean", "Std", "Min", "Max"));
		log("-".repeat(90));

		// Check embeddings
		checkReferenceFile("after_embeddings.bin", "Embeddings");

		// Check all 24 layers
		for (int i = 0; i < 24; i++) {
			checkReferenceFile("after_layer_" + i + ".bin", "Layer " + i);
		}

		// Check final logits
		checkReferenceFile("final_logits.bin", "Final Logits");

		// Now analyze the change between consecutive layers
		log("\n" + "=".repeat(80));
		log("  CHANGE ANALYSIS (layer N vs layer N-1)");
		log("=".repeat(80) + "\n");

		log(String.format("%-15s %-15s %-15s %-15s",
			"Layer", "Mean Diff", "Std Ratio", "Range Ratio"));
		log("-".repeat(65));

		float[] prevData = loadReferenceOutput("after_embeddings.bin");
		for (int i = 0; i < 24; i++) {
			float[] currData = loadReferenceOutput("after_layer_" + i + ".bin");

			double prevMean = mean(prevData);
			double currMean = mean(currData);
			double prevStd = std(prevData);
			double currStd = std(currData);
			double prevRange = max(prevData) - min(prevData);
			double currRange = max(currData) - min(currData);

			double meanDiff = currMean - prevMean;
			double stdRatio = currStd / prevStd;
			double rangeRatio = currRange / prevRange;

			String status = "";
			if (Math.abs(stdRatio - 1.0) > 1.0) {
				status = " *** ANOMALY ***";
			}

			log(String.format("%-15s %-15.6f %-15.3f %-15.3f%s",
				"Layer " + i, meanDiff, stdRatio, rangeRatio, status));

			prevData = currData;
		}

		log("\n=== Verification Complete ===");
	}

	private void checkReferenceFile(String filename, String name) throws IOException {
		float[] data = loadReferenceOutput(filename);
		log(String.format("%-20s %-10d %-15.6f %-15.6f %-15.6f %-15.6f",
			name, data.length, mean(data), std(data), min(data), max(data)));
	}

	private float[] loadReferenceOutput(String filename) throws IOException {
		try (FileChannel channel = FileChannel.open(
				Paths.get(REFERENCE_DIR, filename), StandardOpenOption.READ)) {
			ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			channel.read(buffer);
			buffer.flip();

			int size = buffer.getInt();
			float[] output = new float[size];
			for (int i = 0; i < size; i++) {
				output[i] = buffer.getFloat();
			}
			return output;
		}
	}

	private double mean(float[] arr) {
		double sum = 0;
		for (float v : arr) sum += v;
		return sum / arr.length;
	}

	private double std(float[] arr) {
		double m = mean(arr);
		double sum = 0;
		for (float v : arr) sum += (v - m) * (v - m);
		return Math.sqrt(sum / arr.length);
	}

	private double min(float[] arr) {
		double min = arr[0];
		for (float v : arr) if (v < min) min = v;
		return min;
	}

	private double max(float[] arr) {
		double max = arr[0];
		for (float v : arr) if (v > max) max = v;
		return max;
	}
}
