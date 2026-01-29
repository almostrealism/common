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

package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Proof-of-concept test for audio spectrogram visualization.
 *
 * <p>This test validates the approach of rendering audio as spectrogram images
 * that can be reviewed by an AI agent for quality issues such as:</p>
 * <ul>
 *   <li>Unexpected silence (dark vertical bands)</li>
 *   <li>Noise or artifacts (scattered bright spots)</li>
 *   <li>Missing frequency content</li>
 *   <li>Overall structure issues</li>
 * </ul>
 *
 * <p>The workflow is:</p>
 * <ol>
 *   <li>Export audio to PackedCollection using CellList::export</li>
 *   <li>Wrap in WaveData and apply FFT to get frequency spectrum</li>
 *   <li>Transform FFT output to RGB image format</li>
 *   <li>Save as PNG for visual review</li>
 * </ol>
 */
public class SpectrogramVisualizationTest extends TestSuiteBase implements CellFeatures, RGBFeatures {

	/**
	 * Tests spectrogram generation for a pure 440 Hz sine wave.
	 *
	 * <p>Expected output: A single bright horizontal band near the bottom of the image,
	 * representing the single frequency component at 440 Hz. The band should be
	 * consistent across the entire time axis (left to right), indicating a steady tone.</p>
	 */
	@Test
	public void sineWaveSpectrogram() {
		int sampleRate = OutputLine.sampleRate;
		double duration = 3.0;
		int frames = (int) (sampleRate * duration);

		// Generate a simple sine wave at 440 Hz
		SineWaveCell generator = new SineWaveCell();
		generator.setFreq(440.0);
		generator.setAmplitude(0.8);
		generator.setPhase(0.0);
		generator.setNoteLength((int) (duration * 1000));  // milliseconds

		// Create a cell list with the sine wave
		CellList cells = cells(generator);

		// Destination for exported audio
		PackedCollection audio = new PackedCollection(1, frames);

		// Export audio to the PackedCollection
		Supplier<Runnable> exportOp = export(cells, audio);
		exportOp.get().run();

		// Wrap in WaveData and apply FFT
		WaveData waveData = new WaveData(audio, sampleRate);
		PackedCollection spectrum = waveData.fft(0, true);

		log("Spectrum shape: " + spectrum.getShape());

		// Transform FFT output to RGB image
		PackedCollection image = createSpectrogramImage(spectrum);

		// Save the spectrogram image
		saveRgb("results/spectrogram_sine440.png", c(p(image))).get().run();

		log("Spectrogram saved to results/spectrogram_sine440.png");
	}

	/**
	 * Converts FFT spectrum data to an RGB image suitable for visualization.
	 *
	 * <p>The image has frequency on the Y-axis (low frequencies at bottom, high at top)
	 * and time on the X-axis (left to right). Brightness indicates magnitude.</p>
	 *
	 * @param spectrum FFT output with shape (time_slices, bins, 1)
	 * @return PackedCollection with shape (bins, time_slices, 3) for RGB image
	 */
	protected PackedCollection createSpectrogramImage(PackedCollection spectrum) {
		int timeSlices = spectrum.getShape().length(0);
		int bins = spectrum.getShape().length(1);

		// Find max value for normalization
		double maxVal = 0;
		for (int t = 0; t < timeSlices; t++) {
			for (int b = 0; b < bins; b++) {
				double val = spectrum.valueAt(t, b, 0);
				if (val > maxVal) maxVal = val;
			}
		}

		// Create RGB image (bins x timeSlices x 3) - frequency on Y, time on X
		PackedCollection image = new PackedCollection(bins, timeSlices, 3);
		for (int t = 0; t < timeSlices; t++) {
			for (int b = 0; b < bins; b++) {
				// Flip b so low frequencies are at bottom
				int y = bins - 1 - b;
				double val = spectrum.valueAt(t, b, 0);
				// Normalize and apply log scale for better visualization
				double normalized = maxVal > 0 ? Math.log1p(val) / Math.log1p(maxVal) : 0;
				// Set grayscale RGB
				image.setValueAt(normalized, y, t, 0);
				image.setValueAt(normalized, y, t, 1);
				image.setValueAt(normalized, y, t, 2);
			}
		}

		return image;
	}
}
