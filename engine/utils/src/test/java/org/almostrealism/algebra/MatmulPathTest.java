package org.almostrealism.algebra;

import org.almostrealism.collect.CollectionProducer;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for matmul to verify both vector and weightedSum paths
 * produce correct results and to benchmark compile/run times.
 */
public class MatmulPathTest extends TestSuiteBase implements MatrixFeatures {

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
		for (int i = 0; i < outputSize; i++) {
			assertEquals("Output[" + i + "]", expected[i], output.toDouble(i));
		}

		log("=== PASSED ===\n");
	}

	/**
	 * A column vector of shape (n, 1) is one vector of n elements, not a batch of
	 * n one-element vectors, and the wide-output path must read it the same way
	 * the narrow path does. The shape arises naturally when the output of one
	 * matrix-vector product feeds another: the narrow path returns (m, 1).
	 */
	@Test(timeout = 60000)
	public void columnVectorLargeOutput() {
		int inputSize = 64;
		int outputSize = 1500;

		PackedCollection weights = new PackedCollection(shape(outputSize, inputSize));
		PackedCollection input = new PackedCollection(shape(inputSize));
		initializeWeights(weights, outputSize, inputSize);
		initializeInput(input, inputSize);

		PackedCollection expected = matmul(p(weights), traverseEach(p(input))).evaluate();
		PackedCollection column = input.reshape(shape(inputSize, 1));
		PackedCollection output = matmul(p(weights), p(column)).evaluate();

		assertEquals("Output size", outputSize, output.getShape().getTotalSize());
		for (int i = 0; i < outputSize; i++) {
			assertEquals("Output[" + i + "]", expected.toDouble(i), output.toDouble(i));
		}
	}

	/**
	 * A batch of row vectors is multiplied by every row of the matrix, including when the
	 * batch holds exactly as many vectors as the matrix has rows: with the identity rows
	 * as input, the product is the transpose of the matrix, row by row. A broadcast that
	 * paired the matrix rows with the vectors instead returned {@code [1, 1, 5, 5]} here.
	 */
	@Test(timeout = 60000)
	public void batchAsLongAsMatrixRows() {
		PackedCollection weights = pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3));

		PackedCollection two = matmul(cp(weights), cp(pack(1, 0, 0, 0, 1, 0).reshape(shape(2, 3))).traverse(1)).evaluate();
		assertEquals(4, two.getShape().getTotalSize());
		double[] expectedTwo = {1, 4, 2, 5};
		for (int i = 0; i < expectedTwo.length; i++) {
			assertEquals("Output[" + i + "]", expectedTwo[i], two.toDouble(i));
		}

		PackedCollection three = matmul(cp(weights), cp(pack(1, 0, 0, 0, 1, 0, 0, 0, 1).reshape(shape(3, 3))).traverse(1)).evaluate();
		assertEquals(6, three.getShape().getTotalSize());
		double[] expectedThree = {1, 4, 2, 5, 3, 6};
		for (int i = 0; i < expectedThree.length; i++) {
			assertEquals("Output[" + i + "]", expectedThree[i], three.toDouble(i));
		}
	}

	/**
	 * The matrix is repeated whole for every vector in the batch whatever axis it is
	 * traversed along: a matrix traversed along its columns, if repeated at that axis,
	 * would interleave its rows with the batch and pair the wrong matrix rows with the
	 * vectors once the batch is longer than the matrix.
	 */
	@Test(timeout = 60000)
	public void batchWithColumnTraversedMatrix() {
		PackedCollection weights = pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3));

		PackedCollection three = matmul(traverse(1, cp(weights)),
				cp(pack(1, 0, 0, 0, 1, 0, 0, 0, 1).reshape(shape(3, 3))).traverse(1)).evaluate();
		assertEquals(6, three.getShape().getTotalSize());
		double[] expectedThree = {1, 4, 2, 5, 3, 6};
		for (int i = 0; i < expectedThree.length; i++) {
			assertEquals("Output[" + i + "]", expectedThree[i], three.toDouble(i));
		}
	}

	/**
	 * {@link MatrixFeatures#multiplyTransposed} computes {@code a · bᵀ}: entry {@code (i, j)}
	 * is the dot product of row {@code i} of {@code a} with row {@code j} of {@code b}, for
	 * operands with different row counts so that a swapped axis would change the shape.
	 */
	@Test(timeout = 60000)
	public void multiplyTransposed() {
		CollectionProducer a = cp(pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3)));
		CollectionProducer b = cp(pack(1, 0, -1, 2, 1, 0, 0, 0, 3, 1, 1, 1).reshape(shape(4, 3)));

		PackedCollection result = multiplyTransposed(a, b).evaluate();
		assertEquals(shape(2, 4).getTotalSize(), result.getShape().getTotalSize());

		double[] expected = {-2, 4, 9, 6, -2, 13, 18, 15};
		for (int i = 0; i < expected.length; i++) {
			assertEquals("Output[" + i + "]", expected[i], result.toDouble(i));
		}
	}

	/**
	 * {@link MatrixFeatures#multiplyTransposed} rejects operands that are not matrices or
	 * whose rows differ in length.
	 */
	@Test(timeout = 60000)
	public void multiplyTransposedRejectsMismatchedOperands() {
		CollectionProducer matrix = cp(new PackedCollection(shape(2, 3)));
		CollectionProducer[][] rejected = {
				{ matrix, cp(new PackedCollection(shape(2, 4))) },
				{ cp(new PackedCollection(shape(3))), matrix },
				{ matrix, cp(new PackedCollection(shape(2, 3, 1))) }
		};

		for (CollectionProducer[] operands : rejected) {
			try {
				multiplyTransposed(operands[0], operands[1]);
				Assert.fail("Expected " + operands[0].getShape() + " by the transpose of " +
						operands[1].getShape() + " to be rejected");
			} catch (IllegalArgumentException expected) {
				// The operands are not compatible matrices
			}
		}
	}

	/**
	 * A single vector times a matrix of several rows is computed as one weighted sum in the
	 * {@code (m, 1)} shape the multiply + sum gives, whether the vector is given flat or as a
	 * column, and each output is the dot product of its row with the vector.
	 */
	@Test(timeout = 60000)
	public void singleVectorIsWeightedSum() {
		PackedCollection weights = pack(1, 2, 3, 4, 5, 6, -1, 0, 2).reshape(shape(3, 3));
		PackedCollection vector = pack(1, -1, 2);
		double[] expected = { 5, 11, 3 };

		CollectionProducer flat = matmul(cp(weights), cp(vector));
		CollectionProducer column = matmul(cp(weights), cp(vector.reshape(shape(3, 1))));

		for (CollectionProducer product : new CollectionProducer[] { flat, column }) {
			Assert.assertTrue("single-vector product is " + product.getClass().getSimpleName(),
					product instanceof WeightedSumComputation);
			Assert.assertEquals(2, product.getShape().getDimensions());
			Assert.assertEquals(3, product.getShape().length(0));
			Assert.assertEquals(1, product.getShape().length(1));

			PackedCollection result = product.evaluate();
			Assert.assertEquals(3, result.getShape().getTotalSize());
			for (int i = 0; i < expected.length; i++) {
				assertEquals("Output[" + i + "]", expected[i], result.toDouble(i));
			}
		}
	}

	/**
	 * A matrix of one row times a single vector is a dot product, which keeps the multiply +
	 * sum form rather than taking the single-vector weighted sum.
	 */
	@Test(timeout = 60000)
	public void oneRowMatrixKeepsMultiplySum() {
		CollectionProducer product = matmul(cp(pack(1, 2, 3).reshape(shape(1, 3))), cp(pack(4, -5, 6)));
		Assert.assertFalse(product instanceof WeightedSumComputation);

		PackedCollection result = product.evaluate();
		Assert.assertEquals(1, result.getShape().getTotalSize());
		assertEquals(12.0, result.toDouble(0));
	}

	/**
	 * Benchmark compile times at different output sizes.
	 */
	@Test(timeout = 60000)
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

	/**
	 * Initializes weight matrix with test values.
	 */
	private void initializeWeights(PackedCollection weights, int rows, int cols) {
		int n = Math.min(rows, 100) * cols;
		a(cp(weights.range(new TraversalPolicy(n), 0)), integers(1, n + 1).multiply(0.001)).get().run();
	}

	/**
	 * Initializes input vector with test values.
	 */
	private void initializeInput(PackedCollection input, int size) {
		a(cp(input), integers(1, size + 1).multiply(0.1)).get().run();
	}

	/**
	 * Verifies output correctness against expected computation.
	 */
	private void verifyOutput(PackedCollection output, PackedCollection weights,
							  PackedCollection input, int outputSize, int inputSize) {
		for (int i = 0; i < Math.min(10, outputSize); i++) {
			double expected = 0;
			for (int j = 0; j < inputSize; j++) {
				expected += weights.toDouble(i * inputSize + j) * input.toDouble(j);
			}
			assertEquals("Output[" + i + "]", expected, output.toDouble(i));
		}
	}
}
