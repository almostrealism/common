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

package org.almostrealism.optimize;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Result of a training run containing loss history and metrics.
 *
 * <p>This class provides access to training statistics, validation metrics,
 * and convergence information after a training session completes.</p>
 *
 * @see ModelOptimizer
 * @author Michael Murray
 */
public class TrainingResult {

	private final List<Double> trainLossHistory;
	private final List<Double> validationLossHistory;
	private final int totalSteps;
	private final int bestEpoch;
	private final double bestValidationLoss;
	private final Duration trainingTime;
	private final boolean earlyStopped;

	/**
	 * Creates a training result.
	 *
	 * @param trainLossHistory Training loss per epoch
	 * @param validationLossHistory Validation loss per epoch (may be empty)
	 * @param totalSteps Total number of training steps
	 * @param bestEpoch Epoch with best validation loss
	 * @param bestValidationLoss Best validation loss achieved
	 * @param trainingTime Total training duration
	 * @param earlyStopped Whether training stopped early
	 */
	public TrainingResult(List<Double> trainLossHistory,
						  List<Double> validationLossHistory,
						  int totalSteps,
						  int bestEpoch,
						  double bestValidationLoss,
						  Duration trainingTime,
						  boolean earlyStopped) {
		this.trainLossHistory = Collections.unmodifiableList(trainLossHistory);
		this.validationLossHistory = Collections.unmodifiableList(validationLossHistory);
		this.totalSteps = totalSteps;
		this.bestEpoch = bestEpoch;
		this.bestValidationLoss = bestValidationLoss;
		this.trainingTime = trainingTime;
		this.earlyStopped = earlyStopped;
	}

	/**
	 * Returns the training loss history (one value per epoch).
	 */
	public List<Double> getTrainLossHistory() {
		return trainLossHistory;
	}

	/**
	 * Returns the validation loss history (one value per epoch).
	 * May be empty if no validation data was provided.
	 */
	public List<Double> getValidationLossHistory() {
		return validationLossHistory;
	}

	/**
	 * Returns the total number of training steps (batches processed).
	 */
	public int getTotalSteps() {
		return totalSteps;
	}

	/**
	 * Returns the epoch with the best validation loss.
	 */
	public int getBestEpoch() {
		return bestEpoch;
	}

	/**
	 * Returns the best validation loss achieved.
	 */
	public double getBestValidationLoss() {
		return bestValidationLoss;
	}

	/**
	 * Returns the total training duration.
	 */
	public Duration getTrainingTime() {
		return trainingTime;
	}

	/**
	 * Returns whether training stopped early due to early stopping.
	 */
	public boolean isEarlyStopped() {
		return earlyStopped;
	}

	/**
	 * Returns the final training loss (last epoch).
	 */
	public double getFinalTrainLoss() {
		return trainLossHistory.isEmpty() ? Double.NaN : trainLossHistory.get(trainLossHistory.size() - 1);
	}

	/**
	 * Returns the final validation loss (last epoch).
	 */
	public double getFinalValidationLoss() {
		return validationLossHistory.isEmpty() ? Double.NaN : validationLossHistory.get(validationLossHistory.size() - 1);
	}

	/**
	 * Returns the number of epochs completed.
	 */
	public int getEpochsCompleted() {
		return trainLossHistory.size();
	}

	/**
	 * Returns whether training converged based on validation loss improvement.
	 * Returns true if:
	 * - Early stopping was triggered (validation loss stopped improving)
	 * - Training completed all epochs with validation loss decreasing
	 */
	public boolean converged() {
		if (earlyStopped) {
			return true;
		}

		if (validationLossHistory.size() < 2) {
			// Can't determine convergence without validation history
			return trainLossHistory.size() >= 2 &&
					trainLossHistory.get(trainLossHistory.size() - 1) <
							trainLossHistory.get(trainLossHistory.size() - 2);
		}

		// Check if validation loss was still decreasing at the end
		double lastLoss = validationLossHistory.get(validationLossHistory.size() - 1);
		double prevLoss = validationLossHistory.get(validationLossHistory.size() - 2);
		return lastLoss <= prevLoss;
	}

	/**
	 * Returns the improvement ratio (initial loss / final loss).
	 * Values greater than 1 indicate improvement.
	 */
	public double getImprovementRatio() {
		if (trainLossHistory.size() < 2) {
			return 1.0;
		}
		double initialLoss = trainLossHistory.get(0);
		double finalLoss = trainLossHistory.get(trainLossHistory.size() - 1);
		return finalLoss > 0 ? initialLoss / finalLoss : 1.0;
	}

	@Override
	public String toString() {
		return String.format(
				"TrainingResult{epochs=%d, steps=%d, finalTrainLoss=%.6f, bestValLoss=%.6f, time=%s, earlyStopped=%s}",
				getEpochsCompleted(), totalSteps, getFinalTrainLoss(), bestValidationLoss,
				formatDuration(trainingTime), earlyStopped
		);
	}

	private String formatDuration(Duration duration) {
		long hours = duration.toHours();
		long minutes = duration.toMinutesPart();
		long seconds = duration.toSecondsPart();

		if (hours > 0) {
			return String.format("%dh %dm %ds", hours, minutes, seconds);
		} else if (minutes > 0) {
			return String.format("%dm %ds", minutes, seconds);
		} else {
			return String.format("%ds", seconds);
		}
	}
}
