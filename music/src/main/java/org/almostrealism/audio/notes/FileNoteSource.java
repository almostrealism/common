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

package org.almostrealism.audio.notes;

import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;

import java.util.List;

public class FileNoteSource implements NoteAudioSource {
	private String source;
	private KeyPosition<?> root;
	private KeyboardTuning tuning;

	private NoteAudioProvider note;

	public FileNoteSource() { this(null); }

	public FileNoteSource(String sourceFile) {
		this(sourceFile, WesternChromatic.C1);
	}

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

	public String getOrigin() { return source; }

	public String getSource() { return source; }
	public void setSource(String source) { this.source = source; }

	public KeyPosition<?> getRoot() { return root; }
	public void setRoot(KeyPosition<?> root) { this.root = root; }

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
