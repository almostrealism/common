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

package org.almostrealism.audio.synth;

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * Low Frequency Oscillator for modulation purposes.
 * <p>
 * Generates slow-moving periodic waveforms for modulating synthesis
 * parameters like pitch, filter cutoff, or amplitude. Supports multiple
 * waveform shapes and operates at sub-audio frequencies (typically 0.1-20 Hz).
 * <p>
 * Waveform shapes:
 * <ul>
 *   <li><b>SINE</b>: Smooth, continuous modulation</li>
 *   <li><b>TRIANGLE</b>: Linear ramp up and down</li>
 *   <li><b>SQUARE</b>: Abrupt on/off switching</li>
 *   <li><b>SAW_UP</b>: Rising ramp with instant reset</li>
 *   <li><b>SAW_DOWN</b>: Falling ramp with instant reset</li>
 *   <li><b>SAMPLE_HOLD</b>: Random value held until next cycle</li>
 * </ul>
 *
 * @see ModulationSource
 */
public class LFO implements ModulationSource, Lifecycle, CodeFeatures {

	/**
	 * Available LFO waveform shapes.
	 */
	public enum Shape {
		SINE, TRIANGLE, SQUARE, SAW_UP, SAW_DOWN, SAMPLE_HOLD
	}

	private Shape shape;
	private double frequency;
	private double phase;
	private double currentValue;
	private double sampleHoldValue;

	private final int sampleRate;
	private final PackedCollection output;

	/**
	 * Creates an LFO with default parameters.
	 * Default: sine wave at 1 Hz.
	 */
	public LFO() {
		this(Shape.SINE, 1.0);
	}

	/**
	 * Creates an LFO with the specified shape and frequency.
	 *
	 * @param shape the waveform shape
	 * @param frequency the frequency in Hz
	 */
	public LFO(Shape shape, double frequency) {
		this(shape, frequency, OutputLine.sampleRate);
	}

	/**
	 * Creates an LFO with the specified shape, frequency, and sample rate.
	 *
	 * @param shape the waveform shape
	 * @param frequency the frequency in Hz
	 * @param sampleRate the sample rate in Hz
	 */
	public LFO(Shape shape, double frequency, int sampleRate) {
		this.shape = shape;
		this.frequency = frequency;
		this.sampleRate = sampleRate;
		this.phase = 0;
		this.currentValue = 0;
		this.sampleHoldValue = Math.random() * 2.0 - 1.0;
		this.output = new PackedCollection(1);
	}

	/**
	 * Returns the current waveform shape.
	 */
	public Shape getShape() {
		return shape;
	}

	/**
	 * Sets the waveform shape.
	 */
	public void setShape(Shape shape) {
		this.shape = shape;
	}

	/**
	 * Returns the oscillation frequency in Hz.
	 */
	public double getFrequency() {
		return frequency;
	}

	/**
	 * Sets the oscillation frequency in Hz.
	 *
	 * @param frequency the frequency (typically 0.1 to 20 Hz)
	 */
	public void setFrequency(double frequency) {
		this.frequency = frequency;
	}

	/**
	 * Returns the current phase position (0-1).
	 */
	public double getPhase() {
		return phase;
	}

	/**
	 * Sets the phase position (0-1).
	 */
	public void setPhase(double phase) {
		this.phase = phase % 1.0;
	}

	/**
	 * Resets the LFO phase to the beginning.
	 */
	public void resetPhase() {
		this.phase = 0;
	}

	/**
	 * Syncs the LFO to a specific phase position.
	 * Used for tempo-synced or triggered LFOs.
	 *
	 * @param phase the phase to sync to (0-1)
	 */
	public void sync(double phase) {
		this.phase = phase % 1.0;
	}

	@Override
	public double getValue() {
		return currentValue;
	}

	@Override
	public Producer<PackedCollection> getOutput() {
		return p(output);
	}

	@Override
	public boolean isBipolar() {
		return true;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("LFO Tick");

		tick.add(() -> () -> {
			// Calculate value based on current phase and shape
			currentValue = calculateValue();

			// Advance phase
			double phaseIncrement = frequency / sampleRate;
			double oldPhase = phase;
			phase = (phase + phaseIncrement) % 1.0;

			// Sample & Hold: new random value when phase wraps
			if (shape == Shape.SAMPLE_HOLD && phase < oldPhase) {
				sampleHoldValue = Math.random() * 2.0 - 1.0;
			}

			// Update output
			output.setMem(0, currentValue);
		});

		return tick;
	}

	@Override
	public void reset() {
		phase = 0;
		currentValue = 0;
		sampleHoldValue = Math.random() * 2.0 - 1.0;
	}

	private double calculateValue() {
		switch (shape) {
			case SINE:
				return Math.sin(2.0 * Math.PI * phase);

			case TRIANGLE:
				if (phase < 0.25) {
					return 4.0 * phase;
				} else if (phase < 0.75) {
					return 2.0 - 4.0 * phase;
				} else {
					return -4.0 + 4.0 * phase;
				}

			case SQUARE:
				return phase < 0.5 ? 1.0 : -1.0;

			case SAW_UP:
				return 2.0 * phase - 1.0;

			case SAW_DOWN:
				return 1.0 - 2.0 * phase;

			case SAMPLE_HOLD:
				return sampleHoldValue;

			default:
				return 0;
		}
	}

	// Factory methods for common LFO configurations

	/**
	 * Creates a vibrato LFO (sine wave, moderate speed).
	 */
	public static LFO vibrato(double rate) {
		return new LFO(Shape.SINE, rate);
	}

	/**
	 * Creates a tremolo LFO (triangle wave, moderate speed).
	 */
	public static LFO tremolo(double rate) {
		return new LFO(Shape.TRIANGLE, rate);
	}

	/**
	 * Creates a slow sweep LFO for filter modulation.
	 */
	public static LFO filterSweep(double rate) {
		return new LFO(Shape.TRIANGLE, rate);
	}

	/**
	 * Creates an arpeggiator-style LFO with sample & hold.
	 */
	public static LFO sampleHold(double rate) {
		return new LFO(Shape.SAMPLE_HOLD, rate);
	}
}
