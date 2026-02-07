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
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public interface NoteAudioSource extends KeyboardTuned {
	@JsonIgnore
	String getOrigin();

	@JsonIgnore
	@Override
	void setTuning(KeyboardTuning tuning);

	@JsonIgnore
	default List<PatternNoteAudio> getPatternNotes() {
		return getNotes().stream()
				.map(SimplePatternNote::new)
				.map(PatternNoteAudio.class::cast)
				.toList();
	}

	@JsonIgnore
	List<NoteAudio> getNotes();

	boolean checkResourceUsed(String canonicalPath);
}
