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

package org.almostrealism.audio.grains;

import org.almostrealism.audio.data.WaveDataProvider;

import java.util.ArrayList;
import java.util.List;

public class GrainSet {
	private WaveDataProvider source;
	private List<Grain> grains;
	private List<GrainParameters> params;

	public GrainSet() { }

	public GrainSet(WaveDataProvider source) {
		this.source = source;
		this.grains = new ArrayList<>();
		this.params = new ArrayList<>();
	}

	public WaveDataProvider getSource() { return source; }

	public void setSource(WaveDataProvider source) { this.source = source; }

	public void addGrain(Grain grain) {
		grains.add(grain);
		params.add(GrainParameters.random());
	}

	public void removeGrain(int index) {
		grains.remove(index);
		params.remove(index);
	}

	public Grain addGrain(GrainGenerationSettings settings) {
		Grain grain = new Grain();
		grain.setStart(settings.grainPositionMin + source.getDuration() * Math.random() * (settings.grainPositionMax - settings.grainPositionMin));
		grain.setDuration(settings.grainDurationMin + Math.random() * (settings.grainDurationMax - settings.grainDurationMin));
		grain.setRate(settings.playbackRateMin + Math.random() * (settings.playbackRateMax - settings.playbackRateMin));
		addGrain(grain);
		return grain;
	}

	public Grain getGrain(int index) {
		return grains.get(index);
	}

	public GrainParameters getParams(int index) {
		return params.get(index);
	}

	public List<Grain> getGrains() { return grains; }

	public void setGrains(List<Grain> grains) { this.grains = grains; }

	public List<GrainParameters> getParams() { return params; }

	public void setParams(List<GrainParameters> params) { this.params = params; }
}
