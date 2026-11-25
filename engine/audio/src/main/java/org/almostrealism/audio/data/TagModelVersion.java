/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.data;

import java.util.Objects;

/**
 * Identifies the provenance of a tag set: the tagging model that produced it
 * and the version of the vocabulary its labels were drawn from.
 *
 * <p>Tags depend on both. A revision of the vocabulary alone (a new, renamed or
 * re-weighted label) invalidates tags exactly as a change of model does, so a
 * {@link WaveDetails} stores its {@link WaveTags} keyed by this pair rather
 * than by the model identifier alone.</p>
 *
 * @param modelIdentifier   the identifier of the tagging model
 * @param vocabularyVersion the version of the vocabulary the tags were drawn from
 * @see WaveTags
 */
public record TagModelVersion(String modelIdentifier, String vocabularyVersion) {

	/**
	 * Creates a key, rejecting a missing model identifier or vocabulary version.
	 *
	 * @param modelIdentifier   the identifier of the tagging model
	 * @param vocabularyVersion the version of the vocabulary
	 * @throws NullPointerException if either argument is {@code null}
	 */
	public TagModelVersion {
		Objects.requireNonNull(modelIdentifier, "modelIdentifier");
		Objects.requireNonNull(vocabularyVersion, "vocabularyVersion");
	}
}
