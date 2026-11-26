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
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.notes.NoteAudio;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.almostrealism.uml.Named;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;

import java.util.List;
import java.util.function.Supplier;

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
public interface NoteAudioSource extends KeyboardTuned, Named {
	/** Returns the human-readable origin identifier for this source. */
	@JsonIgnore
	String getOrigin();

	/**
	 * Returns the name this source is shown by. By default this is the last
	 * path segment of the {@link #getOrigin() origin}; sources with a more
	 * descriptive name override it.
	 */
	@JsonIgnore
	@Override
	default String getName() {
		String origin = getOrigin();
		if (origin == null) return "";
		return origin.substring(origin.lastIndexOf('/') + 1);
	}

	/** Sets the keyboard tuning for all notes in this source. */
	@JsonIgnore
	@Override
	void setTuning(KeyboardTuning tuning);

	/**
	 * Binds this source to the sample library its notes are selected from.
	 *
	 * <p>A source that selects its notes from a library tree (such as
	 * {@link TreeNoteSource}) rebuilds its notes from the given tree. A source whose
	 * notes come from a fixed origin of its own — a single file, a note group — does
	 * not draw from the library, so the library it is offered does not change it;
	 * that is the default.</p>
	 *
	 * @param tree the library tree notes are selected from, or {@code null} for none
	 */
	@JsonIgnore
	default void setTree(FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> tree) { }

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
	 * Returns the notes this source has already materialized, including any that are
	 * currently inactive, without loading or recomputing anything. This is the set
	 * whose cached audio must be released when the source's owner is torn down
	 * (see {@link NoteAudio#releaseCachedAudio()}). A teardown path must not trigger
	 * lazy loading, which would build fresh notes only to release them.
	 *
	 * <p>The default returns {@link #getNotes()}, which is correct for sources whose
	 * notes are fixed at construction. Sources that load lazily or filter their notes
	 * override it.</p>
	 *
	 * @return a new list of the materialized notes
	 */
	@JsonIgnore
	default List<NoteAudio> getLoadedNotes() {
		return getNotes();
	}

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
