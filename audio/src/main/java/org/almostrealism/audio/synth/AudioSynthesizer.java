/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio.synth;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyNumbering;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.RelativeFrequencySet;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.Temporal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class AudioSynthesizer implements Temporal, StatelessSource, SamplingFeatures {
	private final RelativeFrequencySet tones;

	private final List<SineWaveCell> cells;
	private final SummationCell output;
	private KeyboardTuning tuning;
	private AudioSynthesisModel model;

	public AudioSynthesizer() {
		this(null);
	}

	public AudioSynthesizer(int subCount, int superCount) {
		this(null, subCount, superCount, 0);
	}

	public AudioSynthesizer(AudioSynthesisModel model) {
		this(model, 2, 5, 0);
	}

	public AudioSynthesizer(AudioSynthesisModel model,
							int subCount, int superCount, int inharmonicCount) {
		this(model, new OvertoneSeries(subCount, superCount, inharmonicCount));
	}

	public AudioSynthesizer(AudioSynthesisModel model, RelativeFrequencySet voices) {
		setModel(model);
		this.tones = voices;
		this.tuning = new DefaultKeyboardTuning();
		this.output = new SummationCell();
		this.cells = new ArrayList<>();

		for (int i = 0; i < voices.count(); i++) {
			cells.add(new SineWaveCell());
			cells.get(i).setReceptor(output);
		}
	}

	public Cell<PackedCollection> getOutput() {
		return output;
	}

	public void setTuning(KeyboardTuning t) { this.tuning = t; }

	public AudioSynthesisModel getModel() { return model; }
	public void setModel(AudioSynthesisModel model) { this.model = model; }

	public void setNoteMidi(int key) {
		setFrequency(tuning.getTone(key, KeyNumbering.MIDI));
	}

	public void setFrequency(Frequency f) {
		Iterator<SineWaveCell> itr = cells.iterator();
		for (Frequency r : tones.getFrequencies(f)) itr.next().setFreq(r.asHertz());
	}

	public void strike() {
		for (SineWaveCell s : cells) s.strike();
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("AudioSynthesizer Tick");
		cells.stream().map(cell -> cell.push(null)).forEach(tick::add);
		return tick;
	}

	@Override
	public Producer<PackedCollection> generate(BufferDetails buffer,
												  Producer<PackedCollection> params,
												  Factor<PackedCollection> frequency) {
		double amp = 0.75;
		return sampling(buffer.getSampleRate(), () -> {
			double scale = amp / tones.count();

			List<Producer<?>> series = new ArrayList<>();

			for (Frequency f : tones) {
				CollectionProducer t =
						integers(0, buffer.getFrames()).divide(buffer.getSampleRate());
				Producer<PackedCollection> ft = frequency.getResultant(t);
				CollectionProducer signal =
						sin(t.multiply(2 * Math.PI).multiply(f.asHertz()).multiply(ft));

				if (model != null) {
					Producer<PackedCollection> levels = model.getLevels(f.asHertz(), t);
					signal = signal.multiply(levels);
				}

				series.add(signal);
			}

			return model == null ? add(series).multiply(scale) : add(series);
		});
	}
}
