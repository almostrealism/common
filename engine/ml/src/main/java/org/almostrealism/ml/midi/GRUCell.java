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

import org.almostrealism.collect.PackedCollection;

/**
 * A single Gated Recurrent Unit (GRU) cell implementing the standard GRU equations.
 *
 * <p>The GRU cell computes:</p>
 * <pre>
 * r = sigmoid(W_ir @ x + b_ir + W_hr @ h + b_hr)     // reset gate
 * z = sigmoid(W_iz @ x + b_iz + W_hz @ h + b_hz)     // update gate
 * n = tanh(W_in @ x + b_in + r * (W_hn @ h + b_hn))  // new gate
 * h' = (1 - z) * n + z * h                            // new hidden state
 * </pre>
 *
 * <p>The weight matrices are stored in stacked form following PyTorch convention:</p>
 * <ul>
 *   <li>{@code weightIh} = [W_ir; W_iz; W_in] of shape (3 * hiddenSize, inputSize)</li>
 *   <li>{@code weightHh} = [W_hr; W_hz; W_hn] of shape (3 * hiddenSize, hiddenSize)</li>
 *   <li>{@code biasIh} = [b_ir; b_iz; b_in] of shape (3 * hiddenSize)</li>
 *   <li>{@code biasHh} = [b_hr; b_hz; b_hn] of shape (3 * hiddenSize)</li>
 * </ul>
 *
 * @see GRUDecoder
 */
public class GRUCell {
	private final int inputSize;
	private final int hiddenSize;

	private final PackedCollection weightIh;
	private final PackedCollection weightHh;
	private final PackedCollection biasIh;
	private final PackedCollection biasHh;

	/**
	 * Create a GRU cell with the given weights.
	 *
	 * @param inputSize dimension of the input vector
	 * @param hiddenSize dimension of the hidden state
	 * @param weightIh input-hidden weights of shape (3 * hiddenSize, inputSize)
	 * @param weightHh hidden-hidden weights of shape (3 * hiddenSize, hiddenSize)
	 * @param biasIh input-hidden bias of shape (3 * hiddenSize)
	 * @param biasHh hidden-hidden bias of shape (3 * hiddenSize)
	 */
	public GRUCell(int inputSize, int hiddenSize,
				   PackedCollection weightIh, PackedCollection weightHh,
				   PackedCollection biasIh, PackedCollection biasHh) {
		this.inputSize = inputSize;
		this.hiddenSize = hiddenSize;
		this.weightIh = weightIh;
		this.weightHh = weightHh;
		this.biasIh = biasIh;
		this.biasHh = biasHh;
	}

	/**
	 * Compute one GRU step.
	 *
	 * @param x input vector of size (inputSize)
	 * @param h previous hidden state of size (hiddenSize)
	 * @return new hidden state of size (hiddenSize)
	 */
	public PackedCollection forward(PackedCollection x, PackedCollection h) {
		// Compute stacked input-hidden gates: (3 * hiddenSize)
		double[] gatesIh = matmulBias(weightIh, 3 * hiddenSize, inputSize, x, biasIh);
		// Compute stacked hidden-hidden gates: (3 * hiddenSize)
		double[] gatesHh = matmulBias(weightHh, 3 * hiddenSize, hiddenSize, h, biasHh);

		PackedCollection hNew = new PackedCollection(hiddenSize);

		for (int i = 0; i < hiddenSize; i++) {
			// Reset gate: r = sigmoid(W_ir@x + b_ir + W_hr@h + b_hr)
			double r = sigmoid(gatesIh[i] + gatesHh[i]);

			// Update gate: z = sigmoid(W_iz@x + b_iz + W_hz@h + b_hz)
			double z = sigmoid(gatesIh[hiddenSize + i] + gatesHh[hiddenSize + i]);

			// New gate: n = tanh(W_in@x + b_in + r * (W_hn@h + b_hn))
			double n = Math.tanh(gatesIh[2 * hiddenSize + i] + r * gatesHh[2 * hiddenSize + i]);

			// Hidden state update: h' = (1 - z) * n + z * h
			hNew.setMem(i, (1.0 - z) * n + z * h.toDouble(i));
		}

		return hNew;
	}

	/**
	 * Returns the input dimension of this GRU cell.
	 */
	public int getInputSize() {
		return inputSize;
	}

	/**
	 * Returns the hidden state dimension of this GRU cell.
	 */
	public int getHiddenSize() {
		return hiddenSize;
	}

	/**
	 * Compute matrix-vector product plus bias: result = weight @ input + bias.
	 *
	 * @param weight weight matrix, row-major (rows, cols)
	 * @param rows number of rows in weight matrix
	 * @param cols number of columns in weight matrix
	 * @param input input vector of size (cols)
	 * @param bias bias vector of size (rows)
	 * @return result vector of size (rows)
	 */
	private static double[] matmulBias(PackedCollection weight, int rows, int cols,
									   PackedCollection input, PackedCollection bias) {
		double[] result = new double[rows];
		for (int i = 0; i < rows; i++) {
			double sum = bias.toDouble(i);
			int rowOffset = i * cols;
			for (int j = 0; j < cols; j++) {
				sum += weight.toDouble(rowOffset + j) * input.toDouble(j);
			}
			result[i] = sum;
		}
		return result;
	}

	private static double sigmoid(double x) {
		return 1.0 / (1.0 + Math.exp(-x));
	}
}
