/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A temporal cell that generates sine wave audio with configurable frequency,
 * amplitude, phase, and envelope. Implements both push and tick operations
 * for real-time audio generation within the graph framework.
 *
 * <h2>Architecture: Initial Values vs Runtime Values</h2>
 * <p>This cell operates in a GPU-accelerated environment where computations are compiled
 * to hardware kernels. This creates two distinct ways to set parameters:</p>
 *
 * <h3>Initial Values (Java fields)</h3>
 * <p>Fields like {@code initialAmplitude}, {@code initialPhase}, etc. are Java-side values
 * that are only used during {@link #setup()}. They are copied to GPU memory once at setup
 * time. After setup completes, changing these fields has NO effect on the running audio.</p>
 * <pre>
 * // These only affect the INITIAL state before setup()
 * cell.setAmplitude(0.5);  // Sets initialAmplitude
 * cell.setFreq(440.0);     // Sets initialWaveLength
 * cell.setup().get().run(); // Copies initial values to GPU memory
 * // After this point, the Java fields are ignored
 * </pre>
 *
 * <h3>Runtime Values (GPU memory via Producers)</h3>
 * <p>To change parameters dynamically during audio generation, you must create
 * compiled operations that write to GPU memory. The Producer-based setters return
 * {@code Supplier<Runnable>} that, when included in an OperationList and executed,
 * will update the GPU memory.</p>
 * <pre>
 * // This creates a PROGRAM that updates GPU memory
 * Supplier&lt;Runnable&gt; updateAmp = cell.setAmplitude(someProducer);
 * // Include it in your OperationList to execute it
 * operationList.add(updateAmp);
 * </pre>
 *
 * <h2>Envelope System</h2>
 * <p>The envelope is a {@link Factor} that transforms the note position (0.0 to 1.0+)
 * into an amplitude multiplier. The note position advances based on {@code noteLength}:</p>
 * <ul>
 *   <li>Position 0.0 = start of note</li>
 *   <li>Position 1.0 = end of note duration</li>
 *   <li>Position &gt; 1.0 = note has ended (envelope typically returns 0)</li>
 * </ul>
 * <p>The envelope receives {@code data.getNotePosition()} as input, allowing it to
 * compute amplitude based on where we are in the note's lifecycle.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #setup()} - Initializes GPU memory with initial values</li>
 *   <li>{@link #push(Producer)} - Computes and outputs one audio sample</li>
 *   <li>{@link #tick()} - Advances wave and note positions for the next sample</li>
 * </ol>
 *
 * @see CollectionTemporalCellAdapter
 * @see SineWaveCellData
 * @see Factor
 */
// TODO  Reimplement as a function of org.almostrealism.graph.TimeCell
public class SineWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures, GeometryFeatures {
	private static final double TWO_PI = 2 * Math.PI;

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
	 * Creates a new SineWaveCell with default polymorphic data storage.
	 */
	public SineWaveCell() {
		this(new PolymorphicAudioData());
	}

	/**
	 * Creates a new SineWaveCell with the specified data storage.
	 *
	 * @param data the GPU-side data storage for wave parameters
	 */
	public SineWaveCell(SineWaveCellData data) {
		this.data = data;
	}

	/**
	 * Sets the envelope Factor that controls amplitude over the note's lifecycle.
	 * <p>
	 * The envelope receives the current note position (0.0 to 1.0+) as input
	 * and returns an amplitude multiplier. This allows for attack/decay/sustain/release
	 * curves or any other amplitude shaping based on note progress.
	 * </p>
	 *
	 * @param e the envelope Factor, or null for constant amplitude
	 * @see Factor#getResultant(Producer)
	 */
	public void setEnvelope(Factor<PackedCollection> e) { this.env = e; }

	/**
	 * Resets the note position to 0, restarting the envelope from the beginning.
	 * <p>
	 * <strong>Warning:</strong> This directly modifies GPU memory. It should only
	 * be called when the cell is not actively being processed, or as part of a
	 * synchronized operation.
	 * </p>
	 */
	public void strike() { data.setNotePosition(0); }

	/**
	 * Sets the initial frequency in Hertz. This value is only used during {@link #setup()}.
	 * <p>
	 * To change frequency dynamically after setup, use {@link #setFreq(Producer)} instead.
	 * </p>
	 *
	 * @param hertz the frequency in Hertz (e.g., 440.0 for A4)
	 */
	public void setFreq(double hertz) {
		this.initialWaveLength = hertz / (double) OutputLine.sampleRate;
	}

	/**
	 * Creates a compiled operation that updates the frequency in GPU memory.
	 * <p>
	 * Use this method for dynamic frequency changes during audio generation.
	 * The returned Supplier must be added to an OperationList and executed.
	 * </p>
	 *
	 * @param hertz a Producer providing the frequency in Hertz
	 * @return a Supplier that, when executed, updates the GPU-side frequency
	 */
	public Supplier<Runnable> setFreq(Producer<PackedCollection> hertz) {
		return a(data.getWaveLength(), divide(hertz, c(OutputLine.sampleRate)));
	}

	/**
	 * Sets the initial note length in milliseconds. This value is only used during {@link #setup()}.
	 * <p>
	 * The note length determines how quickly the note position advances from 0.0 to 1.0,
	 * which affects envelope timing.
	 * </p>
	 *
	 * @param msec the note length in milliseconds
	 */
	// TODO  Rename to milli, default should be seconds
	public void setNoteLength(int msec) { this.initialNoteLength = toFramesMilli(msec); }

	/**
	 * Creates a compiled operation that updates the note length in GPU memory.
	 *
	 * @param noteLength a Producer providing the note length in milliseconds
	 * @return a Supplier that, when executed, updates the GPU-side note length
	 */
	// TODO  Rename to milli, default should be seconds
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
	 * <p>
	 * To change amplitude dynamically after setup, use {@link #setAmplitude(Producer)} instead.
	 * </p>
	 *
	 * @param amp the amplitude (typically 0.0 to 1.0)
	 */
	public void setAmplitude(double amp) {
		this.initialAmplitude = amp;
	}

	/**
	 * Creates a compiled operation that updates the amplitude in GPU memory.
	 * <p>
	 * Use this method for dynamic amplitude changes during audio generation.
	 * The returned Supplier must be added to an OperationList and executed.
	 * </p>
	 *
	 * @param amp a Producer providing the amplitude value
	 * @return a Supplier that, when executed, updates the GPU-side amplitude
	 */
	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	/**
	 * Creates a compiled operation that initializes all GPU memory with initial values.
	 * <p>
	 * This method copies the Java-side initial values ({@code initialAmplitude},
	 * {@code initialWaveLength}, etc.) to GPU memory. It must be called before
	 * {@link #push(Producer)} or {@link #tick()} to ensure the cell is properly initialized.
	 * </p>
	 * <p>
	 * After setup() is executed, the Java-side initial values are no longer used.
	 * Any further parameter changes must use the Producer-based setters.
	 * </p>
	 *
	 * @return a Supplier that, when executed, initializes GPU memory
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("SineWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(initialNoteLength)));
		defaults.add(a(data.getWaveLength(), c(initialWaveLength)));
		defaults.add(a(data.getPhase(), c(initialPhase)));
		defaults.add(a(data.getAmplitude(), c(initialAmplitude)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("SineWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	/**
	 * Creates a compiled operation that computes and outputs one audio sample.
	 * <p>
	 * The output is computed as: {@code sin(2*PI * (wavePosition + phase)) * envelope * amplitude * depth}
	 * </p>
	 * <p>
	 * If an envelope is set, it receives the current note position as input, allowing
	 * the envelope to shape amplitude based on where we are in the note's lifecycle.
	 * </p>
	 *
	 * @param protein input from upstream cells (typically unused for source cells)
	 * @return a Supplier that, when executed, computes and outputs one sample
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection output = new PackedCollection(1);
		OperationList push = new OperationList("SineWaveCell Push");

		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
					env.getResultant(data.getNotePosition());

		// Compute: sin(2*PI * (wavePosition + phase)) * envelope * amplitude * depth
		CollectionProducer angle = multiply(c(TWO_PI), add(data.getWavePosition(), data.getPhase()));
		CollectionProducer sinVal = sin(angle);
		CollectionProducer result = multiply(multiply(multiply(envelope, data.getAmplitude()), sinVal), data.getDepth());
		push.add(a(p(output), result));

		push.add(super.push(p(output)));
		return push;
	}

	/**
	 * Creates a compiled operation that advances the wave and note positions.
	 * <p>
	 * This method should be called after each {@link #push(Producer)} to advance
	 * the oscillator state for the next sample:
	 * </p>
	 * <ul>
	 *   <li>{@code wavePosition += waveLength} - advances the wave phase</li>
	 *   <li>{@code notePosition += 1/noteLength} - advances the note position for envelope</li>
	 * </ul>
	 *
	 * @return a Supplier that, when executed, advances positions for the next sample
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("SineWaveCell Tick");

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
