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
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformerConfig;
import org.almostrealism.ml.audio.TimestepEncoding;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Map;

/**
 * Verifies that {@link DiffusionTransformerWeightFixture} is reproducible: tests that depend on
 * the output of a transformer built from it (such as a decoded generation staying inside a clamp)
 * are only meaningful when the weights are the same on every run.
 */
public class DiffusionTransformerWeightFixtureTest extends TestSuiteBase {

	/**
	 * A small configuration covering the adaLN and memory-token paths.
	 *
	 * @return the transformer configuration
	 */
	private DiffusionTransformerConfig config() {
		return new DiffusionTransformerConfig(2, 16, 1, 2, 1, 0, 8, "rf_denoiser", 6, 0)
				.withConditioningMode(ConditioningMode.ADALN)
				.withMemoryTokens(2)
				.withTimestepEncoding(TimestepEncoding.EXPO);
	}

	/**
	 * The largest element-wise difference between the weights two maps hold under each key.
	 *
	 * @param a the first weight map
	 * @param b the second weight map, holding the same keys and shapes
	 * @return the largest absolute difference over every key
	 */
	private double maxDifference(Map<String, PackedCollection> a, Map<String, PackedCollection> b) {
		assertEquals(a.keySet(), b.keySet());
		double diff = 0.0;
		for (Map.Entry<String, PackedCollection> entry : a.entrySet()) {
			double[] first = entry.getValue().toArray();
			double[] second = b.get(entry.getKey()).toArray();
			assertEquals(entry.getKey(), first.length, second.length);
			for (int i = 0; i < first.length; i++) {
				diff = Math.max(diff, Math.abs(first[i] - second[i]));
			}
		}
		return diff;
	}

	/**
	 * Two independently created fixtures produce identical weights for the same configuration,
	 * which is what makes the weights the same from one run to the next.
	 */
	@Test(timeout = 120000)
	public void separateFixturesProduceIdenticalWeights() {
		DiffusionTransformerConfig config = config();
		Map<String, PackedCollection> first = new DiffusionTransformerWeightFixture().weights(config);
		Map<String, PackedCollection> second = new DiffusionTransformerWeightFixture().weights(config);

		assertEquals(0.0, maxDifference(first, second), 0.0);
	}

	/**
	 * Successive calls on one fixture continue its random sequence, so two models built from one
	 * fixture hold different weights.
	 */
	@Test(timeout = 120000)
	public void successiveCallsProduceDifferentWeights() {
		DiffusionTransformerConfig config = config();
		DiffusionTransformerWeightFixture fixture = new DiffusionTransformerWeightFixture();
		Map<String, PackedCollection> first = fixture.weights(config);
		Map<String, PackedCollection> second = fixture.weights(config);

		assertTrue("successive calls must not repeat the same weights",
				maxDifference(first, second) > 1e-6);
	}
}
