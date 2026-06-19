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
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Standalone shape/composition tests for {@link TransformerResamplingFeatures}, using small synthetic
 * weights and no Stable Audio 3 data. These run in normal CI and prove the plumbing of the
 * learned-resampling primitive independently of the real-weight numerical-parity test
 * ({@link SAMEResamplingParityTest}): the building-block producers ({@link
 * TransformerResamplingFeatures#resamplingLinear} and {@link
 * TransformerResamplingFeatures#softmaxLastAxis}) are checked against hand-computed references, and the
 * full encoder/decoder blocks are checked for the expected down-/up-sampled output shapes.
 */
public class TransformerResamplingShapeTest extends SAMEResamplingTestBase {

	/**
	 * {@link TransformerResamplingFeatures#resamplingLinear} must compute {@code y = x W^T + b}
	 * exactly (within float tolerance) for a small hand-computable case.
	 */
	@Test(timeout = 120000)
	public void linearMatchesReference() {
		int rows = 2;
		int in = 3;
		int out = 4;

		PackedCollection x = new PackedCollection(shape(rows, in)).randnFill();
		PackedCollection w = new PackedCollection(shape(out, in)).randnFill();
		PackedCollection b = new PackedCollection(shape(out)).randnFill();

		PackedCollection y = eval(resamplingLinear(cp(x), cp(w), b));
		assertEquals(rows * out, y.getShape().getTotalSize());

		for (int r = 0; r < rows; r++) {
			for (int o = 0; o < out; o++) {
				double expected = b.valueAt(o);
				for (int i = 0; i < in; i++) {
					expected += x.valueAt(r, i) * w.valueAt(o, i);
				}
				assertEquals(expected, y.valueAt(r, o), 1e-4);
			}
		}
	}

	/**
	 * {@link TransformerResamplingFeatures#softmaxLastAxis} must produce a valid probability
	 * distribution over the trailing axis that matches a direct exp/sum reference.
	 */
	@Test(timeout = 120000)
	public void softmaxMatchesReference() {
		int batch = 1;
		int heads = 2;
		int queries = 3;
		int keys = 4;
		TraversalPolicy shape = shape(batch, heads, queries, keys);

		PackedCollection scores = new PackedCollection(shape).randnFill();
		PackedCollection probs = eval(softmaxLastAxis(cp(scores)));
		assertEquals(shape.getTotalSize(), probs.getShape().getTotalSize());

		for (int h = 0; h < heads; h++) {
			for (int q = 0; q < queries; q++) {
				double max = Double.NEGATIVE_INFINITY;
				for (int k = 0; k < keys; k++) {
					max = Math.max(max, scores.valueAt(0, h, q, k));
				}
				double sum = 0;
				double[] ex = new double[keys];
				for (int k = 0; k < keys; k++) {
					ex[k] = Math.exp(scores.valueAt(0, h, q, k) - max);
					sum += ex[k];
				}

				double rowSum = 0;
				for (int k = 0; k < keys; k++) {
					double expected = ex[k] / sum;
					assertEquals(expected, probs.valueAt(0, h, q, k), 1e-5);
					rowSum += probs.valueAt(0, h, q, k);
				}
				assertEquals(1.0, rowSum, 1e-5);
			}
		}
	}

	/**
	 * An encoder block must down-sample {@code [batch, inChannels, L] -> [batch, outChannels, L/stride]}
	 * and produce finite values, with synthetic weights at small dimensions.
	 */
	@Test(timeout = 120000)
	public void encoderBlockDownsamplesShape() {
		ResamplingConfig config = smallConfig(true);
		int length = 8;
		StateDictionary weights = syntheticWeights(config, "enc");

		PackedCollection input = new PackedCollection(shape(1, config.getInChannels(), length)).randnFill();
		PackedCollection out = evalBlock(transformerResamplingBlock(1, length, config, weights, "enc"), input);

		assertEquals(config.getOutChannels() * (length / config.getStride()), out.getShape().getTotalSize());
		assertFinite(out);
	}

	/**
	 * A decoder block must up-sample {@code [batch, inChannels, L] -> [batch, outChannels, L*stride]}
	 * and produce finite values, with synthetic weights at small dimensions (including the
	 * {@code 3} -kernel mapping convolution).
	 */
	@Test(timeout = 120000)
	public void decoderBlockUpsamplesShape() {
		ResamplingConfig config = smallConfig(false);
		int length = 4;
		StateDictionary weights = syntheticWeights(config, "dec");

		PackedCollection input = new PackedCollection(shape(1, config.getInChannels(), length)).randnFill();
		PackedCollection out = evalBlock(transformerResamplingBlock(1, length, config, weights, "dec"), input);

		assertEquals(config.getOutChannels() * (length * config.getStride()), out.getShape().getTotalSize());
		assertFinite(out);
	}

	/**
	 * The intermediate encoder stages must have the segment/chunk shapes the architecture prescribes.
	 */
	@Test(timeout = 120000)
	public void encoderStageShapes() {
		ResamplingConfig config = smallConfig(true);
		int length = 8;
		StateDictionary weights = syntheticWeights(config, "enc");

		PackedCollection input = new PackedCollection(shape(1, config.getInChannels(), length)).randnFill();

		PackedCollection mapped = eval(resamplingMapping(cp(input), config, weights, "enc"));
		assertEquals(config.getOutChannels() * length, mapped.getShape().getTotalSize());

		PackedCollection segment = eval(resamplingSegment(cp(mapped), 1, config, weights, "enc"));
		int numSeg = length / config.getInputSegSize();
		assertEquals(numSeg * config.getSubChunkSize() * config.getDim(), segment.getShape().getTotalSize());
	}

	/**
	 * Builds a small encoder or decoder configuration that still exercises the full machinery
	 * (segmentation, learned tokens, midpoint-shifted chunking, differential attention, SwiGLU).
	 *
	 * @param encoder {@code true} for an encoder (downsampling) config
	 * @return the small configuration
	 */
	protected ResamplingConfig smallConfig(boolean encoder) {
		int inChannels = encoder ? 4 : 8;
		int outChannels = encoder ? 8 : 4;
		int mappingKernel = encoder ? 1 : 3;
		return new ResamplingConfig(inChannels, outChannels, 2, 4, 2, 4, 2,
				encoder, true, true, 2.0, mappingKernel, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * Generates a complete set of synthetic weights for a resampling block under the given key prefix,
	 * using the shared key/shape map so the synthetic key set matches the real one exactly.
	 *
	 * @param config the block configuration
	 * @param prefix the weight key prefix
	 * @return a {@link StateDictionary} holding randomly-initialized weights for every key the block reads
	 */
	protected StateDictionary syntheticWeights(ResamplingConfig config, String prefix) {
		Map<String, PackedCollection> w = new HashMap<>();
		blockWeightShapes(config, prefix).forEach((key, dims) ->
				w.put(key, new PackedCollection(shape(dims)).randnFill()));
		return new StateDictionary(w);
	}

	/**
	 * Asserts that every element of the collection is finite (no {@code NaN} or infinity).
	 *
	 * @param collection the collection to check
	 */
	protected void assertFinite(PackedCollection collection) {
		double[] values = collection.toArray(0, collection.getShape().getTotalSize());
		for (double v : values) {
			if (!Double.isFinite(v)) {
				throw new AssertionError("Non-finite value in output: " + v);
			}
		}
	}
}
