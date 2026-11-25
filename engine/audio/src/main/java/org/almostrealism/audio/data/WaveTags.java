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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One tagging model's labels for an audio sample, together with the
 * provenance — model identifier and vocabulary version — that produced them.
 *
 * <p>A {@link WaveDetails} can hold several of these, keyed by
 * {@link TagModelVersion}, so that tags from different models, or from
 * different revisions of the same model's vocabulary, sit beside each other
 * rather than overwriting one another. Stale tag sets are reported and deleted
 * deliberately by {@link org.almostrealism.audio.WaveFeatureCleanup}.</p>
 *
 * <p>The payload maps each tag label to a score. A tagger that only reports
 * which labels apply records a score of {@code 1.0} for each present label.
 * The provenance is immutable; a tagger populates the scores incrementally
 * through {@link #putTag(String, Double)}, which rejects a {@code null} label or
 * score so that every tag set can be persisted.</p>
 *
 * <p>Unlike {@link WaveFeatures}, a tag set holds no native memory, so it is
 * not {@link io.almostrealism.lifecycle.Destroyable}.</p>
 *
 * @see WaveDetails#putTags(WaveTags)
 * @see TagModelVersion
 */
public class WaveTags {
	/** Identifier of the tagging model and version of the vocabulary that produced these tags. */
	private final TagModelVersion key;

	/** Tag scores keyed by tag label. */
	private final Map<String, Double> tags;

	/**
	 * Creates a tag set with an empty tag map.
	 *
	 * @param key the tagging model and vocabulary version that produce the tags
	 */
	public WaveTags(TagModelVersion key) {
		this(key, Map.of());
	}

	/**
	 * Creates a tag set holding a copy of the given tag scores.
	 *
	 * @param key  the tagging model and vocabulary version that produced the tags
	 * @param tags the tag scores keyed by label
	 * @throws NullPointerException if the key, the map, a label or a score is {@code null}
	 */
	public WaveTags(TagModelVersion key, Map<String, Double> tags) {
		this.key = Objects.requireNonNull(key, "key");
		this.tags = new HashMap<>();
		Objects.requireNonNull(tags, "tags").forEach(this::putTag);
	}

	/**
	 * Returns the (model, vocabulary-version) key under which this tag set is stored.
	 *
	 * @return the provenance key
	 */
	public TagModelVersion getKey() { return key; }

	/**
	 * Returns the identifier of the tagging model that produced these tags.
	 *
	 * @return the model identifier
	 */
	public String getModelIdentifier() { return key.modelIdentifier(); }

	/**
	 * Returns the version of the vocabulary these tags were drawn from.
	 *
	 * @return the vocabulary version
	 */
	public String getVocabularyVersion() { return key.vocabularyVersion(); }

	/**
	 * Records the score of a tag label, replacing any earlier score for it.
	 *
	 * @param label the tag label
	 * @param score the score of the label
	 * @throws NullPointerException if the label or the score is {@code null}
	 */
	public void putTag(String label, Double score) {
		tags.put(Objects.requireNonNull(label, "label"), Objects.requireNonNull(score, "score"));
	}

	/**
	 * Returns a read-only view of the map from tag label to score. Scores are
	 * added through {@link #putTag(String, Double)}, which rejects {@code null}.
	 *
	 * @return the tag map
	 */
	public Map<String, Double> getTags() { return Collections.unmodifiableMap(tags); }

	/**
	 * Returns the number of tags in this set.
	 *
	 * @return the tag count
	 */
	public int tagCount() { return tags.size(); }
}
