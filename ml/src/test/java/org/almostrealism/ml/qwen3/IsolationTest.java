package org.almostrealism.ml.qwen3;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Isolate exactly which component breaks position handling.
 * Start with passing tests and add components until it breaks.
 */
public class IsolationTest extends TestSuiteBase implements AttentionFeatures, ConsoleFeatures {

	/**
	 * Test 1: Just rope + branches (no dense, no attentionKeys)
	 * This should PASS based on ExactAttentionStructureTest.
	 */
	@Test
	public void test1_RopeWithBranches() throws Exception {
		log("\n=== Test 1: RoPE with branches (should PASS) ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Branches with simple layers (like ExactAttentionStructureTest)
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("b1", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("b2", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		// Main path with RoPE
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, ropeShape.getTotalSize());
		Assert.assertFalse("Test 1 should PASS", allSame);
	}

	/**
	 * Test 2: Add dense() layers before branching and in main path.
	 * Does dense() break anything?
	 */
	@Test
	public void test2_DenseWithRope() throws Exception {
		log("\n=== Test 2: Dense + RoPE (testing dense impact) ===");

		int dim = 8;
		int heads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);
		PackedCollection denseW = createIdentity(dim, dim);

		var inputShape = shape(dim);
		var ropeShape = shape(heads, freqDim, 2);

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);

		// Dense layer first
		main.add(dense(denseW));

		// Branches
		SequentialBlock branch1 = main.branch();
		branch1.add(reshape(inputShape, ropeShape));

		SequentialBlock branch2 = main.branch();
		branch2.add(reshape(inputShape, ropeShape));

		// Main path: reshape + RoPE
		main.add(reshape(inputShape, ropeShape));
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, ropeShape.getTotalSize());
		if (allSame) {
			log("[FAIL] Test 2 - dense breaks position");
		} else {
			log("[PASS] Test 2 - dense is OK");
		}
		Assert.assertFalse("Test 2 should PASS", allSame);
	}

	/**
	 * Test 3: Add attentionKeys operation.
	 * Does reading from cache break position?
	 */
	@Test
	public void test3_AttentionKeys() throws Exception {
		log("\n=== Test 3: AttentionKeys (testing cache read) ===");

		int dim = 8;
		int heads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		// Create a cache with known values
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		for (int i = 0; i < keyCache.getShape().getTotalSize(); i++) {
			keyCache.setMem(i, 0.1);
		}

		var ropeShape = shape(heads, freqDim, 2);
		var headShape = shape(heads, headSize);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Branches
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("b1", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("b2", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		// Main: RoPE + reshape + attentionKeys
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		main.add(reshape(ropeShape, headShape));
		main.add(attentionKeys(headShape, p(keyCache)));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, heads * seqLen);
		if (allSame) {
			log("[FAIL] Test 3 - attentionKeys breaks position");
		} else {
			log("[PASS] Test 3 - attentionKeys is OK");
		}
		Assert.assertFalse("Test 3 should PASS", allSame);
	}

	/**
	 * Test 4: Add softmax after attentionKeys.
	 */
	@Test
	public void test4_Softmax() throws Exception {
		log("\n=== Test 4: Softmax after attentionKeys ===");

		int dim = 8;
		int heads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		keyCache.fill(0.1);

		var ropeShape = shape(heads, freqDim, 2);
		var headShape = shape(heads, headSize);
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Branches
		SequentialBlock branch1 = main.branch();
		branch1.add(layer("b1", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		SequentialBlock branch2 = main.branch();
		branch2.add(layer("b2", ropeShape, ropeShape, input -> multiply(input, scalar(2.0))));

		// Main: RoPE + attentionKeys + causalMask + softmax
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		main.add(reshape(ropeShape, headShape));
		main.add(attentionKeys(headShape, p(keyCache)));

		// Causal mask
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));

		model.add(main);
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, heads * seqLen);
		if (allSame) {
			log("[FAIL] Test 4 - softmax breaks position");
		} else {
			log("[PASS] Test 4 - softmax is OK");
		}
		Assert.assertFalse("Test 4 should PASS", allSame);
	}

	private boolean testPositions(CompiledModel compiled, PackedCollection position, int outputSize) {
		PackedCollection input = new PackedCollection(compiled.getInputShape());
		for (int i = 0; i < input.getShape().getTotalSize(); i++) {
			input.setMem(i, 1.0);
		}

		double[][] outputs = new double[5][outputSize];

		for (int pos = 0; pos < 5; pos++) {
			position.setMem(0, (double) pos);
			PackedCollection result = compiled.forward(input);

			StringBuilder sb = new StringBuilder();
			sb.append("Pos ").append(pos).append(": [");
			for (int i = 0; i < Math.min(4, outputSize); i++) {
				if (i > 0) sb.append(", ");
				outputs[pos][i] = result.toDouble(i);
				sb.append(String.format("%.4f", outputs[pos][i]));
			}
			sb.append(", ...]");
			log(sb.toString());
		}

		boolean allSame = true;
		for (int pos = 1; pos < 5; pos++) {
			for (int i = 0; i < outputSize; i++) {
				if (Math.abs(outputs[pos][i] - outputs[0][i]) > 1e-6) {
					allSame = false;
					break;
				}
			}
		}
		return allSame;
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

	/**
	 * Test 5: Branches with cache writes via andThen(into(...)).
	 * This is what ManualAttentionTest does that might break things.
	 */
	@Test
	public void test5_BranchesWithCacheWrites() throws Exception {
		log("\n=== Test 5: Branches with cache writes ===");

		int heads = 2;
		int headSize = 4;
		int seqLen = 10;
		int freqDim = headSize / 2;
		int dim = heads * headSize;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);

		// Create caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, dim));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, dim));
		keyCache.clear();
		valueCache.clear();

		var ropeShape = shape(heads, freqDim, 2);
		var flatShape = shape(dim);

		Model model = new Model(ropeShape);
		SequentialBlock main = new SequentialBlock(ropeShape);

		// Keys branch: RoPE + cache write (like attention())
		SequentialBlock keys = main.branch();
		keys.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		keys.add(reshape(ropeShape, flatShape));
		keys.andThen(into(keyCache, dynamicPosition));

		// Values branch: simple + cache write
		SequentialBlock values = main.branch();
		values.add(reshape(ropeShape, flatShape));
		values.andThen(into(valueCache, dynamicPosition));

		// Main path: RoPE (should still work even with branch cache writes)
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));

		model.add(main);
		log("Compiling model...");
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, ropeShape.getTotalSize());
		if (allSame) {
			log("[FAIL] Test 5 - cache writes break position!");
		} else {
			log("[PASS] Test 5 - cache writes are OK");
		}

		// Also verify caches
		log("\nKey cache row 0: " + keyCache.toDouble(0) + ", " + keyCache.toDouble(1));
		log("Key cache row 4: " + keyCache.toDouble(4*dim) + ", " + keyCache.toDouble(4*dim+1));

		Assert.assertFalse("Test 5 should PASS", allSame);
	}

	/**
	 * Test 6: Full attention-like structure without attentionValues.
	 */
	@Test
	public void test6_FullMinuAttentionValues() throws Exception {
		log("\n=== Test 6: Full attention without attentionValues ===");

		int dim = 8;
		int heads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);
		PackedCollection denseW = createIdentity(dim, dim);

		// Caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		keyCache.fill(0.1);

		var inputShape = shape(1, dim);
		var ropeShape = shape(heads, freqDim, 2);
		var headShape = shape(heads, headSize);
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);

		// Pre-branch dense
		main.add(dense(denseW));
		main.add(reshape(shape(dim), ropeShape));

		// Keys branch (with cache write)
		SequentialBlock keys = main.branch();
		keys.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		keys.add(reshape(ropeShape, shape(dim)));
		keys.andThen(into(keyCache.reshape(shape(seqLen, dim)), dynamicPosition));

		// Values branch
		SequentialBlock values = main.branch();
		values.add(reshape(ropeShape, shape(dim)));

		// Main: RoPE + attentionKeys + mask + softmax
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		main.add(reshape(ropeShape, headShape));
		main.add(attentionKeys(headShape, p(keyCache)));

		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));

		model.add(main);
		log("Compiling model...");
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, heads * seqLen);
		if (allSame) {
			log("[FAIL] Test 6 - full structure breaks position!");
		} else {
			log("[PASS] Test 6 - full structure is OK");
		}
		Assert.assertFalse("Test 6 should PASS", allSame);
	}

	/**
	 * Test 7: Include attentionValues - does this break things?
	 */
	@Test
	public void test7_WithAttentionValues() throws Exception {
		log("\n=== Test 7: With attentionValues ===");

		int dim = 8;
		int heads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);
		PackedCollection denseW = createIdentity(dim, dim);

		// Caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		PackedCollection valueCache = new PackedCollection(shape(seqLen, heads, headSize));
		keyCache.fill(0.1);
		valueCache.fill(0.1);

		var inputShape = shape(1, dim);
		var ropeShape = shape(heads, freqDim, 2);
		var headShape = shape(heads, headSize);
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);

		// Pre-branch
		main.add(dense(denseW));
		main.add(reshape(shape(dim), ropeShape));

		// Branches
		SequentialBlock keys = main.branch();
		keys.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		keys.add(reshape(ropeShape, shape(dim)));
		keys.andThen(into(keyCache.reshape(shape(seqLen, dim)), dynamicPosition));

		SequentialBlock values = main.branch();
		values.add(reshape(ropeShape, shape(dim)));
		values.andThen(into(valueCache.reshape(shape(seqLen, dim)), dynamicPosition));

		// Main: RoPE + attentionKeys + mask + softmax + attentionValues
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		main.add(reshape(ropeShape, headShape));
		main.add(attentionKeys(headShape, p(keyCache)));

		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));
		main.add(attentionValues(attentionShape, p(valueCache)));

		model.add(main);
		log("Compiling model...");
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, dim);
		if (allSame) {
			log("[FAIL] Test 7 - attentionValues breaks position!");
		} else {
			log("[PASS] Test 7 - attentionValues is OK");
		}
		Assert.assertFalse("Test 7 should PASS", allSame);
	}

	/**
	 * Test 8: Include rmsnorm - does this break things?
	 */
	@Test
	public void test8_WithRmsnorm() throws Exception {
		log("\n=== Test 8: With rmsnorm before branches ===");

		int dim = 8;
		int heads = 2;
		int headSize = dim / heads;
		int seqLen = 10;
		int freqDim = headSize / 2;

		PackedCollection position = new PackedCollection(1);
		Producer<PackedCollection> dynamicPosition =
			new DynamicCollectionProducer(position.getShape(), args -> position);
		PackedCollection freqCis = createFreqCis(seqLen, headSize);
		PackedCollection rmsWeight = new PackedCollection(dim);
		for (int i = 0; i < dim; i++) rmsWeight.setMem(i, 1.0);

		// Caches
		PackedCollection keyCache = new PackedCollection(shape(seqLen, heads, headSize));
		keyCache.fill(0.1);

		var inputShape = shape(1, dim);
		var ropeShape = shape(heads, freqDim, 2);
		var headShape = shape(heads, headSize);
		var attentionShape = shape(heads, seqLen).traverseEach();

		Model model = new Model(inputShape);
		SequentialBlock main = new SequentialBlock(inputShape);

		// rmsnorm before branches
		main.add(rmsnorm(inputShape, rmsWeight, 1e-6));
		main.add(reshape(shape(dim), ropeShape));

		// Branches
		SequentialBlock keys = main.branch();
		keys.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		keys.add(reshape(ropeShape, shape(dim)));
		keys.andThen(into(keyCache.reshape(shape(seqLen, dim)), dynamicPosition));

		SequentialBlock values = main.branch();
		values.add(reshape(ropeShape, shape(dim)));

		// Main: RoPE + attentionKeys + mask + softmax
		main.add(ropeRotation(ropeShape, freqCis, dynamicPosition));
		main.add(reshape(ropeShape, headShape));
		main.add(attentionKeys(headShape, p(keyCache)));

		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer maskRow =
			greaterThan(indices, dynamicPosition, c(-10000.0), c(0.0), false);
		CollectionProducer causalMask = maskRow.reshape(1, 1, seqLen).repeat(heads);
		main.add(layer("mask", attentionShape, attentionShape,
			input -> add(input, causalMask)));

		main.add(softmax(attentionShape, true));

		model.add(main);
		log("Compiling model...");
		CompiledModel compiled = model.compile();

		boolean allSame = testPositions(compiled, position, heads * seqLen);
		if (allSame) {
			log("[FAIL] Test 8 - rmsnorm breaks position!");
		} else {
			log("[PASS] Test 8 - rmsnorm is OK");
		}
		Assert.assertFalse("Test 8 should PASS", allSame);
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
}
