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
 * A temporal cell that generates triangle wave audio with configurable frequency,
 * amplitude, phase, and envelope. The triangle wave produces a mellow, flute-like
 * tone with odd harmonics that roll off quickly, making it softer than square
 * or sawtooth waves.
 * <p>
 * The triangle wave has no discontinuities, so it naturally produces less aliasing
 * than other geometric waveforms and does not require PolyBLEP anti-aliasing.
 *
 * <h2>Architecture: Initial Values vs Runtime Values</h2>
 * <p>This cell operates in a GPU-accelerated environment where computations are compiled
 * to hardware kernels. This creates two distinct ways to set parameters:</p>
 *
 * <h3>Initial Values (Java fields)</h3>
 * <p>Fields like {@code initialAmplitude}, {@code initialPhase}, etc. are Java-side values
 * that are only used during {@link #setup()}. They are copied to GPU memory once at setup
 * time. After setup completes, changing these fields has NO effect on the running audio.</p>
 *
 * <h3>Runtime Values (GPU memory via Producers)</h3>
 * <p>To change parameters dynamically during audio generation, you must create
 * compiled operations that write to GPU memory. The Producer-based setters return
 * {@code Supplier<Runnable>} that, when included in an OperationList and executed,
 * will update the GPU memory.</p>
 *
 * <h2>Envelope System</h2>
 * <p>The envelope is a {@link Factor} that transforms the note position (0.0 to 1.0+)
 * into an amplitude multiplier. The envelope receives {@code data.getNotePosition()}
 * as input, allowing it to compute amplitude based on where we are in the note's lifecycle.</p>
 *
 * @see CollectionTemporalCellAdapter
 * @see SineWaveCellData
 * @see Factor
 */
public class TriangleWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	/** The envelope Factor that transforms note position into amplitude multiplier. */
	private Factor<PackedCollection> env;

	/** GPU-side data storage for all wave parameters. */
	private final SineWaveCellData data;

	/** Initial note length in frames, copied to GPU memory during setup(). */
	private double initialNoteLength;

	/** Initial wave length (frequency/sampleRate), copied to GPU memory during setup(). */
	private double initialWaveLength;

	/** Initial phase offset, copied to GPU memory during setup(). */
	private double initialPhase;

	/** Initial amplitude, copied to GPU memory during setup(). */
	private double initialAmplitude;

	/**
	 * Creates a new TriangleWaveCell with default polymorphic data storage.
	 */
	public TriangleWaveCell() {
		this(new PolymorphicAudioData());
	}

	/**
	 * Creates a new TriangleWaveCell with the specified data storage.
	 *
	 * @param data the GPU-side data storage for wave parameters
	 */
	public TriangleWaveCell(SineWaveCellData data) {
		this.data = data;
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
	 * <strong>Warning:</strong> This directly modifies GPU memory.
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
	 * Creates a compiled operation that updates the frequency in GPU memory.
	 *
	 * @param hertz a Producer providing the frequency in Hertz
	 * @return a Supplier that, when executed, updates the GPU-side frequency
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
	 * Creates a compiled operation that updates the note length in GPU memory.
	 *
	 * @param noteLength a Producer providing the note length in milliseconds
	 * @return a Supplier that, when executed, updates the GPU-side note length
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
	 * Creates a compiled operation that updates the amplitude in GPU memory.
	 *
	 * @param amp a Producer providing the amplitude value
	 * @return a Supplier that, when executed, updates the GPU-side amplitude
	 */
	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	/**
	 * Creates a compiled operation that initializes all GPU memory with initial values.
	 *
	 * @return a Supplier that, when executed, initializes GPU memory
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("TriangleWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(initialNoteLength)));
		defaults.add(a(data.getWaveLength(), c(initialWaveLength)));
		defaults.add(a(data.getPhase(), c(initialPhase)));
		defaults.add(a(data.getAmplitude(), c(initialAmplitude)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("TriangleWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	/**
	 * Creates a compiled operation that computes and outputs one audio sample.
	 * <p>
	 * The triangle wave is computed as a linear ramp from -1 to +1 during the first
	 * half of the cycle, then from +1 to -1 during the second half. No anti-aliasing
	 * is needed because the waveform is continuous (no discontinuities).
	 * </p>
	 *
	 * @param protein input from upstream cells (typically unused for source cells)
	 * @return a Supplier that, when executed, computes and outputs one sample
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection output = new PackedCollection(1);
		OperationList push = new OperationList("TriangleWaveCell Push");

		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(data.getNotePosition());

		// Compute t = wavePosition + phase
		CollectionProducer t = add(data.getWavePosition(), data.getPhase());
		// Compute frac = t - floor(t)
		CollectionProducer frac = subtract(t, floor(t));

		// Triangle wave: linear ramp from -1 to +1 and back to -1
		// if (frac < 0.5) triangle = frac*4 - 1 else triangle = 3 - frac*4
		CollectionProducer triangle = lessThan(frac, c(0.5),
				subtract(multiply(frac, c(4.0)), c(1.0)),
				subtract(c(3.0), multiply(frac, c(4.0))));

		// Compute: envelope * amplitude * triangle * depth
		CollectionProducer result = multiply(multiply(envelope, data.getAmplitude()),
				multiply(triangle, data.getDepth()));
		push.add(a(p(output), result));

		push.add(super.push(p(output)));
		return push;
	}

	/**
	 * Creates a compiled operation that advances the wave and note positions.
	 *
	 * @return a Supplier that, when executed, advances positions for the next sample
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("TriangleWaveCell Tick");

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
