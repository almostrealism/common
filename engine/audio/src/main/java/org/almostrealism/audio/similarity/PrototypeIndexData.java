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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory representation of a persisted prototype index, containing the
 * results of Louvain community detection and PageRank centrality analysis
 * on the audio similarity graph.
 *
 * <p>This data is persisted in the protobuf library file as a
 * {@code PrototypeIndex} message and loaded at startup so the Prototypes
 * tab can display immediately without re-running graph algorithms.</p>
 *
 * <p>The communities are only comparable to each other when they were computed
 * from a single feature model's embeddings, so the identifier of that model is
 * recorded alongside them. {@link #isStale(Set, String)} treats an index built
 * from a different model (or, for an index written before models were recorded,
 * from no recorded model at all) as stale, because similarity must never mix
 * features from different models.</p>
 *
 * @param computedAt epoch millis when this index was computed
 * @param modelIdentifier identifier of the feature model whose embeddings the
 *                        communities were computed from, or {@code null} when it
 *                        was not recorded (an index written before models were)
 * @param communities the discovered communities with their prototypes and members
 *
 * @see AudioSimilarityGraph
 */
public record PrototypeIndexData(long computedAt, String modelIdentifier, List<Community> communities) {

	/** Fraction of indexed members that may go missing before the index is stale. */
	private static final double STALE_MEMBER_FRACTION = 0.05;

	/** Compact constructor ensuring an unmodifiable, non-null community list. */
	public PrototypeIndexData {
		communities = List.copyOf(communities);
	}

	/**
	 * Creates an index with no recorded feature model, matching how an index
	 * written before models were recorded (and one built without a configured
	 * feature provider) is represented.
	 *
	 * @param computedAt  epoch millis when this index was computed
	 * @param communities the discovered communities
	 */
	public PrototypeIndexData(long computedAt, List<Community> communities) {
		this(computedAt, null, communities);
	}

	/**
	 * Determines whether this persisted index needs recomputation against the
	 * library's current contents and feature model.
	 *
	 * <p>Staleness is determined by these conditions (checked in order of cost):</p>
	 * <ol>
	 *   <li>No communities (first run / bootstrap)</li>
	 *   <li>The current feature model differs from the one the communities were
	 *       computed from (so the communities mix features from different models)</li>
	 *   <li>Any prototype identifier not found in current ids (prototype was deleted)</li>
	 *   <li>More than 5% of indexed member identifiers not found (significant removal)</li>
	 *   <li>Current id count exceeds total indexed members by more than 5% (significant additions)</li>
	 *   <li>Index is older than 24 hours AND any difference exists between indexed and current members</li>
	 * </ol>
	 *
	 * @param currentIdentifiers the content identifiers currently known to be complete
	 * @param currentModel       the identifier of the library's current feature model,
	 *                           or {@code null} when none is configured
	 * @return true if the index should be recomputed
	 */
	public boolean isStale(Set<String> currentIdentifiers, String currentModel) {
		if (communities.isEmpty()) {
			return true;
		}

		if (currentModel != null && !currentModel.equals(modelIdentifier)) {
			return true;
		}

		int totalIndexed = 0;
		int missingMembers = 0;

		for (Community community : communities) {
			if (!currentIdentifiers.contains(community.prototypeIdentifier())) {
				return true;
			}

			for (String memberId : community.memberIdentifiers()) {
				totalIndexed++;
				if (!currentIdentifiers.contains(memberId)) {
					missingMembers++;
				}
			}
		}

		if (totalIndexed > 0 && missingMembers > totalIndexed * STALE_MEMBER_FRACTION) {
			return true;
		}

		int currentSize = currentIdentifiers.size();
		if (totalIndexed > 0 && currentSize > totalIndexed * (1 + STALE_MEMBER_FRACTION)) {
			return true;
		}

		long age = System.currentTimeMillis() - computedAt;
		boolean anyDifference = currentSize != totalIndexed || missingMembers > 0;
		return age > Duration.ofHours(24).toMillis() && anyDifference;
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
