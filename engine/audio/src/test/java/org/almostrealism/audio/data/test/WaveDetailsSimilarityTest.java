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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;

/**
 * Pins the values {@link WaveDetailsFactory} reports as feature similarity, which
 * is documented as the trimmed mean of per-frame cosine similarities and so must
 * lie in [-1, 1]: an item is maximally similar to itself and to a rescaled copy of
 * itself, and unrelated features score near zero. The pairwise and the batched
 * paths must agree.
 */
public class WaveDetailsSimilarityTest extends TestSuiteBase {

	/** Audio sample rate of the test items. */
	private static final int SAMPLE_RATE = 44100;

	/** Audio duration of the test items, in seconds. */
	private static final double DURATION = 11.0;

	/** Builds an item whose active-model features are {@code [frames, bins]} gaussian noise times {@code scale}. */
	private static WaveDetails item(String identifier, int frames, int bins, long seed, double scale) {
		Random random = new Random(seed);
		double[] values = new double[frames * bins];
		for (int i = 0; i < values.length; i++) values[i] = scale * random.nextGaussian();

		PackedCollection features = new PackedCollection(frames, bins);
		features.set(0, values);

		WaveDetails details = new WaveDetails(identifier, SAMPLE_RATE);
		details.setSampleRate(SAMPLE_RATE);
		details.setFrameCount((int) (SAMPLE_RATE * DURATION));
		details.putFeatures(new WaveFeatures("model", frames / DURATION, 1, bins, frames, features));
		details.setActiveModelIdentifier("model");
		return details;
	}

	/** Similarity of noise features, their rescaled copies and unrelated noise, at several shapes. */
	@Test(timeout = 120000)
	public void similarityIsACosine() {
		WaveDetailsFactory factory = new WaveDetailsFactory(SAMPLE_RATE);

		for (int[] shape : new int[][] { { 4, 8 }, { 64, 64 }, { 237, 256 } }) {
			WaveDetails a = item("a", shape[0], shape[1], 1, 1.0);
			WaveDetails scaled = item("a-scaled", shape[0], shape[1], 1, 7.5);
			WaveDetails b = item("b", shape[0], shape[1], 2, 1.0);

			String label = shape[0] + "x" + shape[1];
			Assert.assertEquals("self-similarity " + label, 1.0, factory.similarity(a, a), 1e-4);
			Assert.assertEquals("similarity to a rescaled copy " + label,
					1.0, factory.similarity(a, scaled), 1e-4);

			double unrelated = factory.similarity(a, b);
			Assert.assertTrue("unrelated features " + label + " score " + unrelated,
					Math.abs(unrelated) < 0.5);

			double[] batch = factory.batchSimilarity(a, List.of(a, scaled, b));
			Assert.assertEquals("batched self-similarity " + label, 1.0, batch[0], 1e-4);
			Assert.assertEquals("batched rescaled similarity " + label, 1.0, batch[1], 1e-4);
			Assert.assertEquals("batched and pairwise agree " + label, unrelated, batch[2], 1e-4);
		}
	}
}
