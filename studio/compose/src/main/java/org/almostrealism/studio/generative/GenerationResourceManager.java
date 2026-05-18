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
import org.almostrealism.audio.notes.NoteAudioProvider;

import java.io.File;

/**
 * Resource manager for persisting and retrieving ML model files and generated audio
 * associated with a generation session.
 */
public interface GenerationResourceManager {
	/**
	 * Stores a model file under the given identifier and version tag.
	 *
	 * @param id   the model identifier
	 * @param vers the version tag
	 * @param file the model file to store
	 */
	void storeModel(String id, String vers, File file);

	/**
	 * Loads the model with the given identifier into the destination file.
	 *
	 * @param id   the model identifier
	 * @param dest the destination file to write the model to
	 */
	void loadModel(String id, File dest);

	/**
	 * Returns {@code true} if a model with the given identifier is available.
	 *
	 * @param id the model identifier to check
	 */
	boolean isModelAvailable(String id);

	/**
	 * Returns {@code true} if a model with the given version tag is available.
	 *
	 * @param vers the version tag to check
	 */
	boolean isModelVersionAvailable(String vers);

	/**
	 * Stores the audio file under the given identifier and returns a provider for it.
	 *
	 * @param id   the audio identifier
	 * @param file the audio file to store
	 * @return a {@link NoteAudioProvider} for the stored audio
	 */
	NoteAudioProvider storeAudio(String id, File file);

	/**
	 * Stores wave data under the given identifier and returns a provider for it.
	 *
	 * @param id       the audio identifier
	 * @param waveData the wave data to store
	 * @return a {@link NoteAudioProvider} for the stored audio
	 */
	NoteAudioProvider storeAudio(String id, WaveData waveData);

	/**
	 * Returns a provider for the audio stored under the given identifier, or
	 * {@code null} if no such audio exists.
	 *
	 * @param id the audio identifier
	 * @return the audio provider, or {@code null}
	 */
	NoteAudioProvider getAudio(String id);
}
