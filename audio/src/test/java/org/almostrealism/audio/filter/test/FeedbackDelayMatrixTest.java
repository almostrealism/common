/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.filter.DelayNetwork;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class FeedbackDelayMatrixTest extends AudioPassFilterTest {

	@Test
	public void parallelVerb() throws IOException {
		WavFile f = WavFile.openWavFile(new File("Library/Snare Gold 1.wav"));
		DelayNetwork verb = new DelayNetwork(0.001, 512, 1.5, (int) f.getSampleRate(), true);
		runFilter("parallel-reverb", f, verb, true, (int) (f.getSampleRate() * 6));
	}

	@Test
	public void singleFrameVerb() throws IOException {
		WavFile f = WavFile.openWavFile(new File("Library/Snare Gold 1.wav"));
		DelayNetwork verb = new DelayNetwork(0.5, 2, 1.0 / 44100.0, (int) f.getSampleRate(), false);
		runFilter("single-frame-reverb", f, verb, true, (int) (f.getSampleRate() * 6));
	}

	@Test
	public void reverb() throws IOException {
		if (testDepth < 1) return;
		reverb(false);
	}

	@Test
	public void reverbOptimized() throws IOException {
		if (testDepth < 1) return;
		reverb(true);
	}

	public void reverb(boolean optimize) throws IOException {
		WavFile f = WavFile.openWavFile(new File("Library/Snare Gold 1.wav"));
		DelayNetwork verb = new DelayNetwork((int) f.getSampleRate(), false);
		runFilter(optimize ? "reverb-opt" : "reverb", f, verb, optimize, (int) (f.getSampleRate() * 6));
	}
}
