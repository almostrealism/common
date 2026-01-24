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
 * aliasing artifacts at the wave edges.
 * <p>
 * The duty cycle controls the ratio of high to low in each wave period:
 * <ul>
 *   <li>0.5 = standard 50% square wave</li>
 *   <li>0.1 = narrow pulse</li>
 *   <li>0.9 = wide pulse</li>
 * </ul>
 *
 * <h2>Architecture: Initial Values vs Runtime Values</h2>
 * <p>This cell operates in a hardware-accelerated environment where computations are compiled
 * to native kernels. This creates two distinct ways to set parameters:</p>
 *
 * <h3>Initial Values (Java fields)</h3>
 * <p>Fields like {@code initialAmplitude}, {@code initialPhase}, {@code initialDutyCycle}, etc.
 * are Java-side values that are only used during {@link #setup()}. They are copied to
 * {@link PackedCollection} storage once at setup time. After setup completes, changing these
 * fields has NO effect on the running audio.</p>
 *
 * <h3>Runtime Values (PackedCollection storage via Producers)</h3>
 * <p>To change parameters dynamically during audio generation, you must create
 * compiled operations that write to {@link PackedCollection} storage. The Producer-based
 * setters return {@code Supplier<Runnable>} that, when included in an OperationList and
 * executed, will update the hardware-side memory.</p>
 *
 * <h2>Envelope System</h2>
 * <p>The envelope is a {@link Factor} that transforms the note position (0.0 to 1.0+)
 * into an amplitude multiplier. The envelope receives {@code data.getNotePosition()}
 * as input, allowing it to compute amplitude based on where we are in the note's lifecycle.</p>
 *
 * <h2>PolyBLEP Anti-Aliasing</h2>
 * <p>The square wave has two sharp transitions per cycle (rising and falling edges) which
 * can cause aliasing. PolyBLEP is applied at both edges to reduce aliasing artifacts.</p>
 *
 * @see CollectionTemporalCellAdapter
 * @see SquareWaveCellData
 * @see Factor
 */
public class SquareWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	/** The envelope Factor that transforms note position into amplitude multiplier. */
	private Factor<PackedCollection> env;

	/** Hardware-side data storage (PackedCollection) for all wave parameters including duty cycle. */
	private final SquareWaveCellData data;

	/** Initial note length in frames, copied to hardware memory during setup(). */
	private double initialNoteLength;

	/** Initial wave length (frequency/sampleRate), copied to hardware memory during setup(). */
	private double initialWaveLength;

	/** Initial phase offset, copied to hardware memory during setup(). */
	private double initialPhase;

	/** Initial amplitude, copied to hardware memory during setup(). */
	private double initialAmplitude;

	/** Initial duty cycle (0.0 to 1.0), copied to hardware memory during setup(). */
	private double initialDutyCycle;

	/**
	 * Creates a new SquareWaveCell with default polymorphic data storage.
	 */
	public SquareWaveCell() {
		this(new SquareWavePolymorphicData());
	}

	/**
	 * Creates a new SquareWaveCell with the specified data storage.
	 *
	 * @param data the hardware-side data storage for wave parameters
	 */
	public SquareWaveCell(SquareWaveCellData data) {
		this.data = data;
		this.initialDutyCycle = 0.5;
		this.initialAmplitude = 1.0;
	}

	/**
	 * Sets the envelope Factor that controls amplitude over the note's lifecycle.
	 * <p>
	 * The envelope receives the current note position (0.0 to 1.0+) as input
	 * and returns an amplitude multiplier.
	 * </p>
	 *
	 * @param e the envelope Factor, or null for constant amplitude
	 */
	public void setEnvelope(Factor<PackedCollection> e) { this.env = e; }

	/**
	 * Resets the note position to 0, restarting the envelope from the beginning.
	 * <p>
	 * <strong>Warning:</strong> This directly modifies hardware memory.
	 * </p>
	 */
	public void strike() { data.setNotePosition(0); }

	/**
	 * Sets the initial frequency in Hertz. This value is only used during {@link #setup()}.
	 *
	 * @param hertz the frequency in Hertz
	 */
	public void setFreq(double hertz) {
		this.initialWaveLength = hertz / (double) OutputLine.sampleRate;
	}

	/**
	 * Creates a compiled operation that updates the frequency in hardware memory.
	 *
	 * @param hertz a Producer providing the frequency in Hertz
	 * @return a Supplier that, when executed, updates the hardware-side frequency
	 */
	public Supplier<Runnable> setFreq(Producer<PackedCollection> hertz) {
		return a(data.getWaveLength(), divide(hertz, c(OutputLine.sampleRate)));
	}

	/**
	 * Sets the initial note length in milliseconds. This value is only used during {@link #setup()}.
	 *
	 * @param msec the note length in milliseconds
	 */
	public void setNoteLength(int msec) {
		this.initialNoteLength = toFramesMilli(msec);
	}

	/**
	 * Creates a compiled operation that updates the note length in hardware memory.
	 *
	 * @param noteLength a Producer providing the note length in milliseconds
	 * @return a Supplier that, when executed, updates the hardware-side note length
	 */
	public Supplier<Runnable> setNoteLength(Producer<PackedCollection> noteLength) {
		return a(data.getNoteLength(), toFramesMilli(noteLength));
	}

	/**
	 * Sets the initial phase offset. This value is only used during {@link #setup()}.
	 *
	 * @param phase the phase offset (0.0 to 1.0 represents one full cycle)
	 */
	public void setPhase(double phase) { this.initialPhase = phase; }

	/**
	 * Sets the initial amplitude. This value is only used during {@link #setup()}.
	 *
	 * @param amp the amplitude (typically 0.0 to 1.0)
	 */
	public void setAmplitude(double amp) {
		this.initialAmplitude = amp;
	}

	/**
	 * Creates a compiled operation that updates the amplitude in hardware memory.
	 *
	 * @param amp a Producer providing the amplitude value
	 * @return a Supplier that, when executed, updates the hardware-side amplitude
	 */
	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	/**
	 * Sets the initial duty cycle (pulse width). This value is only used during {@link #setup()}.
	 * <p>
	 * The duty cycle controls the ratio of high to low in each wave period.
	 * </p>
	 *
	 * @param dutyCycle ratio of high to low (0.0 to 1.0, default 0.5)
	 */
	public void setDutyCycle(double dutyCycle) {
		this.initialDutyCycle = dutyCycle;
	}

	/**
	 * Creates a compiled operation that updates the duty cycle in hardware memory.
	 * <p>
	 * Use this for pulse-width modulation (PWM) effects where the duty cycle
	 * changes dynamically during audio generation.
	 * </p>
	 *
	 * @param dutyCycle a Producer providing the duty cycle value (0.0 to 1.0)
	 * @return a Supplier that, when executed, updates the hardware-side duty cycle
	 */
	public Supplier<Runnable> setDutyCycle(Producer<PackedCollection> dutyCycle) {
		return a(data.getDutyCycle(), dutyCycle);
	}

	/**
	 * Creates a compiled operation that initializes all hardware memory with initial values.
	 *
	 * @return a Supplier that, when executed, initializes hardware memory
	 */
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

	/**
	 * Creates a compiled operation that computes and outputs one audio sample.
	 * <p>
	 * The square wave output is +1 when phase &lt; duty cycle, -1 otherwise, with
	 * PolyBLEP anti-aliasing applied at both rising and falling edges.
	 * </p>
	 *
	 * @param protein input from upstream cells (typically unused for source cells)
	 * @return a Supplier that, when executed, computes and outputs one sample
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection output = new PackedCollection(1);
		OperationList push = new OperationList("SquareWaveCell Push");

		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(data.getNotePosition());

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

	/**
	 * Creates a compiled operation that advances the wave and note positions.
	 *
	 * @return a Supplier that, when executed, advances positions for the next sample
	 */
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
