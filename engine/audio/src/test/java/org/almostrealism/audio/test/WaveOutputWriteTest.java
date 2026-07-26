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

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Verifies that {@link WaveOutput#write()} produces a correct WAV file when the
 * captured audio spans multiple write batches. The writer reads channel data from
 * device memory in bounded chunks rather than materializing the whole file as
 * {@code double[]} arrays, so this test uses a frame count larger than two batches
 * to exercise the batch boundaries as well as the final partial batch.
 */
public class WaveOutputWriteTest extends TestSuiteBase implements CellFeatures {

	/**
	 * Total frames written by the test; deliberately larger than two write batches
	 * (and not a multiple of the batch size) so the batched writer crosses two
	 * batch boundaries and finishes with a partial batch.
	 */
	private static final int TOTAL_FRAMES = 2_500_000;

	/** Tolerance for sample comparison, covering FP32 storage and 24-bit quantization. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * Bulk appends must land frames at the write cursor exactly as per-frame pushes
	 * do: consecutive appends occupy consecutive regions of the channel buffer and
	 * the cursor advances by the appended count each time.
	 */
	@Test(timeout = 300000)
	public void appendPlacesFramesAtCursor() {
		int count = 1024;
		int total = 2 * count;
		WaveData wave = new WaveData(2, 4 * count, OutputLine.sampleRate);
		WaveOutput out = new WaveOutput(() -> null, 24, wave);

		try {
			PackedCollection first = new PackedCollection(count);
			integers(0, count).divide(c((double) total))
					.into(first.traverseEach()).evaluate();
			PackedCollection second = new PackedCollection(count);
			integers(count, total).divide(c((double) total))
					.into(second.traverseEach()).evaluate();

			out.append(0, first, count).get().run();
			out.append(1, first, count).get().run();
			out.append(0, second, count).get().run();
			out.append(1, second, count).get().run();

			Assert.assertEquals("cursor must advance by the appended frame count",
					total - 1, out.getFrameCount());

			double[] channel = wave.getChannelData(0).toArray(0, total);
			for (int i = 0; i < total; i++) {
				Assert.assertEquals("appended frame " + i + " must land at the"
								+ " cursor position of its append",
						i / (double) total, channel[i], 1e-5);
			}
		} finally {
			out.destroy();
			wave.destroy();
		}
	}

	/**
	 * Writes a stereo ramp signal spanning multiple write batches, then reads the
	 * file back and confirms every frame of both channels matches the source data.
	 *
	 * @throws IOException if the written file cannot be read back
	 */
	@Test(timeout = 300000)
	public void writeMultipleBatches() throws IOException {
		new File("results").mkdirs();
		File f = new File("results/waveoutput-write-batch.wav");

		WaveData wave = new WaveData(2, TOTAL_FRAMES, OutputLine.sampleRate);
		WaveOutput out = new WaveOutput(() -> f, 24, wave);

		try {
			integers(0, TOTAL_FRAMES).divide(c((double) TOTAL_FRAMES))
					.into(wave.getChannelData(0).traverseEach()).evaluate();
			integers(0, TOTAL_FRAMES).divide(c((double) -TOTAL_FRAMES))
					.into(wave.getChannelData(1).traverseEach()).evaluate();

			out.getCursor(0).fill((double) (TOTAL_FRAMES + 1));
			out.getCursor(1).fill((double) (TOTAL_FRAMES + 1));
			Assert.assertEquals(TOTAL_FRAMES, out.getFrameCount());

			out.write().get().run();
		} finally {
			out.destroy();
			wave.destroy();
		}

		WavFile in = WavFile.openWavFile(f);

		try {
			Assert.assertEquals(2, in.getNumChannels());
			Assert.assertEquals(TOTAL_FRAMES, in.getNumFrames());

			double[][] buffer = new double[2][OutputLine.sampleRate];
			int position = 0;

			while (position < TOTAL_FRAMES) {
				int read = in.readFrames(buffer, OutputLine.sampleRate);
				if (read <= 0) break;

				for (int i = 0; i < read; i++) {
					int frame = position + i;
					double expected = frame / (double) TOTAL_FRAMES;
					Assert.assertEquals("left channel frame " + frame,
							expected, buffer[0][i], TOLERANCE);
					Assert.assertEquals("right channel frame " + frame,
							-expected, buffer[1][i], TOLERANCE);
				}

				position += read;
			}

			Assert.assertEquals(TOTAL_FRAMES, position);
		} finally {
			in.close();
		}
	}
}
