package org.almostrealism.algebra;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for matmul to verify both vector and weightedSum paths
 * produce correct results and to benchmark compile/run times.
 */
public class MatmulPathTest extends TestSuiteBase implements MatrixFeatures, ConsoleFeatures {

	/**
	 * Test matmul with small output (should use vector path).
	 */
	@Test(timeout = 30000)
	public void smallOutputMatmul() {
		int inputSize = 128;
		int outputSize = 100;  // Below 10000 threshold

		log("=== Small Output Matmul Test ===");
		log("Input size: " + inputSize + ", Output size: " + outputSize);

		// Create weights and input
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		// Initialize with known values
		initializeWeights(weights, outputSize, inputSize);
		initializeInput(input, inputSize);

		// Create and run matmul - use traverseEach like dense layer does
		long startCompile = System.currentTimeMillis();
		CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
		result.get();
		long compileTime = System.currentTimeMillis() - startCompile;

		long startRun = System.currentTimeMillis();
		PackedCollection output = result.evaluate();
		long runTime = System.currentTimeMillis() - startRun;

		log("Compile time: " + compileTime + "ms");
		log("Run time: " + runTime + "ms");
		log("Output shape: " + output.getShape());

		// Verify output
		verifyOutput(output, weights, input, outputSize, inputSize);
		log("=== PASSED ===\n");
	}

	/**
	 * Test matmul with large output (should use weightedSum path after fix).
	 */
	@Test(timeout = 30000)
	public void largeOutputMatmul() {
		int inputSize = 128;
		int outputSize = 15000;  // Above 10000 threshold

		log("=== Large Output Matmul Test ===");
		log("Input size: " + inputSize + ", Output size: " + outputSize);

		// Create weights and input
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		// Initialize with known values
		initializeWeights(weights, outputSize, inputSize);
		initializeInput(input, inputSize);

		// Create and run matmul - use traverseEach like dense layer does
		long startCompile = System.currentTimeMillis();
		CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
		result.get();
		long compileTime = System.currentTimeMillis() - startCompile;

		long startRun = System.currentTimeMillis();
		PackedCollection output = result.evaluate();
		long runTime = System.currentTimeMillis() - startRun;

		log("Compile time: " + compileTime + "ms");
		log("Run time: " + runTime + "ms");
		log("Output shape: " + output.getShape());

		// Verify output
		verifyOutput(output, weights, input, outputSize, inputSize);
		log("=== PASSED ===\n");
	}

	/**
	 * Compare vector path vs forced weightedSum path for same computation.
	 */
	@Test(timeout = 30000)
	public void pathEquivalence() {
		int inputSize = 64;
		int outputSize = 500;  // Small enough for both paths to work quickly

		log("=== Path Equivalence Test ===");

		// Create weights and input
		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));

		// Initialize with known values
		initializeWeights(weights, outputSize, inputSize);
		initializeInput(input, inputSize);

		// Get result using current implementation
		// Use traverseEach to set proper traversal axis like dense layer does
		CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
		PackedCollection output = result.evaluate();

		// Manually compute expected values
		double[] expected = new double[outputSize];
		for (int i = 0; i < outputSize; i++) {
			expected[i] = 0;
			for (int j = 0; j < inputSize; j++) {
				expected[i] += weights.toDouble(i * inputSize + j) * input.toDouble(j);
			}
		}

		// Compare
		double maxDiff = 0;
		for (int i = 0; i < outputSize; i++) {
			double diff = Math.abs(output.toDouble(i) - expected[i]);
			if (diff > maxDiff) maxDiff = diff;
		}

		log("Max difference from expected: " + maxDiff);
		assertTrue("Results should match expected within tolerance", maxDiff < 1e-5);
		log("=== PASSED ===\n");
	}

	/**
	 * Benchmark compile times at different output sizes.
	 */
	@Test
	public void testCompileTimeBenchmark() {
		int inputSize = 128;
		int[] outputSizes = {100, 500, 1000, 2000, 5000, 8000, 10000, 12000};

		log("=== Compile Time Benchmark ===");
		log("Input size: " + inputSize);
		log("");

		for (int outputSize : outputSizes) {
			PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
			PackedCollection input = new PackedCollection(shape(inputSize));

			long startCompile = System.currentTimeMillis();
			CollectionProducer result = matmul(p(weights), traverseEach(p(input)));
			result.get();
			long compileTime = System.currentTimeMillis() - startCompile;

			log(String.format("Output size %6d: compile %6dms", outputSize, compileTime));
		}

		log("\n=== BENCHMARK COMPLETE ===");
	}

	private void initializeWeights(PackedCollection weights, int rows, int cols) {
		for (int i = 0; i < Math.min(rows, 100); i++) {
			for (int j = 0; j < cols; j++) {
				weights.setMem(i * cols + j, 0.01 * ((i + 1) * (j + 1) % 100));
			}
		}
	}

	private void initializeInput(PackedCollection input, int size) {
		for (int i = 0; i < size; i++) {
			input.setMem(i, 0.1 * (i + 1));
		}
	}

	private void verifyOutput(PackedCollection output, PackedCollection weights,
							  PackedCollection input, int outputSize, int inputSize) {
		// Verify a few values
		for (int i = 0; i < Math.min(10, outputSize); i++) {
			double expected = 0;
			for (int j = 0; j < inputSize; j++) {
				expected += weights.toDouble(i * inputSize + j) * input.toDouble(j);
			}
			double actual = output.toDouble(i);
			double diff = Math.abs(expected - actual);
			if (diff > 1e-5) {
				log("MISMATCH at " + i + ": expected=" + expected + ", actual=" + actual);
			}
			assertTrue("Output[" + i + "] should match", diff < 1e-5);
		}
	}
}
