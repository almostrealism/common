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

package org.almostrealism.audio.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.heredity.ProjectedGenome;
import org.junit.Test;

import java.io.File;
import java.util.function.Supplier;

public class AudioSceneTest {
	@Test
	public void runScene() {
		// Settings for the scene
		double bpm = 120.0;
		int sourceCount = 4;
		int delayLayerCount = 3;
		int sampleRate = 44100;

		// Create the scene
		AudioScene scene = new AudioScene<>(bpm, sourceCount, delayLayerCount, sampleRate);

		// Load a library of material to use for creating notes to use
		// in the patterns that make up the arrangement
		scene.setLibrary(new AudioLibrary(new File("/Users/michael/Music/Samples"), sampleRate));

		// Create a random parameterization of the scene
		ProjectedGenome random = scene.getGenome().random();
		scene.assignGenome(random);

		// Create a destination for the output audio
		WaveOutput output = new WaveOutput(() -> new File("scene.wav"), 24, sampleRate, -1, false);

		// Generate the media pipeline
		Supplier<Runnable> process = scene.runner(new MultiChannelAudioOutput(output)).iter(30 * sampleRate);

		// Compile and run the pipeline
		process.get().run();

		// Save the resulting audio
		output.write().get().run();
	}
}
