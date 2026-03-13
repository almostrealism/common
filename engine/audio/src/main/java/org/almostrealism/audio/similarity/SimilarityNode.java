/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio.similarity;

import io.almostrealism.relation.Node;

import java.util.Map;
import java.util.Objects;

/**
 * Lightweight graph node that holds only the data needed by
 * {@link AudioSimilarityGraph}: a content identifier and the
 * precomputed similarity scores to other nodes.
 *
 * <p>This avoids retaining the full {@link org.almostrealism.audio.data.WaveDetails}
 * (which carries large feature vectors and frequency data) in the graph,
 * allowing the cache to evict heavy entries while the graph algorithms run.</p>
 *
 * @see AudioSimilarityGraph
 */
public class SimilarityNode implements Node {

	private final String identifier;
	private final Map<String, Double> similarities;

	/**
	 * Creates a similarity node from the given identifier and precomputed scores.
	 *
	 * <p>The similarities map is stored by reference, not copied. Modifications
	 * to the map after construction will be visible through {@link #getSimilarities()}.</p>
	 *
	 * @param identifier   content identifier (MD5 hash)
	 * @param similarities map of peer identifier to similarity score
	 */
	public SimilarityNode(String identifier, Map<String, Double> similarities) {
		this.identifier = identifier;
		this.similarities = similarities;
	}

	/** Returns the content identifier (MD5 hash) for this node. */
	public String getIdentifier() { return identifier; }

	/** Returns the precomputed similarity scores keyed by peer identifier. */
	public Map<String, Double> getSimilarities() { return similarities; }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		SimilarityNode that = (SimilarityNode) o;
		return Objects.equals(identifier, that.identifier);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(identifier);
	}
}
