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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.hardware.OperationList;
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
public class AudioPassFilter implements TemporalFactor<PackedCollection>, Lifecycle, GeometryFeatures {
	public static final double MIN_FREQUENCY = 10.0;
	public static final double MAX_INPUT = 0.99;

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
		OperationList ops = new OperationList("AudioPassFilter");

		Producer<PackedCollection> sampleRate = data.getSampleRate();

		// Compute c coefficient using tan
		// High-pass: c = tan(PI * frequency / sampleRate)
		// Low-pass: c = 1 / tan(PI * frequency / sampleRate)
		CollectionProducer angle = multiply(c(Math.PI), divide(frequency, sampleRate));
		CollectionProducer tanVal = tan(angle);
		CollectionProducer cVal = high ? tanVal : divide(c(1), tanVal);
		ops.add(a(p(data.c()), cVal));

		// After c is computed, read it back for coefficient calculations
		Producer<PackedCollection> cProd = data.getC();
		CollectionProducer cSquared = multiply(cProd, cProd);

		// a1 = 1 / (1 + resonance*c + c*c)
		CollectionProducer a1Val = divide(c(1),
				add(c(1), add(multiply(resonance, cProd), cSquared)));
		ops.add(a(p(data.a1()), a1Val));

		// After a1 is computed, read it back
		Producer<PackedCollection> a1Prod = data.getA1();

		if (high) {
			// High-pass coefficients:
			// a2 = -2 * a1
			// a3 = a1
			// b1 = 2 * (c*c - 1) * a1
			// b2 = (1 - resonance*c + c*c) * a1
			ops.add(a(p(data.a2()), multiply(c(-2), a1Prod)));
			ops.add(a(p(data.a3()), a1Prod));
			ops.add(a(p(data.b1()), multiply(multiply(c(2), subtract(cSquared, c(1))), a1Prod)));
			ops.add(a(p(data.b2()), multiply(add(subtract(c(1), multiply(resonance, cProd)), cSquared), a1Prod)));
		} else {
			// Low-pass coefficients:
			// a2 = 2 * a1
			// a3 = a1
			// b1 = 2 * (1 - c*c) * a1
			// b2 = (1 - resonance*c + c*c) * a1
			ops.add(a(p(data.a2()), multiply(c(2), a1Prod)));
			ops.add(a(p(data.a3()), a1Prod));
			ops.add(a(p(data.b1()), multiply(multiply(c(2), subtract(c(1), cSquared)), a1Prod)));
			ops.add(a(p(data.b2()), multiply(add(subtract(c(1), multiply(resonance, cProd)), cSquared), a1Prod)));
		}

		// Clamp input to [-MAX_INPUT, MAX_INPUT]
		CollectionProducer clampedInput = max(min(input, c(MAX_INPUT)), c(-MAX_INPUT));

		// Get coefficient and history producers
		Producer<PackedCollection> a1 = data.getA1();
		Producer<PackedCollection> a2 = data.getA2();
		Producer<PackedCollection> a3 = data.getA3();
		Producer<PackedCollection> b1 = data.getB1();
		Producer<PackedCollection> b2 = data.getB2();
		Producer<PackedCollection> inHist0 = data.getInputHistory0();
		Producer<PackedCollection> inHist1 = data.getInputHistory1();
		Producer<PackedCollection> outHist0 = data.getOutputHistory0();
		Producer<PackedCollection> outHist1 = data.getOutputHistory1();

		// Apply IIR filter: y = a1*x + a2*x[n-1] + a3*x[n-2] - b1*y[n-1] - b2*y[n-2]
		CollectionProducer outputVal = subtract(
				subtract(
						add(add(multiply(a1, clampedInput), multiply(a2, inHist0)), multiply(a3, inHist1)),
						multiply(b1, outHist0)),
				multiply(b2, outHist1));
		ops.add(a(p(data.output()), outputVal));

		// Update history buffers (order matters - read before overwrite)
		ops.add(a(p(data.inputHistory1()), inHist0));
		ops.add(a(p(data.inputHistory0()), clampedInput));
		ops.add(a(p(data.outputHistory2()), outHist1));
		ops.add(a(p(data.outputHistory1()), outHist0));
		ops.add(a(p(data.outputHistory0()), data.getOutput()));

		return ops;
	}

	@Override
	public void reset() {
		this.data.reset();
	}
}
