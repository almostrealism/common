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

package org.almostrealism.ml.audio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Parity of the {@code /pdsl/channel_projection.pdsl} asset against the per-frame channel projection
 * it replaced in {@link SAMEAutoEncoder#channelProjection}. The asset builds the projection as
 * {@code permute(0, 2, 1) -> dense -> permute(0, 2, 1)}; the reference builds the pre-migration Java
 * body, {@code permute(linear(permute(x, 0, 2, 1), weight, bias), 0, 2, 1)}, directly as producer
 * arithmetic, independent of {@link SAMEAutoEncoder}. Both are fed the same random weight, bias and
 * input, so the test pins the asset against what the Java body computed rather than against itself.
 *
 * <p>The cases cover the encoder direction (more channels in than out), the decoder direction (more
 * out than in) and the biasless {@code channel_projection_no_bias} layer, each with a batch and a
 * length greater than one so every axis of the transpose and the per-position projection is
 * exercised.</p>
 */
public class ChannelProjectionAssetTest extends SAMEResamplingTestBase {

	/** The migrated asset. */
	private static final String ASSET = "/pdsl/channel_projection.pdsl";

	/** Seed for the synthetic weight, bias and input, so each run builds the same projection. */
	private static final long SEED = 4703L;

	/** Largest permitted absolute difference between the asset and the reference output. */
	private static final double TOLERANCE = 1e-4;

	/**
	 * The encoder-direction projection (five channels in, three out) with a bias matches the reference.
	 */
	@Test(timeout = 120000)
	public void biasedProjectionMatchesReference() {
		assertProjectionParity(2, 5, 3, 4, true);
	}

	/**
	 * The decoder-direction projection (three channels in, five out) with a bias matches the reference.
	 */
	@Test(timeout = 120000)
	public void biasedProjectionMatchesReferenceDecoderDirection() {
		assertProjectionParity(2, 3, 5, 4, true);
	}

	/**
	 * The biasless {@code channel_projection_no_bias} layer matches a reference built without a bias.
	 */
	@Test(timeout = 120000)
	public void unbiasedProjectionMatchesReference() {
		assertProjectionParity(2, 5, 3, 4, false);
	}

	/**
	 * Builds the asset projection and the reference projection over one shared random weight, bias and
	 * input, and asserts the asset output matches the reference output to {@link #TOLERANCE}.
	 *
	 * @param batch   batch size
	 * @param in      input channels
	 * @param out     output channels
	 * @param len     sequence length
	 * @param hasBias whether the projection has a bias (selects the asset layer and the reference form)
	 */
	private void assertProjectionParity(int batch, int in, int out, int len, boolean hasBias) {
		Random random = new Random(SEED);
		PackedCollection weight = new PackedCollection(shape(out, in)).randnFill(random);
		PackedCollection bias = hasBias ? new PackedCollection(shape(out)).randnFill(random) : null;
		PackedCollection input = new PackedCollection(shape(batch, in, len)).randnFill(random);

		PackedCollection referenceOutput = evalBlock(reference(batch, in, out, len, weight, bias), input);
		PackedCollection assetOutput = evalBlock(assetProjection(batch, in, len, weight, bias), input);

		// assertWithin fails on a non-finite result: !(maxAbs <= tolerance) is true when maxAbs is NaN,
		// so a NaN output is caught here rather than passing silently.
		assertEquals(referenceOutput.getShape().getTotalSize(), assetOutput.getShape().getTotalSize());
		assertWithin("channel_projection", assetOutput, toFloats(referenceOutput), TOLERANCE);
	}

	/**
	 * The pre-migration Java body of {@code SAMEAutoEncoder.channelProjection}, built directly as
	 * producer arithmetic so it does not depend on {@link SAMEAutoEncoder} surviving the migration.
	 *
	 * @param batch  batch size
	 * @param in     input channels
	 * @param out    output channels
	 * @param len    sequence length
	 * @param weight the projection weight, shape {@code [out, in]}
	 * @param bias   the projection bias, shape {@code [out]}, or {@code null} for none
	 * @return the reference projection block
	 */
	private Block reference(int batch, int in, int out, int len,
							PackedCollection weight, PackedCollection bias) {
		return layer("reference", shape(batch, in, len), shape(batch, out, len),
				x -> permute(linear(permute(c(x), 0, 2, 1), weight.reshape(out, in), bias), 0, 2, 1));
	}

	/**
	 * The asset projection, built exactly as {@link SAMEAutoEncoder#channelProjection} builds it: the
	 * weight and optional bias are bound as a checkpoint group and the biased or unbiased layer is
	 * selected by the bias presence. The output channel count is read from the weight by the asset, so
	 * it is not a parameter here.
	 *
	 * @param batch  batch size
	 * @param in     input channels
	 * @param len    sequence length
	 * @param weight the projection weight, shape {@code [out, in]}
	 * @param bias   the projection bias, shape {@code [out]}, or {@code null} for none
	 * @return the asset projection block
	 */
	private Block assetProjection(int batch, int in, int len,
								  PackedCollection weight, PackedCollection bias) {
		Map<String, PackedCollection> entries = new HashMap<>();
		entries.put("proj.weight", weight);
		if (bias != null) {
			entries.put("proj.bias", bias);
		}

		Map<String, Object> args = new HashMap<>();
		args.put("projection", new StateDictionary(entries).group("proj"));

		PdslLoader loader = new PdslLoader();
		String layerName = bias != null ? "channel_projection" : "channel_projection_no_bias";
		return loader.buildLayer(loader.parseResource(ASSET), layerName, shape(batch, in, len), args);
	}

	/**
	 * Reads a collection into a flat {@code float[]} for comparison against an asset output. This runs
	 * at the top of the test stack, where evaluating a collection to host values is permitted.
	 *
	 * @param collection the collection to read
	 * @return its values in row-major order
	 */
	private float[] toFloats(PackedCollection collection) {
		int size = collection.getShape().getTotalSize();
		double[] values = collection.toArray(0, size);
		float[] result = new float[size];
		for (int i = 0; i < size; i++) {
			result[i] = (float) values[i];
		}
		return result;
	}
}
