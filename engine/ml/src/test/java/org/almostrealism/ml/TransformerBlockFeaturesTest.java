/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies that {@link TransformerBlockFeatures#transformerBlock} honors the requested
 * {@link NormalizationType} for the cross-attention query/key normalization, not just the
 * self-attention and feed-forward norms.
 */
public class TransformerBlockFeaturesTest extends TestSuiteBase implements TransformerBlockFeatures {

	/** Model dimension, small enough for a hand-computed reference. */
	private static final int DIM = 4;

	/** A single head spans the whole model dimension. */
	private static final int HEADS = 1;

	/**
	 * With {@link NormalizationType#RMS}, the cross-attention query/key vectors are divided by
	 * their root mean square without centering; with {@link NormalizationType#LAYER} they are
	 * additionally centered on their mean first.
	 *
	 * <p>{@link #crossAttentionScores} uses a query of {@code [1, 1, 1, 1]} and context keys of
	 * {@code [2, 2, 2, 2]} and {@code [1, -1, 1, -1]}, each of which already has unit root mean
	 * square, so RMS normalization leaves them unchanged: the post-softmax weights over the two
	 * keys work out to {@code softmax([4, 0] / sqrt(dimHead)) = [0.880797, 0.119203]}. The query
	 * has zero variance, so LAYER normalization instead collapses it to the zero vector, making
	 * every logit zero regardless of the keys and so producing the uniform {@code [0.5, 0.5]}.
	 * Before this was fixed, {@code transformerBlock} always used LAYER normalization for
	 * cross-attention regardless of the requested type, so an RMS-configured checkpoint (such as
	 * the SA3 DiT) would silently mean-center its cross-attention queries and keys.
	 */
	@Test(timeout = 120000)
	public void crossAttentionHonorsNormalizationType() {
		double[] scoresRms = crossAttentionScores(NormalizationType.RMS);
		double[] scoresLayer = crossAttentionScores(NormalizationType.LAYER);

		assertEquals(0.880797, scoresRms[0], 1e-4);
		assertEquals(0.119203, scoresRms[1], 1e-4);

		assertEquals(0.5, scoresLayer[0], 1e-4);
		assertEquals(0.5, scoresLayer[1], 1e-4);
	}

	/**
	 * Builds a one-layer transformer block with cross-attention enabled and self-attention
	 * neutralized (its output projection is zero, so the residual stream reaching cross-attention
	 * is exactly the block's input), then returns the captured post-softmax cross-attention weights
	 * over the two context tokens.
	 *
	 * @param normType the normalization family under test
	 * @return the two cross-attention weights for the single query position
	 */
	private double[] crossAttentionScores(NormalizationType normType) {
		int contextSeqLen = 2;
		int dimHead = DIM / HEADS;

		PackedCollection identity = PackedCollection.of(
				1.0, 0.0, 0.0, 0.0,
				0.0, 1.0, 0.0, 0.0,
				0.0, 0.0, 1.0, 0.0,
				0.0, 0.0, 0.0, 1.0).reshape(shape(DIM, DIM));
		PackedCollection stackedIdentity = PackedCollection.of(
				1.0, 0.0, 0.0, 0.0,
				0.0, 1.0, 0.0, 0.0,
				0.0, 0.0, 1.0, 0.0,
				0.0, 0.0, 0.0, 1.0,
				1.0, 0.0, 0.0, 0.0,
				0.0, 1.0, 0.0, 0.0,
				0.0, 0.0, 1.0, 0.0,
				0.0, 0.0, 0.0, 1.0).reshape(shape(2 * DIM, DIM));
		PackedCollection ones = new PackedCollection(shape(DIM)).fill(1.0);
		PackedCollection zeroQkv = new PackedCollection(shape(3 * DIM, DIM));
		zeroQkv.clear();
		PackedCollection zeroOut = new PackedCollection(shape(DIM, DIM));
		zeroOut.clear();
		PackedCollection invFreq = new PackedCollection(shape(dimHead / 2));
		invFreq.clear();
		PackedCollection ffnWeightIn = new PackedCollection(shape(2 * DIM, DIM));
		ffnWeightIn.clear();
		PackedCollection ffnWeightOut = new PackedCollection(shape(DIM, DIM));
		ffnWeightOut.clear();

		Model contextModel = new Model(shape(1, contextSeqLen, DIM));
		SequentialBlock contextBlock = contextModel.sequential();

		Model mainModel = new Model(shape(1, 1, DIM));
		SequentialBlock mainBlock = mainModel.sequential();

		PackedCollection scores = new PackedCollection(shape(1, HEADS, 1, contextSeqLen));

		mainBlock.add(transformerBlock(1, DIM, 1, HEADS, true,
				contextSeqLen, contextBlock,
				ones, null,
				zeroQkv, zeroOut,
				ones, null, ones, null,
				invFreq,
				ones, null,
				identity, stackedIdentity, identity,
				ones, null, ones, null,
				ones, null,
				ffnWeightIn, ffnWeightOut, null, null,
				into(scores), ProjectionFactory.dense(),
				AttentionVariant.STANDARD, null, null, null,
				normType, null));

		CompiledModel mainCompiled = mainModel.compile(false);

		PackedCollection contextInput = PackedCollection.of(
				2.0, 2.0, 2.0, 2.0,
				1.0, -1.0, 1.0, -1.0).reshape(shape(1, contextSeqLen, DIM));
		contextModel.compile(false).forward(contextInput);

		PackedCollection mainInput = new PackedCollection(shape(1, 1, DIM)).fill(1.0);
		mainCompiled.forward(mainInput);

		return new double[]{scores.valueAt(0, 0, 0, 0), scores.valueAt(0, 0, 0, 1)};
	}
}
