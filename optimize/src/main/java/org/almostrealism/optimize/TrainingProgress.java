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

/**
 * Progress update during training.
 *
 * <p>This class is passed to progress callbacks to report training status
 * at the end of each epoch.</p>
 *
 * @see ModelOptimizer#setProgressCallback
 * @author Michael Murray
 */
public class TrainingProgress {

	private final int epoch;
	private final int step;
	private final double trainLoss;
	private final Double validationLoss;

	/**
	 * Creates a training progress update.
	 *
	 * @param epoch Current epoch number (0-indexed)
	 * @param step Total training steps so far
	 * @param trainLoss Average training loss for this epoch
	 * @param validationLoss Validation loss for this epoch (null if no validation)
	 */
	public TrainingProgress(int epoch, int step, double trainLoss, Double validationLoss) {
		this.epoch = epoch;
		this.step = step;
		this.trainLoss = trainLoss;
		this.validationLoss = validationLoss;
	}

	/**
	 * Returns the current epoch number (0-indexed).
	 */
	public int getEpoch() {
		return epoch;
	}

	/**
	 * Returns the total training steps completed so far.
	 */
	public int getStep() {
		return step;
	}

	/**
	 * Returns the average training loss for this epoch.
	 */
	public double getTrainLoss() {
		return trainLoss;
	}

	/**
	 * Returns the validation loss for this epoch, or null if no validation data.
	 */
	public Double getValidationLoss() {
		return validationLoss;
	}

	@Override
	public String toString() {
		if (validationLoss != null) {
			return String.format("Epoch %d - train_loss: %.6f - val_loss: %.6f",
					epoch + 1, trainLoss, validationLoss);
		} else {
			return String.format("Epoch %d - train_loss: %.6f", epoch + 1, trainLoss);
		}
	}
}
