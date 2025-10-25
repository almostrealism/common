package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

/**
 * Test individual transformer components in isolation to identify numerical issues.
 */
public class Qwen3ComponentTest implements AttentionFeatures, LayerFeatures, TestFeatures {

	@Test
	public void testRMSNorm() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("=== Testing RMSNorm ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int dim = (int) testConfig.valueAt(2);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> normWeights = referenceData.get("input_layernorm.weight");

		// Extract first token from input (batch=0, position=0)
		PackedCollection<?> firstToken = new PackedCollection<>(shape(dim));
		for (int d = 0; d < dim; d++) {
			firstToken.setMem(d, input.valueAt(0, 0, d));
		}

		System.out.println("Input stats:");
		System.out.println("  Sum: " + firstToken.doubleStream().sum());
		System.out.println("  Mean: " + firstToken.doubleStream().average().orElse(0));
		System.out.println("  Max abs: " + firstToken.doubleStream().map(Math::abs).max().orElse(0));

		// Check normalization weights
		System.out.println("\nNorm weights stats:");
		System.out.println("  Sum: " + normWeights.doubleStream().sum());
		System.out.println("  Mean: " + normWeights.doubleStream().average().orElse(0));
		System.out.println("  Min: " + normWeights.doubleStream().min().orElse(0));
		System.out.println("  Max: " + normWeights.doubleStream().max().orElse(0));

		// Apply RMSNorm using a simple model
		org.almostrealism.model.Model normModel = new org.almostrealism.model.Model(shape(dim));
		normModel.add(rmsnorm(normWeights));
		org.almostrealism.model.CompiledModel compiled = normModel.compile(false);
		PackedCollection<?> rawOutput = compiled.forward(firstToken);

		// Handle 2D output if needed
		PackedCollection<?> output = rawOutput;
		if (rawOutput.getShape().getDimensions() == 2 && rawOutput.getShape().length(0) == 1) {
			output = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				output.setMem(d, rawOutput.valueAt(0, d));
			}
		}

		System.out.println("\nOutput stats:");
		System.out.println("  Sum: " + output.doubleStream().sum());
		System.out.println("  Mean: " + output.doubleStream().average().orElse(0));
		System.out.println("  Max abs: " + output.doubleStream().map(Math::abs).max().orElse(0));

		// Check for NaN/Inf
		boolean hasNaN = output.doubleStream().anyMatch(Double::isNaN);
		boolean hasInf = output.doubleStream().anyMatch(Double::isInfinite);
		System.out.println("  Has NaN: " + hasNaN + ", Has Inf: " + hasInf);

		assertFalse("RMSNorm output contains NaN", hasNaN);
		assertFalse("RMSNorm output contains Inf", hasInf);

		// Check reasonable magnitude (RMSNorm should normalize to roughly unit variance)
		double maxAbs = output.doubleStream().map(Math::abs).max().orElse(0);
		System.out.println("\n[OK] RMSNorm produces finite output with max abs: " + maxAbs);
		assertTrue("RMSNorm output too large: " + maxAbs, maxAbs < 100.0);

