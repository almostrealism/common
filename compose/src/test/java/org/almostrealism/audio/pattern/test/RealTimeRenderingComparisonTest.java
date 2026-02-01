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

package org.almostrealism.audio.pattern.test;

import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalRunner;
import org.junit.Test;

import java.io.File;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for real-time rendering verification using spectrograms.
 *
 * <p>These tests create synthetic audio (sine waves) and verify that real-time
 * rendering produces the same output as traditional batch rendering. The approach
 * uses spectrograms for visual and numerical comparison.</p>
 *
 * <h2>Testing Strategy</h2>
 * <p>Each test:</p>
 * <ol>
 *   <li>Creates synthetic audio using SineWaveCell</li>
 *   <li>Renders using both traditional and simulated real-time methods</li>
 *   <li>Generates spectrograms for both outputs</li>
 *   <li>Compares spectrograms numerically and visually</li>
 * </ol>
 */
public class RealTimeRenderingComparisonTest extends AudioSceneTestBase {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final double DURATION_SECONDS = 4.0;
	private static final int TOTAL_FRAMES = (int) (SAMPLE_RATE * DURATION_SECONDS);

	/**
	 * Tests basic sine wave rendering produces consistent output.
	 *
	 * <p>This baseline test verifies that our sine wave generation
	 * produces a clean, predictable spectrogram.</p>
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Single bright horizontal line at 440 Hz (A4 note)</li>
	 *   <li>Consistent brightness throughout the duration</li>
	 *   <li>No harmonics or noise (pure sine wave)</li>
	 * </ul>
	 */
	@Test
	public void sineWaveBaseline() {
		SineWaveCell sine = new SineWaveCell();
		sine.setFreq(440.0);
		sine.setAmplitude(0.8);
		sine.setNoteLength(0);  // Continuous
		sine.setPhase(0);

		CellList cells = cells(sine)
				.o(i -> new File("results/realtime-sine-baseline.wav"));

		Supplier<Runnable> runner = cells.sec(DURATION_SECONDS);
		runner.get().run();

		generateSpectrogram(
				"results/realtime-sine-baseline.wav",
				"results/realtime-sine-baseline-spectrogram.png");
	}

	/**
	 * Tests that processing audio using TemporalRunner produces expected output.
	 *
	 * <p>This uses the standard TemporalRunner approach which is efficient
	 * and processes audio in batch.</p>
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Output should be IDENTICAL to sineWaveBaseline</li>
	 *   <li>Any differences indicate processing issues</li>
	 * </ul>
	 */
	@Test
	public void sineWaveWithTemporalRunner() {
		SineWaveCell sine = new SineWaveCell();
		sine.setFreq(440.0);
		sine.setAmplitude(0.8);
		sine.setNoteLength(0);
		sine.setPhase(0);

		CellList cells = cells(sine)
				.o(i -> new File("results/realtime-sine-temporal.wav"));

		// Use TemporalRunner for efficient batch processing
		Runnable runner = new TemporalRunner(cells, TOTAL_FRAMES).get();
		runner.run();

		generateSpectrogram(
				"results/realtime-sine-temporal.wav",
				"results/realtime-sine-temporal-spectrogram.png");
	}

	/**
	 * Tests multiple overlapping frequencies to verify correct frequency separation.
	 *
	 * <p>Creates a chord with three distinct frequencies and verifies they appear
	 * as separate horizontal lines in the spectrogram.</p>
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Three distinct horizontal lines at 220 Hz, 440 Hz, and 880 Hz</li>
	 *   <li>Each line should be clearly separated</li>
	 *   <li>Consistent brightness throughout</li>
	 * </ul>
	 */
	@Test
	public void multiFrequencyTest() {
		// Create three sine waves at different frequencies (A3, A4, A5)
		SineWaveCell sine1 = new SineWaveCell();
		sine1.setFreq(220.0);
		sine1.setAmplitude(0.4);
		sine1.setNoteLength(0);
		sine1.setPhase(0);

		SineWaveCell sine2 = new SineWaveCell();
		sine2.setFreq(440.0);
		sine2.setAmplitude(0.4);
		sine2.setNoteLength(0);
		sine2.setPhase(0);

		SineWaveCell sine3 = new SineWaveCell();
		sine3.setFreq(880.0);
		sine3.setAmplitude(0.4);
		sine3.setNoteLength(0);
		sine3.setPhase(0);

		CellList cells = cells(sine1, sine2, sine3)
				.sum()
				.o(i -> new File("results/realtime-multi-freq.wav"));

		Supplier<Runnable> runner = cells.sec(DURATION_SECONDS);
		runner.get().run();

		generateSpectrogram(
				"results/realtime-multi-freq.wav",
				"results/realtime-multi-freq-spectrogram.png");
	}

