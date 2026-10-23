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

package org.almostrealism.audio.data;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Node;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Comprehensive metadata and analysis results for an audio sample.
 *
 * <p>WaveDetails stores both basic audio metadata (sample rate, channels, frame count)
 * and computed analysis data including FFT frequency bins and extracted features.
 * It also maintains similarity scores to other audio samples in a library.</p>
 *
 * <h2>Stored Information</h2>
 * <ul>
 *   <li><b>Basic metadata</b>: Sample rate, channel count, frame count, duration</li>
 *   <li><b>Frequency data</b>: FFT results with configurable bin count and time resolution</li>
 *   <li><b>Feature data</b>: Extracted features for similarity comparison</li>
 *   <li><b>Similarities</b>: Computed similarity scores to other samples</li>
 * </ul>
 *
 * <h2>Persistence</h2>
 * <p>WaveDetails can be marked as persistent to prevent cleanup when the associated
 * audio file is no longer present in the library's file tree.</p>
 *
 * @see WaveDetailsFactory
 * @see org.almostrealism.audio.AudioLibrary
 */
public class WaveDetails implements CodeFeatures, Node {
	/** When true, similarity scores are normalized to [0,1] range by the maximum possible difference. */
	public static boolean enableNormalizeSimilarity = false;

	/** Cache of compiled difference-magnitude evaluables, keyed by bin count. */
	private static final Map<Integer, Evaluable<PackedCollection>> differenceCalc = new HashMap<>();

	/** Unique identifier for this audio sample, typically a file path or URI. */
	private final String identifier;

	/** Sample rate of the audio data in Hz. */
	private int sampleRate;
	/** Number of audio channels. */
	private int channelCount;
	/** Number of audio frames. */
	private int frameCount;
	/** Raw audio sample data. */
	private PackedCollection data;
	/** True if this sample contains only silence. */
	private boolean silent;
	/** True if this entry should persist even when the source file is absent. */
	private boolean persistent;

	/** Sample rate used during frequency analysis. */
	private double freqSampleRate;
	/** Number of channels in the frequency data. */
	private int freqChannelCount;
	/** Number of frequency bins per FFT frame. */
	private int freqBinCount;
	/** Number of FFT frames in the frequency data. */
	private int freqFrameCount;
	/** FFT frequency data, shaped [channels][frames][bins]. */
	private PackedCollection freqData;

	/**
	 * Sample rate used during feature extraction, for the legacy unkeyed slot.
	 * See {@link #getFeatureData()} for how the legacy slot relates to the
	 * model-keyed feature sets.
	 */
	private double featureSampleRate;
	/** Number of channels in the legacy unkeyed feature data. */
	private int featureChannelCount;
	/** Number of feature bins per feature frame in the legacy unkeyed slot. */
	private int featureBinCount;
	/** Number of feature frames in the legacy unkeyed slot. */
	private int featureFrameCount;
	/** Legacy unkeyed feature data, shaped [frames][bins], with no model provenance. */
	private PackedCollection featureData;

	/** Legacy unkeyed similarity scores to other samples, keyed by their identifiers. */
	private Map<String, Double> similarities;

	/**
	 * Feature analyses keyed by the identifier of the model that produced them.
	 * This is the provenance-aware storage: features from a new model sit here
	 * beside features from an old one, each carrying the similarities derived
	 * from it, so switching models neither corrupts nor is blocked by existing
	 * data. The {@link #featureData}/{@code feature*}/{@link #similarities}
	 * fields above remain as the <em>legacy unkeyed slot</em> — the data an
	 * older application wrote with no provenance.
	 */
	private final Map<String, WaveFeatures> featureSets = new HashMap<>();

	/**
	 * Identifier of the model whose features the no-argument accessors read, set
	 * by the library when it resolves or computes this item for the configured
	 * provider. When {@code null}, the no-argument accessors read the single
	 * keyed feature set if exactly one is present, and otherwise fall back to the
	 * legacy unkeyed slot.
	 */
	private String activeModelIdentifier;

