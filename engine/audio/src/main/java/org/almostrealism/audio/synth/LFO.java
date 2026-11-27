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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CounterRandom;
import org.almostrealism.hardware.OperationList;

import java.util.Random;
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
 * <p>Every sample is computed on the device. The running state — the phase and the held
 * random value — lives in device collections, and {@link #tick()} is one compiled
 * operation that writes the value of the current phase to the {@link #getOutput() output},
 * advances the phase by {@code frequency / sampleRate} (wrapping at one), and on a wrap in
 * {@link Shape#SAMPLE_HOLD} takes a new held value from a device-side
 * {@link CounterRandom}. The phase increment and the shape are parameters held on the
 * device, written by the setters at control boundaries, so changing them never
 * recompiles the tick; {@link #getValue()} and {@link #getPhase()} read the device
 * state.</p>
 *
 * @see ModulationSource
 */
public class LFO implements ModulationSource, Lifecycle, CodeFeatures {

	/**
	 * Available LFO waveform shapes.
	 */
	public enum Shape {
		/** Smooth sinusoidal modulation. */
		SINE(0),
		/** Linear ramp up then down within each cycle. */
		TRIANGLE(1),
		/** Abrupt alternation between +1 and -1 each half-cycle. */
		SQUARE(2),
		/** Rising ramp from -1 to +1 with instant reset at the cycle boundary. */
		SAW_UP(3),
		/** Falling ramp from +1 to -1 with instant reset at the cycle boundary. */
		SAW_DOWN(4),
		/** Random value held constant for each complete cycle, updated at the cycle boundary. */
		SAMPLE_HOLD(5);

		/** The code the device tick selects this shape's waveform by. */
		private final double code;

		/**
		 * Creates a shape with the code the device tick selects it by.
		 *
		 * @param code the shape's code
		 */
		Shape(double code) {
			this.code = code;
		}
	}

	/** Current waveform shape, mirrored on the device in {@link #shapeIndex}. */
	private Shape shape;

	/** Oscillation frequency in Hz, mirrored on the device as {@link #increment}. */
	private double frequency;

	/** Audio processing sample rate in Hz; used to compute per-tick phase increment. */
	private final int sampleRate;

	/** The phase, in [0, 1), on the device. */
	private final PackedCollection phase;

	/** The held random value of {@link Shape#SAMPLE_HOLD}, on the device. */
	private final PackedCollection hold;

	/** The phase as it was at the start of the tick, read while the phase is rewritten. */
	private final PackedCollection previousPhase;

	/** The held value as it was at the start of the tick, read while it is rewritten. */
	private final PackedCollection previousHold;

	/** The per-sample phase increment, {@code frequency / sampleRate}, written at control boundaries. */
	private final PackedCollection increment;

	/** The {@link Shape#ordinal() ordinal} of the shape, written at control boundaries. */
	private final PackedCollection shapeIndex;

	/** Single-element PackedCollection holding the current LFO output for GPU-accessible consumers. */
	private final PackedCollection output;

	/** The device-side uniform random source of the held values. */
	private final CounterRandom noise;

	/** The compiled draw of a new held value that {@link #reset()} runs, or null before the first reset. */
	private Runnable redraw;

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
		this.sampleRate = sampleRate;
		this.phase = new PackedCollection(1);
		this.hold = new PackedCollection(1);
		this.previousPhase = new PackedCollection(1);
		this.previousHold = new PackedCollection(1);
		this.increment = new PackedCollection(1);
		this.shapeIndex = new PackedCollection(1);
		this.output = new PackedCollection(1);
		this.noise = new CounterRandom(shape(1), false, (Random) null);
		setShape(shape);
		setFrequency(frequency);
		reset();
	}

	/**
	 * Returns the current waveform shape.
	 */
	public Shape getShape() {
		return shape;
	}

	/**
	 * Sets the waveform shape, which takes effect at the next tick.
	 *
	 * @param shape the new waveform shape
	 */
	public void setShape(Shape shape) {
		this.shape = shape;
		shapeIndex.fill(shape.code);
	}

	/**
	 * Returns the oscillation frequency in Hz.
	 */
	public double getFrequency() {
		return frequency;
	}

	/**
	 * Sets the oscillation frequency in Hz, which takes effect at the next tick.
	 *
	 * @param frequency the frequency (typically 0.1 to 20 Hz)
	 */
	public void setFrequency(double frequency) {
		this.frequency = frequency;
		increment.fill(frequency / sampleRate);
	}

	/**
	 * Returns the current phase position (0-1), read from the device state.
	 */
	public double getPhase() {
		return phase.toDouble(0);
	}

	/**
	 * Sets the phase position (0-1).
	 *
	 * @param phase the phase value; values outside [0, 1) are wrapped automatically
	 */
	public void setPhase(double phase) {
		this.phase.fill(phase % 1.0);
	}

	/**
	 * Resets the LFO phase to the beginning.
	 */
	public void resetPhase() {
		phase.clear();
	}

	/**
	 * Syncs the LFO to a specific phase position.
	 * Used for tempo-synced or triggered LFOs.
	 *
	 * @param phase the phase to sync to (0-1)
	 */
	public void sync(double phase) {
		setPhase(phase);
	}

	/** Returns the value of the last tick, read from the device output. */
	@Override
	public double getValue() {
		return output.toDouble(0);
	}

	@Override
	public Producer<PackedCollection> getOutput() {
		return p(output);
	}

	@Override
	public boolean isBipolar() {
		return true;
	}

	/**
	 * Returns the compiled operation that advances this LFO by one sample on the device:
	 * the phase and held value are copied aside, the value of the current phase becomes
	 * the output, the phase advances and wraps, and a wrap in {@link Shape#SAMPLE_HOLD}
	 * takes a new held value from the random source, which then moves on to its next value.
	 *
	 * @return the tick operation
	 */
	@Override
	public Supplier<Runnable> tick() {
		CollectionProducer current = cp(previousPhase);
		CollectionProducer held = cp(previousHold);
		CollectionProducer selectedShape = cp(shapeIndex);

		CollectionProducer advanced = current.add(cp(increment));
		CollectionProducer next = advanced.subtract(floor(advanced));
		CollectionProducer resample = lessThan(next, current, c(1.0), c(0.0))
				.multiply(equals(selectedShape, c(Shape.SAMPLE_HOLD.code)));
		CollectionProducer drawn = drawn();

		OperationList tick = new OperationList("LFO Tick");
		tick.add(a(cp(previousPhase), cp(phase)));
		tick.add(a(cp(previousHold), cp(hold)));
		tick.add(a(cp(output), waveform(current, held, selectedShape)));
		tick.add(a(cp(phase), next));
		tick.add(a(cp(hold), held.add(resample.multiply(drawn.subtract(held)))));
		tick.add(noise.advance());
		return tick;
	}

	/**
	 * Returns the waveform value at {@code phase} for the shape whose ordinal is
	 * {@code shapeIndex}: every shape is computed and the one selected is kept, so the
	 * shape can change without recompiling.
	 *
	 * @param phase      the phase, in [0, 1)
	 * @param held       the held value of {@link Shape#SAMPLE_HOLD}
	 * @param shapeIndex the ordinal of the shape
	 * @return the value, in [-1, 1]
	 */
	private CollectionProducer waveform(CollectionProducer phase, CollectionProducer held,
										CollectionProducer shapeIndex) {
		CollectionProducer sine = sin(phase.multiply(2.0 * Math.PI));
		CollectionProducer triangle = lessThan(phase, c(0.25), phase.multiply(4.0),
				lessThan(phase, c(0.75), c(2.0).subtract(phase.multiply(4.0)),
						phase.multiply(4.0).subtract(4.0)));
		CollectionProducer square = lessThan(phase, c(0.5), c(1.0), c(-1.0));
		CollectionProducer sawUp = phase.multiply(2.0).subtract(1.0);
		CollectionProducer sawDown = c(1.0).subtract(phase.multiply(2.0));

		return selected(shapeIndex, Shape.SINE, sine)
				.add(selected(shapeIndex, Shape.TRIANGLE, triangle))
				.add(selected(shapeIndex, Shape.SQUARE, square))
				.add(selected(shapeIndex, Shape.SAW_UP, sawUp))
				.add(selected(shapeIndex, Shape.SAW_DOWN, sawDown))
				.add(selected(shapeIndex, Shape.SAMPLE_HOLD, held));
	}

	/**
	 * Returns {@code wave} where {@code shapeIndex} is the ordinal of {@code shape}, and
	 * zero elsewhere.
	 *
	 * @param shapeIndex the ordinal of the active shape
	 * @param shape      the shape {@code wave} belongs to
	 * @param wave       that shape's value
	 * @return the value when {@code shape} is active, otherwise zero
	 */
	private CollectionProducer selected(CollectionProducer shapeIndex, Shape shape,
										CollectionProducer wave) {
		return equals(shapeIndex, c(shape.code)).multiply(wave);
	}

	/**
	 * Returns the next value of the random source scaled to {@code [-1, 1)}, the range of a
	 * held value.
	 *
	 * @return the drawn value
	 */
	private CollectionProducer drawn() {
		return c(noise).multiply(2.0).subtract(1.0);
	}

	/**
	 * Returns the phase to zero, clears the output, and draws a new held value from the
	 * device random source. This is a control-boundary operation, not part of the
	 * per-sample tick; the draw is compiled on the first reset and reused.
	 */
	@Override
	public void reset() {
		phase.clear();
		output.clear();

		if (redraw == null) {
			OperationList draw = new OperationList("LFO Hold Draw");
			draw.add(a(cp(hold), drawn()));
			draw.add(noise.advance());
			redraw = draw.get();
		}

		redraw.run();
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
