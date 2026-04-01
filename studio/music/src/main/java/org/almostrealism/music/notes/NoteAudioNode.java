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

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.almostrealism.relation.Tree;
import io.almostrealism.uml.Named;
import org.almostrealism.audio.data.DataResource;

/**
 * A tree node that represents a note audio element in a hierarchical audio structure.
 *
 * <p>Implementations carry an identifier computed from the identifiers of their children.
 * The identifier is used for caching and deduplication in the audio rendering pipeline.</p>
 *
 * @see NoteAudioSource
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public interface NoteAudioNode extends Tree<NoteAudioNode>, DataResource, Named {
	/** Returns an identifier composed of all children identifiers concatenated. */
	@Override
	default String getIdentifier() {
		return getChildren().stream()
				.map(s -> s == null ? "null" : s.getIdentifier())
				.reduce("", (a, b) -> a + b);
	}

	/**
	 * Sets the identifier (no-op by default).
	 *
	 * <p>Most implementations compute the identifier from their children.
	 * This setter exists to support JSON deserialization but does not need
	 * to store the value.</p>
	 *
	 * @param identifier the identifier (ignored by default)
	 */
	default void setIdentifier(String identifier) {
		// Most implementations will compute the identifier from the children
		// and assign it is not necessary, even though it is useful for it
		// to be stored in the JSON
	}
}
