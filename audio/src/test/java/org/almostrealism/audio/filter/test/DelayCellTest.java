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

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.CellularTemporalFactor;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.function.Supplier;

/**
 * Tests for audio delay cell processing and related effects.
 *
 * <p>These tests load a snare drum sample ("Library/Snare Perc DD.wav") and apply
 * various delay and filter effects. Each test generates both a WAV audio file and
 * a spectrogram PNG image for visual review.</p>
 *
 * <p>The snare drum sample provides a good test signal because it has:</p>
 * <ul>
 *   <li>Sharp transients (the initial hit) that reveal timing accuracy</li>
 *   <li>Broadband frequency content (useful for filter testing)</li>
 *   <li>Short duration (making delays and echoes clearly visible)</li>
 * </ul>
 */
public class DelayCellTest extends TestSuiteBase implements CellFeatures, AudioTestFeatures, RGBFeatures {

	/**
	 * Tests a simple 2-second delay applied to a snare drum sample.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Initial 2 seconds: Silence (dark region) as the delay buffer fills</li>
	 *   <li>At 2 seconds: The snare hit appears as a vertical bright band spanning
	 *       multiple frequencies (broadband transient)</li>
	 *   <li>After the hit: Rapid decay to silence</li>
	 * </ul>
	 *
	 * <p>The spectrogram should show the snare's frequency content shifted 2 seconds
	 * to the right compared to the original sample timing.</p>
	 */
	@Test
	public void delay() {
		CellList c = w(0, getTestWavPath())
				.d(i -> c(2.0))
				.o(i -> new File("results/delay-cell-test.wav"));
		Supplier<Runnable> r = c.sec(6);
		r.get().run();

		generateSpectrogram("results/delay-cell-test.wav", "results/delay-cell-test-spectrogram.png");
	}

	/**
	 * Tests two snare samples with different delays (1s and 2s) summed together.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Initial 1 second: Silence</li>
	 *   <li>At 1 second: First snare hit appears (from the 1-second delayed source)</li>
	 *   <li>At 2 seconds: Second snare hit appears (from the 2-second delayed source)</li>
	 *   <li>The two hits should have similar frequency content but appear at different times</li>
	 * </ul>
	 *
	 * <p>This tests the summing operation - both delayed signals should be combined
	 * without clipping or distortion.</p>
	 */
	@Test
	public void delaySum() {
		CellList c = w(0, getTestWavPath(), getTestWavPath())
				.d(i -> i > 0 ? c(2.0) : c(1.0))
				.sum()
				.o(i -> new File("results/delay-cell-sum-test.wav"));
		Supplier<Runnable> r = c.sec(6);
		r.get().run();

		generateSpectrogram("results/delay-cell-sum-test.wav", "results/delay-cell-sum-test-spectrogram.png");
	}

	/**
	 * Tests a 2-second delay with 0.5x amplitude scaling (50% volume reduction).
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Similar timing to the basic delay test (2 seconds of silence, then snare)</li>
	 *   <li>The snare hit should appear DIMMER than in the basic delay test due to
	 *       the 0.5x scale factor</li>
	 *   <li>All frequency components should be attenuated equally (no frequency-dependent
	 *       changes)</li>
	 * </ul>
	 *
	 * <p>Comparing this spectrogram to the basic delay test should show identical
	 * timing but reduced brightness across all frequencies.</p>
	 */
	@Test
	public void delayScaleFactor() {
		CellList c = w(0, getTestWavPath())
				.d(i -> c(2.0))
				.map(fc(i -> sf(0.5)))
				.o(i -> new File("results/delay-cell-scale-factor-test.wav"));
		Supplier<Runnable> r = c.sec(6);
		r.get().run();

		generateSpectrogram("results/delay-cell-scale-factor-test.wav", "results/delay-cell-scale-factor-test-spectrogram.png");
	}

