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

package org.almostrealism.ml.t5gemma;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.SequenceAttentionFeatures;

import java.util.Arrays;
import java.util.Map;

/**
 * The T5Gemma encoder computed in double precision on the host, written from the published model
 * definition rather than from any framework layer, so that agreement with it means the compiled
 * encoder computes T5Gemma and not merely what another framework assembly computes. It reads the
 * weights in the layout {@link T5GemmaEncoder} reads (see {@link T5GemmaWeightFixture}).
 *
 * <p>Every position of the padded prompt is computed, padded ones included: the token embedding row
 * times the square root of the hidden width (the padding token, zero, beyond the prompt), then every
 * layer as {@code x + norm(attention(norm(x)))} followed by {@code x + norm(ffn(norm(x)))}, then a final
 * RMS norm. Every norm is an RMS norm with the configured epsilon. The attention projects the fused
 * queries, keys and values, rotates each head's queries and keys by their position (feature {@code i}
 * paired with feature {@code i + n}, {@code n} being the number of rotary frequencies), soft-caps the
 * scaled scores when the configured cap is positive, removes the padded keys, and sums the values by
 * the softmax weights; the feed-forward multiplies the linear half of its fused projection by the
 * tanh-approximated GELU of the gate half.</p>
 */
public class T5GemmaReferenceEncoder {

	/** The architecture. */
	private final T5GemmaConfig config;

	/** The weights, keyed as the encoder reads them. */
	private final Map<String, PackedCollection> weights;

	/**
	 * Creates a reference over the given weights.
	 *
	 * @param config  the architecture
	 * @param weights the weights, keyed as {@link T5GemmaEncoder} reads them
	 */
	public T5GemmaReferenceEncoder(T5GemmaConfig config, Map<String, PackedCollection> weights) {
		this.config = config;
		this.weights = weights;
	}

	/**
	 * The prompt mask of a prompt of the given length: one at each of its positions and zero at every
	 * later position up to the compiled length.
	 *
	 * @param promptLength the number of prompt tokens
	 * @return the mask, one value per compiled position
	 */
	public double[] promptMask(int promptLength) {
		double[] mask = new double[config.getMaxLength()];
		Arrays.fill(mask, 0, Math.min(promptLength, mask.length), 1.0);
		return mask;
	}

	/**
	 * Encodes a prompt: the scaled embeddings, every layer and the final norm.
	 *
	 * @param prompt the prompt's token ids, no longer than the compiled length
	 * @return the hidden states, one row per compiled position
	 */
	public double[][] encode(long[] prompt) {
		int hidden = config.getHiddenSize();
		double[] table = values("encoder.embed_tokens.weight");
		double[][] x = new double[config.getMaxLength()][hidden];
		for (int s = 0; s < x.length; s++) {
			int token = s < prompt.length ? (int) prompt[s] : 0;
			for (int f = 0; f < hidden; f++) {
				x[s][f] = table[token * hidden + f] * Math.sqrt(hidden);
			}
		}

		double[] mask = promptMask(prompt.length);
		for (int l = 0; l < config.getLayers(); l++) {
			x = layer(x, l, mask);
		}

		return rmsNorm(x, "encoder.norm.weight");
	}

	/**
	 * Computes one encoder layer: {@code x + norm(attention(norm(x)))}, then
	 * {@code x + norm(ffn(norm(x)))}.
	 *
	 * @param x     the hidden states, one row per position
	 * @param layer the layer index
	 * @param mask  the prompt mask, one value per position
	 * @return the layer output, one row per position
	 */
	public double[][] layer(double[][] x, int layer, double[] mask) {
		String prefix = "encoder.layers." + layer;
		double[][] attention = rmsNorm(attention(rmsNorm(x, prefix + ".pre_self_attn_layernorm.weight"), prefix, mask),
				prefix + ".post_self_attn_layernorm.weight");
		double[][] afterAttention = add(x, attention);
		double[][] feedForward = rmsNorm(feedForward(rmsNorm(afterAttention, prefix + ".pre_feedforward_layernorm.weight"), prefix),
				prefix + ".post_feedforward_layernorm.weight");
		return add(afterAttention, feedForward);
	}

	/**
	 * The self-attention of a layer, before its output norm: the fused projection, the rotation of the
	 * queries and keys, the soft-capped and key-masked scores, the softmax-weighted values of every head,
	 * and the output projection.
	 *
	 * @param x      the normalized hidden states
	 * @param prefix the layer's weight key prefix
	 * @param mask   the prompt mask
	 * @return the attention output, one row per position
	 */
	private double[][] attention(double[][] x, String prefix, double[] mask) {
		int seq = x.length;
		int dim = config.getHiddenSize();
		int headDim = dim / config.getHeads();
		double softcap = config.getAttentionSoftcap();
		double[][] qkv = linear(x, prefix + ".self_attn.to_qkv.weight", 3 * dim);

		double[][] context = new double[seq][dim];
		for (int h = 0; h < config.getHeads(); h++) {
			double[][] queries = new double[seq][];
			double[][] keys = new double[seq][];
			for (int s = 0; s < seq; s++) {
				queries[s] = rotate(Arrays.copyOfRange(qkv[s], h * headDim, (h + 1) * headDim), s);
				keys[s] = rotate(Arrays.copyOfRange(qkv[s], dim + h * headDim, dim + (h + 1) * headDim), s);
			}

			for (int i = 0; i < seq; i++) {
				double[] weightsOverKeys = new double[seq];
				double max = Double.NEGATIVE_INFINITY;
				for (int j = 0; j < seq; j++) {
					double score = 0.0;
					for (int d = 0; d < headDim; d++) {
						score += queries[i][d] * keys[j][d];
					}
					score /= Math.sqrt(headDim);
					if (softcap > 0.0) {
						score = softcap * Math.tanh(score / softcap);
					}
					weightsOverKeys[j] = score + (mask[j] - 1.0) * SequenceAttentionFeatures.MASKED_LOGIT_PENALTY;
					max = Math.max(max, weightsOverKeys[j]);
				}

				double total = 0.0;
				for (int j = 0; j < seq; j++) {
					weightsOverKeys[j] = Math.exp(weightsOverKeys[j] - max);
					total += weightsOverKeys[j];
				}

				for (int d = 0; d < headDim; d++) {
					double sum = 0.0;
					for (int j = 0; j < seq; j++) {
						sum += weightsOverKeys[j] / total * qkv[j][2 * dim + h * headDim + d];
					}
					context[i][h * headDim + d] = sum;
				}
			}
		}

		return linear(context, prefix + ".self_attn.o_proj.weight", dim);
	}

