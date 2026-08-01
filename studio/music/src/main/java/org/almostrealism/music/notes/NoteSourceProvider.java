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

import java.util.List;

/**
 * Provides {@link NoteAudioSource} instances by identifier.
 *
 * <p>Used to resolve note audio sources by their unique string ID during
 * pattern system configuration and rendering.</p>
 *
 * @see NoteAudioSource
 */
public interface NoteSourceProvider {
	/**
	 * Returns the list of audio sources associated with the given identifier.
	 *
	 * @param id the unique identifier of the audio source
	 * @return the list of matching audio sources, or an empty list if none
	 */
	List<NoteAudioSource> getSource(String id);
}