	/**
	 * Persistent sort rank for ordering this sample within the library tree.
	 * {@code null} when the sample has not yet been ranked. A {@link Double}
	 * (rather than a primitive) so a new item can be inserted between two
	 * existing ranks by averaging, without renumbering, and so that an
	 * unranked sample is distinguishable from one ranked at {@code 0.0}.
	 */
	private Double rank;

	/** Creates a WaveDetails with no identifier and unknown sample rate. */
	public WaveDetails() {
		this(null);
	}

	/**
	 * Creates a WaveDetails with the given identifier and unknown sample rate.
	 *
	 * @param identifier the unique identifier for this audio sample
	 */
	public WaveDetails(String identifier) {
		this(identifier, -1);
	}

	/**
	 * Creates a WaveDetails with the given identifier and sample rate.
	 *
	 * @param identifier the unique identifier for this audio sample
	 * @param sampleRate the sample rate in Hz, or -1 if unknown
	 */
	public WaveDetails(String identifier, int sampleRate) {
		this.identifier = identifier;
		this.sampleRate = sampleRate;
		this.similarities = new HashMap<>();
	}

	/**
	 * Returns the unique identifier for this audio sample.
	 *
	 * @return the identifier, typically a file path or URI
	 */
	public String getIdentifier() {
		return identifier;
	}

	/**
	 * Returns the sample rate of the audio data.
	 *
	 * @return the sample rate in Hz, or -1 if unknown
	 */
	public int getSampleRate() {
		return sampleRate;
	}

	/**
	 * Sets the sample rate of the audio data.
	 *
	 * @param sampleRate the sample rate in Hz
	 */
	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	/**
	 * Returns the number of audio channels.
	 *
	 * @return the channel count
	 */
	public int getChannelCount() {
		return channelCount;
	}

	/**
	 * Sets the number of audio channels.
	 *
	 * @param channelCount the channel count
	 */
	public void setChannelCount(int channelCount) {
		this.channelCount = channelCount;
	}

	/**
	 * Returns the number of audio frames.
	 *
	 * @return the frame count
	 */
	public int getFrameCount() {
		return frameCount;
	}

	/**
	 * Sets the number of audio frames.
	 *
	 * @param frameCount the frame count
	 */
	public void setFrameCount(int frameCount) {
		this.frameCount = frameCount;
	}

	/**
	 * Returns the raw audio sample data.
	 *
	 * @return the audio data as a PackedCollection
	 */
	public PackedCollection getData() {
		return data;
	}

	/**
	 * Sets the raw audio sample data.
	 *
	 * @param data the audio data
	 */
	public void setData(PackedCollection data) {
		this.data = data;
	}

	/**
	 * Releases the raw audio sample data, leaving the derived frequency and
	 * feature data intact.
	 *
	 * <p>Raw samples are by far the largest thing a WaveDetails carries, and
	 * unlike the frequency and feature data they are not derived from anything:
	 * the audio file they were read from is an equivalent copy. A WaveDetails
	 * that was loaded to be <em>read</em> — for display, for its metadata, for
	 * its analysis — can therefore drop them immediately and recover them from
	 * that file if they are ever wanted again.</p>
	 *
	 * <p>The memory is destroyed rather than left for the collector. A
	 * collection deserialized from a message commonly refers directly into that
	 * message rather than copying out of it, so dropping the reference alone
	 * keeps the whole parsed message alive until finalization runs.</p>
	 *
	 * <p>Call this only on a WaveDetails that is not shared with anything else.
	 * Afterwards {@link #getData()} is {@code null}, and any collection it
	 * previously returned can no longer be read.</p>
	 */
	public void releaseData() {
		if (data == null) return;

		data.destroy();
		data = null;
	}

	/**
	 * Returns whether this sample contains only silence.
	 *
	 * @return true if silent
	 */
	public boolean isSilent() { return silent; }

	/**
	 * Sets whether this sample contains only silence.
	 *
	 * @param silent true to mark as silent
	 */
	public void setSilent(boolean silent) { this.silent = silent; }

	/**
	 * Returns whether this entry should persist even when the source file is absent.
	 *
	 * @return true if persistent
	 */
	public boolean isPersistent() { return persistent; }

	/**
	 * Sets whether this entry should persist even when the source file is absent.
	 *
	 * @param persistent true to mark as persistent
	 */
	public void setPersistent(boolean persistent) {
		this.persistent = persistent;
	}

