/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.filter;

import org.almostrealism.audio.data.MultipleParameterFunction;
import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.data.ParameterSet;

import java.util.List;

public class ParameterizedEnvelopeLayers {
	private ParameterFunction attackSelection;
	private ParameterFunction sustainSelection;
	private ParameterFunction releaseSelection;

	private MultipleParameterFunction volume0;
	private MultipleParameterFunction volume1;
	private MultipleParameterFunction volume2;
	private MultipleParameterFunction volume3;

	public ParameterizedEnvelopeLayers() { }

	public ParameterizedEnvelopeLayers(ParameterFunction attackSelection,
									   ParameterFunction sustainSelection,
									   ParameterFunction releaseSelection,
									   MultipleParameterFunction volume0,
									   MultipleParameterFunction volume1,
									   MultipleParameterFunction volume2,
									   MultipleParameterFunction volume3) {
		this.attackSelection = attackSelection;
		this.sustainSelection = sustainSelection;
		this.releaseSelection = releaseSelection;
		this.volume0 = volume0;
		this.volume1 = volume1;
		this.volume2 = volume2;
		this.volume3 = volume3;
	}

	public ParameterizedLayerEnvelope getEnvelope(int layer) {
		return new ParameterizedLayerEnvelope(this, layer);
	}

	public double getAttack(int layer, ParameterSet params) {
		return attackSelection.positive().apply(params);
	}

	public double getSustain(int layer, ParameterSet params) {
		return sustainSelection.positive().apply(params);
	}

	public double getRelease(int layer, ParameterSet params) {
		return releaseSelection.positive().apply(params);
	}

	public double getVolume(int layer, int index, ParameterSet params) {
		return getVolume(switch (index) {
			case 0 -> volume0;
			case 1 -> volume1;
			case 2 -> volume2;
			case 3 -> volume3;
			default -> throw new IllegalArgumentException();
		}, layer, params);
	}

	private double getVolume(MultipleParameterFunction volume, int layer, ParameterSet params) {
		List<Double> results = volume.apply(params);

		double v;

		if (layer == results.size()) {
			v = 1.0 - results.stream().max(Double::compare).orElse(0.0);
		} else if (layer < results.size()) {
			results.sort(Double::compare);
			v = results.get(layer);
		} else {
			throw new IllegalArgumentException();
		}

		if (v < 0.0 || v > 1.0) {
			throw new IllegalArgumentException();
		}

		return v;
	}

	public ParameterFunction getAttackSelection() {
		return attackSelection;
	}

	public void setAttackSelection(ParameterFunction attackSelection) {
		this.attackSelection = attackSelection;
	}

	public ParameterFunction getSustainSelection() {
		return sustainSelection;
	}

	public void setSustainSelection(ParameterFunction sustainSelection) {
		this.sustainSelection = sustainSelection;
	}

	public ParameterFunction getReleaseSelection() {
		return releaseSelection;
	}

	public void setReleaseSelection(ParameterFunction releaseSelection) {
		this.releaseSelection = releaseSelection;
	}

	public MultipleParameterFunction getVolume0() {
		return volume0;
	}

	public void setVolume0(MultipleParameterFunction volume0) {
		this.volume0 = volume0;
	}

	public MultipleParameterFunction getVolume1() {
		return volume1;
	}

	public void setVolume1(MultipleParameterFunction volume1) {
		this.volume1 = volume1;
	}

	public MultipleParameterFunction getVolume2() {
		return volume2;
	}

	public void setVolume2(MultipleParameterFunction volume2) {
		this.volume2 = volume2;
	}

	public MultipleParameterFunction getVolume3() {
		return volume3;
	}

	public void setVolume3(MultipleParameterFunction volume3) {
		this.volume3 = volume3;
	}

	public static ParameterizedEnvelopeLayers random(int layers) {
		return new ParameterizedEnvelopeLayers(
				ParameterFunction.random(),
				ParameterFunction.random(),
				ParameterFunction.random(),
				MultipleParameterFunction.random(layers - 1),
				MultipleParameterFunction.random(layers - 1),
				MultipleParameterFunction.random(layers - 1),
				MultipleParameterFunction.random(layers - 1));
	}
}
