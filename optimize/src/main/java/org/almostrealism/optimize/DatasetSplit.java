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

package org.almostrealism.optimize;

import org.almostrealism.hardware.MemoryData;

/**
 * Container for train/validation dataset split.
 *
 * <p>This is a generic container that holds training and validation datasets
 * along with their sizes. It is domain-agnostic and works for any type of data
 * (images, audio, text, tensors, etc.).</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * DatasetSplit<PackedCollection> split = collector.collect(data, config);
 *
 * // Access training data
 * Dataset<PackedCollection> trainSet = split.getTrainSet();
 * System.out.println("Training samples: " + split.getTrainSize());
 *
 * // Access validation data
 * Dataset<PackedCollection> validSet = split.getValidationSet();
 * System.out.println("Validation samples: " + split.getValidationSize());
 *
 * // Use with optimizer
 * optimizer.optimize(trainSet, validSet);
 * }</pre>
 *
 * @param <T> the type of elements in the datasets
 * @see Dataset
 * @author Michael Murray
 */
public class DatasetSplit<T extends MemoryData> {

	private final Dataset<T> trainSet;
	private final Dataset<T> validationSet;
	private final int trainSize;
	private final int validationSize;

	/**
	 * Creates a new dataset split container.
	 *
	 * @param trainSet the training dataset
	 * @param validationSet the validation dataset
	 * @param trainSize the number of training samples
	 * @param validationSize the number of validation samples
	 */
	public DatasetSplit(Dataset<T> trainSet,
						Dataset<T> validationSet,
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
	public Dataset<T> getTrainSet() {
		return trainSet;
	}

	/**
	 * Returns the validation dataset.
	 *
	 * @return the validation dataset
	 */
	public Dataset<T> getValidationSet() {
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
		return String.format("DatasetSplit[train=%d, validation=%d, ratio=%.2f]",
				trainSize, validationSize, getActualSplitRatio());
	}
}
