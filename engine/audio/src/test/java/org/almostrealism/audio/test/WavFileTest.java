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

package org.almostrealism.audio.test;

import org.almostrealism.audio.WavFile;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;

/**
 * Verifies that {@link WavFile} round-trips normalized samples with their sign
 * intact across the bit depths its documentation claims to support (2-64 bits).
 */
public class WavFileTest extends TestSuiteBase {

	/**
	 * Writes a constant positive sample at {@code validBits} depth and reads it
	 * back, returning the recovered normalized value.
	 *
	 * @param validBits the bit depth to exercise
	 * @return the sample read back from the written file
	 * @throws Exception if the temporary WAV file cannot be written or read
	 */
	private double roundTrip(int validBits) throws Exception {
		File file = File.createTempFile("wavfile-" + validBits + "-", ".wav");
		file.deleteOnExit();

		int frames = 8;
		double written = 0.5;
		double[][] out = new double[1][frames];
		for (int i = 0; i < frames; i++) out[0][i] = written;

		try (WavFile wav = WavFile.newWavFile(file, 1, frames, validBits, 44100L)) {
			wav.writeFrames(out, frames);
		}

		double[][] in = new double[1][frames];
		try (WavFile wav = WavFile.openWavFile(file)) {
			wav.readFrames(in, frames);
		}
		return in[0][0];
	}

	/** A 24-bit sample round-trips with its sign and magnitude preserved. */
	@Test(timeout = 30000)
	public void roundTrip24BitPreservesSample() throws Exception {
		Assert.assertEquals(0.5, roundTrip(24), 0.001);
	}

	/**
	 * A 32-bit sample must round-trip with its sign preserved. The read-side
	 * scale factor is computed with a 32-bit shift ({@code 1 << (validBits - 1)}),
	 * which overflows to a negative value at 32 bits and silently inverts every
	 * sample, while the write side uses long arithmetic and stays positive.
	 */
	@Test(timeout = 30000)
	public void roundTrip32BitPreservesSign() throws Exception {
		Assert.assertEquals(0.5, roundTrip(32), 0.001);
	}

	/**
	 * A 64-bit sample must round-trip with its sign preserved. The read-side
	 * scale factor previously used {@code 1L << (validBits - 1)}, which overflows
	 * to {@link Long#MIN_VALUE} at 64 bits and sign-inverts every sample; the
	 * power-of-two scale is now computed in floating point and stays positive
	 * for every depth the class accepts.
	 */
	@Test(timeout = 30000)
	public void roundTrip64BitPreservesSign() throws Exception {
		Assert.assertEquals(0.5, roundTrip(64), 0.001);
	}
}
