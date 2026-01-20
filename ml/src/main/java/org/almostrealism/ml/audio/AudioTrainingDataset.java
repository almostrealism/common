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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.Dataset;

/**
 * Container for audio training and validation datasets.
 *
 * <p>This class holds the train/validation split created by
 * {@link AudioTrainingDataCollector} and provides convenient
 * access methods for both datasets.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AudioTrainingDataset dataset = collector.collect(audioFiles, config);
 *
 * // Access training data
 * Dataset<PackedCollection> trainSet = dataset.getTrainSet();
 * System.out.println("Training samples: " + dataset.getTrainSize());
 *
 * // Access validation data
 * Dataset<PackedCollection> validSet = dataset.getValidationSet();
 * System.out.println("Validation samples: " + dataset.getValidationSize());
 *
 * // Use with FineTuner
 * fineTuner.fineTune(trainSet, validSet);
 * }</pre>
 *
 * @see AudioTrainingDataCollector
 * @see Dataset
 * @author Michael Murray
 */
public class AudioTrainingDataset {

	private final Dataset<PackedCollection> trainSet;
	private final Dataset<PackedCollection> validationSet;
	private final int trainSize;
	private final int validationSize;

	/**
	 * Creates a new audio training dataset container.
	 *
	 * @param trainSet the training dataset
	 * @param validationSet the validation dataset
	 * @param trainSize the number of training samples
	 * @param validationSize the number of validation samples
	 */
	public AudioTrainingDataset(Dataset<PackedCollection> trainSet,
								Dataset<PackedCollection> validationSet,
								int trainSize,
								int validationSize) {
		this.trainSet = trainSet;
		this.validationSet = validationSet;
		this.trainSize = trainSize;
		this.validationSize = validationSize;
	}

	/**
	 * Returns the training dataset.
	 *
	 * @return the training dataset
	 */
	public Dataset<PackedCollection> getTrainSet() {
		return trainSet;
	}

	/**
	 * Returns the validation dataset.
	 *
	 * @return the validation dataset
	 */
	public Dataset<PackedCollection> getValidationSet() {
		return validationSet;
	}

	/**
	 * Returns the number of training samples.
	 *
	 * @return training sample count
	 */
	public int getTrainSize() {
		return trainSize;
	}

	/**
	 * Returns the number of validation samples.
	 *
	 * @return validation sample count
	 */
	public int getValidationSize() {
		return validationSize;
	}

	/**
	 * Returns the total number of samples (train + validation).
	 *
	 * @return total sample count
	 */
	public int getTotalSize() {
		return trainSize + validationSize;
	}

	/**
	 * Returns the actual train/validation split ratio.
	 *
	 * @return ratio of training samples to total samples
	 */
	public double getActualSplitRatio() {
		int total = getTotalSize();
		return total > 0 ? (double) trainSize / total : 0.0;
	}

	@Override
	public String toString() {
		return String.format("AudioTrainingDataset[train=%d, validation=%d, ratio=%.2f]",
				trainSize, validationSize, getActualSplitRatio());
	}
}
