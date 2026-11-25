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

package org.almostrealism.audio.data.test;

import org.almostrealism.audio.data.WaveFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link WaveFeatures#meanPooledEmbedding()}, the vector the audio library indexes for
 * nearest-neighbour search and prototype discovery.
 */
public class WaveFeaturesTest extends TestSuiteBase {

	/** Bins per feature frame, matching the latent width of the Stable Audio 3 autoencoder. */
	private static final int BINS = 256;

	/**
	 * The embedding is the per-bin mean of the frames.
	 */
	@Test(timeout = 60000)
	public void meanPooledEmbeddingAveragesFrames() {
		PackedCollection data = pack(1.0, 2.0, 3.0,
				3.0, 6.0, 9.0).reshape(2, 3);
		PackedCollection embedding = new WaveFeatures("m", 10.0, 1, 3, 2, data).meanPooledEmbedding();

		assertEquals(3, embedding.getMemLength());
		assertEquals(2.0, embedding.toDouble(0), 1e-9);
		assertEquals(4.0, embedding.toDouble(1), 1e-9);
		assertEquals(6.0, embedding.toDouble(2), 1e-9);
	}

	/**
	 * Feature data that is not laid out as {@code [frames, bins]} has no frames to pool.
	 */
	@Test(timeout = 60000)
	public void meanPooledEmbeddingIsNullWithoutFrames() {
		Assert.assertNull(new WaveFeatures("m", 10.0, 1, 1, 1, new PackedCollection(1)).meanPooledEmbedding());
	}

	/**
	 * A clip of more than 63 latent frames (about six seconds of Stable Audio 3 latents) pools
	 * without failing, and to the right mean. Pooling by chaining one addition per frame
	 * overflowed the expansion width of the computation at 64 frames.
	 */
	@Test(timeout = 120000)
	public void meanPooledEmbeddingHandlesLongClips() {
		int frames = 88;
		PackedCollection data = new PackedCollection(frames, BINS).randnFill();
		PackedCollection embedding = new WaveFeatures("m", 10.0, 1, BINS, frames, data).meanPooledEmbedding();

		assertEquals(BINS, embedding.getMemLength());

		double[] values = data.toArray();
		double[] pooled = embedding.toArray();
		for (int b = 0; b < BINS; b += 37) {
			double expected = 0;
			for (int f = 0; f < frames; f++) {
				expected += values[f * BINS + b];
			}
			assertEquals(expected / frames, pooled[b], 1e-6);
		}
	}
}
