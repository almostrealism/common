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

package org.almostrealism.music.notes;
import org.almostrealism.audio.notes.ReversePlaybackAudioFilter;
import org.almostrealism.audio.notes.NoteAudio;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.tone.KeyboardTuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Abstract base class for {@link NoteAudioSource} implementations that support
 * optional synthesizer-based rendering and forward/reverse playback.
 *
 * <p>Subclasses implement the synthesis-related abstract methods and the
 * abstract tuning/playback accessors. The {@link #getPatternNotes()} method
 * builds the final list of pattern notes based on the configured playback modes.</p>
 *
 * @see NoteAudioSource
 * @see TreeNoteSource
 */
public abstract class NoteAudioSourceBase implements NoteAudioSource {
	/** Factory used to create stateless synthesizer sources from raw note audio. */
	private Function<NoteAudio, StatelessSource> synthesizerFactory;

	/**
	 * Returns the synthesizer factory used to create stateless sources from note audio.
	 *
	 * @return the synthesizer factory, or null if not set
	 */
	@JsonIgnore
	public Function<NoteAudio, StatelessSource> getSynthesizerFactory() {
		return synthesizerFactory;
	}

	/**
	 * Sets the synthesizer factory used to create stateless sources from note audio.
	 *
	 * @param synthesizerFactory the factory to set
	 */
	@JsonIgnore
	public void setSynthesizerFactory(Function<NoteAudio, StatelessSource> synthesizerFactory) {
		this.synthesizerFactory = synthesizerFactory;
	}

	@Override
	public List<PatternNoteAudio> getPatternNotes() {
		if (isUseSynthesizer()) {
			if (getSynthesizerFactory() == null) {
				return Collections.emptyList();
			}

			return getNotes().stream().map(getSynthesizerFactory())
					.map(source -> {
						StatelessSourceNoteAudioTuned audio = new StatelessSourceNoteAudioTuned(source,
								new BufferDetails(OutputLine.sampleRate, 10.0));
						audio.setTuning(getTuning());
						return (PatternNoteAudio) audio;
					})
					.toList();
		}

		List<PatternNoteAudio> notes = new ArrayList<>();

		if (isForwardPlayback()) {
			getNotes().stream()
					.map(SimplePatternNote::new)
					.forEach(notes::add);
		}

		if (isReversePlayback()) {
			ReversePlaybackAudioFilter filter = new ReversePlaybackAudioFilter();
			getNotes().stream()
					.map(SimplePatternNote::new)
					.map(n -> n.filter(filter))
					.forEach(notes::add);
		}

		return notes;
	}

	/** Returns the keyboard tuning applied to note providers. */
	public abstract KeyboardTuning getTuning();

	/** Returns {@code true} if synthesizer rendering is enabled. */
	public abstract boolean isUseSynthesizer();

	/** Returns {@code true} if forward playback is enabled. */
	public abstract boolean isForwardPlayback();

	/** Returns {@code true} if reverse playback is enabled. */
	public abstract boolean isReversePlayback();
}