	/**
	 * Returns the sample rate used during frequency analysis.
	 *
	 * @return the frequency analysis sample rate
	 */
	public double getFreqSampleRate() {
		return freqSampleRate;
	}

	/**
	 * Sets the sample rate used during frequency analysis.
	 *
	 * @param freqSampleRate the frequency analysis sample rate
	 */
	public void setFreqSampleRate(double freqSampleRate) {
		this.freqSampleRate = freqSampleRate;
	}

	/**
	 * Returns the number of channels in the frequency data.
	 *
	 * @return the frequency channel count
	 */
	public int getFreqChannelCount() {
		return freqChannelCount;
	}

	/**
	 * Sets the number of channels in the frequency data.
	 *
	 * @param freqChannelCount the frequency channel count
	 */
	public void setFreqChannelCount(int freqChannelCount) {
		this.freqChannelCount = freqChannelCount;
	}

	/**
	 * Returns the number of frequency bins per FFT frame.
	 *
	 * @return the frequency bin count
	 */
	public int getFreqBinCount() {
		return freqBinCount;
	}

	/**
	 * Sets the number of frequency bins per FFT frame.
	 *
	 * @param freqBinCount the frequency bin count
	 */
	public void setFreqBinCount(int freqBinCount) {
		this.freqBinCount = freqBinCount;
	}

	/**
	 * Returns the number of FFT frames in the frequency data.
	 *
	 * @return the frequency frame count
	 */
	public int getFreqFrameCount() {
		return freqFrameCount;
	}

	/**
	 * Sets the number of FFT frames in the frequency data.
	 *
	 * @param freqFrameCount the frequency frame count
	 */
	public void setFreqFrameCount(int freqFrameCount) {
		this.freqFrameCount = freqFrameCount;
	}

	/**
	 * Returns the full FFT frequency data for all channels.
	 *
	 * @return the frequency data as a PackedCollection
	 */
	public PackedCollection getFreqData() {
		return freqData;
	}

	/**
	 * Returns the FFT frequency data for a single channel.
	 *
	 * @param channel the channel index
	 * @return the frequency data slice for the given channel, shaped [frames][bins]
	 */
	public PackedCollection getFreqData(int channel) {
		return getFreqData().get(channel, shape(getFreqFrameCount(), getFreqBinCount(), 1));
	}

	/**
	 * Sets the FFT frequency data for all channels.
	 *
	 * @param freqData the frequency data
	 */
	public void setFreqData(PackedCollection freqData) {
		this.freqData = freqData;
	}

	/**
	 * Returns the feature set the no-argument feature accessors read, or
	 * {@code null} to indicate they should read the legacy unkeyed slot.
	 *
	 * <p>Resolution: the active model's feature set when an active model
	 * identifier is set; otherwise the single keyed set when exactly one is
	 * present; otherwise {@code null} (legacy slot). An active identifier is only
	 * ever set to a model whose feature set is present, so this never mixes one
	 * model's feature data with another's.</p>
	 *
	 * @return the resolved feature set, or {@code null} for the legacy slot
	 */
	private WaveFeatures view() {
		if (activeModelIdentifier != null) {
			return featureSets.get(activeModelIdentifier);
		}
		if (featureSets.size() == 1) {
			return featureSets.values().iterator().next();
		}
		return null;
	}

	/**
	 * Returns the sample rate used during feature extraction, for the active
	 * model's features (falling back to the legacy unkeyed slot).
	 *
	 * @return the feature extraction sample rate
	 */
	public double getFeatureSampleRate() {
		WaveFeatures f = view();
		return f != null ? f.getFeatureSampleRate() : featureSampleRate;
	}

	/**
	 * Sets the sample rate used during feature extraction.
	 *
	 * @param featureSampleRate the feature extraction sample rate
	 */
	public void setFeatureSampleRate(double featureSampleRate) {
		this.featureSampleRate = featureSampleRate;
	}

	/**
	 * Returns the number of channels in the active model's feature data
	 * (falling back to the legacy unkeyed slot).
	 *
	 * @return the feature channel count
	 */
	public int getFeatureChannelCount() {
		WaveFeatures f = view();
		return f != null ? f.getFeatureChannelCount() : featureChannelCount;
	}

