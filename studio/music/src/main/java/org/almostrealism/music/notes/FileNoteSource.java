/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.notes.NoteAudio;

import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;

import java.util.List;

/**
 * A {@link NoteAudioSource} backed by a single audio file.
 *
 * <p>Loads the file lazily on the first call to {@link #getNotes()}, creating a
 * {@link NoteAudioProvider} for the specified root key position.</p>
 *
 * @see NoteAudioSource
 */
public class FileNoteSource implements NoteAudioSource {
	/** The path to the source audio file. */
	private String source;

	/** The root key position for this audio source. */
	private KeyPosition<?> root;

	/** The keyboard tuning applied to the note provider. */
	private KeyboardTuning tuning;

	/** The lazily-initialized note provider. */
	private NoteAudioProvider note;

	/** Creates a {@code FileNoteSource} with a null source file. */
	public FileNoteSource() { this(null); }

	/**
	 * Creates a {@code FileNoteSource} for the given file with default root key {@code C1}.
	 *
	 * @param sourceFile path to the audio file
	 */
	public FileNoteSource(String sourceFile) {
		this(sourceFile, WesternChromatic.C1);
	}

	/**
	 * Creates a {@code FileNoteSource} for the given file and root key position.
	 *
	 * @param sourceFile path to the audio file
	 * @param root       the root key position
	 */
	public FileNoteSource(String sourceFile, KeyPosition<?> root) {
		this.source = sourceFile;
		this.root = root;
	}

	@Override
	public void setTuning(KeyboardTuning tuning) {
		this.tuning = tuning;
		if (note != null) {
			note.setTuning(tuning);
		}
	}

	@Override
	public String getOrigin() { return source; }

	public String getSource() { return source; }
	public void setSource(String source) { this.source = source; }

	public KeyPosition<?> getRoot() { return root; }
	public void setRoot(KeyPosition<?> root) { this.root = root; }

	/**
	 * Returns a singleton list containing the note provider for this file,
	 * creating it lazily if needed.
	 *
	 * @return list with the single note provider
	 */
	@Override
	public List<NoteAudio> getNotes() {
		if (note == null) {
			note = NoteAudioProvider.create(source, root);
			note.setTuning(tuning);
		}

		return List.of(note);
	}

	@Override
	public boolean checkResourceUsed(String canonicalPath) {
		return source != null && source.equals(canonicalPath);
	}
}
