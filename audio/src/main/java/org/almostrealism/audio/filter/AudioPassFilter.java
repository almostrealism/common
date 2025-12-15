/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.AudioFilterData;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalFactor;

import java.util.function.Supplier;

/**
 * High-pass or low-pass audio filter with configurable cutoff frequency and resonance.
 *
 * <p>AudioPassFilter implements {@link TemporalFactor} to provide sample-by-sample
 * filtering with state management. It uses a biquad filter design for efficient
 * real-time processing.</p>
 *
 * <h2>Creating Filters</h2>
 * <pre>{@code
 * // High-pass filter: cutoff 500Hz, resonance 0.1
 * AudioPassFilter highPass = new AudioPassFilter(44100, c(500), scalar(0.1), true);
 *
 * // Low-pass filter: cutoff 2000Hz, resonance 0.2
 * AudioPassFilter lowPass = new AudioPassFilter(44100, c(2000), scalar(0.2), false);
 * }</pre>
 *
 * <h2>Using with CellFeatures</h2>
 * <pre>{@code
 * // Via fluent API
 * cells.f(i -> hp(c(500), scalar(0.1)))   // High-pass at 500Hz
 *      .f(i -> lp(c(5000), scalar(0.1))); // Low-pass at 5000Hz
 * }</pre>
 *
 * <h2>Filter Parameters</h2>
 * <ul>
 *   <li><b>Frequency</b>: Cutoff frequency in Hz (clamped to {@value #MIN_FREQUENCY}-20000 Hz)</li>
 *   <li><b>Resonance</b>: Q factor controlling filter sharpness (0.0-1.0 typical)</li>
 *   <li><b>High</b>: true for high-pass, false for low-pass</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <p>AudioPassFilter implements {@link Lifecycle} for state management:</p>
 * <ul>
 *   <li>{@link #setup()} - Initialize filter state</li>
 *   <li>{@link #tick()} - Advance one sample (update internal state)</li>
 *   <li>{@link #reset()} - Reset filter state to initial values</li>
 * </ul>
 *
 * <p><b>Note:</b> Each AudioPassFilter instance can only be used with one input signal.
 * Calling {@link #getResultant(Producer)} with a different input will throw an exception.</p>
 *
 * @see CellFeatures#hp(Producer, Producer)
 * @see CellFeatures#lp(Producer, Producer)
 * @see DelayNetwork
 */
public class AudioPassFilter implements TemporalFactor<PackedCollection>, Lifecycle {
	public static final double MIN_FREQUENCY = 10.0;

	private final AudioFilterData data;
	private Producer<PackedCollection> frequency;
	private Producer<PackedCollection> resonance;
	private Producer<PackedCollection> input;

	private final boolean high;

	public AudioPassFilter(int sampleRate, Producer<PackedCollection> frequency, Producer<PackedCollection> resonance, boolean high) {
		this(sampleRate, new PolymorphicAudioData(), frequency, resonance, high);
	}

	public AudioPassFilter(int sampleRate, AudioFilterData data, Producer<PackedCollection> frequency, Producer<PackedCollection> resonance, boolean high) {
		this.data = data;
		this.frequency = bound(frequency, MIN_FREQUENCY, 20000);
		this.resonance = resonance;
		this.high = high;
		setSampleRate(sampleRate);
	}

	public Producer<PackedCollection> getFrequency() { return frequency; }
	public void setFrequency(Producer<PackedCollection> frequency) {
		this.frequency = frequency;
	}

	public Producer<PackedCollection> getResonance() {
		return resonance;
	}
	public void setResonance(Producer<PackedCollection> resonance) {
		this.resonance = resonance;
	}

	public int getSampleRate() {
		return (int) data.sampleRate().toDouble(0);
	}
	public void setSampleRate(int sampleRate) {
		data.setSampleRate(sampleRate);
	}

	public boolean isHigh() {
		return high;
	}

	@Override
	public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
		if (input != null && input != value) {
			throw new UnsupportedOperationException("AudioPassFilter cannot be reused");
		}

		input = value;
		return data.getOutput();
	}

	@Override
	public Supplier<Runnable> tick() {
		return new AudioPassFilterComputation(data, frequency, resonance, input, high);
	}

	@Override
	public void reset() {
		this.data.reset();
	}
}
