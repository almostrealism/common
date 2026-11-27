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

package org.almostrealism.audio.similarity;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.data.WaveDetailsFactory;
import org.almostrealism.audio.data.WaveDetailsStore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes similarity scores between one item of an {@link AudioLibrary} and
 * other items of it: either a given set of items, or the items most likely to
 * be the most similar.
 *
 * <p>Scoring an item against the whole library reads every record. For a
 * store-backed library larger than the store's memory budget that means
 * decoding the whole store from disk on every call, which takes seconds per
 * call. A caller that only needs the closest matches (such as a list of the
 * sounds nearest a selected one) can instead use {@link #nearest}, which takes
 * the nearest items by mean-pooled embedding from the store's nearest-neighbor
 * index and computes the exact similarity for those alone.</p>
 *
 * @see AudioLibrary#computeSimilarities(WaveDetails)
 * @see AudioLibrary#getNearestSimilarities(WaveDetails, int, Set)
 */
public class SimilaritySearch implements ConsoleFeatures {
	/**
	 * Fraction of the library's complete items the nearest-neighbor index must
	 * hold for {@link #nearest} to rely on it.
	 */
	public static final double NEIGHBOR_INDEX_COVERAGE = 0.9;

	/** The library whose items are compared. */
	private final AudioLibrary library;

	/**
	 * Creates a search over the given library.
	 *
	 * @param library the library whose items are compared
	 */
	public SimilaritySearch(AudioLibrary library) {
		this.library = library;
	}

	/**
	 * Computes the similarity scores missing from the given details' similarity
	 * map for each of the given targets. Each score is stored in both items'
	 * maps. The details' own identifier is skipped.
	 *
	 * @param details   the details to compute similarities for
	 * @param targetIds identifiers of the items to compare against
	 * @return the same WaveDetails instance with its similarity map updated
	 */
	public WaveDetails compute(WaveDetails details, Collection<String> targetIds) {
		score(details, targetIds.stream()
				.filter(id -> !details.getSimilarities().containsKey(id))
				.toList());
		return details;
	}

	/**
	 * Scores the given details against each of the given targets, whether or not
	 * a score is already stored, in batches of
	 * {@link WaveDetailsFactory#SIMILARITY_BATCH_SIZE}. Each score is stored in
	 * both items' maps, replacing any stored score. The details' own identifier
	 * and targets with no record are skipped.
	 *
	 * @param details   the details to compute similarities for
	 * @param targetIds identifiers of the items to compare against
	 * @return the computed scores, by target identifier
	 */
	private Map<String, Double> score(WaveDetails details, Collection<String> targetIds) {
		Map<String, Double> scores = new HashMap<>();

		try {
			List<String> targets = new ArrayList<>(targetIds);
			targets.remove(details.getIdentifier());

			int batchSize = WaveDetailsFactory.SIMILARITY_BATCH_SIZE;

			for (int start = 0; start < targets.size(); start += batchSize) {
				List<WaveDetails> batch = targets.subList(start, Math.min(start + batchSize, targets.size()))
						.stream().map(library::get).filter(Objects::nonNull).toList();
				if (batch.isEmpty()) continue;

				double[] similarities = library.getWaveDetailsFactory().batchSimilarity(details, batch);

				for (int i = 0; i < batch.size(); i++) {
					WaveDetails target = batch.get(i);
					double similarity = similarities[i];

					if (Math.abs(similarity - 1.0) < 1e-5) {
						warn("Identical features for distinct files");
					}

					scores.put(target.getIdentifier(), similarity);
					details.getSimilarities().put(target.getIdentifier(), similarity);

					if (details.getIdentifier() != null)
						target.getSimilarities().put(details.getIdentifier(), similarity);
				}
			}
		} catch (Exception e) {
			warn("Failed to load similarities for " + details.getIdentifier(), e);
		}

		return scores;
	}

	/**
	 * Returns similarity scores between the given details and the items most
	 * likely to be the most similar to it.
	 *
	 * <p>When the library's store has a nearest-neighbor index covering the
	 * library, the {@code candidates} nearest items by mean-pooled embedding are
	 * scored afresh. The scores are the same values the exhaustive computation
	 * reports; only the set of items scored is narrowed. Without a store, without
	 * features for the current model, or while the index is missing a
	 * substantial part of the library (less than {@link #NEIGHBOR_INDEX_COVERAGE}
	 * of the complete items), every item that is not excluded is scored, so no
	 * item is left out merely because it has not been indexed yet. Either way
	 * the scores are computed afresh rather than read from the stored similarity
	 * map, which may hold scores computed under an earlier definition.</p>
	 *
	 * <p>Excluded items are never scored, so they cost nothing to leave out;
	 * {@code candidates} counts the items that remain.</p>
	 *
	 * @param details    the details to find matches for
	 * @param candidates how many nearest items to score
	 * @param excluded   identifiers of items to leave out
	 * @return a map from item identifiers to similarity scores, covering the
	 *         scored items and no excluded item
	 */
	public Map<String, Double> nearest(WaveDetails details, int candidates, Set<String> excluded) {
		if (details == null) return Collections.emptyMap();

		List<String> targets = nearestIdentifiers(details, candidates, excluded);
		if (targets == null) {
			targets = library.getAllIdentifiers().stream()
					.filter(id -> !excluded.contains(id))
					.toList();
		}

		// Scored afresh, so a score stored under an earlier definition is not mixed in
		return score(details, targets);
	}

	/**
	 * Returns the identifiers of the complete items nearest the given details by
	 * mean-pooled embedding, or {@code null} when the nearest-neighbor index
	 * cannot answer for the whole library.
	 *
	 * @param details    the details to search around
	 * @param candidates how many items to return at most
	 * @param excluded   identifiers of items to leave out
	 * @return the nearest complete identifiers, excluding the details' own and
	 *         the excluded ones, or {@code null}
	 */
	private List<String> nearestIdentifiers(WaveDetails details, int candidates, Set<String> excluded) {
		WaveDetailsStore store = library.getStore();
		if (store == null || candidates <= 0) return null;

		Set<String> complete = library.getAllIdentifiers();
		if (store.indexedEmbeddingCount() < NEIGHBOR_INDEX_COVERAGE * complete.size()) return null;

		PackedCollection embedding = details.meanPooledEmbedding(library.currentModelId());
		if (embedding == null) return null;

		List<String> nearest = store.searchNeighbors(embedding, candidates + excluded.size() + 1).stream()
				.map(WaveDetailsStore.NeighborResult::identifier)
				.filter(id -> !Objects.equals(id, details.getIdentifier()))
				.filter(id -> !excluded.contains(id))
				.filter(complete::contains)
				.limit(candidates)
				.toList();
		return nearest.isEmpty() ? null : nearest;
	}
}
