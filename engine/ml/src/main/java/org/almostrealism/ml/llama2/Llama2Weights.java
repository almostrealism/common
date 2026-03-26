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

package org.almostrealism.ml.llama2;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

import java.nio.FloatBuffer;

/**
 * Weight tensors for a Llama2 model, loaded from a binary checkpoint.
 *
 * <p>Weights are read sequentially from the checkpoint file's float buffer
 * in the order defined by the original llama2.c format. All tensors are
 * stored as {@link PackedCollection} instances for use with the AR compute
 * pipeline.</p>
 *
 * @author Michael Murray
 */
public class Llama2Weights implements CodeFeatures {
	/** Token embedding table (vocab_size, dim). */
	public final PackedCollection tokenEmbeddings;

	/** RMS norm weights for attention layers (layer, dim). */
	public final PackedCollection rmsAttWeights;

	/** Query projection weights (layer, dim, dim). */
	public final PackedCollection wq;

	/** Key projection weights (layer, dim, dim). */
	public final PackedCollection wk;

	/** Value projection weights (layer, dim, dim). */
	public final PackedCollection wv;

	/** Output projection weights (layer, dim, dim). */
	public final PackedCollection wo;

	/** RMS norm weights for FFN layers (layer, dim). */
	public final PackedCollection rmsFfn;

	/** FFN gate weights (layer, hidden_dim, dim). */
	public final PackedCollection w1;

	/** FFN down-projection weights (layer, dim, hidden_dim). */
	public final PackedCollection w2;

	/** FFN up-projection weights (layer, hidden_dim, dim). */
	public final PackedCollection w3;

	/** Final RMS norm weight (dim). */
	public final PackedCollection rmsFinalWeight;

	/** RoPE frequency components (seq_len, head_size/2, 2). */
	public final PackedCollection freqCis;

	/** Classifier weights for logits (may alias tokenEmbeddings). */
	public final PackedCollection wcls;

	/**
	 * Reads all weight tensors from the checkpoint buffer.
	 *
	 * @param config the model configuration (defines tensor shapes)
	 * @param buffer the float buffer positioned after the header
	 */
	public Llama2Weights(Llama2Config config, FloatBuffer buffer) {
		this.tokenEmbeddings =
				pack(take(buffer, config.vocabSize, config.dim))
				.reshape(shape(config.vocabSize, config.dim));

		this.rmsAttWeights =
				pack(take(buffer, config.layerCount, config.dim))
				.reshape(shape(config.layerCount, config.dim));

		this.wq = pack(take(buffer, config.layerCount, config.dim, config.dim))
				.reshape(shape(config.layerCount, config.dim, config.dim));
		this.wk = pack(take(buffer, config.layerCount, config.dim, config.dim))
				.reshape(shape(config.layerCount, config.dim, config.dim));
		this.wv = pack(take(buffer, config.layerCount, config.dim, config.dim))
				.reshape(shape(config.layerCount, config.dim, config.dim));
		this.wo = pack(take(buffer, config.layerCount, config.dim, config.dim))
				.reshape(shape(config.layerCount, config.dim, config.dim));

		this.rmsFfn = pack(take(buffer, config.layerCount, config.dim))
				.reshape(shape(config.layerCount, config.dim));

		this.w1 = pack(take(buffer, config.layerCount, config.hiddenDim, config.dim))
				.reshape(shape(config.layerCount, config.hiddenDim, config.dim));
		this.w2 = pack(take(buffer, config.layerCount, config.dim, config.hiddenDim))
				.reshape(shape(config.layerCount, config.dim, config.hiddenDim));
		this.w3 = pack(take(buffer, config.layerCount, config.hiddenDim, config.dim))
				.reshape(shape(config.layerCount, config.hiddenDim, config.dim));

		this.rmsFinalWeight =
				pack(take(buffer, config.dim))
				.reshape(shape(config.dim));

		this.freqCis = packComplex(
				take(buffer, config.seqLen, config.headSize / 2),
				take(buffer, config.seqLen, config.headSize / 2),
				shape(config.seqLen, config.headSize / 2, 2));

		this.wcls = config.sharedWeights ? tokenEmbeddings
				: pack(take(buffer, config.vocabSize, config.dim))
						.reshape(shape(config.vocabSize, config.dim));
	}

	static float[] take(FloatBuffer buffer, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		float[] floats = new float[shape.getTotalSize()];
		buffer.get(floats);
		return floats;
	}

	static PackedCollection packComplex(float[] real, float[] imag, TraversalPolicy shape) {
		if (shape.length(shape.getDimensions() - 1) != 2)
			throw new IllegalArgumentException();

		double[] data = new double[shape.getTotalSize()];
		for (int i = 0; i < data.length; i += 2) {
			data[i] = real[i / 2];
			data[i + 1] = imag[i / 2];
		}

		PackedCollection c = new PackedCollection(shape);
		c.setMem(0, data, 0, shape.getTotalSize());
		return c;
	}
}
