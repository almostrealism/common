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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;

/**
 * A single Gated Recurrent Unit (GRU) cell implementing the standard GRU equations
 * using hardware-accelerated Producer operations.
 *
 * <p>The GRU cell computes:</p>
 * <pre>
 * r = sigmoid(W_ir @ x + b_ir + W_hr @ h + b_hr)     // reset gate
 * z = sigmoid(W_iz @ x + b_iz + W_hz @ h + b_hz)     // update gate
 * n = tanh(W_in @ x + b_in + r * (W_hn @ h + b_hn))  // new gate
 * h' = (1 - z) * n + z * h                            // new hidden state
 * </pre>
 *
 * <p>All matrix and element-wise operations use the Producer pattern for
 * hardware-accelerated computation, enabling gradient flow for training
 * and eliminating the per-element JNI overhead of host-side loops.</p>
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
public class GRUCell implements LayerFeatures {
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
	 * Compute one GRU step using hardware-accelerated Producer operations.
	 *
	 * <p>Builds a computation graph for the GRU equations using matmul,
	 * sigmoid, tanh, and element-wise operations, then evaluates it on
	 * the hardware backend. This replaces host-side double[] loops with
	 * GPU/accelerated computation and preserves the autodiff chain for
	 * gradient flow during training.</p>
	 *
	 * @param x input vector of size (inputSize)
	 * @param h previous hidden state of size (hiddenSize)
	 * @return new hidden state of size (hiddenSize)
	 */
	public PackedCollection forward(PackedCollection x, PackedCollection h) {
		TraversalPolicy gateShape = shape(hiddenSize);

		// Stacked gate computations: matmul + bias
		// gatesIh = W_ih @ x + b_ih  (3*hiddenSize vector)
		// gatesHh = W_hh @ h + b_hh  (3*hiddenSize vector)
		CollectionProducer gatesIh = add(matmul(p(weightIh), p(x)), c(biasIh));
		CollectionProducer gatesHh = add(matmul(p(weightHh), p(h)), c(biasHh));

		// Subset into reset (r), update (z), and new (n) gate components
		CollectionProducer rIh = subset(gateShape, gatesIh, 0);
		CollectionProducer zIh = subset(gateShape, gatesIh, hiddenSize);
		CollectionProducer nIh = subset(gateShape, gatesIh, 2 * hiddenSize);

		CollectionProducer rHh = subset(gateShape, gatesHh, 0);
		CollectionProducer zHh = subset(gateShape, gatesHh, hiddenSize);
		CollectionProducer nHh = subset(gateShape, gatesHh, 2 * hiddenSize);

		// r = sigmoid(rIh + rHh), z = sigmoid(zIh + zHh)
		CollectionProducer r = sigmoid(add(rIh, rHh));
		CollectionProducer z = sigmoid(add(zIh, zHh));

		// n = tanh(nIh + r * nHh)
		CollectionProducer n = tanh(add(nIh, multiply(r, nHh)));

		// h' = (1 - z) * n + z * h  rewritten as  n + z * (h - n)
		CollectionProducer hNew = add(n, multiply(z, subtract(c(h), n)));

		return hNew.evaluate();
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
}
