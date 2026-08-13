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
import org.almostrealism.audio.notes.NoteAudio;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;

import java.util.List;

/**
 * A source of {@link org.almostrealism.audio.notes.NoteAudio} instances used within
 * the pattern system.
 *
 * <p>Implementations load notes from a backing store (e.g., a file, a tree of files,
 * or a synthesizer) and optionally support keyboard tuning. Pattern notes are derived
 * from the raw notes by wrapping them in {@link SimplePatternNote}.</p>
 *
 * @see NoteAudioChoice
 * @see TreeNoteSource
 * @see FileNoteSource
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public interface NoteAudioSource extends KeyboardTuned {
	/** Returns the human-readable origin identifier for this source. */
	@JsonIgnore
	String getOrigin();

	/** Sets the keyboard tuning for all notes in this source. */
	@JsonIgnore
	@Override
	void setTuning(KeyboardTuning tuning);

	/**
	 * Returns a list of {@link PatternNoteAudio} instances wrapping the raw notes.
	 *
	 * @return the list of pattern notes
	 */
	@JsonIgnore
	default List<PatternNoteAudio> getPatternNotes() {
		return getNotes().stream()
				.map(SimplePatternNote::new)
				.map(PatternNoteAudio.class::cast)
				.toList();
	}

	/** Returns all raw notes available from this source. */
	@JsonIgnore
	List<NoteAudio> getNotes();

	/**
	 * Returns whether this source belongs in a {@link NoteAudioChoice}'s saved
	 * configuration.
	 *
	 * <p>Most sources describe themselves entirely by the fields they persist —
	 * a path, a filter — and are restored by reading them back. A source that is
	 * instead <em>derived</em> from the library each time a choice is assembled
	 * has no such description: writing it produces either a duplicate of library
	 * content or, worse, an entry that cannot be reconstructed on read. Because
	 * {@link NoteAudioChoice#getSources() sources} are serialized polymorphically
	 * by class name, a single unreadable entry aborts the read of the entire
	 * choices file, taking every other choice with it. Derived sources therefore
	 * return {@code false} and are re-added by whatever assembled them.</p>
	 *
	 * @return {@code true} if this source is written with the scene (the default)
	 */
	@JsonIgnore
	default boolean isPersistent() { return true; }

	/**
	 * Returns {@code true} if any note in this source references the given file path.
	 *
	 * @param canonicalPath the canonical file path to check
	 * @return {@code true} if the path is in use
	 */
	boolean checkResourceUsed(String canonicalPath);
}