	/**
	 * Tests a high-pass filter (2000 Hz cutoff) followed by a 2-second delay.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Initial 2 seconds: Silence</li>
	 *   <li>At 2 seconds: Snare hit appears, BUT with reduced low-frequency content</li>
	 *   <li>Frequencies below ~2000 Hz should be significantly attenuated (darker in
	 *       the lower portion of the spectrogram)</li>
	 *   <li>High frequencies (above 2000 Hz) should pass through relatively unchanged</li>
	 * </ul>
	 *
	 * <p>The high-pass filter removes the "body" of the snare sound, leaving primarily
	 * the high-frequency "snap" and overtones. The spectrogram should show a clear
	 * reduction in energy in the lower frequency bands.</p>
	 */
	@Test
	public void filter() {
		Supplier<Runnable> r =
				w(0, getTestWavPath())
						.f(i -> hp(2000, 0.1))
						.d(i -> c(2.0))
						.o(i -> new File("results/filter-delay-cell.wav"))
						.sec(6);
		r.get().run();

		generateSpectrogram("results/filter-delay-cell.wav", "results/filter-delay-cell-spectrogram.png");
	}

	/**
	 * Compares native loop vs Process.isolated() loop execution for filter+delay.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Both output files (filter-loop-comparison-a.wav and filter-loop-comparison-b.wav)
	 *       should produce IDENTICAL spectrograms</li>
	 *   <li>The spectrograms should match the filter() test - high-pass filtered snare
	 *       with 2-second delay</li>
	 *   <li>Any differences between the two spectrograms would indicate a bug in one
	 *       of the execution modes</li>
	 * </ul>
	 *
	 * <p>This test verifies that different execution strategies (native loop vs isolated
	 * process) produce identical audio output. The profiling information printed to
	 * console shows performance differences, but the audio should be the same.</p>
	 */
	@Test
	public void filterLoopComparison() {
		Supplier<Runnable> r =
				iter(w(0, getTestWavPath())
								.f(i -> hp(2000, 0.1))
								.d(i -> c(2.0))
								.o(i -> new File("results/filter-loop-comparison-a.wav")),
						t -> loop(t.tick(), 6 * OutputLine.sampleRate), true);

		OperationProfile profiles = new OperationProfile("Native Loop");
		OperationProfile hardwareProfile = new OperationProfile("HardwareOperator");
		HardwareOperator.timingListener = hardwareProfile.getTimingListener();

		System.out.println("Running native loop...");
		((OperationList) r).get(profiles).run();
		profiles.print();
		System.out.println();
		hardwareProfile.print();
		System.out.println("\n-----\n");

		r =
				iter(w(0, getTestWavPath())
								.f(i -> hp(2000, 0.1))
								.d(i -> c(2.0))
								.o(i -> new File("results/filter-loop-comparison-b.wav")),
						t -> loop(Process.isolated(t.tick()), 6 * OutputLine.sampleRate), true);

		profiles = new OperationProfile("Java Loop");
		hardwareProfile = new OperationProfile("HardwareOperator");
		HardwareOperator.timingListener = hardwareProfile.getTimingListener();

		System.out.println("Running Java loop...");
		((OperationList) r).get(profiles).run();
		profiles.print();
		System.out.println();
		hardwareProfile.print();
		System.out.println();

		generateSpectrogram("results/filter-loop-comparison-a.wav", "results/filter-loop-comparison-a-spectrogram.png");
		generateSpectrogram("results/filter-loop-comparison-b.wav", "results/filter-loop-comparison-b-spectrogram.png");
	}

	/**
	 * Tests a reverb effect (DelayNetwork) applied after high-pass filter and delay.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Initial 2 seconds: Silence (delay buffer filling)</li>
	 *   <li>At 2 seconds: Snare hit appears with high-pass filtering (reduced lows)</li>
	 *   <li>AFTER the initial hit: Extended "tail" of decaying energy as the reverb
	 *       creates multiple delayed reflections</li>
	 *   <li>The reverb tail should show gradual decay over several hundred milliseconds
	 *       to seconds, with energy spread across the frequency spectrum</li>
	 * </ul>
	 *
	 * <p>Unlike a simple delay (single echo), reverb creates many overlapping echoes
	 * that simulate room acoustics. The spectrogram should show the snare's energy
	 * "smearing" over time rather than appearing as discrete hits.</p>
	 */
	@Test
	public void reverb() {
		Supplier<Runnable> r =
				iter(w(0, getTestWavPath())
								.f(i -> hp(2000, 0.1))
								.d(i -> c(2.0))
								.map(fc(i -> new DelayNetwork(32, OutputLine.sampleRate, false)))
								.o(i -> new File("results/reverb-delay-cell-test.wav")),
						t -> new TemporalRunner(t, 6 * OutputLine.sampleRate), true);

		r.get().run();

		generateSpectrogram("results/reverb-delay-cell-test.wav", "results/reverb-delay-cell-test-spectrogram.png");
	}