	/**
	 * Sets the number of channels in the feature data.
	 *
	 * @param featureChannelCount the feature channel count
	 */
	public void setFeatureChannelCount(int featureChannelCount) {
		this.featureChannelCount = featureChannelCount;
	}

	/**
	 * Returns the number of feature bins per feature frame for the active
	 * model's features (falling back to the legacy unkeyed slot).
	 *
	 * @return the feature bin count
	 */
	public int getFeatureBinCount() {
		WaveFeatures f = view();
		return f != null ? f.getFeatureBinCount() : featureBinCount;
	}

	/**
	 * Sets the number of feature bins per feature frame.
	 *
	 * @param featureBinCount the feature bin count
	 */
	public void setFeatureBinCount(int featureBinCount) {
		this.featureBinCount = featureBinCount;
	}

	/**
	 * Returns the number of feature frames for the active model's features
	 * (falling back to the legacy unkeyed slot).
	 *
	 * @return the feature frame count
	 */
	public int getFeatureFrameCount() {
		WaveFeatures f = view();
		return f != null ? f.getFeatureFrameCount() : featureFrameCount;
	}

	/**
	 * Sets the number of feature frames.
	 *
	 * @param featureFrameCount the feature frame count
	 */
	public void setFeatureFrameCount(int featureFrameCount) {
		this.featureFrameCount = featureFrameCount;
	}

	/**
	 * Returns the autoencoder latent features for this audio.
	 *
	 * <p>This is the output of {@code AutoEncoder.encode()} applied to the
	 * audio waveform. It serves dual purposes: similarity comparison between
	 * library samples (cosine similarity in latent space) and as conditioning
	 * input for the {@code AudioComposer} during diffusion-based generation.</p>
	 *
	 * <p>Computed by {@code AutoEncoderFeatureProvider} via the AudioLibrary
	 * job system. For entries with only frequency data (e.g., drawings),
	 * {@code WaveDetailsFactory} first synthesizes audio via IFFT before
	 * encoding.</p>
	 *
	 * <h2>Model provenance</h2>
	 * <p>Feature data is stored keyed by the identifier of the model that
	 * produced it (see {@link WaveFeatures}), so features from several models can
	 * coexist. This no-argument accessor returns the <em>active</em> model's
	 * feature data — the model the library last resolved or computed this item
	 * for. When no active model is set it returns the single keyed set if exactly
	 * one is present, and otherwise the legacy unkeyed slot. The legacy slot is
	 * reachable directly through {@link #getLegacyFeatureData()}.</p>
	 *
	 * @return the latent feature tensor, or null if not yet computed
	 * @see #getFeatureData(boolean)
	 * @see #getFeatures(String)
	 */
	public PackedCollection getFeatureData() {
		WaveFeatures f = view();
		return f != null ? f.getFeatureData() : featureData;
	}

	/**
	 * Sets the latent features in the legacy unkeyed slot.
	 *
	 * <p>This writes the unkeyed slot with no model provenance. Provenance-aware
	 * callers store a {@link WaveFeatures} via {@link #putFeatures(WaveFeatures)}
	 * instead; this setter is retained for the persistence layer (which decodes
	 * the legacy proto fields) and for callers that predate provenance.</p>
	 *
	 * @param featureData the latent features
	 */
	public void setFeatureData(PackedCollection featureData) {
		this.featureData = featureData;
	}

	/**
	 * Returns the autoencoder latent features, optionally transposed.
	 *
	 * <p>The transposed form is used when passing features to
	 * {@code AudioGenerator.addFeatures()} for composer interpolation.</p>
	 *
	 * @param transpose if true, returns the transposed feature tensor
	 * @return the feature tensor, or null if not yet computed
	 */
	public PackedCollection getFeatureData(boolean transpose) {
		PackedCollection data = getFeatureData();
		if (transpose) {
			return data.transpose();
		} else {
			return data;
		}
	}

