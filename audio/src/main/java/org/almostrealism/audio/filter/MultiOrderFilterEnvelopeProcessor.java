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

package org.almostrealism.audio.filter;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A high-order low-pass filter with time-varying cutoff frequency controlled by an ADSR envelope.
 * <p>
 * This processor combines envelope generation with multi-order filtering to apply dynamic frequency
 * shaping to audio signals. The cutoff frequency varies over time according to an ADSR
 * (Attack-Decay-Sustain-Release) envelope, sweeping from 0 Hz up to a configurable peak frequency.
 * </p>
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li><b>40th-order filtering</b>: Provides very steep frequency rolloff for precise spectral control</li>
 *   <li><b>Time-varying cutoff</b>: Cutoff frequency dynamically modulated by ADSR envelope</li>
 *   <li><b>Hardware-accelerated</b>: Operations compiled to native code via ar-common framework</li>
 *   <li><b>Memory-managed</b>: Implements {@link Destroyable} for proper resource cleanup</li>
 * </ul>
 *
 * <h3>Processing Pipeline</h3>
 * <ol>
 *   <li>ADSR envelope is evaluated to generate time-varying control signal (0.0 to 1.0)</li>
 *   <li>Control signal is scaled to cutoff frequency range (0 Hz to {@link #filterPeak})</li>
 *   <li>Multi-order low-pass filter is applied using the time-varying cutoff</li>
 * </ol>
 *
 * <h3>Typical Usage</h3>
 * <pre>
 * MultiOrderFilterEnvelopeProcessor processor = new MultiOrderFilterEnvelopeProcessor(44100, 30.0);
 * processor.setDuration(5.0);
 * processor.setAttack(0.5);
 * processor.setDecay(1.0);
 * processor.setSustain(0.7);
 * processor.setRelease(2.0);
 * processor.process(inputAudio, outputAudio);
 * processor.destroy();
 * </pre>
 *
 * @see EnvelopeProcessor
 * @see EnvelopeFeatures
 * @see FilterEnvelopeProcessor
 */
// TODO  This should implement AudioProcessor
public class MultiOrderFilterEnvelopeProcessor implements EnvelopeProcessor, Destroyable, CellFeatures, EnvelopeFeatures {
	/** Maximum cutoff frequency in Hz. Envelope peak maps to this frequency. */
	public static double filterPeak = 20000;

	/** Order of the low-pass filter. Higher values produce steeper rolloff. */
	public static int filterOrder = 40;

	/** Number of histogram bins for tracking input frame distribution. */
	public static final int HISTOGRAM_BINS = 100;

	/** Minimum frame count for histogram tracking. */
	public static final int HISTOGRAM_MIN_FRAMES = 10;

	/** Maximum frame count for histogram tracking. */
	public static final int HISTOGRAM_MAX_FRAMES = 1_000_000;

	/** Width of each histogram bin in frames. */
	private static final int HISTOGRAM_BIN_WIDTH = (HISTOGRAM_MAX_FRAMES - HISTOGRAM_MIN_FRAMES) / HISTOGRAM_BINS;

	private final PackedCollection cutoff;

	private final PackedCollection duration;
	private final PackedCollection attack;
	private final PackedCollection decay;
	private final PackedCollection sustain;
	private final PackedCollection release;

	private Evaluable<PackedCollection> cutoffEnvelope;
	private Evaluable<PackedCollection> multiOrderFilter;

	private boolean histogramEnabled;
	private long[] histogram;

	/**
	 * Constructs a new multi-order filter envelope processor.
	 * <p>
	 * This constructor pre-allocates memory for the maximum expected duration and compiles
	 * the envelope generation and filtering operations for the specified sample rate.
	 * </p>
	 *
	 * @param sampleRate  The sample rate in Hz (e.g., 44100)
	 * @param maxSeconds  Maximum duration in seconds that can be processed in a single call.
	 *                    Determines the size of internal buffers.
	 */
	public MultiOrderFilterEnvelopeProcessor(int sampleRate, double maxSeconds) {
		int maxFrames = (int) (maxSeconds * sampleRate);

		cutoff = new PackedCollection(maxFrames);
		duration = new PackedCollection(1);
		attack = new PackedCollection(1);
		decay = new PackedCollection(1);
		sustain = new PackedCollection(1);
		release = new PackedCollection(1);

		EnvelopeSection envelope = envelope(cp(duration), cp(attack), cp(decay), cp(sustain), cp(release));
		Producer<PackedCollection> env =
				sampling(sampleRate, () -> envelope.get().getResultant(c(filterPeak)));

		cutoffEnvelope = env.get();
		multiOrderFilter = lowPass(v(shape(-1, maxFrames), 0),
								v(shape(-1, maxFrames), 1),
								sampleRate, filterOrder)
							.get();
	}

	/**
	 * Sets the total duration of the envelope in seconds.
	 * <p>
	 * This is the total time from the start of the attack phase to the end of the release phase.
	 * The envelope will reach 0 at this point.
	 * </p>
	 *
	 * @param duration  Total envelope duration in seconds (must be positive)
	 */
	public void setDuration(double duration) {
		this.duration.set(0, duration);
	}

	/**
	 * Sets the attack time in seconds.
	 * <p>
	 * During the attack phase, the cutoff frequency ramps up from 0 Hz to {@link #filterPeak}.
	 * The attack time is automatically clamped to 75% of the total duration.
	 * </p>
	 *
	 * @param attack  Attack time in seconds (must be positive)
	 */
	public void setAttack(double attack) {
		this.attack.set(0, attack);
	}

	/**
	 * Sets the decay time in seconds.
	 * <p>
	 * During the decay phase, the cutoff frequency drops from the peak to the sustain level.
	 * The decay time is automatically clamped to 25% of the total duration.
	 * </p>
	 *
	 * @param decay  Decay time in seconds (must be positive)
	 */
	public void setDecay(double decay) {
		this.decay.set(0, decay);
	}

	/**
	 * Sets the sustain level as a fraction of the peak cutoff frequency.
	 * <p>
	 * The sustain level determines the cutoff frequency during the sustain phase,
	 * as a proportion of {@link #filterPeak}.
	 * </p>
	 *
	 * @param sustain  Sustain level (0.0 to 1.0, where 1.0 = full {@link #filterPeak})
	 */
	public void setSustain(double sustain) {
		this.sustain.set(0, sustain);
	}

	/**
	 * Sets the release time in seconds.
	 * <p>
	 * During the release phase, the cutoff frequency ramps down from the sustain level to 0 Hz.
	 * The release begins at the envelope duration and extends backward by the release time.
	 * </p>
	 *
	 * @param release  Release time in seconds (must be positive)
	 */
	public void setRelease(double release) {
		this.release.set(0, release);
	}

	/**
	 * Enables histogram tracking of input frame sizes.
	 * <p>
	 * When enabled, each call to {@link #process(PackedCollection, PackedCollection)} will
	 * increment a histogram bin based on the input size. The histogram has {@value #HISTOGRAM_BINS}
	 * bins covering frame counts from {@value #HISTOGRAM_MIN_FRAMES} to {@value #HISTOGRAM_MAX_FRAMES}.
	 * </p>
	 * <p>
	 * Use {@link #saveHistogram(File)} to export the collected data for analysis or replication
	 * in performance tests.
	 * </p>
	 *
	 * @param enabled  {@code true} to enable histogram tracking, {@code false} to disable
	 */
	public void setHistogramEnabled(boolean enabled) {
		this.histogramEnabled = enabled;
		if (enabled && histogram == null) {
			histogram = new long[HISTOGRAM_BINS];
		}
	}

	/**
	 * Returns whether histogram tracking is currently enabled.
	 *
	 * @return {@code true} if histogram tracking is enabled
	 */
	public boolean isHistogramEnabled() {
		return histogramEnabled;
	}

	/**
	 * Returns a copy of the current histogram data.
	 * <p>
	 * The returned array has {@value #HISTOGRAM_BINS} elements, where each element contains
	 * the count of process() calls with input sizes falling in that bin's range.
	 * </p>
	 *
	 * @return Copy of histogram counts, or {@code null} if histogram is not enabled
	 */
	public long[] getHistogram() {
		if (histogram == null) {
			return null;
		}
		return histogram.clone();
	}

	/**
	 * Resets all histogram bins to zero.
	 */
	public void resetHistogram() {
		if (histogram != null) {
			for (int i = 0; i < histogram.length; i++) {
				histogram[i] = 0;
			}
		}
	}

	/**
	 * Applies the time-varying low-pass filter to the input audio.
	 * <p>
	 * This method performs the following steps:
	 * </p>
	 * <ol>
	 *   <li>Generates a cutoff frequency envelope based on current ADSR parameters</li>
	 *   <li>Applies the {@link #filterOrder}-order low-pass filter using the time-varying cutoff</li>
	 *   <li>Writes the filtered result to the output buffer</li>
	 * </ol>
	 * <p>
	 * The input and output collections must have compatible shapes. The number of frames
	 * processed is determined by the input size and must not exceed the {@code maxSeconds}
	 * specified in the constructor.
	 * </p>
	 *
	 * @param input   Input audio data as a {@link PackedCollection}
	 * @param output  Output buffer for filtered audio (must be pre-allocated)
	 * @throws IllegalArgumentException if input size exceeds maximum configured frames
	 */
	@Override
	public void process(PackedCollection input, PackedCollection output) {
		int frames = input.getShape().getTotalSize();

		// Update histogram if enabled
		if (histogramEnabled && histogram != null) {
			int binIndex = (frames - HISTOGRAM_MIN_FRAMES) / HISTOGRAM_BIN_WIDTH;
			binIndex = Math.min(HISTOGRAM_BINS - 1, Math.max(0, binIndex));
			histogram[binIndex]++;
		}

		PackedCollection cf = cutoff.range(shape(frames));
		cutoffEnvelope.into(cf.traverseEach()).evaluate();
		multiOrderFilter.into(output.traverse(1))
				.evaluate(input.traverse(0), cf.traverse(0));
	}

	/**
	 * Saves the histogram data to a CSV file.
	 * <p>
	 * The CSV format includes a header row and one row per bin with columns:
	 * </p>
	 * <ul>
	 *   <li><b>bin</b>: Bin index (0 to {@value #HISTOGRAM_BINS} - 1)</li>
	 *   <li><b>min_frames</b>: Minimum frame count for this bin (inclusive)</li>
	 *   <li><b>max_frames</b>: Maximum frame count for this bin (inclusive)</li>
	 *   <li><b>count</b>: Number of process() calls with input sizes in this range</li>
	 * </ul>
	 *
	 * @param file  The file to write the CSV data to
	 * @throws IOException if an I/O error occurs
	 * @throws IllegalStateException if histogram tracking is not enabled
	 */
	public void saveHistogram(File file) throws IOException {
		if (histogram == null) {
			throw new IllegalStateException("Histogram tracking is not enabled");
		}

		try (PrintWriter writer = new PrintWriter(file)) {
			writer.println("bin,min_frames,max_frames,count");
			for (int i = 0; i < HISTOGRAM_BINS; i++) {
				int minFrames = HISTOGRAM_MIN_FRAMES + (i * HISTOGRAM_BIN_WIDTH);
				int maxFrames = minFrames + HISTOGRAM_BIN_WIDTH - 1;
				if (i == HISTOGRAM_BINS - 1) {
					maxFrames = HISTOGRAM_MAX_FRAMES;
				}
				writer.printf("%d,%d,%d,%d%n", i, minFrames, maxFrames, histogram[i]);
			}
		}
	}

	/**
	 * Loads histogram data from a CSV file previously saved with {@link #saveHistogram(File)}.
	 * <p>
	 * This method can be used in performance tests to replicate a realistic distribution of
	 * input sizes. The CSV must match the format produced by {@link #saveHistogram(File)}.
	 * </p>
	 * <p>
	 * Histogram tracking will be automatically enabled if it was not already enabled.
	 * </p>
	 *
	 * @param file  The CSV file to load
	 * @throws IOException if an I/O error occurs or the file format is invalid
	 */
	public void loadHistogram(File file) throws IOException {
		if (histogram == null) {
			histogram = new long[HISTOGRAM_BINS];
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String header = reader.readLine();
			if (header == null || !header.startsWith("bin,")) {
				throw new IOException("Invalid histogram CSV format: missing or invalid header");
			}

			for (int i = 0; i < HISTOGRAM_BINS; i++) {
				String line = reader.readLine();
				if (line == null) {
					throw new IOException("Unexpected end of file at bin " + i);
				}

				String[] parts = line.split(",");
				if (parts.length != 4) {
					throw new IOException("Invalid line format at bin " + i + ": expected 4 columns");
				}

				int binIndex = Integer.parseInt(parts[0].trim());
				long count = Long.parseLong(parts[3].trim());

				if (binIndex != i) {
					throw new IOException("Bin index mismatch at line " + (i + 2) + ": expected " + i + ", got " + binIndex);
				}

				histogram[i] = count;
			}
		}

		histogramEnabled = true;
	}

	/**
	 * Releases all allocated resources including PackedCollection buffers.
	 * <p>
	 * This method should be called when the processor is no longer needed to free
	 * GPU/CPU memory. After calling destroy, this processor instance should not be used.
	 * </p>
	 */
	@Override
	public void destroy() {
		cutoff.destroy();
		duration.destroy();
		attack.destroy();
		decay.destroy();
		sustain.destroy();
		release.destroy();
		cutoffEnvelope = null;
		multiOrderFilter = null;
	}
}
