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

import java.util.List;
import java.util.Objects;

/**
 * In-memory representation of a persisted prototype index, containing the
 * results of Louvain community detection and PageRank centrality analysis
 * on the audio similarity graph.
 *
 * <p>This data is persisted in the protobuf library file as a
 * {@code PrototypeIndex} message and loaded at startup so the Prototypes
 * tab can display immediately without re-running graph algorithms.</p>
 *
 * @param computedAt epoch millis when this index was computed
 * @param communities the discovered communities with their prototypes and members
 *
 * @see AudioSimilarityGraph
 */
public record PrototypeIndexData(long computedAt, List<Community> communities) {

	/** Compact constructor ensuring an unmodifiable, non-null community list. */
	public PrototypeIndexData {
		communities = List.copyOf(communities);
	}

	/**
	 * A single community discovered by Louvain, with its PageRank-selected
	 * prototype and full membership list.
	 *
	 * @param prototypeIdentifier content identifier (MD5) of the representative sample
	 * @param centrality PageRank score of the prototype within the similarity graph
	 * @param memberIdentifiers content identifiers of all samples in this community
	 */
	public record Community(String prototypeIdentifier, double centrality,
							List<String> memberIdentifiers) {

		/** Compact constructor ensuring non-null fields and an unmodifiable member list. */
		public Community {
			Objects.requireNonNull(prototypeIdentifier, "prototypeIdentifier");
			memberIdentifiers = List.copyOf(memberIdentifiers);
		}
	}

	/**
	 * Returns the total number of samples indexed across all communities.
	 */
	public int totalIndexedMembers() {
		return communities.stream()
				.mapToInt(c -> c.memberIdentifiers().size())
				.sum();
	}
}
