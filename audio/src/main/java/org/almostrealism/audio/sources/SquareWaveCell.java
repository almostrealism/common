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

package org.almostrealism.audio.sources;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A temporal cell that generates square wave audio with configurable frequency,
 * amplitude, phase, duty cycle, and envelope. Supports pulse-width modulation (PWM)
 * through dynamic duty cycle control. Includes PolyBLEP anti-aliasing to reduce
 * aliasing artifacts.
 * <p>
 * The duty cycle controls the ratio of high to low in each wave period:
 * <ul>
 *   <li>0.5 = standard 50% square wave</li>
 *   <li>0.1 = narrow pulse</li>
 *   <li>0.9 = wide pulse</li>
 * </ul>
 *
 * @see CollectionTemporalCellAdapter
 * @see SquareWaveCellData
 */
public class SquareWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	private Factor<PackedCollection> env;
	private final SquareWaveCellData data;

	private double initialNoteLength;
	private double initialWaveLength;
	private double initialPhase;
	private double initialAmplitude;
	private double initialDutyCycle;

	public SquareWaveCell() {
		this(new SquareWavePolymorphicData());
	}

	public SquareWaveCell(SquareWaveCellData data) {
		this.data = data;
		this.initialDutyCycle = 0.5;
		this.initialAmplitude = 1.0;
	}

	public void setEnvelope(Factor<PackedCollection> e) { this.env = e; }

	public void strike() { data.setNotePosition(0); }

	public void setFreq(double hertz) {
		this.initialWaveLength = hertz / (double) OutputLine.sampleRate;
	}

	public Supplier<Runnable> setFreq(Producer<PackedCollection> hertz) {
		return a(data.getWaveLength(), divide(hertz, c(OutputLine.sampleRate)));
	}

	public void setNoteLength(int msec) {
		this.initialNoteLength = toFramesMilli(msec);
	}

	public Supplier<Runnable> setNoteLength(Producer<PackedCollection> noteLength) {
		return a(data.getNoteLength(), toFramesMilli(noteLength));
	}

	public void setPhase(double phase) { this.initialPhase = phase; }

	public void setAmplitude(double amp) {
		this.initialAmplitude = amp;
	}

	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	/**
	 * Sets the duty cycle (pulse width).
	 *
	 * @param dutyCycle Ratio of high to low (0.0 to 1.0, default 0.5)
	 */
	public void setDutyCycle(double dutyCycle) {
		this.initialDutyCycle = dutyCycle;
	}

	/**
	 * Sets the duty cycle dynamically for PWM effects.
	 *
	 * @param dutyCycle Producer providing duty cycle value
	 * @return Supplier that updates the duty cycle
	 */
	public Supplier<Runnable> setDutyCycle(Producer<PackedCollection> dutyCycle) {
		return a(data.getDutyCycle(), dutyCycle);
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("SquareWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(initialNoteLength)));
		defaults.add(a(data.getWaveLength(), c(initialWaveLength)));
		defaults.add(a(data.getPhase(), c(initialPhase)));
		defaults.add(a(data.getAmplitude(), c(initialAmplitude)));
		defaults.add(a(data.getDutyCycle(), c(initialDutyCycle)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("SquareWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection output = new PackedCollection(1);
		OperationList push = new OperationList("SquareWaveCell Push");

		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(scalar(1.0));

		// Compute t = wavePosition + phase
		CollectionProducer t = add(data.getWavePosition(), data.getPhase());
		// Compute frac = t - floor(t)
		CollectionProducer frac = subtract(t, floor(t));

		Producer<PackedCollection> dutyCycleProd = data.getDutyCycle();

		// Raw square wave: +1 if frac < dutyCycle, else -1
		CollectionProducer rawSquare = lessThan(frac, dutyCycleProd, c(1.0), c(-1.0));

		// PolyBLEP anti-aliasing for rising edge (at frac=0)
		Producer<PackedCollection> dt = data.getWaveLength();
		CollectionProducer blepRising = polyBlep(frac, dt);

		// PolyBLEP anti-aliasing for falling edge (at frac=dutyCycle)
		// Need to adjust frac to be relative to the falling edge position
		CollectionProducer fracMinusDuty = subtract(frac, dutyCycleProd);
		// If frac < dutyCycle, add 1 to wrap around; otherwise add 0
		CollectionProducer blepFallingArg = add(fracMinusDuty, lessThan(frac, dutyCycleProd, c(1.0), c(0.0)));
		CollectionProducer blepFalling = polyBlep(blepFallingArg, dt);

		// Combine: rawSquare + blepRising - blepFalling
		CollectionProducer antiAliased = subtract(add(rawSquare, blepRising), blepFalling);

		// Compute: envelope * amplitude * antiAliased * depth
		CollectionProducer result = multiply(multiply(envelope, data.getAmplitude()),
				multiply(antiAliased, data.getDepth()));
		push.add(a(p(output), result));

		push.add(super.push(p(output)));
		return push;
	}

	/**
	 * PolyBLEP (Polynomial Band-Limited Step) anti-aliasing function.
	 * Smooths discontinuities in geometric waveforms to reduce aliasing.
	 *
	 * @param t   Phase position (0-1)
	 * @param dt  Phase increment per sample (frequency/sampleRate)
	 * @return Correction value to apply to the raw waveform
	 */
	private CollectionProducer polyBlep(CollectionProducer t, Producer<PackedCollection> dt) {
		// When t < dt: -(t/dt - 1)^2
		CollectionProducer belowDt = lessThan(t, dt,
				multiply(pow(subtract(divide(t, dt), c(1.0)), c(2.0)), c(-1.0)),
				c(0.0));

		// When t > 1-dt: ((t-1)/dt + 1)^2
		CollectionProducer oneMinusDt = subtract(c(1.0), dt);
		CollectionProducer aboveOneMinusDt = greaterThan(t, oneMinusDt,
				pow(add(divide(subtract(t, c(1.0)), dt), c(1.0)), c(2.0)),
				c(0.0));

		return add(belowDt, aboveOneMinusDt);
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("SquareWaveCell Tick");

		// Update state: wavePosition += waveLength
		CollectionProducer newWavePos = add(data.getWavePosition(), data.getWaveLength());
		tick.add(a(p(data.wavePosition()), newWavePos));

		// Update state: notePosition += 1/noteLength
		CollectionProducer newNotePos = add(data.getNotePosition(), divide(c(1), data.getNoteLength()));
		tick.add(a(p(data.notePosition()), newNotePos));

		tick.add(super.tick());
		return tick;
	}

	/**
	 * Internal data class that implements SquareWaveCellData using PolymorphicAudioData storage.
	 */
	private static class SquareWavePolymorphicData extends PolymorphicAudioData implements SquareWaveCellData {
		public SquareWavePolymorphicData() {
			super();
		}
	}
}
