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

import org.almostrealism.audio.notes.NoteAudio;

import java.util.List;

/** The GenerationProvider interface. */
public interface GenerationProvider {
	/** Performs the refresh operation. */
	boolean refresh(String requestId, String generatorId, List<NoteAudio> sources);

	/** Performs the getStatus operation. */
	GeneratorStatus getStatus(String id);

	/** Performs the generate operation. */
	List<NoteAudio> generate(String requestId, String generatorId, int count);

	/** Performs the getSampleRate operation. */
	int getSampleRate();
}
