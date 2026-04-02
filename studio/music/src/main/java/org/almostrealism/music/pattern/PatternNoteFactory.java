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

package org.almostrealism.music.pattern;

import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedEnvelopeLayers;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.PatternNoteAudioChoice;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating {@link PatternNote} instances with layered audio and envelope processing.
 *
 * <p>Constructs notes by combining a fixed number of audio layers, each optionally
 * processed through a {@link ParameterizedEnvelopeLayers} configuration.</p>
 *
 * @see PatternNote
 * @see PatternElementFactory
 */
public class PatternNoteFactory {
	/** The number of audio layers per note. */
	public static final int LAYER_COUNT = 3;

	/** The layer envelope configuration applied when blending layers. */
	private ParameterizedEnvelopeLayers layerEnvelopes;

	/** Optional volume envelope applied to each layer. */
	private ParameterizedVolumeEnvelope volumeEnvelope;

	/** Optional filter envelope applied to each layer. */
	private ParameterizedFilterEnvelope filterEnvelope;

	/** Creates a {@code PatternNoteFactory} and initializes selection functions. */
	public PatternNoteFactory() {
		initSelectionFunctions();
	}

	/** Initializes layer envelopes with random selection functions. */
	public void initSelectionFunctions() {
		layerEnvelopes = ParameterizedEnvelopeLayers.random(LAYER_COUNT);
	}

	/** Returns the number of layers per note. */
	public int getLayerCount() { return LAYER_COUNT; }

	/** Returns the layer envelope configuration. */
	public ParameterizedEnvelopeLayers getLayerEnvelopes() {
		return layerEnvelopes;
	}

	/** Sets the layer envelope configuration. */
	public void setLayerEnvelopes(ParameterizedEnvelopeLayers layerEnvelopes) {
		this.layerEnvelopes = layerEnvelopes;
	}

	/** Returns the volume envelope applied to layers. */
	public ParameterizedVolumeEnvelope getVolumeEnvelope() {
		return volumeEnvelope;
	}

	/** Sets the volume envelope applied to layers. */
	public void setVolumeEnvelope(ParameterizedVolumeEnvelope volumeEnvelope) {
		this.volumeEnvelope = volumeEnvelope;
	}

	/** Returns the filter envelope applied to layers. */
	public ParameterizedFilterEnvelope getFilterEnvelope() {
		return filterEnvelope;
	}

	/** Sets the filter envelope applied to layers. */
	public void setFilterEnvelope(ParameterizedFilterEnvelope filterEnvelope) {
		this.filterEnvelope = filterEnvelope;
	}

	/**
	 * Creates a {@link PatternNote} from the given audio selection choices.
	 *
	 * @param params  the parameter set
	 * @param voicing the signal path voicing
	 * @param blend   whether to apply layer envelope blending
	 * @param choices the audio selection values, one per layer
	 * @return the created pattern note
	 */
	public PatternNote apply(ParameterSet params, ChannelInfo.Voicing voicing, boolean blend, double... choices) {
		List<PatternNoteAudio> layers = new ArrayList<>();

		for (int i = 0; i < getLayerCount(); i++) {
			PatternNoteAudio l = new PatternNoteAudioChoice(choices[i]);

			if (blend) {
				l = layerEnvelopes.getEnvelope(i).apply(params, voicing, l);
			} else {
//				l = volumeEnvelope.apply(params, l);
//				l = filterEnvelope.apply(params, l);
			}

			layers.add(l);
		}

		return new PatternNote(layers);
	}
}
