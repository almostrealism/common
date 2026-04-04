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

package org.almostrealism.music.sequence;
import org.almostrealism.audio.sequence.TempoAware;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.DynamicWaveDataProvider;
import org.almostrealism.music.data.ParameterFunctionSequence;
import org.almostrealism.music.data.ParameterSet;
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

/**
 * A grid-based step sequencer that schedules audio samples at regular intervals.
 *
 * <p>{@code GridSequencer} plays back a list of audio samples in a repeating grid
 * pattern, with each step controlled by a {@link ParameterFunctionSequence} that
 * determines the selection value for each step position.</p>
 *
 * @deprecated This class uses legacy {@link WaveOutput} and CellList APIs. Use
 *             pattern-based rendering instead.
 */
@Deprecated
public class GridSequencer implements StatelessSource, TempoAware, CellFeatures {
	/** The tempo in BPM. */
	private Frequency bpm;
	/** The duration of each step in beats. */
	private double stepSize;
	/** The number of steps in one grid cycle. */
	private int stepCount;
	/** The total number of beats in the sequence. */
	private int totalBeats;
	/** The list of audio sample providers. */
	private List<WaveDataProvider> samples;

	/** The parameterized sequence controlling step selection values. */
	private ParameterFunctionSequence sequence;

	/** Creates a {@code GridSequencer} with default tempo (120 BPM), 16 steps, and no samples. */
	public GridSequencer() {
		setBpm(120);
		setStepSize(1.0);
		setStepCount(16);
		setTotalBeats(16);
		setSamples(new ArrayList<>());
	}

	/** Initializes the parameter sequence with random functions for each step. */
	public void initParamSequence() {
		sequence = ParameterFunctionSequence.random(getStepCount());
	}

	/** Returns the tempo in BPM. */
	public double getBpm() { return bpm.asBPM(); }

	/** Sets the tempo in BPM. */
	@Override
	public void setBpm(double bpm) { this.bpm = bpm(bpm); }

	/** Returns the step size in beats. */
	public double getStepSize() { return stepSize; }
	/** Sets the step size in beats. */
	public void setStepSize(double stepSize) { this.stepSize = stepSize; }

	/** Returns the number of steps in one grid cycle. */
	public int getStepCount() { return stepCount; }
	/** Sets the number of steps in one grid cycle. */
	public void setStepCount(int stepCount) { this.stepCount = stepCount; }

	/** Returns the total number of beats in the sequence. */
	public int getTotalBeats() { return totalBeats; }
	/** Sets the total number of beats in the sequence. */
	public void setTotalBeats(int totalBeats) { this.totalBeats = totalBeats; }

	/** Returns the list of audio sample providers. */
	public List<WaveDataProvider> getSamples() { return samples; }
	/** Sets the list of audio sample providers. */
	public void setSamples(List<WaveDataProvider> samples) { this.samples = samples; }

	/** Returns the parameterized sequence controlling step selection. */
	public ParameterFunctionSequence getSequence() { return sequence; }

	/** Sets the parameterized sequence controlling step selection. */
	public void setSequence(ParameterFunctionSequence sequence) { this.sequence = sequence; }

	/** Returns the total duration of the sequence in seconds. */
	@JsonIgnore
	public double getDuration() { return bpm.l(getTotalBeats()); }

	@Override
	public Producer<PackedCollection> generate(BufferDetails buffer,
												  Producer<PackedCollection> params,
												  Factor<PackedCollection> frequency) {
		// TODO
		throw new UnsupportedOperationException();
	}

	/** Returns the total number of audio frames for the full sequence duration. */
	@Deprecated
	public int getCount() { return (int) (getDuration() * OutputLine.sampleRate); }

	/**
	 * Creates a {@link WaveDataProviderList} by rendering the grid sequence.
	 *
	 * @param x             the x parameter producer
	 * @param y             the y parameter producer
	 * @param z             the z parameter producer
	 * @param playbackRates the playback rates (currently ignored)
	 * @return the rendered wave data provider list
	 * @deprecated Use pattern-based rendering instead.
	 */
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
