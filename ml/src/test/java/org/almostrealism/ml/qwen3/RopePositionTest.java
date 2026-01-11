package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test to verify RoPE rotation with dynamic position.
 */
public class RopePositionTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	@Test
	public void testRopeWithDynamicPosition() throws Exception {
		String logFile = "/workspace/project/common/ml/test_output/rope_position_test.txt";
		Console.root().addListener(OutputFeatures.fileOutput(logFile));

		log("\n" + "=".repeat(70));
		log("  ROPE WITH DYNAMIC POSITION TEST");
		log("=".repeat(70) + "\n");

		int heads = 2;
		int headSize = 4;  // Must be even for complex representation
		int seqLen = 10;
		int freqDim = headSize / 2;

		// Create RoPE frequency tensor: (seqLen, freqDim, 2) containing [cos, sin]
		PackedCollection freqCis = new PackedCollection(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = 1.0 / Math.pow(10000.0, (2.0 * i) / headSize);
				double angle = pos * freq;
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}

		log("FreqCis at position 0: [" +
			freqCis.toDouble(0) + ", " + freqCis.toDouble(1) + ", " +
			freqCis.toDouble(2) + ", " + freqCis.toDouble(3) + "]");
		log("FreqCis at position 1: [" +
			freqCis.toDouble(1 * freqDim * 2) + ", " + freqCis.toDouble(1 * freqDim * 2 + 1) + ", " +
			freqCis.toDouble(1 * freqDim * 2 + 2) + ", " + freqCis.toDouble(1 * freqDim * 2 + 3) + "]");
		log("FreqCis at position 3: [" +
			freqCis.toDouble(3 * freqDim * 2) + ", " + freqCis.toDouble(3 * freqDim * 2 + 1) + ", " +
			freqCis.toDouble(3 * freqDim * 2 + 2) + ", " + freqCis.toDouble(3 * freqDim * 2 + 3) + "]");

		// Create position collection
		PackedCollection position = new PackedCollection(1);

		// Create dynamic position producer
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);

		// Create RoPE layer shape: (heads, headSize/2, 2)
		var ropeShape = shape(heads, freqDim, 2);

		// Build model with just ropeRotation
		CellularLayer ropeLayer = ropeRotation(ropeShape, freqCis, dynamicPosition);

		Model model = new Model(ropeShape);
		model.add(ropeLayer);

		CompiledModel compiled = model.compile();

		// Create input: simple values for debugging
		// Shape: (heads, freqDim, 2) = (2, 2, 2) = 8 elements
		PackedCollection input = new PackedCollection(ropeShape);
		// Set input as [1, 0] for each head (represents (1, 0) complex numbers)
		for (int h = 0; h < heads; h++) {
			for (int f = 0; f < freqDim; f++) {
				input.setMem((h * freqDim + f) * 2, 1.0);  // real
				input.setMem((h * freqDim + f) * 2 + 1, 0.0);  // imag
			}
		}

		log("\nInput (all 1+0i complex):");
		log("  [" + input.toDouble(0) + ", " + input.toDouble(1) + ", " +
			input.toDouble(2) + ", " + input.toDouble(3) + ", ...]");

		// Test at position 0
		position.setMem(0, 0.0);
		PackedCollection result0 = compiled.forward(input);
		double[] r0 = new double[8];
		for (int i = 0; i < 8; i++) r0[i] = result0.toDouble(i);
		log("\nPosition = 0:");
		log("  Result: [" + r0[0] + ", " + r0[1] + ", " + r0[2] + ", " + r0[3] + ", ...]");
		log("  Expected (cos(0), sin(0)): [1, 0, 1, 0, ...]");

		// Test at position 3
		position.setMem(0, 3.0);
		PackedCollection result3 = compiled.forward(input);
		double[] r3 = new double[8];
		for (int i = 0; i < 8; i++) r3[i] = result3.toDouble(i);
		log("\nPosition = 3:");
		log("  Result: [" + r3[0] + ", " + r3[1] + ", " + r3[2] + ", " + r3[3] + ", ...]");
		double angle0 = 3.0 * (1.0 / Math.pow(10000.0, 0.0 / headSize));
		double angle1 = 3.0 * (1.0 / Math.pow(10000.0, 2.0 / headSize));
		log("  Expected (cos(3*freq), sin(3*freq)): [" +
			Math.cos(angle0) + ", " + Math.sin(angle0) + ", " +
			Math.cos(angle1) + ", " + Math.sin(angle1) + ", ...]");

		// Check if results are different
		log("\nComparing position 0 vs position 3:");
		log("  r0[0]=" + r0[0] + " vs r3[0]=" + r3[0] + " diff=" + (r0[0] - r3[0]));
		log("  r0[1]=" + r0[1] + " vs r3[1]=" + r3[1] + " diff=" + (r0[1] - r3[1]));

		boolean different = false;
		for (int i = 0; i < 8; i++) {
			if (Math.abs(r0[i] - r3[i]) > 0.001) {
				different = true;
				break;
			}
		}

		if (different) {
			log("\nSUCCESS: RoPE rotation produces different outputs at different positions!");
		} else {
			log("\nFAILURE: RoPE rotation produces SAME output at both positions!");
			log("This means position is NOT being read dynamically.");
		}

		log("\n" + "=".repeat(70));

		Assert.assertTrue("RoPE rotation should produce different outputs at different positions", different);
	}
}