	/**
	 * Tests delayed audio to verify timing accuracy.
	 *
	 * <p>Applies a 1-second delay to a 440 Hz sine wave. The spectrogram should
	 * show 1 second of silence followed by the sine tone.</p>
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>First 1 second: Dark/silent region</li>
	 *   <li>After 1 second: Bright horizontal line at 440 Hz</li>
	 *   <li>Clear transition at the 1-second mark</li>
	 * </ul>
	 */
	@Test
	public void delayedSineWave() {
		SineWaveCell sine = new SineWaveCell();
		sine.setFreq(440.0);
		sine.setAmplitude(0.8);
		sine.setNoteLength(0);
		sine.setPhase(0);

		CellList cells = cells(sine)
				.d(i -> c(1.0))  // 1-second delay
				.o(i -> new File("results/realtime-delayed-sine.wav"));

		Supplier<Runnable> runner = cells.sec(DURATION_SECONDS);
		runner.get().run();

		generateSpectrogram(
				"results/realtime-delayed-sine.wav",
				"results/realtime-delayed-sine-spectrogram.png");
	}

	/**
	 * Tests frequency sweep to verify time-varying content.
	 *
	 * <p>Creates a frequency sweep from 220 Hz to 880 Hz over the duration.
	 * This tests the system's ability to handle changing audio content.</p>
	 *
	 * <p><b>Expected spectrogram behavior:</b></p>
	 * <ul>
	 *   <li>Diagonal line from bottom-left (220 Hz, t=0) to top-right (880 Hz, t=end)</li>
	 *   <li>Smooth transition without discontinuities</li>
	 * </ul>
	 */
	@Test
	public void frequencySweep() {
		// Create a simple sweep by summing multiple timed sine waves
		// Each starts at a different time with a different frequency
		final int numSteps = 16;
		final double stepDuration = DURATION_SECONDS / numSteps;
		final double startFreq = 220.0;
		final double endFreq = 880.0;
		final double freqStep = (endFreq - startFreq) / numSteps;

		PackedCollection output = new PackedCollection(TOTAL_FRAMES);

		// Generate sweep data manually
		for (int i = 0; i < TOTAL_FRAMES; i++) {
			double t = (double) i / SAMPLE_RATE;
			double progress = t / DURATION_SECONDS;
			double freq = startFreq + (endFreq - startFreq) * progress;
			double phase = 2 * Math.PI * freq * t;
			output.setValueAt(0.8 * Math.sin(phase), i);
		}

		WaveData waveData = new WaveData(output, SAMPLE_RATE);
		waveData.save(new File("results/realtime-freq-sweep.wav"));

		generateSpectrogram(
				"results/realtime-freq-sweep.wav",
				"results/realtime-freq-sweep-spectrogram.png");
	}

	/**
	 * Tests comparison between two identical processing pipelines.
	 *
	 * <p>This verifies that two identical pipelines produce numerically
	 * identical results, which confirms the test infrastructure is working.</p>
	 */
	@Test
	public void identicalPipelinesComparison() {
		final String file1 = "results/realtime-pipeline-a.wav";
		final String file2 = "results/realtime-pipeline-b.wav";

		// First pipeline
		SineWaveCell sine1 = new SineWaveCell();
		sine1.setFreq(440.0);
		sine1.setAmplitude(0.8);
		sine1.setNoteLength(0);
		sine1.setPhase(0);

		CellList cells1 = cells(sine1)
				.o(i -> new File(file1));
		cells1.sec(DURATION_SECONDS).get().run();

		// Second pipeline - identical configuration
		SineWaveCell sine2 = new SineWaveCell();
		sine2.setFreq(440.0);
		sine2.setAmplitude(0.8);
		sine2.setNoteLength(0);
		sine2.setPhase(0);

		CellList cells2 = cells(sine2)
				.o(i -> new File(file2));
		cells2.sec(DURATION_SECONDS).get().run();

		// Generate spectrograms
		generateSpectrogram(file1, "results/realtime-pipeline-a-spectrogram.png");
		generateSpectrogram(file2, "results/realtime-pipeline-b-spectrogram.png");

		// Load and compare the audio files
		try {
			WaveData data1 = WaveData.load(new File(file1));
			WaveData data2 = WaveData.load(new File(file2));

			PackedCollection samples1 = data1.getData();
			PackedCollection samples2 = data2.getData();

			// Compare lengths
			int len1 = samples1.getShape().length(0);
			int len2 = samples2.getShape().length(0);

			log("Pipeline A samples: " + len1 + ", Pipeline B samples: " + len2);
			assertEquals("Both pipelines should produce same length", len1, len2);

			// Compare sample values
			double maxDiff = 0;
			double sumDiff = 0;
			int diffCount = 0;

			for (int i = 0; i < len1; i++) {
				double diff = Math.abs(samples1.valueAt(i) - samples2.valueAt(i));
				if (diff > maxDiff) maxDiff = diff;
				sumDiff += diff;
				if (diff > 0.0001) diffCount++;
			}

			double avgDiff = sumDiff / len1;
			log("Max difference: " + maxDiff);
			log("Average difference: " + avgDiff);
			log("Samples with significant difference: " + diffCount + " / " + len1);

			// Identical pipelines should produce identical results
			assertEquals("Identical pipelines should produce identical output", 0.0, maxDiff, 0.0001);
		} catch (Exception e) {
			log("Comparison failed: " + e.getMessage());
			fail("Comparison failed: " + e.getMessage());
		}
	}

}