	/**
	 * Returns the number of feature frames that correspond to actual audio content,
	 * clamped by the audio duration.
	 *
	 * @return the valid feature frame count (at most getFeatureFrameCount())
	 */
	public int getValidFeatureFrameCount() {
		double duration = (double) getFrameCount() / (double) getSampleRate();
		int limit = (int) Math.ceil(duration * getFeatureSampleRate());
		return Math.min(getFeatureFrameCount(), limit);
	}

	/**
	 * Returns the audio data as a WaveData instance.
	 *
	 * @return a WaveData wrapping this sample's data and sample rate
	 */
	public WaveData getWaveData() {
		return new WaveData(data, getSampleRate());
	}

	/**
	 * Returns the similarity scores to other samples for the active model's
	 * features, keyed by their identifiers.
	 *
	 * <p>Resolves the same way as {@link #getFeatureData()}: the active model's
	 * similarity map, else the single keyed set's map, else the legacy unkeyed
	 * map. Because the map returned is the one derived from a single model's
	 * features, a similarity is never computed or stored across models.</p>
	 *
	 * @return a map from sample identifier to similarity score
	 */
	public Map<String, Double> getSimilarities() {
		WaveFeatures f = view();
		return f != null ? f.getSimilarities() : similarities;
	}

	/**
	 * Sets the similarity scores in the legacy unkeyed slot.
	 *
	 * @param similarities a map from sample identifier to similarity score
	 */
	public void setSimilarities(Map<String, Double> similarities) {
		this.similarities = similarities;
	}

	/**
	 * Returns the feature analysis produced by the given model, or {@code null}
	 * if this item has no features for that model.
	 *
	 * @param modelIdentifier the identifier of the producing model
	 * @return the model's feature set, or {@code null}
	 */
	public WaveFeatures getFeatures(String modelIdentifier) {
		return featureSets.get(modelIdentifier);
	}

	/**
	 * Stores a model's feature analysis, keyed by its model identifier. Any
	 * existing feature set for the same model is replaced.
	 *
	 * @param features the feature set to store
	 */
	public void putFeatures(WaveFeatures features) {
		featureSets.put(features.getModelIdentifier(), features);
	}

	/**
	 * Returns the identifiers of every model that has produced feature data for
	 * this item. The legacy unkeyed slot is not included.
	 *
	 * @return the set of model identifiers present
	 */
	public Set<String> getFeatureModelIdentifiers() {
		return new HashSet<>(featureSets.keySet());
	}

	/**
	 * Returns the identifier of the active model — the one the no-argument
	 * feature accessors read — or {@code null} if none has been set.
	 *
	 * @return the active model identifier, or {@code null}
	 */
	public String getActiveModelIdentifier() {
		return activeModelIdentifier;
	}

	/**
	 * Sets the active model identifier. The no-argument feature accessors then
	 * read that model's features. It should only be set to a model whose features
	 * are present (see {@link #getFeatures(String)}); when set to a model whose
	 * features are absent, {@link #view()} resolves to {@code null} and the
	 * no-argument accessors fall back to the legacy unkeyed slot — never to
	 * another keyed model's data — so one model's features are never mistaken for
	 * another's.
	 *
	 * @param activeModelIdentifier the model identifier to activate, or {@code null}
	 */
	public void setActiveModelIdentifier(String activeModelIdentifier) {
		this.activeModelIdentifier = activeModelIdentifier;
	}

	/**
	 * Returns the legacy unkeyed feature data exactly as stored, bypassing the
	 * active-model resolution of {@link #getFeatureData()}. Used by the
	 * persistence layer, which preserves the legacy proto fields verbatim, and
	 * by the other-model cleanup accounting.
	 *
	 * @return the legacy unkeyed feature data, or {@code null}
	 */
	public PackedCollection getLegacyFeatureData() { return featureData; }

	/**
	 * Returns the legacy unkeyed feature sample rate, bypassing active-model
	 * resolution.
	 *
	 * @return the legacy feature sample rate
	 */
	public double getLegacyFeatureSampleRate() { return featureSampleRate; }

	/**
	 * Returns the legacy unkeyed feature channel count, bypassing active-model
	 * resolution.
	 *
	 * @return the legacy feature channel count
	 */
	public int getLegacyFeatureChannelCount() { return featureChannelCount; }

