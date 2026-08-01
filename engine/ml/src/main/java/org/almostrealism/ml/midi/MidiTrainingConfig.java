/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.midi;

/**
 * Training hyperparameters for the Moonbeam MIDI model.
 *
 * <p>Provides configuration for both full training and LoRA fine-tuning,
 * matching the reference Moonbeam implementation's defaults.</p>
 *
 * <h2>Default Configuration</h2>
 * <table>
 * <caption>Training Hyperparameters</caption>
 *   <tr><th>Parameter</th><th>Value</th></tr>
 *   <tr><td>Learning rate</td><td>3e-4</td></tr>
 *   <tr><td>Adam beta1</td><td>0.9</td></tr>
 *   <tr><td>Adam beta2</td><td>0.999</td></tr>
 *   <tr><td>Max sequence length</td><td>2048</td></tr>
 *   <tr><td>Batch size</td><td>4</td></tr>
 *   <tr><td>LoRA rank</td><td>8</td></tr>
 *   <tr><td>LoRA alpha</td><td>32.0</td></tr>
 * </table>
 *
 * @see MidiDataset
 * @see MoonbeamMidi
 */
public class MidiTrainingConfig {

	/** Learning rate for the Adam optimizer. */
	private final double learningRate;

	/** Adam beta1 (first moment decay). */
	private final double beta1;

	/** Adam beta2 (second moment decay). */
	private final double beta2;

	/** Maximum sequence length for training samples. */
	private final int maxSeqLen;

	/** Training batch size. */
	private final int batchSize;

	/** LoRA rank for fine-tuning. */
	private final int loraRank;

	/** LoRA alpha scaling factor. */
	private final double loraAlpha;

	/** Number of training epochs. */
	private final int epochs;

	/** Log frequency (log every N iterations). */
	private final int logFrequency;

	/**
	 * Create a training config with explicit parameters.
	 *
	 * @param learningRate Adam learning rate
	 * @param beta1        Adam beta1
	 * @param beta2        Adam beta2
	 * @param maxSeqLen    maximum sequence length
	 * @param batchSize    training batch size
	 * @param loraRank     LoRA rank
	 * @param loraAlpha    LoRA alpha
	 * @param epochs       number of training epochs
	 * @param logFrequency log every N iterations
	 */
	public MidiTrainingConfig(double learningRate, double beta1, double beta2,
							  int maxSeqLen, int batchSize,
							  int loraRank, double loraAlpha,
							  int epochs, int logFrequency) {
		this.learningRate = learningRate;
		this.beta1 = beta1;
		this.beta2 = beta2;
		this.maxSeqLen = maxSeqLen;
		this.batchSize = batchSize;
		this.loraRank = loraRank;
		this.loraAlpha = loraAlpha;
		this.epochs = epochs;
		this.logFrequency = logFrequency;
	}

	/**
	 * Factory method for the default training configuration matching the
	 * Moonbeam reference implementation.
	 *
	 * @return default training config
	 */
	public static MidiTrainingConfig defaultConfig() {
		return new MidiTrainingConfig(
				3e-4,    // learningRate
				0.9,     // beta1
				0.999,   // beta2
				2048,    // maxSeqLen
				4,       // batchSize
				8,       // loraRank
				32.0,    // loraAlpha
				10,      // epochs
				10       // logFrequency
		);
	}

	/**
	 * Factory method for a small test configuration suitable for unit tests.
	 *
	 * @return test training config
	 */
	public static MidiTrainingConfig testConfig() {
		return new MidiTrainingConfig(
				1e-3,    // learningRate (higher for fast convergence)
				0.9,     // beta1
				0.999,   // beta2
				32,      // maxSeqLen
				2,       // batchSize
				4,       // loraRank
				8.0,     // loraAlpha
				3,       // epochs
				1        // logFrequency
		);
	}

	/** Returns the learning rate. */
	public double getLearningRate() { return learningRate; }

	/** Returns Adam beta1. */
	public double getBeta1() { return beta1; }

	/** Returns Adam beta2. */
	public double getBeta2() { return beta2; }

	/** Returns the maximum sequence length. */
	public int getMaxSeqLen() { return maxSeqLen; }

	/** Returns the batch size. */
	public int getBatchSize() { return batchSize; }

	/** Returns the LoRA rank. */
	public int getLoraRank() { return loraRank; }

	/** Returns the LoRA alpha. */
	public double getLoraAlpha() { return loraAlpha; }

	/** Returns the number of epochs. */
	public int getEpochs() { return epochs; }

	/** Returns the log frequency. */
	public int getLogFrequency() { return logFrequency; }

	@Override
	public String toString() {
		return String.format(
				"MidiTrainingConfig{lr=%.1e, beta1=%.3f, beta2=%.4f, " +
						"maxSeq=%d, batch=%d, loraRank=%d, loraAlpha=%.1f, epochs=%d}",
				learningRate, beta1, beta2, maxSeqLen, batchSize,
				loraRank, loraAlpha, epochs);
	}
}