	/**
	 * Tests dynamic delay time adjustment using a sine wave modulator.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>The delay time varies sinusoidally around 2.0 seconds (base) with +/- 1.0
	 *       second modulation at ~3.4 Hz</li>
	 *   <li>This creates a "wobbling" or "chorus-like" effect on the delayed signal</li>
	 *   <li>The snare hit timing should vary over the 7.5 second duration</li>
	 *   <li>Pitch shifting artifacts may appear as the delay time changes (Doppler-like
	 *       effect)</li>
	 * </ul>
	 *
	 * <p>This tests the ability to modulate delay parameters in real-time. The spectrogram
	 * may show frequency smearing or pitch variation due to the changing delay time.</p>
	 */
	@Test
	public void adjust() {
		SineWaveCell generator = new SineWaveCell();
		generator.setPhase(0.5);
		generator.setNoteLength(0);
		generator.setFreq(3.424);
		generator.setAmplitude(1.0);

		PackedCollection v = new PackedCollection(1);

		CellularTemporalFactor<PackedCollection> adjustment = generator.toFactor(() -> v, this::a);

		CellList cells = w(0, getTestWavPath());
		cells.addRequirement(adjustment);

		cells = cells
				.d(i -> c(2.6), i -> c(2.0).add(adjustment.getResultant(c(1.0))))
				.o(i -> new File("results/adjust-delay-cell-test.wav"));

		Supplier<Runnable> r = cells.sec(7.5);
		r.get().run();

		log(v);

		generateSpectrogram("results/adjust-delay-cell-test.wav", "results/adjust-delay-cell-test-spectrogram.png");
	}

	/**
	 * Tests abort functionality - the operation should stop early when abort flag is set.
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>The test is configured to run for 120 seconds but aborts after ~40ms</li>
	 *   <li>The spectrogram should show a very short duration (only ~40ms of audio)</li>
	 *   <li>Most of the expected 120-second duration should be missing/silent</li>
	 *   <li>The initial portion should show silence (delay hasn't elapsed yet) or
	 *       possibly the very beginning of the delay buffer filling</li>
	 * </ul>
	 *
	 * <p>This tests the abort mechanism rather than audio quality. The spectrogram
	 * is included for completeness but the main verification is that the operation
	 * terminates early rather than running for the full 120 seconds.</p>
	 */
	@Test
	public void abortDelay() {
		PackedCollection abortFlag = new PackedCollection(1);
		OperationList.setAbortFlag(abortFlag);

		Supplier<Runnable> r =
				w(0, getTestWavPath())
						.d(i -> c(2.0))
						.o(i -> new File("results/delay-cell-abort-test.wav"))
						.sec(120);
		Runnable op = r.get();

		Runnable abort = a(1, (Producer) p((PackedCollection) OperationList.getAbortFlag()), (Producer) scalar(1.0)).get();

		new Thread(() -> {
			try {
				Thread.sleep(40);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			abort.run();
		}).start();

		op.run();

		generateSpectrogram("results/delay-cell-abort-test.wav", "results/delay-cell-abort-test-spectrogram.png");
	}

	/**
	 * Generates a spectrogram image from a WAV file.
	 *
	 * @param wavPath path to the input WAV file
	 * @param outputPath path for the output PNG spectrogram image
	 */
	private void generateSpectrogram(String wavPath, String outputPath) {
		try {
			WaveData waveData = WaveData.load(new File(wavPath));
			PackedCollection spectrum = waveData.fft(0, true);

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
					int y = bins - 1 - b;  // Flip so low frequencies at bottom
					double val = spectrum.valueAt(t, b, 0);
					double normalized = maxVal > 0 ? Math.log1p(val) / Math.log1p(maxVal) : 0;
					image.setValueAt(normalized, y, t, 0);
					image.setValueAt(normalized, y, t, 1);
					image.setValueAt(normalized, y, t, 2);
				}
			}

			saveRgb(outputPath, c(p(image))).get().run();
			log("Generated spectrogram: " + outputPath + " (" + bins + "x" + timeSlices + ")");
		} catch (Exception e) {
			log("Failed to generate spectrogram for " + wavPath + ": " + e.getMessage());
		}
	}
}
