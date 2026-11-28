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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.studio.AudioScene;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Verifies {@link AudioScene#renderChannel(int, int, String)}, which builds a real-time runner
 * and an output of its own for each call and releases both before returning.
 */
public class AudioSceneRenderChannelTest extends AudioSceneTestBase {

	/** The channel rendered. */
	private static final int CHANNEL = 0;

	/** Frames rendered per call: two seconds, several real-time buffers. */
	private static final int FRAMES = 2 * SAMPLE_RATE;

	/**
	 * Rendering a channel twice on the same scene writes a complete, finite WAV each time.
	 * Each call destroys the runner and the output it created, so the second render runs on a
	 * scene whose previous render resources have been released and must still produce the full
	 * requested length.
	 *
	 * @throws IOException if a rendered WAV cannot be read back
	 */
	@Test(timeout = 900_000)
	public void renderChannelWritesCompleteWavOnRepeatedCalls() throws IOException {
		AudioScene<?> scene = createBaselineScene(getSamplesDir(), 2);
		long seed = findGenomeSeedForChannels(scene, List.of(CHANNEL));
		Assert.assertTrue("No genome seed found that gives channel " + CHANNEL + " notes", seed >= 0);

		File outDir = new File("results/render-channel");
		outDir.mkdirs();

		for (int render = 0; render < 2; render++) {
			File outFile = new File(outDir, "render_channel_" + render + ".wav");
			outFile.delete();

			String returned = scene.renderChannel(CHANNEL, FRAMES, outFile.getPath());
			Assert.assertEquals("renderChannel must return the path it wrote",
					outFile.getPath(), returned);
			Assert.assertTrue("render " + render + " must write " + outFile, outFile.isFile());

			double[] samples = readWavSamples(outFile);
			Assert.assertTrue("render " + render + " wrote " + samples.length
					+ " samples, fewer than the " + FRAMES + " frames requested",
					samples.length >= FRAMES);
			for (double sample : samples) {
				Assert.assertTrue("render " + render + " produced a non-finite sample",
						Double.isFinite(sample));
			}
		}
	}
}
