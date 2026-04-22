/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class AudioSceneTest extends TestSuiteBase {
	@Test(timeout = 600_000)
	public void runScene() {
		File samplesDir = new File(AudioSceneOptimizer.LIBRARY);
		Assume.assumeTrue("Library directory required", samplesDir.exists());

		double bpm = 120.0;
		int sourceCount = 4;
		int delayLayerCount = 3;
		int sampleRate = 44100;
		int bufferSize = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;
		int totalFrames = 30 * sampleRate;

		AudioScene<?> scene = new AudioScene<>(bpm, sourceCount, delayLayerCount, sampleRate);
		scene.setLibrary(new AudioLibrary(samplesDir, sampleRate));

		ProjectedGenome random = scene.getGenome().random();
		scene.assignGenome(random);

		File outputFile = new File("results/scene.wav");
		outputFile.getParentFile().mkdirs();
		WaveOutput output = new WaveOutput(() -> outputFile, 24, sampleRate, -1, false);

		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);
		runner.setup().get().run();

		Runnable tick = runner.tick().get();
		int bufferCount = (totalFrames + bufferSize - 1) / bufferSize;
		for (int i = 0; i < bufferCount; i++) {
			tick.run();
		}

		output.write().get().run();

		assertTrue("Output WAV should exist", outputFile.exists());
		assertTrue("Output WAV should not be empty (header is 44 bytes)",
				outputFile.length() > 1024);
	}
}
