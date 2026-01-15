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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A temporal cell that generates audio by reading from a wavetable containing
 * a single cycle of an arbitrary waveform. Supports configurable frequency,
 * amplitude, phase, and envelope with linear interpolation for smooth playback.
 * <p>
 * Wavetable synthesis allows for:
 * <ul>
 *   <li>Complex waveforms that can't be generated mathematically</li>
 *   <li>Sampled sounds from real instruments</li>
 *   <li>Morphing between multiple waveforms</li>
 *   <li>Efficient playback of complex timbres</li>
 * </ul>
 * <p>
 * The wavetable should contain one complete cycle of the desired waveform,
 * with values normalized to the range [-1, 1].
 *
 * @see CollectionTemporalCellAdapter
 * @see SineWaveCellData
 */
public class WavetableCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	public static final int DEFAULT_TABLE_SIZE = 2048;

	private Factor<PackedCollection> env;
	private final SineWaveCellData data;
	private final PackedCollection wavetable;
	private final int tableSize;

	private double noteLength;
	private double waveLength;
	private double phase;
	private double amplitude;

	/**
	 * Creates a WavetableCell with a default sine wave table.
	 */
	public WavetableCell() {
		this(DEFAULT_TABLE_SIZE);
	}

	/**
	 * Creates a WavetableCell with a sine wave table of the specified size.
	 */
	public WavetableCell(int tableSize) {
		this(new PolymorphicAudioData(), createSineTable(tableSize));
	}

	/**
	 * Creates a WavetableCell with the specified wavetable.
	 *
	 * @param wavetable The wavetable containing one cycle of the waveform
	 */
	public WavetableCell(PackedCollection wavetable) {
		this(new PolymorphicAudioData(), wavetable);
	}

	/**
	 * Creates a WavetableCell with the specified data and wavetable.
	 */
	public WavetableCell(SineWaveCellData data, PackedCollection wavetable) {
		this.data = data;
		this.wavetable = wavetable;
		this.tableSize = wavetable.getMemLength();
		this.amplitude = 1.0;
	}

	/**
	 * Creates a sine wave table for use as a default wavetable.
	 */
	public static PackedCollection createSineTable(int size) {
		PackedCollection table = new PackedCollection(size);
		for (int i = 0; i < size; i++) {
			double phase = (double) i / size;
			table.setMem(i, Math.sin(2.0 * Math.PI * phase));
		}
		return table;
	}

	/**
	 * Creates a square wave table.
	 */
	public static PackedCollection createSquareTable(int size) {
		return createSquareTable(size, 0.5);
	}

	/**
	 * Creates a square wave table with the specified duty cycle.
	 */
	public static PackedCollection createSquareTable(int size, double dutyCycle) {
		PackedCollection table = new PackedCollection(size);
		int threshold = (int) (size * dutyCycle);
		for (int i = 0; i < size; i++) {
			table.setMem(i, i < threshold ? 1.0 : -1.0);
		}
		return table;
	}

	/**
	 * Creates a sawtooth wave table.
	 */
	public static PackedCollection createSawtoothTable(int size) {
		PackedCollection table = new PackedCollection(size);
		for (int i = 0; i < size; i++) {
			double phase = (double) i / size;
			table.setMem(i, 2.0 * phase - 1.0);
		}
		return table;
	}

	/**
	 * Creates a triangle wave table.
	 */
	public static PackedCollection createTriangleTable(int size) {
		PackedCollection table = new PackedCollection(size);
		for (int i = 0; i < size; i++) {
			double phase = (double) i / size;
			if (phase < 0.5) {
				table.setMem(i, 4.0 * phase - 1.0);
			} else {
				table.setMem(i, 3.0 - 4.0 * phase);
			}
		}
		return table;
	}

	/**
	 * Returns the wavetable.
	 */
	public PackedCollection getWavetable() {
		return wavetable;
	}

	/**
	 * Returns the wavetable size.
	 */
	public int getTableSize() {
		return tableSize;
	}

	public void setEnvelope(Factor<PackedCollection> e) { this.env = e; }

	public void strike() { data.setNotePosition(0); }

	public void setFreq(double hertz) {
		this.waveLength = hertz / (double) OutputLine.sampleRate;
	}

	public Supplier<Runnable> setFreq(Producer<PackedCollection> hertz) {
		return a(data.getWaveLength(), divide(hertz, c(OutputLine.sampleRate)));
	}

	public void setNoteLength(int msec) {
		this.noteLength = toFramesMilli(msec);
	}

	public Supplier<Runnable> setNoteLength(Producer<PackedCollection> noteLength) {
		return a(data.getNoteLength(), toFramesMilli(noteLength));
	}

	public void setPhase(double phase) { this.phase = phase; }

	public void setAmplitude(double amp) { amplitude = amp; }

	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("WavetableCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(noteLength)));
		defaults.add(a(data.getWaveLength(), c(waveLength)));
		defaults.add(a(data.getPhase(), c(phase)));
		defaults.add(a(data.getAmplitude(), c(amplitude)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("WavetableCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection value = new PackedCollection(1);
		OperationList push = new OperationList("WavetableCell Push");
		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(cp(data.notePosition()));
		push.add(new WavetablePush(data, envelope, wavetable, tableSize, value));
		push.add(super.push(p(value)));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("WavetableCell Tick");
		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(cp(data.notePosition()));
		tick.add(new WavetableTick(data, envelope));
		tick.add(super.tick());
		return tick;
	}
}
