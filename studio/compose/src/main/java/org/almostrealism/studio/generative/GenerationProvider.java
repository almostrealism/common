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

import org.almostrealism.audio.notes.NoteAudio;

import java.util.List;

/**
 * Service-provider interface for ML audio generation back-ends. Implementations
 * connect the studio to specific generative model inference services.
 */
public interface GenerationProvider {
	/**
	 * Refreshes the generative model for the given generator using the provided audio sources.
	 *
	 * @param requestId   unique identifier for this refresh request
	 * @param generatorId the ID of the generator to refresh
	 * @param sources     audio samples to use as conditioning inputs
	 * @return {@code true} if the refresh succeeded
	 */
	boolean refresh(String requestId, String generatorId, List<NoteAudio> sources);

	/**
	 * Returns the current generation status for the given generator.
	 *
	 * @param id the generator ID to query
	 * @return the current {@link GeneratorStatus}
	 */
	GeneratorStatus getStatus(String id);

	/**
	 * Generates a list of audio samples for the given generator.
	 *
	 * @param requestId   unique identifier for this generation request
	 * @param generatorId the ID of the generator to use
	 * @param count       the number of audio samples to generate
	 * @return the generated {@link NoteAudio} list, or {@code null} on failure
	 */
	List<NoteAudio> generate(String requestId, String generatorId, int count);

	/**
	 * Returns the audio sample rate used by this provider.
	 *
	 * @return sample rate in Hz
	 */
	int getSampleRate();
}
