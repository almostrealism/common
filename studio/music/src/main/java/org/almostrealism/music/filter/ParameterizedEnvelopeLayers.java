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

package org.almostrealism.music.filter;

import org.almostrealism.music.data.MultipleParameterFunction;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;

import java.util.List;

/**
 * Envelope configuration for multi-layer audio rendering.
 *
 * <p>Holds ADSR selection functions and four volume functions (one per audio layer)
 * used by {@link ParameterizedLayerEnvelope} to compute per-layer envelope values
 * from a {@link org.almostrealism.music.data.ParameterSet}.</p>
 *
 * @see ParameterizedLayerEnvelope
 */
public class ParameterizedEnvelopeLayers {
	/** Selects the attack duration (shared across all layers). */
	private ParameterFunction attackSelection;

	/** Selects the sustain duration (shared across all layers). */
	private ParameterFunction sustainSelection;

	/** Selects the release duration (shared across all layers). */
	private ParameterFunction releaseSelection;

	/** Volume selection for audio source 0. */
	private MultipleParameterFunction volume0;

	/** Volume selection for audio source 1. */
	private MultipleParameterFunction volume1;

	/** Volume selection for audio source 2. */
	private MultipleParameterFunction volume2;

	/** Volume selection for audio source 3. */
	private MultipleParameterFunction volume3;

	/** Creates a {@code ParameterizedEnvelopeLayers} with null selection functions. */
	public ParameterizedEnvelopeLayers() { }

	/**
	 * Creates a {@code ParameterizedEnvelopeLayers} with the given ADSR and volume functions.
	 *
	 * @param attackSelection  function selecting the attack duration
	 * @param sustainSelection function selecting the sustain duration
	 * @param releaseSelection function selecting the release duration
	 * @param volume0 volume selection for layer 0
	 * @param volume1 volume selection for layer 1
	 * @param volume2 volume selection for layer 2
	 * @param volume3 volume selection for layer 3
	 */
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

	/**
	 * Returns a {@link ParameterizedLayerEnvelope} for the given layer index.
	 *
	 * @param layer the zero-based layer index
	 * @return the envelope for that layer
	 */
	public ParameterizedLayerEnvelope getEnvelope(int layer) {
		return new ParameterizedLayerEnvelope(this, layer);
	}

	/**
	 * Returns the attack duration for the given layer using the current parameters.
	 *
	 * @param layer the layer index (currently unused; attack is shared)
	 * @param params the parameter set to evaluate
	 * @return the attack duration
	 */
	public double getAttack(int layer, ParameterSet params) {
		return attackSelection.positive().apply(params);
	}

	/**
	 * Returns the sustain duration for the given layer using the current parameters.
	 *
	 * @param layer the layer index (currently unused; sustain is shared)
	 * @param params the parameter set to evaluate
	 * @return the sustain duration
	 */
	public double getSustain(int layer, ParameterSet params) {
		return sustainSelection.positive().apply(params);
	}

	/**
	 * Returns the release duration for the given layer using the current parameters.
	 *
	 * @param layer the layer index (currently unused; release is shared)
	 * @param params the parameter set to evaluate
	 * @return the release duration
	 */
	public double getRelease(int layer, ParameterSet params) {
		return releaseSelection.positive().apply(params);
	}

	/**
	 * Returns the volume for the given layer and audio source index.
	 *
	 * @param layer the layer index
	 * @param index the audio source index (0-3)
	 * @param params the parameter set to evaluate
	 * @return the volume value in [0, 1]
	 */
	public double getVolume(int layer, int index, ParameterSet params) {
		return getVolume(switch (index) {
			case 0 -> volume0;
			case 1 -> volume1;
			case 2 -> volume2;
			case 3 -> volume3;
			default -> throw new IllegalArgumentException();
		}, layer, params);
	}

	/**
	 * Returns the volume factor for the given layer using the specified volume function.
	 *
	 * @param volume the multi-output parameter function providing volume values
	 * @param layer  the layer index
	 * @param params the parameter set
	 * @return the volume factor for the layer
	 */
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

	/** Returns the function that selects the attack duration. */
	public ParameterFunction getAttackSelection() {
		return attackSelection;
	}

	/** Sets the function that selects the attack duration. */
	public void setAttackSelection(ParameterFunction attackSelection) {
		this.attackSelection = attackSelection;
	}

	/** Returns the function that selects the sustain duration. */
	public ParameterFunction getSustainSelection() {
		return sustainSelection;
	}

	/** Sets the function that selects the sustain duration. */
	public void setSustainSelection(ParameterFunction sustainSelection) {
		this.sustainSelection = sustainSelection;
	}

	/** Returns the function that selects the release duration. */
	public ParameterFunction getReleaseSelection() {
		return releaseSelection;
	}

	/** Sets the function that selects the release duration. */
	public void setReleaseSelection(ParameterFunction releaseSelection) {
		this.releaseSelection = releaseSelection;
	}

	/** Returns the volume selection function for audio source 0. */
	public MultipleParameterFunction getVolume0() {
		return volume0;
	}

	/** Sets the volume selection function for audio source 0. */
	public void setVolume0(MultipleParameterFunction volume0) {
		this.volume0 = volume0;
	}

	/** Returns the volume selection function for audio source 1. */
	public MultipleParameterFunction getVolume1() {
		return volume1;
	}

	/** Sets the volume selection function for audio source 1. */
	public void setVolume1(MultipleParameterFunction volume1) {
		this.volume1 = volume1;
	}

	/** Returns the volume selection function for audio source 2. */
	public MultipleParameterFunction getVolume2() {
		return volume2;
	}

	/** Sets the volume selection function for audio source 2. */
	public void setVolume2(MultipleParameterFunction volume2) {
		this.volume2 = volume2;
	}

	/** Returns the volume selection function for audio source 3. */
	public MultipleParameterFunction getVolume3() {
		return volume3;
	}

	/** Sets the volume selection function for audio source 3. */
	public void setVolume3(MultipleParameterFunction volume3) {
		this.volume3 = volume3;
	}

	/**
	 * Creates a randomly initialized {@code ParameterizedEnvelopeLayers} for the given layer count.
	 *
	 * @param layers the number of layers (volumes are sized to {@code layers - 1})
	 * @return a new randomly initialized instance
	 */
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