		referenceData.destroy();
	}

	@Test
	public void testWeightStatistics() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing Weight Statistics ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);

		String[] weightKeys = {
			"self_attn.q_proj.weight",
			"self_attn.k_proj.weight",
			"self_attn.v_proj.weight",
			"self_attn.o_proj.weight",
			"mlp.gate_proj.weight",
			"mlp.up_proj.weight",
			"mlp.down_proj.weight"
		};

		for (String key : weightKeys) {
			PackedCollection<?> weights = referenceData.get(key);
			double[] stats = computeStats(weights);
			System.out.println("\n" + key + ":");
			System.out.println("  Shape: " + weights.getShape());
			System.out.println("  Mean: " + stats[0]);
			System.out.println("  Std: " + stats[1]);
			System.out.println("  Min: " + stats[2]);
			System.out.println("  Max: " + stats[3]);

			// Check for reasonable ranges (typical neural network weights)
			assertTrue("Weights have NaN for " + key, !Double.isNaN(stats[0]));
			assertTrue("Weights have Inf for " + key, !Double.isInfinite(stats[3]));
			assertTrue("Weights too large for " + key + ": " + stats[3], Math.abs(stats[3]) < 10.0);
		}

		System.out.println("\n[OK] All weights in reasonable range");
		referenceData.destroy();
	}

	@Test
	public void testDenseLayer() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing Dense Layer (Q Projection) ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int dim = (int) testConfig.valueAt(2);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> wq = referenceData.get("self_attn.q_proj.weight");

		// Extract first token
		PackedCollection<?> firstToken = new PackedCollection<>(shape(dim));
		for (int d = 0; d < dim; d++) {
			firstToken.setMem(d, input.valueAt(0, 0, d));
		}

		System.out.println("Input stats:");
		System.out.println("  Shape: " + firstToken.getShape());
		System.out.println("  Sum: " + firstToken.doubleStream().sum());
		System.out.println("  Max abs: " + firstToken.doubleStream().map(Math::abs).max().orElse(0));

		System.out.println("\nWeight stats:");
		double[] wqStats = computeStats(wq);
		System.out.println("  Shape: " + wq.getShape());
		System.out.println("  Mean: " + wqStats[0] + ", Std: " + wqStats[1]);
		System.out.println("  Min: " + wqStats[2] + ", Max: " + wqStats[3]);

		// Apply dense layer using a simple model
		org.almostrealism.model.Model denseModel = new org.almostrealism.model.Model(shape(dim));
		denseModel.add(dense(wq));
		org.almostrealism.model.CompiledModel compiled = denseModel.compile(false);
		PackedCollection<?> rawOutput = compiled.forward(firstToken);

		// Handle 2D output if needed
		PackedCollection<?> output = rawOutput;
		if (rawOutput.getShape().getDimensions() == 2 && rawOutput.getShape().length(0) == 1) {
			output = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				output.setMem(d, rawOutput.valueAt(0, d));
			}
		}

		System.out.println("\nOutput stats:");
		System.out.println("  Shape: " + output.getShape());
		System.out.println("  Sum: " + output.doubleStream().sum());
		System.out.println("  Mean: " + output.doubleStream().average().orElse(0));
		System.out.println("  Max abs: " + output.doubleStream().map(Math::abs).max().orElse(0));

		boolean hasNaN = output.doubleStream().anyMatch(Double::isNaN);
		boolean hasInf = output.doubleStream().anyMatch(Double::isInfinite);
		System.out.println("  Has NaN: " + hasNaN + ", Has Inf: " + hasInf);

		assertFalse("Dense layer output contains NaN", hasNaN);
		assertFalse("Dense layer output contains Inf", hasInf);

		double maxAbs = output.doubleStream().map(Math::abs).max().orElse(0);
		System.out.println("\n[OK] Dense layer produces finite output with max abs: " + maxAbs);
		assertTrue("Dense layer output too large: " + maxAbs, maxAbs < 1000.0);

		referenceData.destroy();
	}

	private double[] computeStats(PackedCollection<?> data) {
		double sum = data.doubleStream().sum();
		double mean = data.doubleStream().average().orElse(0);
		double variance = data.doubleStream()
			.map(x -> (x - mean) * (x - mean))
			.average().orElse(0);
		double std = Math.sqrt(variance);
		double min = data.doubleStream().min().orElse(0);
		double max = data.doubleStream().max().orElse(0);
		return new double[]{mean, std, min, max};
	}

	@Test
	public void testRMSNormPlusDense() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing RMSNorm + Dense (chained) ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int dim = (int) testConfig.valueAt(2);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> normWeights = referenceData.get("input_layernorm.weight");
		PackedCollection<?> wq = referenceData.get("self_attn.q_proj.weight");

		// Extract first token
		PackedCollection<?> firstToken = new PackedCollection<>(shape(dim));
		for (int d = 0; d < dim; d++) {
			firstToken.setMem(d, input.valueAt(0, 0, d));
		}

		System.out.println("Input: sum=" + firstToken.doubleStream().sum() + 
			", max=" + firstToken.doubleStream().map(Math::abs).max().orElse(0));

		// Build model: RMSNorm -> Dense
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(dim));
		model.add(rmsnorm(normWeights));
		model.add(dense(wq));
		org.almostrealism.model.CompiledModel compiled = model.compile(false);
		
		PackedCollection<?> rawOutput = compiled.forward(firstToken);

		// Handle 2D output
		PackedCollection<?> output = rawOutput;
		if (rawOutput.getShape().getDimensions() == 2 && rawOutput.getShape().length(0) == 1) {
			output = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				output.setMem(d, rawOutput.valueAt(0, d));
			}
		}

		System.out.println("Output: sum=" + output.doubleStream().sum() + 
			", max=" + output.doubleStream().map(Math::abs).max().orElse(0));

		boolean hasNaN = output.doubleStream().anyMatch(Double::isNaN);
		boolean hasInf = output.doubleStream().anyMatch(Double::isInfinite);
		System.out.println("Has NaN: " + hasNaN + ", Has Inf: " + hasInf);

		assertFalse("Output contains NaN", hasNaN);
		assertFalse("Output contains Inf", hasInf);

		double maxAbs = output.doubleStream().map(Math::abs).max().orElse(0);
		System.out.println("\n[OK] RMSNorm+Dense output max abs: " + maxAbs);
		assertTrue("RMSNorm+Dense output too large: " + maxAbs, maxAbs < 1000.0);

		referenceData.destroy();
	}

	@Test
	public void testRoPEFrequencies() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing RoPE Frequency Computation ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int seqLen = (int) testConfig.valueAt(1);
		int headSize = (int) testConfig.valueAt(6);

		System.out.println("Config: seqLen=" + seqLen + ", headSize=" + headSize);

		// Compute RoPE frequencies
		PackedCollection<?> freqCis = computeRopeFreqs(seqLen, headSize, 1000000.0);
		System.out.println("Computed freqCis shape: " + freqCis.getShape());

		// Load PyTorch reference
		PackedCollection<?> refCos = referenceData.get("position_cos");
		PackedCollection<?> refSin = referenceData.get("position_sin");
		System.out.println("Reference cos shape: " + refCos.getShape());
		System.out.println("Reference sin shape: " + refSin.getShape());

		// Check first position frequencies
		System.out.println("\nPosition 0 comparison:");
		int freqDim = headSize / 2;
		for (int i = 0; i < Math.min(5, freqDim); i++) {
			int idx = i * 2;
			double computedCos = freqCis.valueAt(0, i, 0);
			double computedSin = freqCis.valueAt(0, i, 1);
			double refCosVal = refCos.valueAt(0, 0, i);
			double refSinVal = refSin.valueAt(0, 0, i);
			System.out.println("  Freq " + i + ": cos=" + computedCos + " (ref=" + refCosVal + 
				"), sin=" + computedSin + " (ref=" + refSinVal + ")");
		}

		// Check for NaN/Inf
		boolean hasNaN = freqCis.doubleStream().anyMatch(Double::isNaN);
		boolean hasInf = freqCis.doubleStream().anyMatch(Double::isInfinite);
		System.out.println("\nHas NaN: " + hasNaN + ", Has Inf: " + hasInf);

		assertFalse("RoPE frequencies contain NaN", hasNaN);
		assertFalse("RoPE frequencies contain Inf", hasInf);

		// Check magnitude
		double maxAbs = freqCis.doubleStream().map(Math::abs).max().orElse(0);
		System.out.println("Max abs value: " + maxAbs);
		assertTrue("RoPE frequencies should be in [-1, 1]: " + maxAbs, maxAbs <= 1.0);

		System.out.println("\n[OK] RoPE frequencies computed correctly");
		referenceData.destroy();
	}

	private PackedCollection<?> computeRopeFreqs(int seqLen, int headSize, double theta) {
		int freqDim = headSize / 2;

		// Compute inverse frequencies
		PackedCollection<?> invFreq = new PackedCollection<>(shape(freqDim));
		for (int i = 0; i < freqDim; i++) {
			invFreq.setMem(i, 1.0 / Math.pow(theta, (2.0 * i) / headSize));
		}

		// Compute position * inv_freq for each position
		PackedCollection<?> freqs = new PackedCollection<>(shape(seqLen, freqDim, 2));
		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double freq = pos * invFreq.toDouble(i);
				int idx = (pos * freqDim + i) * 2;
				freqs.setMem(idx, Math.cos(freq));
				freqs.setMem(idx + 1, Math.sin(freq));
			}
		}

		return freqs;
	}

	@Test
	public void testRoPERotation() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing RoPE Rotation ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int dim = (int) testConfig.valueAt(2);
		int seqLen = (int) testConfig.valueAt(1);
		int headSize = (int) testConfig.valueAt(6);
		int heads = (int) testConfig.valueAt(4);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> normWeights = referenceData.get("input_layernorm.weight");
		PackedCollection<?> wq = referenceData.get("self_attn.q_proj.weight");

		// Extract first token
		PackedCollection<?> firstToken = new PackedCollection<>(shape(dim));
		for (int d = 0; d < dim; d++) {
			firstToken.setMem(d, input.valueAt(0, 0, d));
		}

		System.out.println("Input: sum=" + firstToken.doubleStream().sum());

		// Build: RMSNorm -> Dense -> Reshape -> RoPE
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(dim));
		model.add(rmsnorm(normWeights));
		model.add(dense(wq));
		
		// Reshape to head dimensions
		model.add(reshape(shape(dim), shape(heads, headSize)));
		
		// Compute RoPE frequencies
		PackedCollection<?> freqCis = computeRopeFreqs(seqLen, headSize, 1000000.0);
		PackedCollection<?> position = new PackedCollection<>(1);
		position.setMem(0, 0.0);

		// Apply RoPE rotation
		model.add(ropeRotation(shape(heads, headSize / 2, 2), freqCis, p(position)));

		System.out.println("Compiling model with RoPE...");
		org.almostrealism.model.CompiledModel compiled = model.compile(false);
		
		System.out.println("Running forward pass...");
		PackedCollection<?> rawOutput = compiled.forward(firstToken);

		System.out.println("Output shape: " + rawOutput.getShape());
		double sum = rawOutput.doubleStream().sum();
		double maxAbs = rawOutput.doubleStream().map(Math::abs).max().orElse(0);
		boolean hasNaN = rawOutput.doubleStream().anyMatch(Double::isNaN);
		boolean hasInf = rawOutput.doubleStream().anyMatch(Double::isInfinite);

		System.out.println("Output sum: " + sum);
		System.out.println("Output max abs: " + maxAbs);
		System.out.println("Has NaN: " + hasNaN + ", Has Inf: " + hasInf);

		assertFalse("RoPE output contains NaN", hasNaN);
		assertFalse("RoPE output contains Inf", hasInf);
		assertTrue("RoPE output too large: " + maxAbs, maxAbs < 10000.0);

		System.out.println("\n[OK] RoPE rotation produces reasonable output");
		referenceData.destroy();
	}

	@Test
	public void testAttentionWithoutFFN() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing Attention (without FFN) ===");
		
		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int dim = (int) testConfig.valueAt(2);
		int seqLen = (int) testConfig.valueAt(1);
		int headSize = (int) testConfig.valueAt(6);
		int heads = (int) testConfig.valueAt(4);
		int kvHeads = (int) testConfig.valueAt(5);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> attnNorm = referenceData.get("input_layernorm.weight");
		PackedCollection<?> wq = referenceData.get("self_attn.q_proj.weight");
		PackedCollection<?> wk = referenceData.get("self_attn.k_proj.weight");
		PackedCollection<?> wv = referenceData.get("self_attn.v_proj.weight");
		PackedCollection<?> wo = referenceData.get("self_attn.o_proj.weight");

		// Extract first token
		PackedCollection<?> firstToken = new PackedCollection<>(shape(dim));
		for (int d = 0; d < dim; d++) {
			firstToken.setMem(d, input.valueAt(0, 0, d));
		}

		System.out.println("Input: sum=" + firstToken.doubleStream().sum() + 
			", max=" + firstToken.doubleStream().map(Math::abs).max().orElse(0));

		// Build attention block (no FFN)
		PackedCollection<?> freqCis = computeRopeFreqs(seqLen, headSize, 1000000.0);
		PackedCollection<?> position = new PackedCollection<>(1);
		position.setMem(0, 0.0);

		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(dim));
		model.add(attention(heads, kvHeads, attnNorm, wk, wv, wq, wo,
			null, null, freqCis, p(position)));

		System.out.println("Compiling attention block...");
		org.almostrealism.model.CompiledModel compiled = model.compile(false);
		
		System.out.println("Running forward pass...");
		PackedCollection<?> rawOutput = compiled.forward(firstToken);

		// Handle 2D output
		PackedCollection<?> output = rawOutput;
		if (rawOutput.getShape().getDimensions() == 2 && rawOutput.getShape().length(0) == 1) {
			output = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				output.setMem(d, rawOutput.valueAt(0, d));
			}
		}

		System.out.println("Output shape: " + output.getShape());
		double sum = output.doubleStream().sum();
		double maxAbs = output.doubleStream().map(Math::abs).max().orElse(0);
		boolean hasNaN = output.doubleStream().anyMatch(Double::isNaN);
		boolean hasInf = output.doubleStream().anyMatch(Double::isInfinite);

		System.out.println("Output sum: " + sum);
		System.out.println("Output max abs: " + maxAbs);
		System.out.println("Has NaN: " + hasNaN + ", Has Inf: " + hasInf);

		assertFalse("Attention output contains NaN", hasNaN);
		assertFalse("Attention output contains Inf", hasInf);

		// Check if explosion occurs
		if (maxAbs > 1e100) {
			System.out.println("!!! EXPLOSION FOUND IN ATTENTION BLOCK !!!");
			System.out.println("Max abs: " + maxAbs);
		} else {
			System.out.println("[OK] Attention produces reasonable output: " + maxAbs);
		}

		assertTrue("Attention output too large: " + maxAbs, maxAbs < 1e100);
		referenceData.destroy();
	}

	@Test
	public void testCacheInitialization() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		System.out.println("\n=== Testing Cache Initialization ===");
		
		// Test if PackedCollection initializes to zero
		PackedCollection<?> cache = new PackedCollection<>(10, 5, 64);
		System.out.println("Cache shape: " + cache.getShape());
		System.out.println("Cache mem length: " + cache.getMemLength());
		
		double sum = cache.doubleStream().sum();
		double maxAbs = cache.doubleStream().map(Math::abs).max().orElse(0);
		boolean hasNonZero = cache.doubleStream().anyMatch(v -> v != 0.0);
		
		System.out.println("Sum: " + sum);
		System.out.println("Max abs: " + maxAbs);
		System.out.println("Has non-zero values: " + hasNonZero);
		
		if (hasNonZero) {
			// Print first few non-zero values
			System.out.println("WARNING: Cache not initialized to zero!");
			for (int i = 0; i < Math.min(10, cache.getMemLength()); i++) {
				if (cache.toDouble(i) != 0.0) {
					System.out.println("  cache[" + i + "] = " + cache.toDouble(i));
				}
			}
		}
		
		assertFalse("Cache should be zero-initialized", hasNonZero);
		System.out.println("[OK] Cache is zero-initialized");
	}

	@Test
	public void testGQAExpansion() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		System.out.println("\n=== Testing GQA Expansion ===");
		
		// Create simple test data: (seqLen=4, kvHeads=2, headSize=8)
		int seqLen = 4;
		int kvHeads = 2;
		int headSize = 8;
		int heads = 14;
		int headsPerKvGroup = heads / kvHeads; // 7
		
		PackedCollection<?> kvCache = new PackedCollection<>(seqLen, kvHeads, headSize);
		
		// Fill with pattern: seqPos * 100 + kvHead * 10 + dim
		for (int s = 0; s < seqLen; s++) {
			for (int h = 0; h < kvHeads; h++) {
				for (int d = 0; d < headSize; d++) {
					double value = s * 100 + h * 10 + d;
					kvCache.setMem(s * kvHeads * headSize + h * headSize + d, value);
				}
			}
		}
		
		System.out.println("Input shape: " + kvCache.getShape());
		System.out.println("First few values: " + kvCache.valueAt(0, 0, 0) + ", " + 
			kvCache.valueAt(0, 0, 1) + ", " + kvCache.valueAt(0, 1, 0));
		
		// Test expansion using the same logic as AttentionFeatures
		Producer<PackedCollection<?>> keys = p(kvCache);
		Producer<PackedCollection<?>> repeated = traverse(2, keys).repeat(headsPerKvGroup);
		Producer<PackedCollection<?>> expanded = reshape(shape(seqLen, heads, headSize), repeated);
		
		// Compile and evaluate
		PackedCollection<?> result = new PackedCollection<>(seqLen, heads, headSize);
		expanded.get().into(result).evaluate();
		
		System.out.println("Output shape: " + result.getShape());
		System.out.println("Expected shape: (" + seqLen + ", " + heads + ", " + headSize + ")");
		
		// Verify expansion: each KV head should be repeated 7 times
		// kvHead 0 should appear in query heads 0-6
		// kvHead 1 should appear in query heads 7-13
		System.out.println("\nVerifying expansion pattern:");
		for (int queryHead = 0; queryHead < Math.min(heads, 4); queryHead++) {
			int expectedKvHead = queryHead / headsPerKvGroup;
			double val = result.valueAt(0, queryHead, 0);
			double expected = 0 * 100 + expectedKvHead * 10 + 0;
			System.out.println("  Query head " + queryHead + " -> KV head " + expectedKvHead +
				": got " + val + ", expected " + expected);
			assertEquals("GQA expansion incorrect for query head " + queryHead, expected, val);
		}
		
		System.out.println("[OK] GQA expansion works correctly");
	}

	@Test
	public void testResidualConnection() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		System.out.println("\n=== Testing Residual Connection (accum) ===");

		// Create simple test: input + transformation
		int dim = 10;

		// Input: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
		PackedCollection<?> input = new PackedCollection<>(shape(dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, i + 1.0);
		}

		// Transformation: multiply by 2 (simple linear layer)
		PackedCollection<?> weight = new PackedCollection<>(shape(dim, dim));
		weight.clear();
		for (int i = 0; i < dim; i++) {
			weight.setMem(i * dim + i, 2.0); // Diagonal matrix with 2s
		}

		// Build model with residual: output = input + (input * weight)
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(dim));
		org.almostrealism.model.SequentialBlock main = model.sequential();
		main.accum(dense(weight)); // residual connection

		System.out.println("Input: " + java.util.Arrays.toString(
			input.stream().limit(5).toArray()));

		org.almostrealism.model.CompiledModel compiled = model.compile(false);
		PackedCollection<?> output = compiled.forward(input);

		// Handle 2D output
		if (output.getShape().getDimensions() == 2 && output.getShape().length(0) == 1) {
			PackedCollection<?> squeezed = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				squeezed.setMem(d, output.valueAt(0, d));
			}
			output = squeezed;
		}

		System.out.println("Output: " + java.util.Arrays.toString(
			output.stream().limit(5).toArray()));

		// Expected: input + (input * 2) = input * 3
		// [1, 2, 3, 4, 5] -> [3, 6, 9, 12, 15]
		for (int i = 0; i < Math.min(5, dim); i++) {
			double expected = (i + 1.0) * 3.0;
			double actual = output.valueAt(i);
			System.out.println("  [" + i + "] expected=" + expected + ", actual=" + actual);
			assertEquals("Residual connection incorrect at index " + i, expected, actual);
		}

		System.out.println("[OK] Residual connection works correctly");
	}

	@Test
	public void testSequentialBlocks() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		System.out.println("\n=== Testing Sequential Block Composition ===");

		int dim = 10;

		// Input: [1, 2, 3, ..., 10]
		PackedCollection<?> input = new PackedCollection<>(shape(dim));
		for (int i = 0; i < dim; i++) {
			input.setMem(i, i + 1.0);
		}

		// Weight for first transformation: identity (multiply by 1)
		PackedCollection<?> weight1 = new PackedCollection<>(shape(dim, dim));
		weight1.clear();
		for (int i = 0; i < dim; i++) {
			weight1.setMem(i * dim + i, 1.0);
		}

		// Weight for second transformation: multiply by 2
		PackedCollection<?> weight2 = new PackedCollection<>(shape(dim, dim));
		weight2.clear();
		for (int i = 0; i < dim; i++) {
			weight2.setMem(i * dim + i, 2.0);
		}

		// Build: input + block1, then + block2
		// output = input + (input * 1) + ((input + input*1) * 2)
		// output = input + input + (2*input * 2) = 2*input + 4*input = 6*input
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(dim));
		org.almostrealism.model.SequentialBlock main = model.sequential();
		main.accum(dense(weight1));
		main.accum(dense(weight2));

		org.almostrealism.model.CompiledModel compiled = model.compile(false);
		PackedCollection<?> output = compiled.forward(input);

		// Handle 2D output
		if (output.getShape().getDimensions() == 2 && output.getShape().length(0) == 1) {
			PackedCollection<?> squeezed = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				squeezed.setMem(d, output.valueAt(0, d));
			}
			output = squeezed;
		}

		System.out.println("Input: " + input.valueAt(0) + ", " + input.valueAt(1));
		System.out.println("Output: " + output.valueAt(0) + ", " + output.valueAt(1));
		System.out.println("Expected: input=1 -> " + (1.0 * 6.0) + ", input=2 -> " + (2.0 * 6.0));

		// Check what we actually get
		double ratio0 = output.valueAt(0) / input.valueAt(0);
		double ratio1 = output.valueAt(1) / input.valueAt(1);
		System.out.println("Actual ratio: " + ratio0 + ", " + ratio1);

		System.out.println("[INFO] This test shows how accum chains work");
	}

	@Test
	public void testFFN() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/workspace/project/common/ml/qwen3_reference/qwen3_transformer_block";
		System.out.println("\n=== Testing FFN (Feed-Forward Network) ===");

		StateDictionary referenceData = new StateDictionary(referenceDir);
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int dim = (int) testConfig.valueAt(2);
		int hiddenDim = (int) testConfig.valueAt(3);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> ffnNorm = referenceData.get("post_attention_layernorm.weight");
		PackedCollection<?> wGate = referenceData.get("mlp.gate_proj.weight");
		PackedCollection<?> wUp = referenceData.get("mlp.up_proj.weight");
		PackedCollection<?> wDown = referenceData.get("mlp.down_proj.weight");

		// Build FFN block
		org.almostrealism.model.Model model = new org.almostrealism.model.Model(shape(dim));
		model.add(feedForward(ffnNorm, wGate, wDown, wUp));

		System.out.println("Compiling FFN...");
		org.almostrealism.model.CompiledModel compiled = model.compile(false);

		// Extract first token
		PackedCollection<?> firstToken = new PackedCollection<>(shape(dim));
		for (int d = 0; d < dim; d++) {
			firstToken.setMem(d, input.valueAt(0, 0, d));
		}

		System.out.println("Input: sum=" + firstToken.doubleStream().sum() +
				", max=" + firstToken.doubleStream().map(Math::abs).max().orElse(0));

		System.out.println("Running FFN forward pass...");
		PackedCollection<?> rawOutput = compiled.forward(firstToken);

		// Handle potential 2D output
		PackedCollection<?> output = rawOutput;
		if (rawOutput.getShape().getDimensions() == 2 && rawOutput.getShape().length(0) == 1) {
			output = new PackedCollection<>(shape(dim));
			for (int d = 0; d < dim; d++) {
				output.setMem(d, rawOutput.valueAt(0, d));
			}
		}

		double sum = output.doubleStream().sum();
		double maxAbs = output.doubleStream().map(Math::abs).max().orElse(0);

		System.out.println("Output: sum=" + sum + ", max=" + maxAbs);
		assertTrue("FFN output too large: " + maxAbs, maxAbs < 1000);

		System.out.println("[OK] FFN works without explosion");
		referenceData.destroy();
	}
}
