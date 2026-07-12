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

package org.almostrealism.music.grains;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.DynamicWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.data.WaveDataProviderList;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Input;
import org.almostrealism.time.Frequency;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Granular synthesizer that generates audio from grain sets.
 */
public class GranularSynthesizer implements StatelessSource, CellFeatures {

	/** Minimum wavelength for amplitude modulation in seconds. */
	public static double ampModWavelengthMin = 0.1;

	/** Maximum wavelength for amplitude modulation in seconds. */
	public static double ampModWavelengthMax = 10;

	/** Default duration for grain processing in seconds. */
	public static double duration = 10;

	/** Amplitude gain factor applied to all output. */
	private double gain;

	/** List of grain sets used by this synthesizer. */
	private List<GrainSet> grains;

	/** The grain processor handling audio generation. */
	private final GrainProcessor processor;

	/**
	 * Creates a granular synthesizer with the specified sample rate.
	 *
	 * @param sampleRate the audio sample rate in Hz
	 */
	public GranularSynthesizer(int sampleRate) {
		gain = 1.0;
		grains = new ArrayList<>();
		processor = new GrainProcessor(duration, sampleRate);
	}

	/**
	 * Returns the grain duration.
	 *
	 * @return the duration in seconds
	 */
	@JsonIgnore
	public double getDuration() {
		return 10;
	}

	/**
	 * Returns the amplitude gain factor.
	 *
	 * @return the gain value
	 */
	public double getGain() {
		return gain;
	}

	/**
	 * Sets the amplitude gain factor.
	 *
	 * @param gain the gain value to set
	 */
	public void setGain(double gain) {
		this.gain = gain;
	}

	/**
	 * Returns the list of grain sets.
	 *
	 * @return the list of grain sets
	 */
	public List<GrainSet> getGrains() {
		return grains;
	}

	/**
	 * Sets the list of grain sets.
	 *
	 * @param grains the grain sets to set
	 */
	public void setGrains(List<GrainSet> grains) {
		this.grains = grains;
	}

	/**
	 * Adds a grain set from a file.
	 *
	 * @param file the path to the audio file
	 * @return the created grain set
	 */
	public GrainSet addFile(String file) {
		GrainSet g = new GrainSet(new FileWaveDataProvider(file));
		grains.add(g);
		return g;
	}

	/**
	 * Adds a grain with the specified settings to a random grain set.
	 *
	 * @param settings the grain generation settings
	 * @throws UnsupportedOperationException if no grain sets exist
	 */
	public void addGrain(GrainGenerationSettings settings) {
		if (grains.isEmpty()) throw new UnsupportedOperationException();
		grains.get((int) (Math.random() * grains.size())).addGrain(settings);
	}

	/**
	 * Generates audio output for the given buffer.
	 *
	 * @param buffer the buffer details
	 * @param params the parameter producer
	 * @param frequency the frequency factor
	 * @return the generated audio producer
	 */
	@Override
	public Producer<PackedCollection> generate(BufferDetails buffer,
												  Producer<PackedCollection> params,
												  Factor<PackedCollection> frequency) {
		return null;
	}

	/**
	 * Creates a wave data provider list for granular synthesis.
	 *
	 * @param x the x parameter producer
	 * @param y the y parameter producer
	 * @param z the z parameter producer
	 * @param playbackRates the list of playback frequencies
	 * @return the wave data provider list
	 * @deprecated use grain-based processing instead
	 */
	@Deprecated
	public WaveDataProviderList create(Producer<PackedCollection> x, Producer<PackedCollection> y, Producer<PackedCollection> z, List<Frequency> playbackRates) {
		Evaluable<PackedCollection> evX = x.get();
		Evaluable<PackedCollection> evY = y.get();
		Evaluable<PackedCollection> evZ = z.get();

		List<WaveDataProvider> providers = new ArrayList<>();
		playbackRates.forEach(rate -> {
			PackedCollection output = new PackedCollection(processor.getFrames()).traverse(1);
			WaveData destination = new WaveData(output, OutputLine.sampleRate);
			providers.add(new DynamicWaveDataProvider("synth://" + UUID.randomUUID(), destination));
		});

		return new WaveDataProviderList(providers, () -> () -> {
			ParameterSet params = new ParameterSet(evX.evaluate().toDouble(0), evY.evaluate().toDouble(0), evZ.evaluate().toDouble(0));

			PackedCollection playbackRate = new PackedCollection(1);

			PackedCollection w = new PackedCollection(1);
			PackedCollection p = new PackedCollection(1);
			PackedCollection a = new PackedCollection(1);

			for (int i = 0; i < playbackRates.size(); i++) {
				c(playbackRates.get(i).asHertz()).into(playbackRate.traverseEach()).evaluate();
				if (WaveOutput.enableVerbose)
					log("GranularSynthesizer: Rendering grains for playback rate " + playbackRates.get(i) + "...");

				List<PackedCollection> results = new ArrayList<>();
				int count = grains.stream().map(GrainSet::getGrains).mapToInt(List::size).sum();

				for (GrainSet grainSet : grains) {
					WaveData source = grainSet.getSource().get();

					for (int n = 0; n < grainSet.getGrains().size(); n++) {
						Grain grain = grainSet.getGrain(n);
						GrainParameters gp = grainSet.getParams(n);

						double amp = gp.getAmp().apply(params);
						double phase = gp.getPhase().apply(params);
						double wavelength = ampModWavelengthMin + Math.abs(gp.getWavelength().apply(params)) * (ampModWavelengthMax - ampModWavelengthMin);

						c(wavelength).into(w.traverseEach()).evaluate();
						c(phase).into(p.traverseEach()).evaluate();
						c(amp).into(a.traverseEach()).evaluate();

						PackedCollection rendered = processor.getKernel()
								.into(processor.newOutputBuffer())
								.evaluate(source.getChannelData(0).traverse(0), grain, w, p, a);
						results.add(new WaveData(rendered, processor.getSampleRate()).getChannelData(0));
					}
				}

				Producer[] args = Input.generateArguments(processor.getFrames(), 0, results.size());
				Producer sum = args[0];

				for (int j = 1; j < args.length; j++) {
					sum = add(sum, args[j]);
				}

				sum = multiply(sum, c(gain / count));

				if (WaveOutput.enableVerbose) log("GranularSynthesizer: Summing grains...");
				sum.get().into(providers.get(i).get().getChannelData(0)).evaluate(results.stream().toArray(Object[]::new));
				if (WaveOutput.enableVerbose) log("GranularSynthesizer: Done");
			}
		});
	}
}
