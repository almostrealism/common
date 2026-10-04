/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Pins the behaviour of {@link AttentionFeatures#scaledDotProductAttention} — the parallel
 * (full-sequence) attention core — as it is migrated from a Java {@code SequentialBlock} assembly
 * to the {@code /pdsl/sdpa.pdsl} asset.
 *
 * <p>Four tests compare {@code scaledDotProductAttention} against an independent host-side
 * reference ({@code softmax(mask(softcap(Q Kᵀ / sqrt(d)))) V} computed directly in double
 * precision) for the plain, soft-capped, key-masked, and soft-capped-plus-key-masked
 * configurations. The reference does not use any framework block, so these tests pin the numerical
 * contract whether the computation is the former Java assembly or the migrated asset. A fifth test
 * passes a non-null {@code attentionScores} receptor and asserts it receives the post-mask,
 * post-softmax attention weights while the main output still reaches the context half, covering the
 * receptor tap between the two halves. A sixth test builds the asset directly through
 * {@link PdslLoader} and asserts it agrees with {@code scaledDotProductAttention} to a tight
 * tolerance, so the loader glue that chains the asset's two halves is exercised on its own.</p>
 */
public class SdpaAssetTest extends TestSuiteBase implements AttentionFeatures {

	/** Classpath location of the parallel attention asset. */
	private static final String SDPA_ASSET = "/pdsl/sdpa.pdsl";

	/** Batch size the parallel attention core supports. */
	private static final int BATCH = 1;

	/** Head count of the scaled-down but every-stage-exercised configuration. */
	private static final int HEADS = 3;

	/** Sequence length (queries and keys) of the configuration. */
	private static final int SEQ = 4;

	/** Dimension per head of the configuration. */
	private static final int DIM_HEAD = 5;

	/**
	 * Builds a randomly-filled query, key or value tensor of shape {@code [1, HEADS, SEQ, DIM_HEAD]}.
	 * The random values still pin the contract, because the host reference reads the same filled
	 * values back rather than recomputing them.
	 *
	 * @return the filled tensor
	 */
	private PackedCollection randnInput() {
		return new PackedCollection(shape(BATCH, HEADS, SEQ, DIM_HEAD)).randnFill();
	}

	/**
	 * Host reference for scaled dot-product attention of the fixtures, computed directly in double
	 * precision: {@code softmax_j(mask_j(softcap(sum_d q[h,i,d] k[h,j,d] / sqrt(d)))) · v[h,j,·]}.
	 *
	 * @param q       query values, row-major {@code [HEADS, SEQ, DIM_HEAD]}
	 * @param k       key values, same layout
	 * @param v       value values, same layout
	 * @param softcap the logit soft-cap, or {@code 0} for none
	 * @param mask    per-key validity of length {@code SEQ} (one valid, zero masked), or {@code null}
	 * @return the expected output, row-major {@code [HEADS, SEQ, DIM_HEAD]}
	 */
	private double[] oracle(double[] q, double[] k, double[] v, double softcap, double[] mask) {
		double[] weights = oracleWeights(q, k, softcap, mask);
		double[] out = new double[HEADS * SEQ * DIM_HEAD];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < SEQ; i++) {
				for (int d = 0; d < DIM_HEAD; d++) {
					double o = 0;
					for (int j = 0; j < SEQ; j++) {
						o += weights[(h * SEQ + i) * SEQ + j] * v[(h * SEQ + j) * DIM_HEAD + d];
					}
					out[(h * SEQ + i) * DIM_HEAD + d] = o;
				}
			}
		}
		return out;
	}

	/**
	 * Host reference for the post-mask, post-softmax attention weights — the tensor the score half
	 * of the asset produces and the receptor observes between the two halves:
	 * {@code softmax_j(mask_j(softcap(sum_d q[h,i,d] k[h,j,d] / sqrt(d))))}.
	 *
	 * @param q       query values, row-major {@code [HEADS, SEQ, DIM_HEAD]}
	 * @param k       key values, same layout
	 * @param softcap the logit soft-cap, or {@code 0} for none
	 * @param mask    per-key validity of length {@code SEQ} (one valid, zero masked), or {@code null}
	 * @return the expected attention weights, row-major {@code [HEADS, SEQ, SEQ]}
	 */
	private double[] oracleWeights(double[] q, double[] k, double softcap, double[] mask) {
		double invSqrt = 1.0 / Math.sqrt(DIM_HEAD);
		double[] weights = new double[HEADS * SEQ * SEQ];
		for (int h = 0; h < HEADS; h++) {
			for (int i = 0; i < SEQ; i++) {
				double[] score = new double[SEQ];
				for (int j = 0; j < SEQ; j++) {
					double dot = 0;
					for (int d = 0; d < DIM_HEAD; d++) {
						dot += q[(h * SEQ + i) * DIM_HEAD + d] * k[(h * SEQ + j) * DIM_HEAD + d];
					}
					double s = dot * invSqrt;
					if (softcap > 0) {
						s = softcap * Math.tanh(s / softcap);
					}
					if (mask != null) {
						s += (mask[j] - 1.0) * MASKED_LOGIT_PENALTY;
					}
					score[j] = s;
				}
				double max = score[0];
				for (int j = 1; j < SEQ; j++) {
					max = Math.max(max, score[j]);
				}
				double sum = 0;
				for (int j = 0; j < SEQ; j++) {
					score[j] = Math.exp(score[j] - max);
					sum += score[j];
				}
				for (int j = 0; j < SEQ; j++) {
					weights[(h * SEQ + i) * SEQ + j] = score[j] / sum;
				}
			}
		}
		return weights;
	}

	/**
	 * Runs {@code scaledDotProductAttention} for the fixtures and compares it to the host reference.
	 *
	 * @param softcap the logit soft-cap to pass (and apply in the reference), or {@code 0}
	 * @param mask    the per-key validity mask, or {@code null} for none
	 * @param label   a label for the assertion message
	 */
	private void assertMatchesOracle(double softcap, PackedCollection mask, String label) {
		PackedCollection q = randnInput();
		PackedCollection k = randnInput();
		PackedCollection v = randnInput();

		Model model = new Model(shape(BATCH, HEADS, SEQ, DIM_HEAD));
		model.add(scaledDotProductAttention(BATCH, SEQ, SEQ, HEADS, DIM_HEAD, k, v, null,
				softcap, mask == null ? null : cp(mask)));
		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(q);

		double[] expected = oracle(q.doubleStream().toArray(), k.doubleStream().toArray(),
				v.doubleStream().toArray(), softcap, mask == null ? null : mask.doubleStream().toArray());
		double[] actual = output.doubleStream().toArray();

		assertEquals(label + " output size", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(label + " element " + i, expected[i], actual[i], 1e-4);
		}
	}

	/**
	 * Plain scaled dot-product attention (no soft-cap, no mask) matches the host reference.
	 */
	@Test(timeout = 120000)
	public void sdpaMatchesHostOracle() {
		assertMatchesOracle(0.0, null, "sdpa");
	}

	/**
	 * Soft-capped scaled dot-product attention (the T5Gemma configuration) matches the host
	 * reference, exercising the {@code sdpa_scores_softcapped} body of the asset.
	 */
	@Test(timeout = 120000)
	public void sdpaSoftcapMatchesHostOracle() {
		assertMatchesOracle(30.0, null, "sdpa softcap");
	}

	/**
	 * Key-masked scaled dot-product attention matches the host reference: the last two keys are
	 * masked, so every query attends only to the first two, exercising the {@code key_mask} stage.
	 */
	@Test(timeout = 120000)
	public void sdpaKeyMaskMatchesHostOracle() {
		// Valid for the first two key positions, masked for the rest: mask[j] = (j < 2) ? 1 : 0.
		int validKeys = 2;
		PackedCollection mask = new PackedCollection(shape(BATCH, SEQ));
		lessThan(integers(0, SEQ), c((double) validKeys), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();
		assertMatchesOracle(0.0, mask, "sdpa key-mask");
	}

	/**
	 * Soft-capping and key masking together (the T5Gemma encoder configuration) match the host
	 * reference: the soft-capped logits are masked before the softmax, exercising the
	 * {@code sdpa_scores_softcapped} body with a non-trivial mask.
	 */
	@Test(timeout = 120000)
	public void sdpaSoftcapAndKeyMaskMatchHostOracle() {
		int validKeys = 3;
		PackedCollection mask = new PackedCollection(shape(BATCH, SEQ));
		lessThan(integers(0, SEQ), c((double) validKeys), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();
		assertMatchesOracle(30.0, mask, "sdpa softcap+key-mask");
	}

	/**
	 * A non-null {@code attentionScores} receptor receives the post-mask, post-softmax attention
	 * weights the score half produces, while the main output still flows through to the context
	 * half. A key mask is applied so the captured weights are non-trivial (every query attends only
	 * to the first three keys), exercising the receptor tap between the two halves that no other test
	 * covers. The captured weights are checked against the host reference and the main output against
	 * the full-attention host reference, so a wiring regression in either path fails the assertion.
	 */
	@Test(timeout = 120000)
	public void sdpaReceptorReceivesAttentionWeights() {
		PackedCollection q = randnInput();
		PackedCollection k = randnInput();
		PackedCollection v = randnInput();

		int validKeys = 3;
		PackedCollection mask = new PackedCollection(shape(BATCH, SEQ));
		lessThan(integers(0, SEQ), c((double) validKeys), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();

		PackedCollection captured = new PackedCollection(shape(BATCH, HEADS, SEQ, SEQ));
		CollectionReceptor receptor = new CollectionReceptor(captured);

		Model model = new Model(shape(BATCH, HEADS, SEQ, DIM_HEAD));
		model.add(scaledDotProductAttention(BATCH, SEQ, SEQ, HEADS, DIM_HEAD, k, v, receptor,
				0.0, cp(mask)));
		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(q);

		double[] qh = q.doubleStream().toArray();
		double[] kh = k.doubleStream().toArray();
		double[] vh = v.doubleStream().toArray();
		double[] maskh = mask.doubleStream().toArray();

		// The receptor received the post-mask, post-softmax attention weights.
		double[] expectedWeights = oracleWeights(qh, kh, 0.0, maskh);
		double[] actualWeights = captured.doubleStream().toArray();
		assertEquals("captured weights size", expectedWeights.length, actualWeights.length);
		for (int i = 0; i < expectedWeights.length; i++) {
			assertEquals("captured weight " + i, expectedWeights[i], actualWeights[i], 1e-4);
		}

		// The main output still reaches the context half.
		double[] expectedOutput = oracle(qh, kh, vh, 0.0, maskh);
		double[] actualOutput = output.doubleStream().toArray();
		assertEquals("output size", expectedOutput.length, actualOutput.length);
		for (int i = 0; i < expectedOutput.length; i++) {
			assertEquals("output element " + i, expectedOutput[i], actualOutput[i], 1e-4);
		}
	}

	/**
	 * Building the asset's two halves directly through {@link PdslLoader} agrees with
	 * {@code scaledDotProductAttention} to a tight tolerance, so the loader glue that chains
	 * {@code sdpa_scores} into {@code sdpa_context} is verified against the same asset built by the
	 * feature method.
	 */
	@Test(timeout = 120000)
	public void sdpaAssetMatchesFeatureMethod() {
		PackedCollection q = randnInput();
		PackedCollection k = randnInput();
		PackedCollection v = randnInput();

		TraversalPolicy inputShape = shape(BATCH, HEADS, SEQ, DIM_HEAD);
		PackedCollection ones = new PackedCollection(shape(BATCH, SEQ)).fill(1.0);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(SDPA_ASSET);

		Map<String, Object> scoresArgs = new HashMap<>();
		scoresArgs.put("k", k);
		scoresArgs.put("key_mask", ones);
		scoresArgs.put("dim_head", DIM_HEAD);

		SequentialBlock asset = new SequentialBlock(inputShape);
		asset.add(loader.buildLayer(program, "sdpa_scores", inputShape, scoresArgs));
		Map<String, Object> contextArgs = new HashMap<>();
		contextArgs.put("v", v);
		asset.add(loader.buildLayer(program, "sdpa_context", asset.getOutputShape(), contextArgs));

		Model assetModel = new Model(inputShape);
		assetModel.add(asset);
		CompiledModel compiledAsset = assetModel.compile(false);
		double[] assetOutput = compiledAsset.forward(q).doubleStream().toArray();

		Model featureModel = new Model(inputShape);
		featureModel.add(scaledDotProductAttention(BATCH, SEQ, HEADS, DIM_HEAD, k, v));
		CompiledModel compiledFeature = featureModel.compile(false);
		double[] featureOutput = compiledFeature.forward(q).doubleStream().toArray();

		assertEquals(featureOutput.length, assetOutput.length);
		for (int i = 0; i < featureOutput.length; i++) {
			assertEquals("asset vs feature element " + i, featureOutput[i], assetOutput[i], 1e-6);
		}
	}
}
