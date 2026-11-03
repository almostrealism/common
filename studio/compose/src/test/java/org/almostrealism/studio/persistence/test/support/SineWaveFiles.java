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

package org.almostrealism.studio.persistence.test.support;

import org.almostrealism.audio.WavFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a library of mono sine-wave WAV files for library and store tests.
 */
public final class SineWaveFiles {

	/** Not instantiable. */
	private SineWaveFiles() { }

	/**
	 * Generates sine wave WAV files at logarithmically spaced frequencies
	 * from 55 Hz to 8000 Hz.
	 *
	 * @param directory  the directory to write the files into
	 * @param count      the number of files
	 * @param sampleRate the sample rate in Hz
	 * @param duration   the duration of each file in seconds
	 * @return the files written, in frequency order
	 * @throws IOException if a file cannot be written
	 */
	public static List<File> generate(Path directory, int count, int sampleRate,
									  double duration) throws IOException {
		List<File> files = new ArrayList<>(count);
		double minFreq = 55.0;
		double maxFreq = 8000.0;
		double logMin = Math.log(minFreq);
		double logMax = Math.log(maxFreq);
		int frames = (int) (sampleRate * duration);

		for (int n = 0; n < count; n++) {
			double t = count > 1 ? (double) n / (count - 1) : 0.0;
			double frequency = Math.exp(logMin + t * (logMax - logMin));

			File wavFile = directory.resolve(
					String.format("sine_%04d_%.0fHz.wav", n, frequency)).toFile();

			try (WavFile wav = WavFile.newWavFile(wavFile, 1, frames, 16, sampleRate)) {
				double[][] buffer = new double[1][frames];
				for (int i = 0; i < frames; i++) {
					buffer[0][i] = 0.8 * Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
				}
				wav.writeFrames(buffer, frames);
			}

			files.add(wavFile);
		}

		return files;
	}
}
