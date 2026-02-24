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

package org.almostrealism.audio.generative;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.notes.FileNoteSource;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.util.ProcessFeatures;

import java.io.File;

public class LocalResourceManager implements GenerationResourceManager, ProcessFeatures {
	private final File models;
	private final File audio;

	public LocalResourceManager(File models, File audio) {
		this.models = models;
		this.audio = audio;
	}

	@Override
	public void storeModel(String id, String vers, File file) {
		run("mv", file.getAbsolutePath(), models.getAbsolutePath() + "/" + id);
		run("touch", models.getAbsolutePath() + "/" + vers);
		System.out.println("LocalResourceManager: Saved model " + id);
	}

	@Override
	public void loadModel(String id, File dest) {
		run("cp", models.getAbsolutePath() + "/" + id, dest.getAbsolutePath());
		System.out.println("LocalResourceManager: Loaded model " + id);
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
		System.out.println("LocalResourceManager: Storing audio " + id);
		run("mv", file.getAbsolutePath(), audio.getAbsolutePath() + "/" + id);
		return (NoteAudioProvider) new FileNoteSource(audio.getAbsolutePath() + "/" + id).getNotes().get(0);
	}

	@Override
	public NoteAudioProvider storeAudio(String id, WaveData waveData) {
		System.out.println("LocalResourceManager: Storing audio " + id);
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
