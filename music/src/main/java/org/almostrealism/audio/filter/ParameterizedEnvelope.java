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

package org.almostrealism.audio.filter;

import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.audio.notes.PatternNote;
import org.almostrealism.audio.notes.PatternNoteAudio;
import org.almostrealism.audio.notes.PatternNoteLayer;

public interface ParameterizedEnvelope extends EnvelopeFeatures {

	default PatternNoteLayer apply(ParameterSet params, ChannelInfo.Voicing voicing, PatternNoteAudio note) {
		return PatternNoteLayer.create(note, createFilter(params, voicing));
	}

	default PatternNote apply(ParameterSet params, ChannelInfo.Voicing voicing, PatternNote note) {
		return new PatternNote(note, createFilter(params, voicing));
	}

	NoteAudioFilter createFilter(ParameterSet params, ChannelInfo.Voicing voicing);
}
