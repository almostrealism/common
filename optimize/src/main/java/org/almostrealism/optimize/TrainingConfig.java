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

import java.nio.file.Path;

/**
 * Configuration for model training.
 *
 * <p>This class follows the builder pattern for fluent configuration
 * of training hyperparameters and settings.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * TrainingConfig config = TrainingConfig.forAudioDiffusion()
 *     .epochs(10)
 *     .learningRate(1e-4)
 *     .batchSize(4)
 *     .checkpointDir(Path.of("checkpoints"));
 * }</pre>
 *
 * @see ModelOptimizer
 * @author Michael Murray
 */
public class TrainingConfig {

	private int epochs = 10;
	private int batchSize = 4;
	private double learningRate = 1e-4;
	private double beta1 = 0.9;
	private double beta2 = 0.999;
	private double weightDecay = 0.0;
	private double maxGradNorm = 1.0;
	private int warmupSteps = 0;
	private int saveEveryNSteps = 0;
	private int earlyStoppingPatience = 3;
	private double earlyStoppingMinDelta = 1e-6;
	private int logEveryNSteps = 10;
	private Path checkpointDir;

	/**
	 * Creates a TrainingConfig with default settings.
	 */
	public TrainingConfig() {
	}

	/**
	 * Sets the number of training epochs.
	 *
	 * @param epochs Number of epochs (default: 10)
	 * @return This config for chaining
	 */
	public TrainingConfig epochs(int epochs) {
		if (epochs < 1) {
			throw new IllegalArgumentException("Epochs must be at least 1");
		}
		this.epochs = epochs;
		return this;
	}