	/**
	 * Returns the legacy unkeyed feature bin count, bypassing active-model
	 * resolution.
	 *
	 * @return the legacy feature bin count
	 */
	public int getLegacyFeatureBinCount() { return featureBinCount; }

	/**
	 * Returns the legacy unkeyed feature frame count, bypassing active-model
	 * resolution.
	 *
	 * @return the legacy feature frame count
	 */
	public int getLegacyFeatureFrameCount() { return featureFrameCount; }

	/**
	 * Returns the legacy unkeyed similarity map exactly as stored, bypassing
	 * active-model resolution.
	 *
	 * @return the legacy unkeyed similarity map
	 */
	public Map<String, Double> getLegacySimilarities() { return similarities; }

	/**
	 * Returns whether this item holds any feature data belonging to a model
	 * other than the given one — that is, a keyed feature set for a different
	 * model, or non-empty legacy unkeyed data.
	 *
	 * @param keepModelIdentifier the identifier of the model to keep
	 * @return true if other-model data is present
	 */
	public boolean hasOtherModelFeatures(String keepModelIdentifier) {
		if (featureData != null) return true;
		for (String id : featureSets.keySet()) {
			if (!id.equals(keepModelIdentifier)) return true;
		}
		return false;
	}

	/**
	 * Returns the number of bytes of feature data this item holds for models
	 * other than the given one, including the legacy unkeyed slot.
	 *
	 * @param keepModelIdentifier the identifier of the model to keep
	 * @return the total other-model feature data size in bytes
	 */
	public long otherModelByteLength(String keepModelIdentifier) {
		long total = featureData == null ? 0L
				: (long) featureData.getMemLength() * Double.BYTES;
		for (Map.Entry<String, WaveFeatures> e : featureSets.entrySet()) {
			if (!e.getKey().equals(keepModelIdentifier)) {
				total += e.getValue().byteLength();
			}
		}
		return total;
	}

	/**
	 * Removes every feature set belonging to a model other than the given one
	 * and clears the legacy unkeyed slot, leaving only the kept model's data.
	 *
	 * @param keepModelIdentifier the identifier of the model to keep
	 */
	public void removeOtherModelFeatures(String keepModelIdentifier) {
		featureSets.keySet().removeIf(id -> !id.equals(keepModelIdentifier));

		featureData = null;
		featureSampleRate = 0;
		featureChannelCount = 0;
		featureBinCount = 0;
		featureFrameCount = 0;
		similarities = new HashMap<>();

		if (activeModelIdentifier != null && !featureSets.containsKey(activeModelIdentifier)) {
			activeModelIdentifier = null;
		}
	}

	/**
	 * Computes a mean-pooled embedding vector from the active model's feature
	 * data, averaging across feature frames. Returns {@code null} when there is
	 * no feature data to pool.
	 *
	 * <p>This reads this item's own feature data, so it is a method of
	 * {@link WaveDetails} rather than of a caller that would otherwise reach in
	 * for the feature tensor. The embedding uses only the active model's
	 * features, so an index built from these embeddings never mixes models.</p>
	 *
	 * @return the mean-pooled embedding vector, or {@code null}
	 */
	public PackedCollection meanPooledEmbedding() {
		PackedCollection featureData = getFeatureData();
		if (featureData == null) return null;

		int frames = featureData.getShape().length(0);
		int bins = featureData.getShape().length(1);
		if (frames == 0 || bins == 0) return null;

		CollectionProducer sum = null;

		for (int f = 0; f < frames; f++) {
			if ((f + 1) * bins > featureData.getMemLength()) break;

			CollectionProducer row = Ops.o().cp(
					featureData.range(Ops.o().shape(bins), f * bins));
			sum = sum == null ? row : sum.add(row);
		}

		if (sum == null) return null;

		return sum.multiply(1.0 / frames).evaluate();
	}

	/**
	 * Returns whether this sample has a persistent sort rank assigned.
	 *
	 * @return true if a rank has been set
	 */
	public boolean hasRank() {
		return rank != null;
	}

	/**
	 * Returns the persistent sort rank for this sample, or {@code null} if it
	 * has not yet been ranked. Unranked samples sort to the end of the library
	 * tree.
	 *
	 * @return the rank, or null if unranked
	 */
	public Double getRank() {
		return rank;
	}