	/**
	 * The GELU-gated feed-forward of a layer, before its output norm: the fused projection, the linear
	 * half times the tanh-approximated GELU of the gate half, and the down projection.
	 *
	 * @param x      the normalized hidden states
	 * @param prefix the layer's weight key prefix
	 * @return the feed-forward output, one row per position
	 */
	private double[][] feedForward(double[][] x, String prefix) {
		int inner = config.getIntermediateSize();
		double[][] projected = linear(x, prefix + ".mlp.proj.weight", 2 * inner);
		double[][] gated = new double[x.length][inner];
		for (int s = 0; s < x.length; s++) {
			for (int i = 0; i < inner; i++) {
				double gate = projected[s][inner + i];
				double gelu = 0.5 * gate * (1.0 + Math.tanh(Math.sqrt(2.0 / Math.PI)
						* (gate + 0.044715 * gate * gate * gate)));
				gated[s][i] = projected[s][i] * gelu;
			}
		}

		return linear(gated, prefix + ".mlp.down_proj.weight", config.getHiddenSize());
	}

	/**
	 * Rotates one head's row by its position: feature {@code i} and feature {@code i + n} are rotated
	 * together by the angle {@code position * inv_freq[i]}; features beyond the first {@code 2 n} are
	 * left unchanged.
	 *
	 * @param row      the head's row
	 * @param position the row's position in the sequence
	 * @return the rotated row
	 */
	private double[] rotate(double[] row, int position) {
		double[] invFreq = values("encoder.rotary.inv_freq");
		int n = invFreq.length;
		double[] rotated = row.clone();
		for (int i = 0; i < n; i++) {
			double angle = position * invFreq[i];
			rotated[i] = row[i] * Math.cos(angle) - row[i + n] * Math.sin(angle);
			rotated[i + n] = row[i + n] * Math.cos(angle) + row[i] * Math.sin(angle);
		}
		return rotated;
	}

	/**
	 * Divides every row by its root mean square (with the configured epsilon added to the mean square)
	 * and scales it by the named norm weights.
	 *
	 * @param x   the rows
	 * @param key the norm weight key
	 * @return the normalized rows
	 */
	private double[][] rmsNorm(double[][] x, String key) {
		double[] scale = values(key);
		double[][] normalized = new double[x.length][];
		for (int s = 0; s < x.length; s++) {
			double meanSquare = 0.0;
			for (double v : x[s]) {
				meanSquare += v * v;
			}
			double factor = 1.0 / Math.sqrt(meanSquare / x[s].length + config.getNormEpsilon());
			normalized[s] = new double[x[s].length];
			for (int f = 0; f < x[s].length; f++) {
				normalized[s][f] = x[s][f] * factor * scale[f];
			}
		}
		return normalized;
	}

	/**
	 * Projects every row through the named {@code [outputs, inputs]} weight matrix.
	 *
	 * @param x       the rows
	 * @param key     the weight key
	 * @param outputs the number of outputs
	 * @return the projected rows
	 */
	private double[][] linear(double[][] x, String key, int outputs) {
		double[] matrix = values(key);
		int inputs = x[0].length;
		double[][] projected = new double[x.length][outputs];
		for (int s = 0; s < x.length; s++) {
			for (int o = 0; o < outputs; o++) {
				double sum = 0.0;
				for (int i = 0; i < inputs; i++) {
					sum += matrix[o * inputs + i] * x[s][i];
				}
				projected[s][o] = sum;
			}
		}
		return projected;
	}

	/**
	 * Adds two equally shaped sets of rows.
	 *
	 * @param a the first rows
	 * @param b the second rows
	 * @return the element-wise sums
	 */
	private static double[][] add(double[][] a, double[][] b) {
		double[][] sum = new double[a.length][];
		for (int s = 0; s < a.length; s++) {
			sum[s] = new double[a[s].length];
			for (int f = 0; f < a[s].length; f++) {
				sum[s][f] = a[s][f] + b[s][f];
			}
		}
		return sum;
	}

	/**
	 * The values of the named weight, in storage order.
	 *
	 * @param key the weight key
	 * @return the values
	 */
	private double[] values(String key) {
		PackedCollection weight = weights.get(key);
		return weight.toArray(0, weight.getShape().getTotalSize());
	}
}