	/**
	 * Sets the batch size for training.
	 *
	 * @param batchSize Batch size (default: 4)
	 * @return This config for chaining
	 */
	public TrainingConfig batchSize(int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("Batch size must be at least 1");
		}
		this.batchSize = batchSize;
		return this;
	}

	/**
	 * Sets the learning rate for the optimizer.
	 *
	 * @param learningRate Learning rate (default: 1e-4)
	 * @return This config for chaining
	 */
	public TrainingConfig learningRate(double learningRate) {
		if (learningRate <= 0) {
			throw new IllegalArgumentException("Learning rate must be positive");
		}
		this.learningRate = learningRate;
		return this;
	}

	/**
	 * Sets the beta1 parameter for Adam optimizer.
	 *
	 * @param beta1 First moment decay rate (default: 0.9)
	 * @return This config for chaining
	 */
	public TrainingConfig beta1(double beta1) {
		if (beta1 <= 0 || beta1 >= 1) {
			throw new IllegalArgumentException("Beta1 must be in (0, 1)");
		}
		this.beta1 = beta1;
		return this;
	}

	/**
	 * Sets the beta2 parameter for Adam optimizer.
	 *
	 * @param beta2 Second moment decay rate (default: 0.999)
	 * @return This config for chaining
	 */
	public TrainingConfig beta2(double beta2) {
		if (beta2 <= 0 || beta2 >= 1) {
			throw new IllegalArgumentException("Beta2 must be in (0, 1)");
		}
		this.beta2 = beta2;
		return this;
	}

	/**
	 * Sets the weight decay for regularization.
	 *
	 * @param weightDecay Weight decay coefficient (default: 0.0)
	 * @return This config for chaining
	 */
	public TrainingConfig weightDecay(double weightDecay) {
		if (weightDecay < 0) {
			throw new IllegalArgumentException("Weight decay must be non-negative");
		}
		this.weightDecay = weightDecay;
		return this;
	}

	/**
	 * Sets the maximum gradient norm for gradient clipping.
	 *
	 * @param maxGradNorm Maximum gradient norm (default: 1.0, 0 to disable)
	 * @return This config for chaining
	 */
	public TrainingConfig maxGradNorm(double maxGradNorm) {
		if (maxGradNorm < 0) {
			throw new IllegalArgumentException("Max grad norm must be non-negative");
		}
		this.maxGradNorm = maxGradNorm;
		return this;
	}

	/**
	 * Sets the number of warmup steps for learning rate warmup.
	 *
	 * @param warmupSteps Number of warmup steps (default: 0)
	 * @return This config for chaining
	 */
	public TrainingConfig warmupSteps(int warmupSteps) {
		if (warmupSteps < 0) {
			throw new IllegalArgumentException("Warmup steps must be non-negative");
		}
		this.warmupSteps = warmupSteps;
		return this;
	}

	/**
	 * Sets the checkpoint save frequency.
	 *
	 * @param saveEveryNSteps Save every N steps (default: 0 = disabled)
	 * @return This config for chaining
	 */
	public TrainingConfig saveEveryNSteps(int saveEveryNSteps) {
		if (saveEveryNSteps < 0) {
			throw new IllegalArgumentException("Save frequency must be non-negative");
		}
		this.saveEveryNSteps = saveEveryNSteps;
		return this;
	}

	/**
	 * Sets the early stopping patience.
	 *
	 * @param earlyStoppingPatience Number of epochs without improvement (default: 3, 0 to disable)
	 * @return This config for chaining
	 */
	public TrainingConfig earlyStoppingPatience(int earlyStoppingPatience) {
		if (earlyStoppingPatience < 0) {
			throw new IllegalArgumentException("Patience must be non-negative");
		}
		this.earlyStoppingPatience = earlyStoppingPatience;
		return this;
	}

	/**
	 * Sets the minimum delta for early stopping improvement detection.
	 *
	 * @param earlyStoppingMinDelta Minimum improvement (default: 1e-6)
	 * @return This config for chaining
	 */
	public TrainingConfig earlyStoppingMinDelta(double earlyStoppingMinDelta) {
		if (earlyStoppingMinDelta < 0) {
			throw new IllegalArgumentException("Min delta must be non-negative");
		}
		this.earlyStoppingMinDelta = earlyStoppingMinDelta;
		return this;
	}

	/**
	 * Sets the logging frequency.
	 *
	 * @param logEveryNSteps Log every N steps (default: 10)
	 * @return This config for chaining
	 */
	public TrainingConfig logEveryNSteps(int logEveryNSteps) {
		if (logEveryNSteps < 1) {
			throw new IllegalArgumentException("Log frequency must be at least 1");
		}
		this.logEveryNSteps = logEveryNSteps;
		return this;
	}

	/**
	 * Sets the checkpoint directory.
	 *
	 * @param checkpointDir Directory for saving checkpoints
	 * @return This config for chaining
	 */
	public TrainingConfig checkpointDir(Path checkpointDir) {
		this.checkpointDir = checkpointDir;
		return this;
	}

	// Getters
	public int getEpochs() { return epochs; }
	public int getBatchSize() { return batchSize; }
	public double getLearningRate() { return learningRate; }
	public double getBeta1() { return beta1; }
	public double getBeta2() { return beta2; }
	public double getWeightDecay() { return weightDecay; }
	public double getMaxGradNorm() { return maxGradNorm; }
	public int getWarmupSteps() { return warmupSteps; }
	public int getSaveEveryNSteps() { return saveEveryNSteps; }
	public int getEarlyStoppingPatience() { return earlyStoppingPatience; }
	public double getEarlyStoppingMinDelta() { return earlyStoppingMinDelta; }
	public int getLogEveryNSteps() { return logEveryNSteps; }
	public Path getCheckpointDir() { return checkpointDir; }

	/**
	 * Creates a configuration optimized for audio diffusion training.
	 *
	 * @return Configuration with sensible defaults for audio diffusion
	 */
	public static TrainingConfig forAudioDiffusion() {
		return new TrainingConfig()
				.epochs(10)
				.batchSize(4)
				.learningRate(1e-4)
				.warmupSteps(100)
				.saveEveryNSteps(500)
				.earlyStoppingPatience(3);
	}

	/**
	 * Creates a configuration for quick testing/experimentation.
	 *
	 * @return Configuration with fast settings for testing
	 */
	public static TrainingConfig forTesting() {
		return new TrainingConfig()
				.epochs(3)
				.batchSize(2)
				.learningRate(1e-3)
				.warmupSteps(0)
				.saveEveryNSteps(0)
				.earlyStoppingPatience(0)
				.logEveryNSteps(1);
	}

	@Override
	public String toString() {
		return "TrainingConfig{" +
				"epochs=" + epochs +
				", batchSize=" + batchSize +
				", learningRate=" + learningRate +
				", beta1=" + beta1 +
				", beta2=" + beta2 +
				", warmupSteps=" + warmupSteps +
				", earlyStoppingPatience=" + earlyStoppingPatience +
				'}';
	}
}
