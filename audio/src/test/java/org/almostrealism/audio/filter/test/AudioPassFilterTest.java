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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalFactor;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class AudioPassFilterTest implements CellFeatures, TestFeatures {
	public static boolean enableVerbose = false;

	@Test
	public void highPass() throws IOException {
		WavFile f = WavFile.openWavFile(new File("Library/Snare Perc DD.wav"));
		AudioPassFilter filter = new AudioPassFilter((int) f.getSampleRate(), c(2000), scalar(0.1), true);
		runFilter("high-pass", f, filter);
	}

	@Test
	public void lowPass() throws IOException {
		WavFile f = WavFile.openWavFile(new File("Library/Snare Perc DD.wav"));
		AudioPassFilter filter = new AudioPassFilter((int) f.getSampleRate(), c(1800), scalar(0.1), false);
		runFilter("low-pass", f, filter);
	}

	public void runFilter(String name, WavFile f, TemporalFactor<PackedCollection> filter) throws IOException {
		runFilter(name, f, filter, false);
	}

	public void runFilter(String name, WavFile f, TemporalFactor<PackedCollection> filter, boolean optimize) throws IOException {
		runFilter(name, f, filter, optimize, 0);
	}

	public void runFilter(String name, WavFile f, TemporalFactor<PackedCollection> filter, boolean optimize, int padFrames) throws IOException {
		double[][] data = new double[f.getNumChannels()][(int) f.getFramesRemaining()];
		f.readFrames(data, (int) f.getFramesRemaining());

		PackedCollection values = WavFile.channel(data, 0, padFrames);
		PackedCollection out = new PackedCollection(values.getMemLength());
		PackedCollection current = new PackedCollection(1);

		Evaluable<PackedCollection> ev = filter.getResultant(p(current)).get();
		Runnable tick = optimize ? Process.optimized(filter.tick()).get() : filter.tick().get();

		Runnable r = () -> {
			for (int i = 0; i < values.getMemLength(); i++) {
				current.setMem(values.toDouble(i));
				out.setMem(i, ev.evaluate().toDouble(0));
				tick.run();
			}
		};

		if (enableVerbose) {
			verboseLog(r);
		} else {
			r.run();
		}

		WavFile wav = WavFile.newWavFile(new File("results/" + name  + "-filter-test.wav"), 1, out.getMemLength(),
				f.getValidBits(), f.getSampleRate());

		for (int i = 0; i < out.getMemLength(); i++) {
			double value = out.toDouble(i);

			try {
				wav.writeFrames(new double[][]{{value}}, 1);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		wav.close();
	}
}
