/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.generative;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.music.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.ProcessFeatures;

import java.io.File;

/**
 * File-system-backed implementation of {@link GenerationResourceManager} that stores
 * model files and generated audio in local directories.
 */
public class LocalResourceManager implements GenerationResourceManager, ProcessFeatures, ConsoleFeatures {
	/** Directory containing persisted model files. */
	private final File models;

	/** Directory containing persisted audio files. */
	private final File audio;

	/**
	 * Creates a local resource manager.
	 *
	 * @param models directory for model storage
	 * @param audio  directory for audio storage
	 */
	public LocalResourceManager(File models, File audio) {
		this.models = models;
		this.audio = audio;
	}

	@Override
	public void storeModel(String id, String vers, File file) {
		run("mv", file.getAbsolutePath(), models.getAbsolutePath() + "/" + id);
		run("touch", models.getAbsolutePath() + "/" + vers);
		log("Saved model " + id);
	}

	@Override
	public void loadModel(String id, File dest) {
		run("cp", models.getAbsolutePath() + "/" + id, dest.getAbsolutePath());
		log("Loaded model " + id);
	}

	@Override
	public boolean isModelAvailable(String id) {
		return new File(models.getAbsolutePath() + "/" + id).exists();
	}

	@Override
	public boolean isModelVersionAvailable(String vers) {
		return new File(models.getAbsolutePath() + "/" + vers).exists();
	}

	@Override
	public NoteAudioProvider storeAudio(String id, File file) {
		log("Storing audio " + id);
		run("mv", file.getAbsolutePath(), audio.getAbsolutePath() + "/" + id);
		return (NoteAudioProvider) new FileNoteSource(audio.getAbsolutePath() + "/" + id).getNotes().get(0);
	}

	@Override
	public NoteAudioProvider storeAudio(String id, WaveData waveData) {
		log("Storing audio " + id);
		waveData.save(new File(audio.getAbsolutePath() + "/" + id));
		return (NoteAudioProvider) new FileNoteSource(audio.getAbsolutePath() + "/" + id).getNotes().get(0);
	}

	@Override
	public NoteAudioProvider getAudio(String id) {
		File file = new File(audio.getAbsolutePath() + "/" + id);

		if (file.exists()) {
			return (NoteAudioProvider) new FileNoteSource(file.getAbsolutePath()).getNotes().get(0);
		} else {
			return null;
		}
	}
}
