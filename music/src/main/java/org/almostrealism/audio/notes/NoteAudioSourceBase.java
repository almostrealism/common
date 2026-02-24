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

package org.almostrealism.audio.notes;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.tone.KeyboardTuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public abstract class NoteAudioSourceBase implements NoteAudioSource {
	private Function<NoteAudio, StatelessSource> synthesizerFactory;

	@JsonIgnore
	public Function<NoteAudio, StatelessSource> getSynthesizerFactory() {
		return synthesizerFactory;
	}

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

	public abstract KeyboardTuning getTuning();

	public abstract boolean isUseSynthesizer();
	public abstract boolean isForwardPlayback();
	public abstract boolean isReversePlayback();
}
