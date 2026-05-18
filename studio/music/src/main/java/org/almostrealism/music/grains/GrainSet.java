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

package org.almostrealism.music.grains;

import org.almostrealism.audio.data.WaveDataProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * A collection of {@link Grain} instances associated with a shared audio source.
 *
 * <p>Each grain identifies a short excerpt from the source audio, and each grain
 * has a corresponding {@link GrainParameters} that controls amplitude, phase, and
 * wavelength modulation during rendering.</p>
 *
 * @see Grain
 * @see GrainParameters
 * @see GrainGenerationSettings
 */
public class GrainSet {
	/** The audio source from which grains are drawn. */
	private WaveDataProvider source;

	/** The list of grains in this set. */
	private List<Grain> grains;

	/** The list of parameter configurations, one per grain. */
	private List<GrainParameters> params;

	/** Creates an uninitialized {@code GrainSet}. */
	public GrainSet() { }

	/**
	 * Creates a {@code GrainSet} with the given audio source.
	 *
	 * @param source the audio source from which grains are drawn
	 */
	public GrainSet(WaveDataProvider source) {
		this.source = source;
		this.grains = new ArrayList<>();
		this.params = new ArrayList<>();
	}

	/** Returns the audio source for this grain set. */
	public WaveDataProvider getSource() { return source; }

	/** Sets the audio source for this grain set. */
	public void setSource(WaveDataProvider source) { this.source = source; }

	/**
	 * Adds an existing grain with randomly generated parameters.
	 *
	 * @param grain the grain to add
	 */
	public void addGrain(Grain grain) {
		grains.add(grain);
		params.add(GrainParameters.random());
	}

	/**
	 * Removes the grain and its parameters at the given index.
	 *
	 * @param index the zero-based index of the grain to remove
	 */
	public void removeGrain(int index) {
		grains.remove(index);
		params.remove(index);
	}

	/**
	 * Generates a new grain using the given settings and adds it to this set.
	 *
	 * @param settings the generation settings defining the random bounds
	 * @return the newly created grain
	 */
	public Grain addGrain(GrainGenerationSettings settings) {
		Grain grain = new Grain();
		grain.setStart(settings.grainPositionMin + source.getDuration() * Math.random() * (settings.grainPositionMax - settings.grainPositionMin));
		grain.setDuration(settings.grainDurationMin + Math.random() * (settings.grainDurationMax - settings.grainDurationMin));
		grain.setRate(settings.playbackRateMin + Math.random() * (settings.playbackRateMax - settings.playbackRateMin));
		addGrain(grain);
		return grain;
	}

	/**
	 * Returns the grain at the given index.
	 *
	 * @param index the zero-based index
	 * @return the grain at {@code index}
	 */
	public Grain getGrain(int index) {
		return grains.get(index);
	}

	/**
	 * Returns the parameters for the grain at the given index.
	 *
	 * @param index the zero-based index
	 * @return the {@link GrainParameters} at {@code index}
	 */
	public GrainParameters getParams(int index) {
		return params.get(index);
	}

	/** Returns the list of grains in this set. */
	public List<Grain> getGrains() { return grains; }

	/** Sets the list of grains for this set. */
	public void setGrains(List<Grain> grains) { this.grains = grains; }

	/** Returns the list of parameter configurations for this set. */
	public List<GrainParameters> getParams() { return params; }

	/** Sets the list of parameter configurations for this set. */
	public void setParams(List<GrainParameters> params) { this.params = params; }
}
