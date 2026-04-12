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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;

import java.util.HashMap;
import java.util.Map;

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

	/** Sample rate used during feature extraction. */
	private double featureSampleRate;
	/** Number of channels in the feature data. */
	private int featureChannelCount;
	/** Number of feature bins per feature frame. */
	private int featureBinCount;
	/** Number of feature frames. */
	private int featureFrameCount;
	/** Extracted feature data for similarity comparison, shaped [frames][bins]. */
	private PackedCollection featureData;

	/** Similarity scores to other samples, keyed by their identifiers. */
	private Map<String, Double> similarities;

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
	 * Returns the sample rate used during feature extraction.
	 *
	 * @return the feature extraction sample rate
	 */
	public double getFeatureSampleRate() {
		return featureSampleRate;
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
	 * Returns the number of channels in the feature data.
	 *
	 * @return the feature channel count
	 */
	public int getFeatureChannelCount() {
		return featureChannelCount;
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
	 * Returns the number of feature bins per feature frame.
	 *
	 * @return the feature bin count
	 */
	public int getFeatureBinCount() {
		return featureBinCount;
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
	 * Returns the number of feature frames.
	 *
	 * @return the feature frame count
	 */
	public int getFeatureFrameCount() {
		return featureFrameCount;
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
	 * @return the latent feature tensor, or null if not yet computed
	 * @see #getFeatureData(boolean)
	 */
	public PackedCollection getFeatureData() {
		return featureData;
	}

	/**
	 * Sets the autoencoder latent features for this audio clip.
	 *
	 * @param featureData the autoencoder latent features
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
		if (transpose) {
			return featureData.transpose();
		} else {
			return featureData;
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
	 * Returns the similarity scores to other samples, keyed by their identifiers.
	 *
	 * @return a map from sample identifier to similarity score
	 */
	public Map<String, Double> getSimilarities() {
		return similarities;
	}

	/**
	 * Sets the similarity scores to other samples.
	 *
	 * @param similarities a map from sample identifier to similarity score
	 */
	public void setSimilarities(Map<String, Double> similarities) {
		this.similarities = similarities;
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
