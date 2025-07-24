/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AttentionTests implements AttentionFeatures, TestFeatures {

	private static final int TEST_BATCH_SIZE = 1;
	private static final int TEST_SEQ_LEN = 4;
	private static final int TEST_DIM = 16;
	private static final int TEST_HEADS = 2;
	private static final int TEST_DIM_HEAD = TEST_DIM / TEST_HEADS;
	private static final int TEST_INV_FREQ_SIZE = TEST_DIM_HEAD / 4;

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

	@Test
	public void qkvSplitOperation() {
		int batchSize = 1;
		int seqLen = 4;
		int embedDim = 16;

		// Create a simple input that's easy to verify
		PackedCollection<?> input = new PackedCollection<>(shape(batchSize, seqLen, embedDim * 3));
		input.fill(pos -> (double) pos[0] * seqLen * embedDim * 3 + pos[1] * embedDim * 3 + pos[2]);

		// Create a model that simulates the QKV split
		Model model = new Model(shape(batchSize, seqLen, 3 * embedDim));
		SequentialBlock main = model.sequential();
		main.reshape(batchSize, seqLen, 3, embedDim);

		// Test the QKV split using subset operations
		List<Block> qkv = main.split(shape( batchSize, seqLen, 1, embedDim), 0);
		SequentialBlock q = (SequentialBlock) qkv.get(0).reshape(batchSize, seqLen, embedDim);
		SequentialBlock k = (SequentialBlock) qkv.get(1).reshape(batchSize, seqLen, embedDim);
		SequentialBlock v = (SequentialBlock) qkv.get(2).reshape(batchSize, seqLen, embedDim);

		PackedCollection<?> qOut = new PackedCollection<>(shape(batchSize, seqLen, embedDim));
		PackedCollection<?> kOut = new PackedCollection<>(shape(batchSize, seqLen, embedDim));
		PackedCollection<?> vOut = new PackedCollection<>(shape(batchSize, seqLen, embedDim));

		q.andThen(into(qOut));
		k.andThen(into(kOut));
		v.andThen(into(vOut));

		CompiledModel compiled = model.compile(false);
		compiled.forward(input);

		// Verify the split worked correctly
		for (int b = 0; b < batchSize; b++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < embedDim; d++) {
					double expectedQ = input.valueAt(b, s, d);
					double expectedK = input.valueAt(b, s, d + embedDim);
					double expectedV = input.valueAt(b, s, d + 2 * embedDim);

					double actualQ = qOut.valueAt(b, s, d);
					double actualK = kOut.valueAt(b, s, d);
					double actualV = vOut.valueAt(b, s, d);

					assertEquals("Q mismatch at [" + b + "," + s + "," + d + "]", expectedQ, actualQ);
					assertEquals("K mismatch at [" + b + "," + s + "," + d + "]", expectedK, actualK);
					assertEquals("V mismatch at [" + b + "," + s + "," + d + "]", expectedV, actualV);
				}
			}
		}

		log("QKV split test passed!");
	}


	/**
	 * Tests isolated QK normalization against Python LayerNorm reference.
	 * This helps debug the norm step in sequenceAttention by testing it in isolation.
	 */
	@Test
	public void qkNormCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/qk_norm";

		// Load reference data
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract test configuration
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int batchSize = (int) testConfig.valueAt(0);
		int heads = (int) testConfig.valueAt(1);
		int seqLen = (int) testConfig.valueAt(2);
		int dimHead = (int) testConfig.valueAt(3);

		log("QK Norm test configuration:");
		log("  batchSize=" + batchSize + ", heads=" + heads +
				", seqLen=" + seqLen + ", dimHead=" + dimHead);

		// Load test data
		PackedCollection<?> qInput = referenceData.get("q_input");
		PackedCollection<?> kInput = referenceData.get("k_input");
		PackedCollection<?> qNormWeight = referenceData.get("q_norm_weight");
		PackedCollection<?> qNormBias = referenceData.get("q_norm_bias");
		PackedCollection<?> kNormWeight = referenceData.get("k_norm_weight");
		PackedCollection<?> kNormBias = referenceData.get("k_norm_bias");
		PackedCollection<?> qExpectedOutput = referenceData.get("q_expected_output");
		PackedCollection<?> kExpectedOutput = referenceData.get("k_expected_output");

		// Test Q normalization
		Model qModel = new Model(shape(batchSize, heads, seqLen, dimHead));
		SequentialBlock qMain = qModel.sequential();
		qMain.add(norm(qNormWeight, qNormBias, 1e-6));

		CompiledModel qCompiled = qModel.compile(false);
		PackedCollection<?> qActualOutput = qCompiled.forward(qInput);

		// Test K normalization
		Model kModel = new Model(shape(batchSize, heads, seqLen, dimHead));
		SequentialBlock kMain = kModel.sequential();
		kMain.add(norm(kNormWeight, kNormBias, 1e-6));

		CompiledModel kCompiled = kModel.compile(false);
		PackedCollection<?> kActualOutput = kCompiled.forward(kInput);

		// Compare results
		double qDiff = compare(qExpectedOutput, qActualOutput);
		double kDiff = compare(kExpectedOutput, kActualOutput);

		log("Q norm difference: " + qDiff);
		log("K norm difference: " + kDiff);
		log("Q expected output total: " + qExpectedOutput.doubleStream().map(Math::abs).sum());
		log("Q actual output total: " + qActualOutput.doubleStream().map(Math::abs).sum());
		log("K expected output total: " + kExpectedOutput.doubleStream().map(Math::abs).sum());
		log("K actual output total: " + kActualOutput.doubleStream().map(Math::abs).sum());

		assertTrue("Q normalization does not match Python reference within tolerance", qDiff < 1e-5);
		assertTrue("K normalization does not match Python reference within tolerance", kDiff < 1e-5);
	}

	/**
	 * Tests scaledDotProductAttention against PyTorch's F.scaled_dot_product_attention
	 * to isolate and verify just the attention computation mechanism.
	 */
	@Test
	public void scaledDotProductAttentionCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/scaled_dot_product_attention";

		// Load reference data
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract test inputs and expected output
		PackedCollection<?> q = referenceData.get("q");
		PackedCollection<?> k = referenceData.get("k");
		PackedCollection<?> v = referenceData.get("v");
		PackedCollection<?> expectedOutput = referenceData.get("expected_output");

		assertNotNull("Q tensor not found", q);
		assertNotNull("K tensor not found", k);
		assertNotNull("V tensor not found", v);
		assertNotNull("Expected output not found", expectedOutput);

		// Extract dimensions
		TraversalPolicy qShape = q.getShape();
		int batchSize = qShape.length(0);
		int heads = qShape.length(1);
		int seqLen = qShape.length(2);
		int dimHead = qShape.length(3);

		log("Scaled dot-product attention test dimensions:");
		log("  batch=" + batchSize + ", heads=" + heads + ", seq=" + seqLen + ", dimHead=" + dimHead);
		log("  Q shape=" + q.getShape() + ", K shape=" + k.getShape() + ", V shape=" + v.getShape());

		// Test our scaled dot-product attention implementation
		Model model = new Model(shape(batchSize, heads, seqLen, dimHead));
		model.add(scaledDotProductAttention(batchSize, seqLen, heads, dimHead, k, v));

		CompiledModel compiled = model.compile(false);
		PackedCollection<?> actualOutput = compiled.forward(q);
		log("Expected output total: " + expectedOutput.doubleStream().map(Math::abs).sum());
		log("Actual output total: " + actualOutput.doubleStream().map(Math::abs).sum());

		assertEquals(expectedOutput.getShape().getTotalSize(),
				actualOutput.getShape().getTotalSize());

		double diff = compare(expectedOutput, actualOutput);
		log("Difference between expected and actual scaled dot-product attention = " + diff);
		assertTrue("Scaled dot-product attention output does not match PyTorch reference within tolerance", diff < 1e-5);
	}

	@Test
	public void sequenceAttentionSimplified() {
		// Use smaller dimensions for easier debugging
		int batchSize = 1;
		int seqLen = 4;
		int embedDim = 16;
		int heads = 2;
		int dimHead = embedDim / heads;

		// Create simple test data
		PackedCollection<?> input = new PackedCollection<>(shape(batchSize, seqLen, embedDim)).randnFill();
		
		// QKV weight that keeps values mostly unchanged (near-identity)
		PackedCollection<?> toQKV = new PackedCollection<>(shape(embedDim * 3, embedDim));
		toQKV.fill(pos -> {
			int outIdx = pos[0];
			int inIdx = pos[1];
			// Create a block diagonal structure
			if (outIdx < embedDim && inIdx == outIdx) return 1.0;
			else if (outIdx >= embedDim && outIdx < 2 * embedDim && inIdx == (outIdx - embedDim)) return 1.0;
			else if (outIdx >= 2 * embedDim && inIdx == (outIdx - 2 * embedDim)) return 1.0;
			else return 0.0;
		});
		
		// Identity output projection
		PackedCollection<?> toOut = new PackedCollection<>(shape(embedDim, embedDim));
		toOut.fill(pos -> pos[0] == pos[1] ? 1.0 : 0.0);
		
		// Identity norms
		PackedCollection<?> qNormWeight = new PackedCollection<>(shape(dimHead)).fill(pos -> 1.0);
		PackedCollection<?> qNormBias = new PackedCollection<>(shape(dimHead)).fill(pos -> 0.0);
		PackedCollection<?> kNormWeight = new PackedCollection<>(shape(dimHead)).fill(pos -> 1.0);
		PackedCollection<?> kNormBias = new PackedCollection<>(shape(dimHead)).fill(pos -> 0.0);
		
		// Simple inv_freq for rotary
		PackedCollection<?> invFreq = new PackedCollection<>(shape(dimHead / 4)).fill(pos -> 0.01);

		// Run through attention
		Block attention = sequenceAttention(
				batchSize, seqLen, embedDim, heads,
				toQKV, toOut,
				qNormWeight, qNormBias,
				kNormWeight, kNormBias,
				invFreq
		);

		Model model = new Model(shape(batchSize, seqLen, embedDim));
		model.sequential().add(attention);
		
		CompiledModel compiled = model.compile(false);
		PackedCollection<?> output = compiled.forward(input);

		log("Simplified attention test:");
		log("Input shape: " + input.getShape() + ", total: " + input.doubleStream().map(Math::abs).sum());
		log("Output shape: " + output.getShape() + ", total: " + output.doubleStream().map(Math::abs).sum());
		
		// The output should be somewhat similar to input with these near-identity weights
		double diff = compare(input, output);
		log("Difference between input and output: " + diff);
	}

	/**
	 * Tests sequenceAttention against reference data generated from the actual
	 * DiT Attention class in stable-audio-tools. This ensures our Java implementation
	 * matches the real Python behavior rather than a made-up reference.
	 */
	@Test
	public void sequenceAttentionCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

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
		PackedCollection<?> toQKV = referenceData.get("model.model.transformer.layers.0.self_attn.to_qkv.weight");
		PackedCollection<?> toOut = referenceData.get("model.model.transformer.layers.0.self_attn.to_out.weight");
		PackedCollection<?> qNormWeight = referenceData.get("model.model.transformer.layers.0.self_attn.q_norm.weight");
		PackedCollection<?> qNormBias = referenceData.get("model.model.transformer.layers.0.self_attn.q_norm.bias");
		PackedCollection<?> kNormWeight = referenceData.get("model.model.transformer.layers.0.self_attn.k_norm.weight");
		PackedCollection<?> kNormBias = referenceData.get("model.model.transformer.layers.0.self_attn.k_norm.bias");
		PackedCollection<?> invFreq = referenceData.get("model.model.transformer.rotary_pos_emb.inv_freq");

		// Verify all weights were loaded
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
		int heads = 8;
		int dimHead = embedDim / heads;

		log("DiT Reference dimensions - batch=" + batchSize + ", seq=" + seqLen +
				", embed_dim=" + embedDim + ", heads=" + heads + ", dim_head=" + dimHead);
		log("QKV weight shape = " + toQKV.getShape());
		log("Output weight shape = " + toOut.getShape());

		// DiT uses fused QKV projection - no need to split manually anymore
		// toQKV has shape (embed_dim * 3, embed_dim)
		assertEquals(embedDim * 3, toQKV.getShape().length(0));
		assertEquals(embedDim, toQKV.getShape().length(1));

		// Create a simplified test model with just one layer
		Model model = new Model(inputShape);
		SequentialBlock main = model.sequential();

		// Add self-attention block
		main.add(sequenceAttention(
				batchSize, seqLen, embedDim, heads,
				toQKV, toOut,
				qNormWeight, qNormBias,
				kNormWeight, kNormBias,
				invFreq
		));

		// Compile and run the model
		CompiledModel compiled = model.compile(false);
		PackedCollection<?> actualOutput = compiled.forward(referenceInput);

		log("Expected output total is " + expectedOutput.doubleStream().map(Math::abs).sum());
		log("Actual output total is " + actualOutput.doubleStream().map(Math::abs).sum());

		assertEquals(expectedOutput.getShape().getTotalSize(),
				actualOutput.getShape().getTotalSize());
		double diff = compare(expectedOutput.reshape(seqLen, heads * dimHead),
				actualOutput.reshape(seqLen, heads * dimHead));
		log("Difference between expected and actual output = " + diff);
		assertTrue("Output does not match reference within tolerance", diff < 1e-5);
	}

	/**
	* Tests sequenceCrossAttention against reference data generated from the actual
	* DiT Attention class in cross-attention mode. This ensures our Java implementation
	* matches the real Python cross-attention behavior.
	*/
	@Test
	public void sequenceCrossAttentionCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/cross_attention";

		// Load reference data using StateDictionary
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract test configuration
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int batchSize = (int) testConfig.valueAt(0);
		int querySeqLen = (int) testConfig.valueAt(1);
		int contextSeqLen = (int) testConfig.valueAt(2);
		int embedDim = (int) testConfig.valueAt(3);
		int contextDim = (int) testConfig.valueAt(4);
		int heads = (int) testConfig.valueAt(5);
		int dimHead = embedDim / heads;

		log("Cross-attention test dimensions:");
		log("  batch=" + batchSize + ", querySeq=" + querySeqLen + ", contextSeq=" + contextSeqLen);
		log("  embedDim=" + embedDim + ", contextDim=" + contextDim + ", heads=" + heads + ", dimHead=" + dimHead);

		// Extract inputs and expected output
		PackedCollection<?> mainInput = referenceData.get("main_input");
		PackedCollection<?> contextInput = referenceData.get("context_input");
		PackedCollection<?> expectedOutput = referenceData.get("expected_output");

		assertNotNull("Main input not found", mainInput);
		assertNotNull("Context input not found", contextInput);
		assertNotNull("Expected output not found", expectedOutput);

		// Load cross-attention weights
		PackedCollection<?> toQ = referenceData.get("to_q.weight");
		PackedCollection<?> toKv = referenceData.get("to_kv.weight");
		PackedCollection<?> toOut = referenceData.get("to_out.weight");
		PackedCollection<?> qNormWeight = referenceData.get("q_norm.weight");
		PackedCollection<?> qNormBias = referenceData.get("q_norm.bias");
		PackedCollection<?> kNormWeight = referenceData.get("k_norm.weight");
		PackedCollection<?> kNormBias = referenceData.get("k_norm.bias");

		// Verify all weights were loaded
		assertNotNull("toQ not found", toQ);
		assertNotNull("toKv not found", toKv);
		assertNotNull("toOut not found", toOut);
		assertNotNull("qNormWeight not found", qNormWeight);
		assertNotNull("qNormBias not found", qNormBias);
		assertNotNull("kNormWeight not found", kNormWeight);
		assertNotNull("kNormBias not found", kNormBias);

		log("Cross-attention weight shapes:");
		log("  toQ: " + toQ.getShape());
		log("  toKv: " + toKv.getShape());
		log("  toOut: " + toOut.getShape());

		// Verify weight shapes match cross-attention expectations
		assertEquals(embedDim, toQ.getShape().length(0));  // Q projects from embedDim to embedDim
		assertEquals(embedDim, toQ.getShape().length(1));
		assertEquals(contextDim * 2, toKv.getShape().length(0));  // KV projects from contextDim to contextDim*2
		assertEquals(contextDim, toKv.getShape().length(1));

		// Create context input model
		Model contextModel = new Model(shape(batchSize, contextSeqLen, contextDim));
		SequentialBlock contextBlock = contextModel.sequential();
		// Context input is passed through as-is for this test

		// Create main model with cross-attention
		Model mainModel = new Model(shape(batchSize, querySeqLen, embedDim));
		SequentialBlock mainBlock = mainModel.sequential();

		// Add cross-attention block (no invFreq parameter since cross-attention doesn't use rotary embeddings)
		mainBlock.add(sequenceCrossAttention(
				batchSize, querySeqLen, contextSeqLen,
				embedDim, heads,
				toQ, toKv, toOut,
				qNormWeight, qNormBias,
				kNormWeight, kNormBias,
				contextBlock, null
		));

		// Compile and run the model
		CompiledModel mainCompiled = mainModel.compile(false);
		
		// Set context input in context model
		contextModel.compile(false).forward(contextInput);
		
		// Run main model with cross-attention
		PackedCollection<?> actualOutput = mainCompiled.forward(mainInput);

		log("Expected output total: " + expectedOutput.doubleStream().map(Math::abs).sum());
		log("Actual output total: " + actualOutput.doubleStream().map(Math::abs).sum());

		assertEquals(expectedOutput.getShape().getTotalSize(),
				actualOutput.getShape().getTotalSize());

		double diff = compare(expectedOutput.reshape(querySeqLen, embedDim),
				actualOutput.reshape(querySeqLen, embedDim));
		log("Cross-attention difference between expected and actual output = " + diff);
		assertTrue("Cross-attention output does not match reference within tolerance", diff < 1e-5);
	}

	/**
	* Tests feedForward against reference data generated from the actual
	* DiT FeedForward class. This ensures our Java SwiGLU implementation
	* matches the real Python behavior.
	*/
	@Test
	public void feedForwardCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/feedforward";

		// Load reference data using StateDictionary
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract test configuration
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int batchSize = (int) testConfig.valueAt(0);
		int seqLen = (int) testConfig.valueAt(1);
		int dim = (int) testConfig.valueAt(2);
		int innerDim = (int) testConfig.valueAt(3);

		log("Feed-forward test configuration:");
		log("  batchSize=" + batchSize + ", seqLen=" + seqLen +
				", dim=" + dim + ", innerDim=" + innerDim);

		// Load test data
		PackedCollection<?> input = referenceData.get("input");
		PackedCollection<?> expectedOutput = referenceData.get("expected_output");

		// Load weights
		PackedCollection<?> w1Weight = referenceData.get("w1_weight");
		PackedCollection<?> w1Bias = referenceData.get("w1_bias");
		PackedCollection<?> w2Weight = referenceData.get("w2_weight");
		PackedCollection<?> w2Bias = referenceData.get("w2_bias");
		PackedCollection<?> normWeight = referenceData.get("norm_weight");
		PackedCollection<?> normBias = referenceData.get("norm_bias");

		// Verify all weights were loaded
		assertNotNull("w1Weight not found", w1Weight);
		assertNotNull("w1Bias not found", w1Bias);
		assertNotNull("w2Weight not found", w2Weight);
		assertNotNull("w2Bias not found", w2Bias);
		assertNotNull("normWeight not found", normWeight);
		assertNotNull("normBias not found", normBias);

		log("Feed-forward weight shapes:");
		log("  w1: " + w1Weight.getShape() + ", bias: " + w1Bias.getShape());
		log("  w2: " + w2Weight.getShape() + ", bias: " + w2Bias.getShape());
		log("  norm: " + normWeight.getShape() + ", bias: " + normBias.getShape());

		// Create model with just feed-forward
		Model model = new Model(shape(batchSize, seqLen, dim));
		SequentialBlock main = model.sequential();

		List<PackedCollection<?>> states = new ArrayList<>();

		// Add feed-forward block
		main.add(gatedLinearFeedForward(
				shape(batchSize, seqLen, dim),
				normWeight, normBias,
				w1Weight, w1Bias,
				w2Weight, w2Bias
		));

		// Compile and run the model
		CompiledModel compiled = model.compile(false);
		PackedCollection<?> actualOutput = compiled.forward(input);

		log("Expected output total: " + expectedOutput.doubleStream().map(Math::abs).sum());
		log("Actual output total: " + actualOutput.doubleStream().map(Math::abs).sum());

		assertEquals(expectedOutput.getShape().getTotalSize(),
				actualOutput.getShape().getTotalSize());

		double diff = compare(expectedOutput, actualOutput);
		log("Feed-forward difference between expected and actual output = " + diff);
		assertTrue("Feed-forward output does not match reference within tolerance", diff < 1e-5);
	}

	/**
	* Tests transformerBlock against reference data generated from the actual
	* DiT TransformerBlock class. This ensures our Java implementation of the
	* complete transformer block (self-attention + cross-attention + feed-forward)
	* matches the real Python behavior.
	*/
	@Test
	public void transformerBlockCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/transformer_block";

		// Load reference data using StateDictionary
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract test configuration
		PackedCollection<?> testConfig = referenceData.get("test_config");
		int batchSize = (int) testConfig.valueAt(0);
		int seqLen = (int) testConfig.valueAt(1);
		int contextSeqLen = (int) testConfig.valueAt(2);
		int dim = (int) testConfig.valueAt(3);
		int contextDim = (int) testConfig.valueAt(4);
		int heads = (int) testConfig.valueAt(5);
		int dimHead = (int) testConfig.valueAt(6);

		log("TransformerBlock test configuration:");
		log("  batch=" + batchSize + ", seq=" + seqLen + ", contextSeq=" + contextSeqLen);
		log("  dim=" + dim + ", contextDim=" + contextDim + ", heads=" + heads + ", dimHead=" + dimHead);

		// Extract inputs and expected output
		PackedCollection<?> mainInput = referenceData.get("main_input");
		PackedCollection<?> contextInput = referenceData.get("context_input");
		PackedCollection<?> expectedOutput = referenceData.get("expected_output");

		assertNotNull("Main input not found", mainInput);
		assertNotNull("Context input not found", contextInput);
		assertNotNull("Expected output not found", expectedOutput);

		// Load all transformer block weights
		// Self-attention weights
		PackedCollection<?> selfQkv = referenceData.get("self_attn.to_qkv.weight");
		PackedCollection<?> selfWo = referenceData.get("self_attn.to_out.weight");
		PackedCollection<?> selfQNormWeight = referenceData.get("self_attn.q_norm.weight");
		PackedCollection<?> selfQNormBias = referenceData.get("self_attn.q_norm.bias");
		PackedCollection<?> selfKNormWeight = referenceData.get("self_attn.k_norm.weight");
		PackedCollection<?> selfKNormBias = referenceData.get("self_attn.k_norm.bias");

		// Cross-attention weights
		PackedCollection<?> crossWq = referenceData.get("cross_attn.to_q.weight");
		PackedCollection<?> crossKv = referenceData.get("cross_attn.to_kv.weight");
		PackedCollection<?> crossWo = referenceData.get("cross_attn.to_out.weight");
		PackedCollection<?> crossQNormWeight = referenceData.get("cross_attn.q_norm.weight");
		PackedCollection<?> crossQNormBias = referenceData.get("cross_attn.q_norm.bias");
		PackedCollection<?> crossKNormWeight = referenceData.get("cross_attn.k_norm.weight");
		PackedCollection<?> crossKNormBias = referenceData.get("cross_attn.k_norm.bias");

		// Layer normalization weights
		PackedCollection<?> preNormWeight = referenceData.get("pre_norm.gamma");
		PackedCollection<?> preNormBias = referenceData.get("pre_norm.beta");
		PackedCollection<?> crossAttPreNormWeight = referenceData.get("cross_attend_norm.gamma");
		PackedCollection<?> crossAttPreNormBias = referenceData.get("cross_attend_norm.beta");
		PackedCollection<?> ffnNormWeight = referenceData.get("ff_norm.gamma");
		PackedCollection<?> ffnNormBias = referenceData.get("ff_norm.beta");

		// Feed-forward weights
		PackedCollection<?> w1Weight = referenceData.get("ff.w1_weight");
		PackedCollection<?> w1Bias = referenceData.get("ff.w1_bias");
		PackedCollection<?> w2Weight = referenceData.get("ff.w2_weight");
		PackedCollection<?> w2Bias = referenceData.get("ff.w2_bias");
		PackedCollection<?> w3Weight = referenceData.get("ff.w3_weight");
		PackedCollection<?> w3Bias = referenceData.get("ff.w3_bias");

		// Rotary embeddings
		PackedCollection<?> invFreq = referenceData.get("rope.inv_freq");

		// Verify all weights were loaded
		assertNotNull("Self QKV weight not found", selfQkv);
		assertNotNull("Self output weight not found", selfWo);
		assertNotNull("Cross Q weight not found", crossWq);
		assertNotNull("Cross KV weight not found", crossKv);
		assertNotNull("Cross output weight not found", crossWo);
		assertNotNull("Pre-norm weight not found", preNormWeight);
		assertNotNull("FF norm weight not found", ffnNormWeight);
		assertNotNull("W1 weight not found", w1Weight);
		assertNotNull("W2 weight not found", w2Weight);
		assertNotNull("W3 weight not found", w3Weight);
		assertNotNull("Inverse frequency not found", invFreq);

		log("TransformerBlock weight shapes:");
		log("  Self QKV: " + selfQkv.getShape());
		log("  Cross Q: " + crossWq.getShape() + ", Cross KV: " + crossKv.getShape());
		log("  FF W1: " + w1Weight.getShape() + ", W2: " + w2Weight.getShape() + ", W3: " + w3Weight.getShape());

		// Create main model with transformer block
		Model model = new Model(shape(batchSize, seqLen, dim));
		
		// Create context input using addInput
		SequentialBlock contextBlock = new SequentialBlock(shape(batchSize, contextSeqLen, contextDim));
		// Context block passes input through as-is for this test
		model.addInput(contextBlock);

		SequentialBlock main = model.sequential();

		// Add transformer block with all weights
		main.add(transformerBlock(
				batchSize, dim, seqLen, heads,
				true, // crossAttend
				contextSeqLen,
				// globalCond
				contextBlock,
				// Self-attention weights
				preNormWeight, preNormBias,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq,
				// Cross-attention weights
				crossAttPreNormWeight, crossAttPreNormBias,
				crossWq, crossKv, crossWo,
				crossQNormWeight, crossQNormBias,
				crossKNormWeight, crossKNormBias,
				// Feed-forward weights
				ffnNormWeight, ffnNormBias,
				w1Weight, w2Weight,
				w1Bias, w2Bias
		));

		// Compile and run the model with both inputs
		CompiledModel compiled = model.compile(false);
		PackedCollection<?> actualOutput = compiled.forward(mainInput, contextInput);

		log("Expected output total: " + expectedOutput.doubleStream().map(Math::abs).sum());
		log("Actual output total: " + actualOutput.doubleStream().map(Math::abs).sum());

		assertEquals(expectedOutput.getShape().getTotalSize(),
				actualOutput.getShape().getTotalSize());

		double diff = compare(expectedOutput, actualOutput);
		log("TransformerBlock difference between expected and actual output = " + diff);
		assertTrue("TransformerBlock output does not match reference within tolerance", diff < 1e-5);
	}
}