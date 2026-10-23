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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.Map;

/**
 * One model's feature analysis of an audio sample, together with the model
 * identifier that produced it.
 *
 * <p>A {@link WaveDetails} can hold several of these, keyed by model identifier,
 * so that features produced by different feature models sit beside each other
 * rather than overwriting a single slot. Each instance bundles the four pieces
 * of feature metadata ({@linkplain #getFeatureSampleRate() sample rate},
 * {@linkplain #getFeatureChannelCount() channel count},
 * {@linkplain #getFeatureBinCount() bin count} and
 * {@linkplain #getFeatureFrameCount() frame count}), the feature
 * {@link PackedCollection} itself, and the similarity map derived from these
 * features — similarities computed from one model's features are therefore never
 * mixed with those from another.</p>
 *
 * <p>The similarity map is mutable and is populated incrementally as pairwise
 * similarities are computed; the remaining state is immutable for the lifetime of
 * the instance.</p>
 *
 * <p>The feature {@link PackedCollection} is potentially GPU-resident native
 * memory, so {@code WaveFeatures} is {@link Destroyable}: a holder that is
 * discarding a feature set permanently — the other-model cleanup, for one —
 * calls {@link #destroy()} to release that memory immediately rather than
 * leaving it for the garbage collector.</p>
 *
 * @see WaveDetails
 * @see WaveDataFeatureProvider#getModelIdentifier()
 */
public class WaveFeatures implements Destroyable {
	/** Identifier of the model that produced these features. */
	private final String modelIdentifier;

	/** Sample rate used during feature extraction. */
	private final double featureSampleRate;
	/** Number of channels in the feature data. */
	private final int featureChannelCount;
	/** Number of feature bins per feature frame. */
	private final int featureBinCount;
	/** Number of feature frames. */
	private final int featureFrameCount;

	/** Extracted feature data for similarity comparison, shaped [frames][bins]. */
	private final PackedCollection featureData;

	/** Similarity scores to other samples derived from these features, keyed by identifier. */
	private final Map<String, Double> similarities;

	/**
	 * Creates a feature set with an empty similarity map.
	 *
	 * @param modelIdentifier     the identifier of the producing model
	 * @param featureSampleRate   the feature extraction sample rate
	 * @param featureChannelCount the number of feature channels
	 * @param featureBinCount     the number of feature bins
	 * @param featureFrameCount   the number of feature frames
	 * @param featureData         the extracted feature data
	 */
	public WaveFeatures(String modelIdentifier, double featureSampleRate,
						int featureChannelCount, int featureBinCount,
						int featureFrameCount, PackedCollection featureData) {
		this(modelIdentifier, featureSampleRate, featureChannelCount, featureBinCount,
				featureFrameCount, featureData, new HashMap<>());
	}

	/**
	 * Creates a feature set with the given similarity map.
	 *
	 * @param modelIdentifier     the identifier of the producing model
	 * @param featureSampleRate   the feature extraction sample rate
	 * @param featureChannelCount the number of feature channels
	 * @param featureBinCount     the number of feature bins
	 * @param featureFrameCount   the number of feature frames
	 * @param featureData         the extracted feature data
	 * @param similarities        the similarity map to adopt (used directly, not copied)
	 */
	public WaveFeatures(String modelIdentifier, double featureSampleRate,
						int featureChannelCount, int featureBinCount,
						int featureFrameCount, PackedCollection featureData,
						Map<String, Double> similarities) {
		this.modelIdentifier = modelIdentifier;
		this.featureSampleRate = featureSampleRate;
		this.featureChannelCount = featureChannelCount;
		this.featureBinCount = featureBinCount;
		this.featureFrameCount = featureFrameCount;
		this.featureData = featureData;
		this.similarities = similarities;
	}

	/**
	 * Returns the identifier of the model that produced these features.
	 *
	 * @return the model identifier
	 */
	public String getModelIdentifier() { return modelIdentifier; }

	/**
	 * Returns the sample rate used during feature extraction.
	 *
	 * @return the feature extraction sample rate
	 */
	public double getFeatureSampleRate() { return featureSampleRate; }

	/**
	 * Returns the number of channels in the feature data.
	 *
	 * @return the feature channel count
	 */
	public int getFeatureChannelCount() { return featureChannelCount; }

	/**
	 * Returns the number of feature bins per feature frame.
	 *
	 * @return the feature bin count
	 */
	public int getFeatureBinCount() { return featureBinCount; }

	/**
	 * Returns the number of feature frames.
	 *
	 * @return the feature frame count
	 */
	public int getFeatureFrameCount() { return featureFrameCount; }

	/**
	 * Returns the extracted feature data.
	 *
	 * @return the feature tensor, or {@code null} if none was recorded
	 */
	public PackedCollection getFeatureData() { return featureData; }

	/**
	 * Returns the mutable similarity map derived from these features, keyed by
	 * the identifiers of other samples.
	 *
	 * @return the similarity map
	 */
	public Map<String, Double> getSimilarities() { return similarities; }

	/**
	 * Reports the size in bytes of the feature data this set carries. The
	 * accounting of how much memory a model's features occupy belongs here, on
	 * the type that owns the data, rather than in a helper that reaches into it.
	 *
	 * @return the number of bytes occupied by the feature data, or {@code 0} if
	 *         there is no feature data
	 */
	public long byteLength() {
		return featureData == null ? 0L : (long) featureData.getMemLength() * Double.BYTES;
	}

	/**
	 * Releases the native memory backing this feature set's feature data. The
	 * feature {@link PackedCollection} is potentially GPU-resident, and dropping
	 * the Java reference alone leaves that memory held until the collector runs a
	 * finalizer — the same reason {@link WaveDetails#releaseData()} destroys the
	 * raw samples rather than nulling them. Call this only when the feature set is
	 * being discarded permanently and is not shared with anything else; afterwards
	 * {@link #getFeatureData()} must not be read.
	 */
	@Override
	public void destroy() {
		if (featureData != null) featureData.destroy();
	}
}
