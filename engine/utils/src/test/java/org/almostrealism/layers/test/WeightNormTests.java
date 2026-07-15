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

package org.almostrealism.layers.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.ConvolutionLayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests that the device-computed weight normalization matches a host-computed reference
 * (W = g * v / ||v|| with per-channel norms), and that the per-channel broadcast used by
 * the learnable snake activation gathers the expected values.
 */
public class WeightNormTests extends TestSuiteBase implements ConvolutionLayerFeatures {

	/**
	 * Tests both weight-norm layouts against the reference formula: for each leading
	 * channel, every element is scaled by its channel's magnitude over its channel's
	 * L2 norm (plus epsilon).
	 */
	@Test(timeout = 120000)
	public void weightNormReference() {
		int channels = 4;
		int other = 3;
		int kernel = 5;

		PackedCollection weightG = new PackedCollection(shape(channels, 1, 1));
		weightG.fill(pos -> 0.5 + Math.random());

		PackedCollection weightV = new PackedCollection(shape(channels, other, kernel));
		weightV.fill(pos -> 2.0 * Math.random() - 1.0);

		PackedCollection standard = computeWeightNormWeights(weightG, weightV, channels, other, kernel);
		PackedCollection transposed = computeWeightNormWeightsTransposed(weightG, weightV, channels, other, kernel);

		int vSize = other * kernel;

		for (int c = 0; c < channels; c++) {
			double normSq = 0.0;
			for (int i = 0; i < vSize; i++) {
				double v = weightV.toDouble(c * vSize + i);
				normSq += v * v;
			}

			double scale = weightG.toDouble(c) / (Math.sqrt(normSq) + 1e-12);

			for (int i = 0; i < vSize; i++) {
				double expected = weightV.toDouble(c * vSize + i) * scale;
				assertEquals(expected, standard.toDouble(c * vSize + i));
				assertEquals(expected, transposed.toDouble(c * vSize + i));
			}
		}
	}

	/**
	 * Tests the per-channel gather used to broadcast snake activation parameters:
	 * element (b, c, s) of the expanded collection is parameter c.
	 */
	@Test(timeout = 120000)
	public void channelBroadcast() {
		int batch = 2;
		int channels = 5;
		int seqLen = 7;

		PackedCollection alpha = new PackedCollection(shape(channels));
		alpha.fill(pos -> Math.random());

		PackedCollection expanded = new PackedCollection(shape(batch, channels, seqLen));
		CollectionProducer channelIndex = mod(
				floor(integers(0, batch * channels * seqLen).divide(c((double) seqLen))),
				c((double) channels));
		a(cp(expanded), c(expanded.getShape(), cp(alpha), channelIndex)).get().run();

		for (int b = 0; b < batch; b++) {
			for (int ch = 0; ch < channels; ch++) {
				for (int s = 0; s < seqLen; s++) {
					assertEquals(alpha.toDouble(ch), expanded.valueAt(b, ch, s));
				}
			}
		}
	}
}