	/**
	 * Sets the persistent sort rank for this sample. Pass {@code null} to mark
	 * the sample as unranked.
	 *
	 * @param rank the rank, or null to clear it
	 */
	public void setRank(Double rank) {
		this.rank = rank;
	}

	/**
	 * Returns the number of frames in the given PackedCollection (first dimension).
	 *
	 * @param data the collection to inspect
	 * @return the frame count
	 */
	private static int frameCount(PackedCollection data) {
		return data.getShape().length(0);
	}

	/**
	 * Returns the number of bins in the given PackedCollection (second dimension).
	 *
	 * @param data the collection to inspect
	 * @return the bin count
	 */
	private static int binCount(PackedCollection data) {
		return data.getShape().length(1);
	}

	/**
	 * Returns a compiled evaluable that computes the magnitude of the difference between
	 * two frequency data slices with the given bin count.
	 *
	 * @param bins the number of frequency bins
	 * @return an Evaluable that computes difference magnitude
	 */
	private static Evaluable<PackedCollection> differenceMagnitude(int bins) {
		return differenceCalc.computeIfAbsent(bins, key -> Ops.op(o ->
						o.cv(new TraversalPolicy(bins, 1), 0)
				.subtract(o.cv(new TraversalPolicy(bins, 1), 1))
				.traverseEach()
				.magnitude()).get());
	}

	/**
	 * Computes a similarity distance between two frequency or feature data collections.
	 *
	 * <p>The returned value is the sum of absolute differences across all aligned frames
	 * and bins, plus contributions from any unmatched frames. Lower values indicate
	 * greater similarity. If {@link #enableNormalizeSimilarity} is true, the result
	 * is normalized by the maximum possible difference magnitude.</p>
	 *
	 * @param a the first collection (2D or 3D: frames x bins x 1)
	 * @param b the second collection (2D or 3D: frames x bins x 1)
	 * @return the similarity distance (lower = more similar)
	 * @throws IllegalArgumentException if the dimensions do not match the expected shape
	 */
	public static double differenceSimilarity(PackedCollection a, PackedCollection b) {
		if (a.getShape().getDimensions() != 2 && a.getShape().getDimensions() != 3) {
			throw new IllegalArgumentException();
		} else if (a.getShape().getDimensions() != b.getShape().getDimensions()) {
			throw new IllegalArgumentException();
		}

		int bins = 0;
		int n;

		if (binCount(a) == binCount(b)) {
			n = Math.min(frameCount(a), frameCount(b));
			bins = binCount(a);
		} else {
			// Feature data with different shapes are not easily comparable
			n = 0;
		}

		TraversalPolicy overlap = new TraversalPolicy(true, n, bins, 1);

		double d = 0.0;

		if (n > 0) {
			PackedCollection featuresA = a.range(overlap).traverse(1);
			PackedCollection featuresB = b.range(overlap).traverse(1);
			PackedCollection diff = differenceMagnitude(bins)
					.evaluate(featuresA, featuresB);
			d += diff.doubleStream().sum();
		}

		if (frameCount(a) > n) {
			d += a.range(new TraversalPolicy(frameCount(a) - n, binCount(a), 1), overlap.getTotalSize())
					.doubleStream().map(Math::abs).sum();
		}

		if (frameCount(b) > n) {
			d += b.range(new TraversalPolicy(frameCount(b) - n, binCount(b), 1), overlap.getTotalSize())
					.doubleStream().map(Math::abs).sum();
		}

		if (enableNormalizeSimilarity) {
			double max = Math.max(
					frameCount(a) <= 0 ? 0.0 : a.doubleStream().map(Math::abs).max().orElse(0.0),
					frameCount(b) <= 0 ? 0.0 : b.doubleStream().map(Math::abs).max().orElse(0.0));
			max = max * bins * Math.max(frameCount(a), frameCount(b));
			double r = max == 0 ? Double.MAX_VALUE : (d / max);

			if (r > 1.0 && max != 0) {
				Console.root().features(WaveDetails.class).warn("Similarity = " + r);
			}

			return r;
		} else {
			return d;
		}
	}
}
