/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.MonitorReceptor;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.model.Model;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class AttentionTests implements AttentionFeatures, TestFeatures {

	private static final int TEST_BATCH_SIZE = 1;
	private static final int TEST_SEQ_LEN = 4;
	private static final int TEST_DIM = 16;
	private static final int TEST_HEADS = 2;
	private static final int TEST_DIM_HEAD = TEST_DIM / TEST_HEADS;
	private static final int TEST_INV_FREQ_SIZE = TEST_DIM_HEAD / 4;

	/**
	* Sets up all weight matrices required for sequenceAttention with random values
	*/
	private Map<String, PackedCollection<?>> setupSequenceAttentionWeights() {
		Map<String, PackedCollection<?>> weights = new HashMap<>();
		
		// Pre-normalization weights
		weights.put("preNormWeight", new PackedCollection<>(shape(TEST_DIM)).fill(1.0));
		weights.put("preNormBias", new PackedCollection<>(shape(TEST_DIM)).fill(0.0));
		
		// Query, Key, Value, Output projection weights
		weights.put("wq", new PackedCollection<>(shape(TEST_DIM, TEST_DIM)).randnFill());
		weights.put("wk", new PackedCollection<>(shape(TEST_DIM, TEST_DIM)).randnFill());
		weights.put("wv", new PackedCollection<>(shape(TEST_DIM, TEST_DIM)).randnFill());
		weights.put("wo", new PackedCollection<>(shape(TEST_DIM, TEST_DIM)).randnFill());
		
		// Query and Key normalization weights (per head)
		weights.put("qNormWeight", new PackedCollection<>(shape(TEST_DIM_HEAD)).fill(1.0));
		weights.put("qNormBias", new PackedCollection<>(shape(TEST_DIM_HEAD)).fill(0.0));
		weights.put("kNormWeight", new PackedCollection<>(shape(TEST_DIM_HEAD)).fill(1.0));
		weights.put("kNormBias", new PackedCollection<>(shape(TEST_DIM_HEAD)).fill(0.0));
		
		// Inverse frequencies for rotary embeddings
		weights.put("invFreq", new PackedCollection<>(shape(TEST_INV_FREQ_SIZE)).fill(pos -> 1.0 / Math.pow(10000, 2.0 * pos[0] / TEST_DIM_HEAD)));
		
		return weights;
	}

	@Test
	public void sequenceAttentionBasic() {
		System.out.println("Testing sequenceAttention basic functionality");
		
		// Setup weights
		Map<String, PackedCollection<?>> weights = setupSequenceAttentionWeights();
		
		// Create input tensor: (batchSize, seqLen, dim)
		PackedCollection<?> input = new PackedCollection<>(shape(TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM))
				.randnFill();
		
		// Create the sequence attention block
		Block sequenceAttentionBlock = sequenceAttention(
				TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM, TEST_HEADS,
				weights.get("preNormWeight"), weights.get("preNormBias"),
				weights.get("wk"), weights.get("wv"), weights.get("wq"), weights.get("wo"),
				weights.get("qNormWeight"), weights.get("qNormBias"),
				weights.get("kNormWeight"), weights.get("kNormBias"),
				weights.get("invFreq")
		);
		
		// Setup the block
		sequenceAttentionBlock.setup().get().run();
		
		// Verify input and output shapes
		TraversalPolicy expectedShape = shape(TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM);
		assertEquals(expectedShape, sequenceAttentionBlock.getInputShape());
		assertEquals(expectedShape, sequenceAttentionBlock.getOutputShape());
		
		// Run forward pass
		PackedCollection<?> output = new PackedCollection<>(expectedShape);
		sequenceAttentionBlock.getForward().setReceptor(into(output));
		Process.optimized(sequenceAttentionBlock.getForward().push(cp(input))).get().run();
		
		// Basic validation - output should have same shape as input
		assertEquals(expectedShape, output.getShape());
		
		// Check that output contains reasonable values (not NaN, not all zeros)
		boolean hasNonZero = false;
		boolean hasNaN = false;
		
		for (int b = 0; b < TEST_BATCH_SIZE; b++) {
			for (int s = 0; s < TEST_SEQ_LEN; s++) {
				for (int d = 0; d < TEST_DIM; d++) {
					double val = output.valueAt(b, s, d);
					if (Double.isNaN(val)) {
						hasNaN = true;
					}
					if (Math.abs(val) > 1e-6) {
						hasNonZero = true;
					}
				}
			}
		}
		
		assertFalse("Output contains NaN values", hasNaN);
		assertTrue("Output should contain non-zero values", hasNonZero);
		
		System.out.println("sequenceAttention basic test passed - output shape: " + output.getShape());
	}

	@Test
	public void sequenceAttentionDimensions() {
		System.out.println("Testing sequenceAttention dimension handling");
		
		// Test with different dimensions
		int[] testBatchSizes = {1, 2};
		int[] testSeqLens = {2, 4, 8};
		int[] testDims = {4, 8, 12};
		int[] testHeads = {1, 2, 4};
		
		for (int batchSize : testBatchSizes) {
			for (int seqLen : testSeqLens) {
				for (int dim : testDims) {
					for (int heads : testHeads) {
						if (dim % heads != 0) continue; // Skip invalid head configurations
						
						int dimHead = dim / heads;
						if (dimHead % 4 != 0) continue; // Skip configurations where invFreq size would be fractional
						
						int invFreqSize = dimHead / 4;
						if (invFreqSize < 1) continue; // Skip very small dimensions
						
						System.out.println("Testing dimensions: batch=" + batchSize +
								", seq=" + seqLen + ", dim=" + dim + ", heads=" + heads);
						
						// Setup weights for this configuration
						Map<String, PackedCollection<?>> weights = new HashMap<>();
						weights.put("preNormWeight", new PackedCollection<>(shape(dim)).fill(1.0));
						weights.put("preNormBias", new PackedCollection<>(shape(dim)).fill(0.0));
						weights.put("wq", new PackedCollection<>(shape(dim, dim)).randnFill());
						weights.put("wk", new PackedCollection<>(shape(dim, dim)).randnFill());
						weights.put("wv", new PackedCollection<>(shape(dim, dim)).randnFill());
						weights.put("wo", new PackedCollection<>(shape(dim, dim)).randnFill());
						weights.put("qNormWeight", new PackedCollection<>(shape(dimHead)).fill(1.0));
						weights.put("qNormBias", new PackedCollection<>(shape(dimHead)).fill(0.0));
						weights.put("kNormWeight", new PackedCollection<>(shape(dimHead)).fill(1.0));
						weights.put("kNormBias", new PackedCollection<>(shape(dimHead)).fill(0.0));
						weights.put("invFreq", new PackedCollection<>(shape(invFreqSize)).randnFill());
						
						// Create input
						PackedCollection<?> input = new PackedCollection<>(shape(batchSize, seqLen, dim))
								.randnFill();
						
						// Create and test the block
						Block block = sequenceAttention(
								batchSize, seqLen, dim, heads,
								weights.get("preNormWeight"), weights.get("preNormBias"),
								weights.get("wk"), weights.get("wv"), weights.get("wq"), weights.get("wo"),
								weights.get("qNormWeight"), weights.get("qNormBias"),
								weights.get("kNormWeight"), weights.get("kNormBias"),
								weights.get("invFreq")
						);
						
						// Verify shapes
						TraversalPolicy expectedShape = shape(batchSize, seqLen, dim);
						assertEquals(expectedShape, block.getInputShape());
						assertEquals(expectedShape, block.getOutputShape());
					}
				}
			}
		}
		
		System.out.println("sequenceAttention dimension tests passed");
	}

	@Test
	public void sequenceAttentionInModel() {
		System.out.println("Testing sequenceAttention within a Model");
		
		// Setup weights
		Map<String, PackedCollection<?>> weights = setupSequenceAttentionWeights();
		
		// Create a Model with just the sequence attention layer
		Model model = new Model(shape(TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM));
		SequentialBlock main = model.sequential();
		
		// Add the sequence attention block
		main.add(sequenceAttention(
				TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM, TEST_HEADS,
				weights.get("preNormWeight"), weights.get("preNormBias"),
				weights.get("wk"), weights.get("wv"), weights.get("wq"), weights.get("wo"),
				weights.get("qNormWeight"), weights.get("qNormBias"),
				weights.get("kNormWeight"), weights.get("kNormBias"),
				weights.get("invFreq")
		));
		
		// Create input
		PackedCollection<?> input = new PackedCollection<>(shape(TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM))
				.randnFill();
		
		// Setup and run the model
		CompiledModel compiled = model.compile(false);
		PackedCollection<?> output = compiled.forward(input);
		
		// Validate output
		assertNotNull(output);
		assertEquals(shape(TEST_BATCH_SIZE, TEST_SEQ_LEN, TEST_DIM), output.getShape());
		
		// Check for reasonable output values
		boolean hasFiniteValues = true;
		for (int i = 0; i < output.getMemLength(); i++) {
			if (!Double.isFinite(output.toDouble(i))) {
				hasFiniteValues = false;
				break;
			}
		}
		
		assertTrue("Output should contain finite values", hasFiniteValues);
	}

	/**
	* Tests sequenceAttention against reference data generated from the actual
	* DiT Attention class in stable-audio-tools. This ensures our Java implementation
	* matches the real Python behavior rather than a made-up reference.
	*/
	@Test
	public void sequenceAttentionAgainstPythonReference() throws Exception {
		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/sequence_attention";

		// Load reference data using StateDictionary
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract input and expected output
		PackedCollection<?> referenceInput = referenceData.get("input");
		PackedCollection<?> expectedOutput = referenceData.get("expected_output");

		assertNotNull("Reference input not found", referenceInput);
		assertNotNull("Expected output not found", expectedOutput);

		System.out.println("Reference input total is " + referenceInput.doubleStream().map(Math::abs).sum());

		// Load all weights
		PackedCollection<?> preNormWeight = referenceData.get("model.model.transformer.layers.0.pre_norm.gamma");
		PackedCollection<?> preNormBias = referenceData.get("model.model.transformer.layers.0.pre_norm.beta");
		PackedCollection<?> toQKV = referenceData.get("model.model.transformer.layers.0.self_attn.to_qkv.weight");
		PackedCollection<?> toOut = referenceData.get("model.model.transformer.layers.0.self_attn.to_out.weight");
		PackedCollection<?> qNormWeight = referenceData.get("model.model.transformer.layers.0.self_attn.q_norm.weight");
		PackedCollection<?> qNormBias = referenceData.get("model.model.transformer.layers.0.self_attn.q_norm.bias");
		PackedCollection<?> kNormWeight = referenceData.get("model.model.transformer.layers.0.self_attn.k_norm.weight");
		PackedCollection<?> kNormBias = referenceData.get("model.model.transformer.layers.0.self_attn.k_norm.bias");
		PackedCollection<?> invFreq = referenceData.get("model.model.transformer.rotary_pos_emb.inv_freq");

		// Verify all weights were loaded
		assertNotNull("preNormWeight not found", preNormWeight);
		assertNotNull("preNormBias not found", preNormBias);
		assertNotNull("toQKV not found", toQKV);
		assertNotNull("toOut not found", toOut);
		assertNotNull("qNormWeight not found", qNormWeight);
		assertNotNull("qNormBias not found", qNormBias);
		assertNotNull("kNormWeight not found", kNormWeight);
		assertNotNull("kNormBias not found", kNormBias);
		assertNotNull("invFreq not found", invFreq);

		// Extract dimensions from the reference data
		TraversalPolicy inputShape = referenceInput.getShape();
		int batchSize = inputShape.length(0);
		int seqLen = inputShape.length(1);
		int embedDim = inputShape.length(2);
		int numHeads = 8; // From DiT config
		int dimHead = embedDim / numHeads;

		System.out.println("DiT Reference dimensions: batch=" + batchSize + ", seq=" + seqLen +
				", embed_dim=" + embedDim + ", heads=" + numHeads + ", dim_head=" + dimHead);
		System.out.println("QKV weight shape: " + toQKV.getShape());
		System.out.println("Output weight shape: " + toOut.getShape());

		// Split the fused QKV weight into separate Q, K, V weights (DiT uses fused projection)
		// toQKV has shape (embed_dim * 3, embed_dim) -> (3072, 1024) for embed_dim=1024
		int qkvDim = toQKV.getShape().length(0) / 3; // Should equal embed_dim
		assertEquals(embedDim, qkvDim);

		PackedCollection<?> wq = toQKV.range(shape(embedDim, embedDim), 0);
		PackedCollection<?> wk = toQKV.range(shape(embedDim, embedDim), embedDim);
		PackedCollection<?> wv = toQKV.range(shape(embedDim, embedDim), 2 * embedDim);

		// Create a simplified test model with just one layer to match Python reference
		Model model = new Model(inputShape);
		SequentialBlock main = model.sequential();

		// Add sequence attention that matches the actual DiT flow
		main.add(sequenceAttention(
				batchSize, seqLen, embedDim, numHeads,
				preNormWeight, preNormBias,
				wk, wv, wq, toOut, // Use toOut as the output projection
				qNormWeight, qNormBias,
				kNormWeight, kNormBias,
				invFreq
		));

		// Compile and run the model
		CompiledModel compiled = model.compile(false);
		PackedCollection<?> actualOutput = compiled.forward(referenceInput);

		System.out.println("Expected output total is " + expectedOutput.doubleStream().map(Math::abs).sum());
		System.out.println("Actual output total is " + actualOutput.doubleStream().map(Math::abs).sum());

		assertEquals(expectedOutput.getShape().getTotalSize(),
					actualOutput.getShape().getTotalSize());
		assertEquals(expectedOutput, actualOutput);
	}

	@Test
	public void attentionKeys() {
		int seqLength = 128;
		int heads = 12;
		int headSize = 64;
		int dim = heads * headSize;

		TraversalPolicy inputShape = shape(heads, headSize);
		TraversalPolicy keyShape = shape(seqLength, heads, headSize);
		TraversalPolicy outputShape = shape(heads, seqLength);

		PackedCollection<?> q = new PackedCollection<>(inputShape); // (heads, headSize)
		PackedCollection<?> keyCache = new PackedCollection<>(keyShape); // (seqLength, heads, headSize)

		q.fill(pos -> Math.random());
		keyCache.fill(pos -> Math.random());

		Producer<PackedCollection<?>> o = c(p(keyCache)).traverse(1).map(v -> v.multiply(p(q)))
											.traverse(2).sum()
											.divide(c(Math.sqrt(headSize)))
											.reshape(shape(seqLength, heads))
											.enumerate(1, 1)
											.reshape(outputShape);

//		PackedCollection<?> att = o.get().evaluate();
		// TODO This should not require optimization to pass, but currently it does
		PackedCollection<?> att = ((Evaluable<PackedCollection<?>>) ((ParallelProcess) o).optimize().get()).evaluate();

//		int p = (int) (0.8 * seqLength);
		int p = seqLength - 1;

		for (int h = 0; h < heads; h++) {
			for (int t = 0; t <= p; t++) {
				double score = 0.0;

				for (int i = 0; i < headSize; i++) {
					score += q.valueAt(h, i) * keyCache.valueAt(t, h, i);
				}

				score /= Math.sqrt(headSize);

				System.out.println("AttentionTests[" + t + "]: " + score + " vs " + att.valueAt(h, t));
				assertEquals(score, att.valueAt(h, t));
			}
		}
	}


	@Test
	public void attentionValues() {
		int seqLength = 1024;
		int heads = 12;
		int headSize = 64;
		int dim = heads * headSize;

		TraversalPolicy inputShape = shape(heads, seqLength);
		TraversalPolicy valueShape = shape(seqLength, heads, headSize);
		TraversalPolicy outputShape = shape(heads, headSize);
		TraversalPolicy finalShape = outputShape.flatten();

		PackedCollection<?> att = new PackedCollection<>(inputShape); // (heads, seqLength)
		PackedCollection<?> values = new PackedCollection<>(valueShape); // (seqLength, heads, headSize)
		PackedCollection<?> out = new PackedCollection<>(finalShape); // (heads, headSize)

		att.fill(pos -> Math.random());
		values.fill(pos -> Math.random());

//		int p = (int) (0.8 * seqLength);
		int p = seqLength - 1;

		CollectionProducer<PackedCollection<?>> v = c(p(values)).reshape(shape(seqLength, dim))
														.enumerate(1, 1)
														.reshape(shape(heads, headSize, seqLength));
		CollectionProducer<PackedCollection<?>> a = c(p(att)).traverse(1).expand(headSize, x -> x.repeat(headSize));
		CollectionProducer<PackedCollection<?>> o = multiply(traverseEach(a), traverseEach(v)).traverse(2).sum().reshape(finalShape);

		OperationProfile profiles = new OperationProfile();

		OperationList op = new OperationList("attention", false);
		op.add(a("attentionValues", traverseEach(p(out)), o));
		((OperationList) op.optimize()).get(profiles).run();

		profiles.print();

//		out = o.get().evaluate().reshape(finalShape);

		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				double vo = 0.0;

				for (int t = 0; t <= p; t++) {
					vo += att.valueAt(h, t) * values.valueAt(t, h, i);
				}

				System.out.println("AttentionTests[" + i + "]: " + vo + " vs " + out.valueAt(h * headSize + i));
				assertEquals(vo, out.valueAt(h * headSize + i));
			}
		}
	}

	@Test
	public void linearAttention() {
		int batchSize = 1;
		int dim = 8;
		int inputChannels = 8;
		int rows = 4;
		int cols = 4;

		PackedCollection<?> input =
				new PackedCollection<>(shape(batchSize, inputChannels, rows, cols)).randnFill();

		Block b = linearAttention(batchSize, dim, inputChannels, rows, cols);
		b.setup().get().run();

		b.getForward().setReceptor(new MonitorReceptor(out -> {
			out.print();
		}));
		Process.optimized(b.forward(cp(input))).get().run();
	}
}