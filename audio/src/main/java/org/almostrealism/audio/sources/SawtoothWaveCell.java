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
 * A temporal cell that generates sawtooth wave audio with configurable frequency,
 * amplitude, phase, direction, and envelope. Supports both ascending (ramp up)
 * and descending (ramp down) waveforms. Includes PolyBLEP anti-aliasing to reduce
 * aliasing artifacts at the wave discontinuity.
 * <p>
 * The sawtooth wave produces a linear ramp that creates a bright, buzzy tone
 * rich in harmonics, commonly used in synthesizers for brass and string sounds.
 *
 * <h2>Architecture: Initial Values vs Runtime Values</h2>
 * <p>This cell operates in a hardware-accelerated environment where computations are compiled
 * to native kernels. This creates two distinct ways to set parameters:</p>
 *
 * <h3>Initial Values (Java fields)</h3>
 * <p>Fields like {@code initialAmplitude}, {@code initialPhase}, etc. are Java-side values
 * that are only used during {@link #setup()}. They are copied to {@link PackedCollection}
 * storage once at setup time. After setup completes, changing these fields has NO effect
 * on the running audio.</p>
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
 * <p>The sawtooth wave has a sharp discontinuity at the end of each cycle which can
 * cause aliasing. PolyBLEP (Polynomial Band-Limited Step) smooths this discontinuity
 * to reduce aliasing artifacts without significant computational overhead.</p>
 *
 * @see CollectionTemporalCellAdapter
 * @see SineWaveCellData
 * @see Factor
 */
public class SawtoothWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	/** The envelope Factor that transforms note position into amplitude multiplier. */
	private Factor<PackedCollection> env;

	/** Hardware-side data storage (PackedCollection) for all wave parameters. */
	private final SineWaveCellData data;

	/** Initial note length in frames, copied to hardware memory during setup(). */
	private double initialNoteLength;

	/** Initial wave length (frequency/sampleRate), copied to hardware memory during setup(). */
	private double initialWaveLength;

	/** Initial phase offset, copied to hardware memory during setup(). */
	private double initialPhase;

	/** Initial amplitude, copied to hardware memory during setup(). */
	private double initialAmplitude;

	/** Wave direction: true for ascending ramp (-1 to +1), false for descending (+1 to -1). */
	private boolean ascending;

	/**
	 * Creates a new SawtoothWaveCell with default polymorphic data storage.
	 */
	public SawtoothWaveCell() {
		this(new PolymorphicAudioData());
	}

	/**
	 * Creates a new SawtoothWaveCell with the specified data storage.
	 *
	 * @param data the hardware-side data storage for wave parameters
	 */
	public SawtoothWaveCell(SineWaveCellData data) {
		this.data = data;
		this.ascending = true;
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
	 * Sets the wave direction. This is a compile-time parameter that affects
	 * the generated GPU code - it cannot be changed dynamically.
	 *
	 * @param ascending true for ascending ramp (-1 to +1), false for descending (+1 to -1)
	 */
	public void setAscending(boolean ascending) {
		this.ascending = ascending;
	}

	/**
	 * Returns whether this is an ascending (ramp up) sawtooth.
	 *
	 * @return true if ascending, false if descending
	 */
	public boolean isAscending() {
		return ascending;
	}

	/**
	 * Creates a compiled operation that initializes all hardware memory with initial values.
	 *
	 * @return a Supplier that, when executed, initializes hardware memory
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("SawtoothWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(initialNoteLength)));
		defaults.add(a(data.getWaveLength(), c(initialWaveLength)));
		defaults.add(a(data.getPhase(), c(initialPhase)));
		defaults.add(a(data.getAmplitude(), c(initialAmplitude)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("SawtoothWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	/**
	 * Creates a compiled operation that computes and outputs one audio sample.
	 * <p>
	 * The sawtooth output is computed as a linear ramp with PolyBLEP anti-aliasing
	 * applied at the discontinuity, then multiplied by envelope, amplitude, and depth.
	 * </p>
	 *
	 * @param protein input from upstream cells (typically unused for source cells)
	 * @return a Supplier that, when executed, computes and outputs one sample
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection output = new PackedCollection(1);
		OperationList push = new OperationList("SawtoothWaveCell Push");

		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(data.getNotePosition());

		// Compute t = wavePosition + phase
		CollectionProducer t = add(data.getWavePosition(), data.getPhase());
		// Compute frac = t - floor(t)
		CollectionProducer frac = subtract(t, floor(t));

		// Raw sawtooth: linear ramp from -1 to +1 (ascending) or +1 to -1 (descending)
		CollectionProducer rawSaw;
		if (ascending) {
			rawSaw = subtract(multiply(frac, c(2.0)), c(1.0));  // frac*2 - 1
		} else {
			rawSaw = subtract(c(1.0), multiply(frac, c(2.0)));  // 1 - frac*2
		}

		// PolyBLEP anti-aliasing
		Producer<PackedCollection> dt = data.getWaveLength();
		CollectionProducer blep = polyBlep(frac, dt);

		CollectionProducer antiAliased;
		if (ascending) {
			antiAliased = subtract(rawSaw, blep);
		} else {
			antiAliased = add(rawSaw, blep);
		}

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
		OperationList tick = new OperationList("SawtoothWaveCell Tick");

		// Update state: wavePosition += waveLength
		CollectionProducer newWavePos = add(data.getWavePosition(), data.getWaveLength());
		tick.add(a(p(data.wavePosition()), newWavePos));

		// Update state: notePosition += 1/noteLength
		CollectionProducer newNotePos = add(data.getNotePosition(), divide(c(1), data.getNoteLength()));
		tick.add(a(p(data.notePosition()), newNotePos));

		tick.add(super.tick());
		return tick;
	}
}
