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
 * A temporal cell that generates sawtooth wave audio with configurable frequency,
 * amplitude, phase, direction, and envelope. Supports both ascending (ramp up)
 * and descending (ramp down) waveforms. Includes PolyBLEP anti-aliasing.
 * <p>
 * The sawtooth wave produces a linear ramp that creates a bright, buzzy tone
 * rich in harmonics, commonly used in synthesizers for brass and string sounds.
 *
 * @see CollectionTemporalCellAdapter
 * @see SineWaveCellData
 * @see SawtoothWavePush
 * @see SawtoothWaveTick
 */
public class SawtoothWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	private Factor<PackedCollection> env;
	private final SineWaveCellData data;

	private double noteLength;
	private double waveLength;
	private double phase;
	private double amplitude;
	private boolean ascending;

	public SawtoothWaveCell() {
		this(new PolymorphicAudioData());
	}

	public SawtoothWaveCell(SineWaveCellData data) {
		this.data = data;
		this.ascending = true;
		this.amplitude = 1.0;
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

	/**
	 * Sets the wave direction.
	 *
	 * @param ascending true for ascending ramp (default), false for descending
	 */
	public void setAscending(boolean ascending) {
		this.ascending = ascending;
	}

	/**
	 * Returns whether this is an ascending (ramp up) sawtooth.
	 */
	public boolean isAscending() {
		return ascending;
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("SawtoothWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(noteLength)));
		defaults.add(a(data.getWaveLength(), c(waveLength)));
		defaults.add(a(data.getPhase(), c(phase)));
		defaults.add(a(data.getAmplitude(), c(amplitude)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("SawtoothWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection value = new PackedCollection(1);
		OperationList push = new OperationList("SawtoothWaveCell Push");
		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(cp(data.notePosition()));
		push.add(new SawtoothWavePush(data, envelope, value, ascending));
		push.add(super.push(p(value)));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("SawtoothWaveCell Tick");
		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(cp(data.notePosition()));
		tick.add(new SawtoothWaveTick(data, envelope));
		tick.add(super.tick());
		return tick;
	}
}
