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
 * for real-time audio generation within the graph framework. Supports dynamic
 * parameter control through Producer-based setters.
 *
 * @see CollectionTemporalCellAdapter
 * @see SineWaveCellData
 */
// TODO  Reimplement as a function of org.almostrealism.graph.TimeCell
public class SineWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures, GeometryFeatures {
	private static final double TWO_PI = 2 * Math.PI;
	private Factor<PackedCollection> env;
	private final SineWaveCellData data;

	private double noteLength;
	private double waveLength;
	private double phase;
	private double amplitude;

	public SineWaveCell() {
		this(new PolymorphicAudioData());
	}

	public SineWaveCell(SineWaveCellData data) {
		this.data = data;
	}

	public void setEnvelope(Factor<PackedCollection> e) { this.env = e; }

	public void strike() { data.setNotePosition(0); }
	
	public void setFreq(double hertz) {
		this.waveLength = hertz / (double) OutputLine.sampleRate;
		data.setWaveLength(this.waveLength);
	}

	public Supplier<Runnable> setFreq(Producer<PackedCollection> hertz) {
		return a(data.getWaveLength(), divide(hertz, c(OutputLine.sampleRate)));
	}

	// TODO  Rename to milli, default should be seconds
	public void setNoteLength(int msec) { this.noteLength = toFramesMilli(msec); }

	// TODO  Rename to milli, default should be seconds
	public Supplier<Runnable> setNoteLength(Producer<PackedCollection> noteLength) {
		return a(data.getNoteLength(), toFramesMilli(noteLength));
	}
	
	public void setPhase(double phase) { this.phase = phase; }
	
	public void setAmplitude(double amp) {
		amplitude = amp;
		data.setAmplitude(amp);
	}

	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("SineWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(noteLength)));
		defaults.add(a(data.getWaveLength(), c(waveLength)));
		defaults.add(a(data.getPhase(), c(phase)));
		defaults.add(a(data.getAmplitude(), c(amplitude)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("SineWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection output = new PackedCollection(1);
		OperationList push = new OperationList("SineWaveCell Push");

		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
					env.getResultant(scalar(1.0));

		// Compute: sin(2*PI * (wavePosition + phase)) * envelope * amplitude * depth
		CollectionProducer angle = multiply(c(TWO_PI), add(data.getWavePosition(), data.getPhase()));
		CollectionProducer sinVal = sin(angle);
		CollectionProducer result = multiply(multiply(multiply(envelope, data.getAmplitude()), sinVal), data.getDepth());
		push.add(a(p(output), result));

		push.add(super.push(p(output)));
		return push;
	}

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
