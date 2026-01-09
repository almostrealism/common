/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.sequence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.DynamicWaveDataProvider;
import org.almostrealism.audio.data.ParameterFunctionSequence;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDataProviderList;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Frequency;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

public class GridSequencer implements StatelessSource, TempoAware, CellFeatures {
	private Frequency bpm;
	private double stepSize;
	private int stepCount;
	private int totalBeats;
	private List<WaveDataProvider> samples;

	private ParameterFunctionSequence sequence;

	public GridSequencer() {
		setBpm(120);
		setStepSize(1.0);
		setStepCount(16);
		setTotalBeats(16);
		setSamples(new ArrayList<>());
	}

	public void initParamSequence() {
		sequence = ParameterFunctionSequence.random(getStepCount());
	}

	public double getBpm() { return bpm.asBPM(); }

	@Override
	public void setBpm(double bpm) { this.bpm = bpm(bpm); }

	public double getStepSize() { return stepSize; }
	public void setStepSize(double stepSize) { this.stepSize = stepSize; }

	public int getStepCount() { return stepCount; }
	public void setStepCount(int stepCount) { this.stepCount = stepCount; }

	public int getTotalBeats() { return totalBeats; }
	public void setTotalBeats(int totalBeats) { this.totalBeats = totalBeats; }

	public List<WaveDataProvider> getSamples() { return samples; }
	public void setSamples(List<WaveDataProvider> samples) { this.samples = samples; }

	public ParameterFunctionSequence getSequence() { return sequence; }

	public void setSequence(ParameterFunctionSequence sequence) { this.sequence = sequence; }

	@JsonIgnore
	public double getDuration() { return bpm.l(getTotalBeats()); }

	@Override
	public Producer<PackedCollection> generate(BufferDetails buffer,
												  Producer<PackedCollection> params,
												  Factor<PackedCollection> frequency) {
		// TODO
		throw new UnsupportedOperationException();
	}

	@Deprecated
	public int getCount() { return (int) (getDuration() * OutputLine.sampleRate); }

	@Deprecated
	public WaveDataProviderList create(Producer<PackedCollection> x, Producer<PackedCollection> y, Producer<PackedCollection> z, List<Frequency> playbackRates) {
		PackedCollection export = new PackedCollection(getCount());
		WaveData destination = new WaveData(export, OutputLine.sampleRate);

		Evaluable<PackedCollection> evX = x.get();
		Evaluable<PackedCollection> evY = y.get();
		Evaluable<PackedCollection> evZ = z.get();

		WaveOutput output = new WaveOutput();
		CellList cells = silence();

		OperationList setup = new OperationList();

		for (WaveDataProvider p : samples) {
			try {
				cells = cells.and(w(0, c(bpm.l(1)), p.get()));
			} catch (Exception e) {
				System.out.println("Skipping invalid sample: " + e.getMessage());
			}
		}

		cells = cells
				.grid(bpm.l(getStepSize() * getStepCount()), getStepCount(),
						(IntFunction<Producer<PackedCollection>>) i -> () -> args -> {
							ParameterSet params = new ParameterSet(evX.evaluate().toDouble(0), evY.evaluate().toDouble(0), evZ.evaluate().toDouble(0));
							PackedCollection s = new PackedCollection(1);
							s.setMem(sequence.apply(i).apply(params));
							return s;
						})
				.sum().map(i -> output.getWriterCell(0));

		setup.add(cells.iter(getCount()));
		setup.add(output.export(0, export));

		// TODO  Should respect playbackRates
		return new WaveDataProviderList(List.of(new DynamicWaveDataProvider("seq://" + UUID.randomUUID(), destination)), setup);
	}
}
