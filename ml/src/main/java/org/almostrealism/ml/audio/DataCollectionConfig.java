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

package org.almostrealism.ml.audio;

/**
 * Configuration for audio training data collection.
 *
 * <p>This class controls how audio files are preprocessed and segmented
 * for training. All settings can be customized using the builder pattern.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * DataCollectionConfig config = new DataCollectionConfig()
 *     .segmentLength(5.0)       // 5 second segments
 *     .minDuration(1.0)         // Skip files shorter than 1 second
 *     .trainSplitRatio(0.9)     // 90% train, 10% validation
 *     .augment(true)            // Enable augmentation
 *     .maxSamplesTotal(1000);   // Limit total samples
 * }</pre>
 *
 * @see AudioTrainingDataCollector
 * @author Michael Murray
 */
public class DataCollectionConfig {

	/** Minimum audio duration in seconds. Shorter files are skipped. */
	private double minDuration = 1.0;

	/** Target segment length in seconds for splitting long audio files. */
	private double segmentLength = 10.0;

	/** Ratio of samples to use for training (rest go to validation). */
	private double trainSplitRatio = 0.9;

	/** Whether to enable data augmentation. */
	private boolean augment = false;

	/** Whether to use time stretching augmentation. */
	private boolean timeStretch = false;

	/** Whether to add noise augmentation in latent space. */
	private boolean addNoise = false;

	/** Noise level for latent space augmentation. */
	private double noiseLevel = 0.01;

	/** Maximum total samples to collect (0 for unlimited). */
	private int maxSamplesTotal = 0;

	/** Target sample rate for audio normalization (0 to keep original). */
	private int targetSampleRate = 44100;

	/** Whether to normalize audio amplitude to [-1, 1]. */
	private boolean normalizeAmplitude = true;

	/** Whether to convert stereo to mono. */
	private boolean convertToMono = true;

	/**
	 * Sets the minimum audio duration in seconds.
	 * Files shorter than this are skipped during collection.
	 *
	 * @param minDuration minimum duration in seconds
	 * @return this config for chaining
	 */
	public DataCollectionConfig minDuration(double minDuration) {
		this.minDuration = minDuration;
		return this;
	}

	/**
	 * Sets the target segment length in seconds.
	 * Long audio files are split into segments of this length.
	 *
	 * @param segmentLength segment length in seconds
	 * @return this config for chaining
	 */
	public DataCollectionConfig segmentLength(double segmentLength) {
		this.segmentLength = segmentLength;
		return this;
	}

	/**
	 * Sets the train/validation split ratio.
	 * For example, 0.9 means 90% training and 10% validation.
	 *
	 * @param trainSplitRatio ratio between 0 and 1
	 * @return this config for chaining
	 */
	public DataCollectionConfig trainSplitRatio(double trainSplitRatio) {
		this.trainSplitRatio = trainSplitRatio;
		return this;
	}

	/**
	 * Enables or disables data augmentation.
	 *
	 * @param augment true to enable augmentation
	 * @return this config for chaining
	 */
	public DataCollectionConfig augment(boolean augment) {
		this.augment = augment;
		return this;
	}

	/**
	 * Enables or disables time stretching augmentation.
	 * Only effective if augmentation is enabled.
	 *
	 * @param timeStretch true to enable time stretching
	 * @return this config for chaining
	 */
	public DataCollectionConfig timeStretch(boolean timeStretch) {
		this.timeStretch = timeStretch;
		return this;
	}

	/**
	 * Enables or disables noise augmentation in latent space.
	 * Only effective if augmentation is enabled.
	 *
	 * @param addNoise true to enable noise augmentation
	 * @return this config for chaining
	 */
	public DataCollectionConfig addNoise(boolean addNoise) {
		this.addNoise = addNoise;
		return this;
	}

	/**
	 * Sets the noise level for latent space augmentation.
	 *
	 * @param noiseLevel standard deviation of Gaussian noise
	 * @return this config for chaining
	 */
	public DataCollectionConfig noiseLevel(double noiseLevel) {
		this.noiseLevel = noiseLevel;
		return this;
	}

	/**
	 * Sets the maximum total samples to collect.
	 * Use 0 for unlimited.
	 *
	 * @param maxSamplesTotal maximum samples, or 0 for unlimited
	 * @return this config for chaining
	 */
	public DataCollectionConfig maxSamplesTotal(int maxSamplesTotal) {
		this.maxSamplesTotal = maxSamplesTotal;
		return this;
	}

	/**
	 * Sets the target sample rate for audio normalization.
	 * Use 0 to keep the original sample rate.
	 *
	 * @param targetSampleRate sample rate in Hz, or 0 to keep original
	 * @return this config for chaining
	 */
	public DataCollectionConfig targetSampleRate(int targetSampleRate) {
		this.targetSampleRate = targetSampleRate;
		return this;
	}

	/**
	 * Enables or disables amplitude normalization.
	 * When enabled, audio is normalized to the range [-1, 1].
	 *
	 * @param normalizeAmplitude true to normalize amplitude
	 * @return this config for chaining
	 */
	public DataCollectionConfig normalizeAmplitude(boolean normalizeAmplitude) {
		this.normalizeAmplitude = normalizeAmplitude;
		return this;
	}

	/**
	 * Enables or disables stereo to mono conversion.
	 *
	 * @param convertToMono true to convert to mono
	 * @return this config for chaining
	 */
	public DataCollectionConfig convertToMono(boolean convertToMono) {
		this.convertToMono = convertToMono;
		return this;
	}

	// Getters

	public double getMinDuration() { return minDuration; }
	public double getSegmentLength() { return segmentLength; }
	public double getTrainSplitRatio() { return trainSplitRatio; }
	public boolean isAugment() { return augment; }
	public boolean isTimeStretch() { return timeStretch; }
	public boolean isAddNoise() { return addNoise; }
	public double getNoiseLevel() { return noiseLevel; }
	public int getMaxSamplesTotal() { return maxSamplesTotal; }
	public int getTargetSampleRate() { return targetSampleRate; }
	public boolean isNormalizeAmplitude() { return normalizeAmplitude; }
	public boolean isConvertToMono() { return convertToMono; }

	/**
	 * Creates a default configuration suitable for most use cases.
	 *
	 * @return a new default configuration
	 */
	public static DataCollectionConfig defaults() {
		return new DataCollectionConfig();
	}

	/**
	 * Creates a configuration optimized for quick testing with small datasets.
	 *
	 * @return a test configuration
	 */
	public static DataCollectionConfig forTesting() {
		return new DataCollectionConfig()
				.segmentLength(2.0)
				.minDuration(0.5)
				.maxSamplesTotal(100)
				.augment(false);
	}
}
