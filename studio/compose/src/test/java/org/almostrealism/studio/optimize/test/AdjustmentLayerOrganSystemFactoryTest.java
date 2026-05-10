/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class AdjustmentLayerOrganSystemFactoryTest extends AudioSceneOptimizationTest {

	private static final int BUFFER_SIZE = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;
	private static final double SECONDS = 8.0;

	private static void render(TemporalCellular runner) {
		runner.setup().get().run();
		Runnable tick = runner.tick().get();
		int totalFrames = (int) (SECONDS * OutputLine.sampleRate);
		int bufferCount = (totalFrames + BUFFER_SIZE - 1) / BUFFER_SIZE;
		for (int i = 0; i < bufferCount; i++) tick.run();
	}

	@Test(timeout = 300_000)
	@TestDepth(2)
	public void compare() {
		dc(() -> {
			File fa = new File("results/layered-organ-factory-comp-a.wav");
			File fb = new File("results/layered-organ-factory-comp-b.wav");
			fa.getParentFile().mkdirs();
			WaveOutput outa = new WaveOutput(fa);
			TemporalCellular organa = randomOrgan(pattern(2, 2),
					new MultiChannelAudioOutput(outa), BUFFER_SIZE);
			organa.reset();

			WaveOutput outb = new WaveOutput(fb);
			TemporalCellular organb = randomOrgan(pattern(2, 2),
					new MultiChannelAudioOutput(outb), BUFFER_SIZE);
			organb.reset();

			render(organa);
			outa.write().get().run();

			render(organb);
			outb.write().get().run();

			assertTrue("Comparison output A should exist", fa.exists());
			assertTrue("Comparison output A should not be empty", fa.length() > 1024);
			assertTrue("Comparison output B should exist", fb.exists());
			assertTrue("Comparison output B should not be empty", fb.length() > 1024);
		});
	}

	@Test(timeout = 300_000)
	@TestDepth(1)
	public void layered() {
		File outFile = new File("results/layered-organ-factory-test.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput out = new WaveOutput(outFile);
		TemporalCellular organ = randomOrgan(pattern(2, 2),
				new MultiChannelAudioOutput(out), BUFFER_SIZE);
		organ.reset();

		render(organ);
		out.write().get().run();

		long firstLen = outFile.length();
		render(organ);
		out.write().get().run();

		assertTrue("Output should exist", outFile.exists());
		assertTrue("Output should grow on second render (was " + firstLen
						+ " then " + outFile.length() + ")",
				outFile.length() > firstLen);
	}

	@Test(timeout = 180_000)
	public void layeredRandom() {
		File outFile = new File("results/layered-organ-factory-rand-test.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput out = new WaveOutput(outFile);
		TemporalCellular organ = randomOrgan(pattern(2, 2),
				new MultiChannelAudioOutput(out), BUFFER_SIZE);
		organ.reset();

		render(organ);
		out.write().get().run();

		assertTrue("Output should exist", outFile.exists());
		assertTrue("Output should not be empty", outFile.length() > 1024);
	}
}
