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
import org.junit.Test;

public class AttentionTests implements AttentionFeatures, TestFeatures {

	private static final int TEST_BATCH_SIZE = 1;
	private static final int TEST_SEQ_LEN = 4;
	private static final int TEST_DIM = 16;
	private static final int TEST_HEADS = 2;
	private static final int TEST_DIM_HEAD = TEST_DIM / TEST_HEADS;
	private static final int TEST_INV_FREQ_SIZE = TEST_DIM_HEAD / 4;

	/**
	* Tests sequenceAttention against reference data generated from the actual
	* DiT Attention class in stable-audio-tools. This ensures our Java implementation
	* matches the real Python behavior rather than a made-up reference.
	*/
	@Test
	public void sequenceAttentionCompare() throws Exception {
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
		int heads = 8; // From DiT config
		int dimHead = embedDim / heads;

		log("DiT Reference dimensions: batch=" + batchSize + ", seq=" + seqLen +
				", embed_dim=" + embedDim + ", heads=" + heads + ", dim_head=" + dimHead);
		log("QKV weight shape: " + toQKV.getShape());
		log("Output weight shape: " + toOut.getShape());

		// DiT uses fused QKV projection - no need to split manually anymore
		// toQKV has shape (embed_dim * 3, embed_dim) -> (3072, 1024) for embed_dim=1024
		assertEquals(embedDim * 3, toQKV.getShape().length(0));
		assertEquals(embedDim, toQKV.getShape().length(1));

		// Create a simplified test model with just one layer to match Python reference
		Model model = new Model(inputShape);
		SequentialBlock main = model.sequential();

		// Add sequence attention that matches the actual DiT flow using fused QKV
		main.add(sequenceAttention(
				batchSize, seqLen, embedDim, heads,
				preNormWeight, preNormBias,
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
	public void scaledDotProduct() {
		int batchSize = 2;
		int heads = 3;
		int seqLenA = 5;
		int seqLenB = 7;
		int dim = 4;

		// Create input tensors
		// a: (batch, heads, seqLenA, dim)
		// b: (batch, heads, dim, seqLenB)
		PackedCollection<?> a = new PackedCollection<>(shape(batchSize, heads, seqLenA, dim))
				.fill(pos -> Math.random());
		PackedCollection<?> b = new PackedCollection<>(shape(batchSize, heads, dim, seqLenB))
				.fill(pos -> Math.random());

		// Compute expected result manually
		// Result should have shape (batch, heads, seqLenA, seqLenB)
		PackedCollection<?> expected = new PackedCollection<>(shape(batchSize, heads, seqLenA, seqLenB));
		
		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLenA; i++) {
					for (int j = 0; j < seqLenB; j++) {
						double sum = 0.0;
						for (int k = 0; k < dim; k++) {
							sum += a.valueAt(batch, head, i, k) * b.valueAt(batch, head, k, j);
						}
						expected.setValueAt(sum, batch, head, i, j);
					}
				}
			}
		}

		CollectionProducer<PackedCollection<?>> result = scaledDotProduct(cp(a), cp(b));
		PackedCollection<?> actual = result.evaluate();

		// Verify shapes match
		assertEquals(expected.getShape(), actual.getShape());

		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLenA; i++) {
					for (int j = 0; j < seqLenB; j++) {
						assertEquals(expected.valueAt(batch, head, i, j), actual.valueAt(batch, head, i, j));
					}
				}
			}
		}
	}

	@Test
	public void scaledDotProductTranspose() {
		int batchSize = 2;
		int heads = 3;
		int seqLen = 6;
		int dim = 4;

		// Test case for Q @ K^T pattern used in attention
		// q: (batch, heads, seqLen, dim)
		// k: (batch, heads, seqLen, dim)
		// Result after k transpose: (batch, heads, seqLen, seqLen)
		PackedCollection<?> q = new PackedCollection<>(shape(batchSize, heads, seqLen, dim))
				.fill(pos -> Math.random());
		PackedCollection<?> k = new PackedCollection<>(shape(batchSize, heads, seqLen, dim))
				.fill(pos -> Math.random());

		// Compute expected result for Q @ K^T
		PackedCollection<?> expected = new PackedCollection<>(shape(batchSize, heads, seqLen, seqLen));
		
		for (int batch = 0; batch < batchSize; batch++) {
			for (int head = 0; head < heads; head++) {
				for (int i = 0; i < seqLen; i++) {
					for (int j = 0; j < seqLen; j++) {
						double sum = 0.0;
						for (int d = 0; d < dim; d++) {
							// Q[i,d] * K[j,d] (K transposed)
							sum += q.valueAt(batch, head, i, d) * k.valueAt(batch, head, j, d);
						}

						expected.setValueAt(sum, batch, head, i, j);
					}
				}
			}
		}

		// Test with transposed K
		// We need to transpose last two dimensions of k: (batch, heads, seqLen, dim) -> (batch, heads, dim, seqLen)
		CollectionProducer<PackedCollection<?>> kTransposed = cp(k)
				.reshape(batchSize, heads, seqLen, dim)
				.permute(0, 1, 3, 2);
		
		CollectionProducer<PackedCollection<?>> result = scaledDotProduct(cp(q), kTransposed);
		PackedCollection<?> actual = result.evaluate();

		// Verify shapes match
		assertEquals(expected.getShape(), actual.getShape());

		// Compare results
		double diff = compare(expected, actual);
		log("batchMatrixMultiply with transpose difference: " + diff);
		assertTrue("Batch matrix multiplication with transpose differs from expected", diff < 1e-6);
	}
}