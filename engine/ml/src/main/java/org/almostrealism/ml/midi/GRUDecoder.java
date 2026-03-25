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

import java.util.Random;

/**
 * GRU-based output decoder for compound MIDI token generation.
 *
 * <p>Moonbeam does not use a standard lm_head linear projection. Instead, it uses
 * a multi-layer GRU that autoregressively generates 7 output tokens per position
 * from the transformer hidden state.</p>
 *
 * <h2>Decode Flow</h2>
 * <ol>
 *   <li>Summary projection: hidden_state (hiddenSize) -&gt; (decoderHiddenSize)</li>
 *   <li>Initialize all GRU layer hidden states to the projected summary</li>
 *   <li>For each of 7 output tokens:
 *     <ol type="a">
 *       <li>Forward through all GRU layers</li>
 *       <li>lm_head: last layer output -&gt; (decodeVocabSize) logits</li>
 *       <li>Argmax to select token</li>
 *       <li>Embed selected token as input for next step</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <h2>Output Token Order</h2>
 * <p>The 7 output tokens per position are: [sos_out, timeshift, duration, octave,
 * pitch_class, instrument, velocity]. The decode vocabulary is flat (8487 tokens),
 * with attribute vocabs concatenated at known offsets.</p>
 *
 * <h2>Vocabulary Offset Mapping</h2>
 * <table>
 * <caption>Decode Vocabulary Layout</caption>
 *   <tr><th>Output Index</th><th>Attribute</th><th>Offset</th><th>Vocab Size</th></tr>
 *   <tr><td>0</td><td>sos_out</td><td>0</td><td>1</td></tr>
 *   <tr><td>1</td><td>onset</td><td>1</td><td>4099</td></tr>
 *   <tr><td>2</td><td>duration</td><td>4100</td><td>4099</td></tr>
 *   <tr><td>3</td><td>octave</td><td>8199</td><td>13</td></tr>
 *   <tr><td>4</td><td>pitch class</td><td>8212</td><td>14</td></tr>
 *   <tr><td>5</td><td>instrument</td><td>8226</td><td>131</td></tr>
 *   <tr><td>6</td><td>velocity</td><td>8357</td><td>130</td></tr>
 * </table>
 *
 * @see GRUCell
 * @see MoonbeamConfig
 */
public class GRUDecoder {
	/** Number of output tokens generated per position. */
	public static final int TOKENS_PER_NOTE = 7;

	private final MoonbeamConfig config;
	private final GRUCell[] layers;
	private final PackedCollection summaryWeight;
	private final PackedCollection summaryBias;
	private final PackedCollection fcOutWeight;
	private final PackedCollection fcOutBias;
	private final PackedCollection lmHeadWeight;
	private final PackedCollection lmHeadBias;
	private final PackedCollection decoderEmbedding;
	private final int[] vocabOffsets;

	/** Cached array views for bulk computation (lazily initialized). */
	private double[] summaryWeightArr;
	private double[] summaryBiasArr;
	private double[] fcOutWeightArr;
	private double[] fcOutBiasArr;
	private double[] lmHeadWeightArr;
	private double[] lmHeadBiasArr;
	private double[] decoderEmbeddingArr;

	/**
	 * Create a GRU decoder with explicit weights.
	 *
	 * @param config model configuration
	 * @param layers array of GRU cells (one per layer)
	 * @param summaryWeight summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias summary projection bias (decoderHiddenSize)
	 * @param fcOutWeight fc_out projection weights (decoderHiddenSize, decoderHiddenSize), may be null
	 * @param fcOutBias fc_out projection bias (decoderHiddenSize), may be null
	 * @param lmHeadWeight output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias output projection bias (decodeVocabSize)
	 * @param decoderEmbedding output token embedding (decodeVocabSize, decoderHiddenSize)
	 */
	public GRUDecoder(MoonbeamConfig config, GRUCell[] layers,
					  PackedCollection summaryWeight, PackedCollection summaryBias,
					  PackedCollection fcOutWeight, PackedCollection fcOutBias,
					  PackedCollection lmHeadWeight, PackedCollection lmHeadBias,
					  PackedCollection decoderEmbedding) {
		this.config = config;
		this.layers = layers;
		this.summaryWeight = summaryWeight;
		this.summaryBias = summaryBias;
		this.fcOutWeight = fcOutWeight;
		this.fcOutBias = fcOutBias;
		this.lmHeadWeight = lmHeadWeight;
		this.lmHeadBias = lmHeadBias;
		this.decoderEmbedding = decoderEmbedding;
		this.vocabOffsets = computeVocabOffsets(config);
	}

	/**
	 * Create a GRU decoder without fc_out layer (backward compatibility).
	 *
	 * @param config model configuration
	 * @param layers array of GRU cells (one per layer)
	 * @param summaryWeight summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias summary projection bias (decoderHiddenSize)
	 * @param lmHeadWeight output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias output projection bias (decodeVocabSize)
	 * @param decoderEmbedding output token embedding (decodeVocabSize, decoderHiddenSize)
	 */
	public GRUDecoder(MoonbeamConfig config, GRUCell[] layers,
					  PackedCollection summaryWeight, PackedCollection summaryBias,
					  PackedCollection lmHeadWeight, PackedCollection lmHeadBias,
					  PackedCollection decoderEmbedding) {
		this(config, layers, summaryWeight, summaryBias, null, null,
				lmHeadWeight, lmHeadBias, decoderEmbedding);
	}

	/**
	 * Decode output tokens from a transformer hidden state using greedy argmax.
	 *
	 * <p>Autoregressively generates {@link #TOKENS_PER_NOTE} tokens using the
	 * multi-layer GRU. The first token is always conditioned on the SOS output
	 * embedding (token 0 in the decode vocabulary).</p>
	 *
	 * @param transformerHidden the transformer output hidden state of size (hiddenSize)
	 * @return array of 7 output token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden) {
		return runGruLoop(transformerHidden,
				logits -> argmax(logits, config.decodeVocabSize));
	}

	/**
	 * Core GRU decode loop shared by greedy and sampling decode methods.
	 *
	 * <p>Performs the summary projection, initializes per-layer hidden states,
	 * and autoregressively generates {@link #TOKENS_PER_NOTE} tokens. The
	 * token selection strategy (argmax vs. sampling) is delegated to the
	 * provided selector function.</p>
	 *
	 * @param transformerHidden the transformer output hidden state of size (hiddenSize)
	 * @param selector function that selects a token index from logits
	 * @return array of 7 output token indices in the flat decode vocabulary
	 */
	private int[] runGruLoop(PackedCollection transformerHidden, TokenSelector selector) {
		ensureWeightsCached();
		int decoderHidden = config.decoderHiddenSize;

		// Summary projection: hidden_size -> decoder_hidden_size
		PackedCollection initialHidden = linearForwardCached(
				transformerHidden.toArray(), config.hiddenSize,
				summaryWeightArr, decoderHidden, summaryBiasArr);

		// Initialize hidden state for each GRU layer
		PackedCollection[] h = new PackedCollection[layers.length];
		for (int l = 0; l < layers.length; l++) {
			h[l] = copyCollection(initialHidden, decoderHidden);
		}

		// Start with SOS output embedding (token 0)
		PackedCollection x = getEmbeddingCached(0);
		int[] outputTokens = new int[TOKENS_PER_NOTE];

		for (int step = 0; step < TOKENS_PER_NOTE; step++) {
			// Forward through all GRU layers
			PackedCollection layerInput = x;
			for (int l = 0; l < layers.length; l++) {
				h[l] = layers[l].forward(layerInput, h[l]);
				layerInput = h[l];
			}

			// fc_out: optional intermediate projection after GRU
			PackedCollection gruOutput = h[layers.length - 1];
			if (fcOutWeightArr != null) {
				gruOutput = linearForwardCached(gruOutput.toArray(), decoderHidden,
						fcOutWeightArr, decoderHidden, fcOutBiasArr);
			}

			// lm_head: project to decode vocabulary logits
			PackedCollection logits = linearForwardCached(
					gruOutput.toArray(), decoderHidden,
					lmHeadWeightArr, config.decodeVocabSize, lmHeadBiasArr);

			// Select token via the provided strategy
			int token = selector.select(logits);
			outputTokens[step] = token;

			// Embed selected token as input for next step
			x = getEmbeddingCached(token);
		}

		return outputTokens;
	}

	/**
	 * Functional interface for token selection from logits.
	 *
	 * <p>Used by {@link #runGruLoop} to abstract the token selection
	 * strategy (greedy argmax vs. temperature/nucleus sampling).</p>
	 */
	@FunctionalInterface
	interface TokenSelector {
		/**
		 * Select a token index from a logits vector.
		 *
		 * @param logits raw logits of shape (decodeVocabSize)
		 * @return selected token index
		 */
		int select(PackedCollection logits);
	}

	/**
	 * Convert flat decode vocabulary indices to per-attribute values.
	 *
	 * <p>The decode vocabulary is a concatenation of all attribute vocabs.
	 * This method maps each output token back to its attribute-local index
	 * by subtracting the appropriate offset.</p>
	 *
	 * @param decodeTokens array of 7 tokens in flat decode vocabulary
	 * @return per-attribute values: [sos, onset, duration, octave, pitchClass, instrument, velocity]
	 */
	public int[] toAttributeValues(int[] decodeTokens) {
		int[] attributeValues = new int[TOKENS_PER_NOTE];
		for (int i = 0; i < TOKENS_PER_NOTE; i++) {
			attributeValues[i] = decodeTokens[i] - vocabOffsets[i];
		}
		return attributeValues;
	}

	/**
	 * Returns the vocabulary offset for each output token position.
	 */
	public int[] getVocabOffsets() {
		return vocabOffsets.clone();
	}

	/**
	 * Returns the number of GRU layers.
	 */
	public int getNumLayers() {
		return layers.length;
	}

	/**
	 * Returns the decoder hidden size.
	 */
	public int getDecoderHiddenSize() {
		return config.decoderHiddenSize;
	}

	/**
	 * Compute vocabulary offsets for the flat decode vocabulary.
	 * Layout: [sos_out(1), onset(4099), duration(4099), octave(13),
	 *          pitchClass(14), instrument(131), velocity(130)] = 8487
	 */
	public static int[] computeVocabOffsets(MoonbeamConfig config) {
		int[] offsets = new int[TOKENS_PER_NOTE];
		offsets[0] = 0; // SOS output token
		int cumulative = 1; // SOS takes 1 slot
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			offsets[i + 1] = cumulative;
			cumulative += config.vocabSizes[i];
		}
		return offsets;
	}

	/**
	 * Decode output tokens with temperature and top-p (nucleus) sampling.
	 *
	 * <p>When temperature is 0, reverts to greedy argmax decoding.
	 * Otherwise, logits are divided by temperature before softmax.
	 * If topP &lt; 1.0, nucleus sampling is applied: tokens are sorted by
	 * probability, and only the smallest set whose cumulative probability
	 * exceeds topP is considered for sampling.</p>
	 *
	 * @param transformerHidden the transformer output hidden state of size (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy)
	 * @param topP              nucleus sampling threshold (1.0 = no filtering)
	 * @param random            random number generator for sampling
	 * @return array of 7 output token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden, double temperature,
						double topP, Random random) {
		if (temperature <= 0.0) {
			return decode(transformerHidden);
		}

		return runGruLoop(transformerHidden,
				logits -> sampleFromLogits(logits, config.decodeVocabSize,
						temperature, topP, random));
	}

	/**
	 * Sample a token index from logits using temperature scaling and top-p filtering.
	 *
	 * @param logits      raw logits of shape (vocabSize)
	 * @param vocabSize   vocabulary size
	 * @param temperature temperature for scaling
	 * @param topP        nucleus sampling threshold
	 * @param random      random number generator
	 * @return sampled token index
	 */
	public static int sampleFromLogits(PackedCollection logits, int vocabSize,
									   double temperature, double topP, Random random) {
		double[] logitArr = logits.toArray(0, vocabSize);
		double[] probs = new double[vocabSize];
		double maxLogit = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < vocabSize; i++) {
			double scaled = logitArr[i] / temperature;
			if (scaled > maxLogit) maxLogit = scaled;
			probs[i] = scaled;
		}

		double sumExp = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			probs[i] = Math.exp(probs[i] - maxLogit);
			sumExp += probs[i];
		}
		for (int i = 0; i < vocabSize; i++) {
			probs[i] /= sumExp;
		}

		if (topP < 1.0) {
			applyTopP(probs, vocabSize, topP);
		}

		double r = random.nextDouble();
		double cumulative = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			cumulative += probs[i];
			if (cumulative >= r) return i;
		}

		return vocabSize - 1;
	}

	/**
	 * Apply top-p (nucleus) filtering to a probability distribution in-place.
	 *
	 * <p>Zeroes out all probabilities outside the nucleus (the smallest set
	 * of tokens whose cumulative probability exceeds topP), then
	 * renormalizes.</p>
	 */
	private static void applyTopP(double[] probs, int vocabSize, double topP) {
		int[] sortedIndices = new int[vocabSize];
		for (int i = 0; i < vocabSize; i++) sortedIndices[i] = i;

		for (int i = 0; i < vocabSize - 1; i++) {
			int maxIdx = i;
			for (int j = i + 1; j < vocabSize; j++) {
				if (probs[sortedIndices[j]] > probs[sortedIndices[maxIdx]]) {
					maxIdx = j;
				}
			}
			if (maxIdx != i) {
				int tmp = sortedIndices[i];
				sortedIndices[i] = sortedIndices[maxIdx];
				sortedIndices[maxIdx] = tmp;
			}

			double cumProb = 0.0;
			for (int k = 0; k <= i; k++) {
				cumProb += probs[sortedIndices[k]];
			}
			if (cumProb >= topP) {
				for (int j = i + 1; j < vocabSize; j++) {
					probs[sortedIndices[j]] = 0.0;
				}
				break;
			}
		}

		double sum = 0.0;
		for (int i = 0; i < vocabSize; i++) sum += probs[i];
		if (sum > 0.0) {
			for (int i = 0; i < vocabSize; i++) probs[i] /= sum;
		}
	}

	/**
	 * Look up a token embedding from the decoder embedding table.
	 *
	 * <p>Uses bulk {@code toArray()} to read the embedding slice,
	 * avoiding per-element JNI overhead.</p>
	 *
	 * @param tokenIndex index in the flat decode vocabulary
	 * @return embedding vector of size (decoderHiddenSize)
	 */
	private PackedCollection getEmbedding(int tokenIndex) {
		int decoderHidden = config.decoderHiddenSize;
		PackedCollection embedding = new PackedCollection(decoderHidden);
		int baseOffset = tokenIndex * decoderHidden;
		double[] slice = decoderEmbedding.toArray(baseOffset, decoderHidden);
		embedding.setMem(0, slice, 0, decoderHidden);
		return embedding;
	}

	/**
	 * Look up a token embedding from the cached decoder embedding array.
	 */
	private PackedCollection getEmbeddingCached(int tokenIndex) {
		int decoderHidden = config.decoderHiddenSize;
		PackedCollection embedding = new PackedCollection(decoderHidden);
		int baseOffset = tokenIndex * decoderHidden;
		embedding.setMem(0, decoderEmbeddingArr, baseOffset, decoderHidden);
		return embedding;
	}

	/**
	 * Cache all weight arrays from PackedCollections on first use.
	 */
	private void ensureWeightsCached() {
		if (summaryWeightArr == null) {
			summaryWeightArr = summaryWeight.toArray();
			summaryBiasArr = summaryBias.toArray();
			lmHeadWeightArr = lmHeadWeight.toArray();
			lmHeadBiasArr = lmHeadBias.toArray();
			decoderEmbeddingArr = decoderEmbedding.toArray();
			if (fcOutWeight != null) {
				fcOutWeightArr = fcOutWeight.toArray();
				fcOutBiasArr = fcOutBias.toArray();
			}
		}
	}

	/**
	 * Compute matrix-vector product plus bias using pre-cached arrays.
	 */
	private static PackedCollection linearForwardCached(double[] inputArr, int inputSize,
														double[] weightArr, int outputSize,
														double[] biasArr) {
		double[] resultArr = new double[outputSize];
		for (int i = 0; i < outputSize; i++) {
			double sum = biasArr[i];
			int rowOffset = i * inputSize;
			for (int j = 0; j < inputSize; j++) {
				sum += weightArr[rowOffset + j] * inputArr[j];
			}
			resultArr[i] = sum;
		}

		PackedCollection result = new PackedCollection(outputSize);
		result.setMem(0, resultArr, 0, outputSize);
		return result;
	}

	/**
	 * Compute matrix-vector product plus bias: result = weight @ input + bias.
	 *
	 * <p>Uses bulk {@code toArray()} to read weight, input, and bias data
	 * into Java arrays, avoiding per-element JNI overhead.</p>
	 *
	 * @param input input vector
	 * @param inputSize dimension of input
	 * @param weight weight matrix, row-major (outputSize, inputSize)
	 * @param outputSize number of output dimensions
	 * @param bias bias vector of size (outputSize)
	 * @return result as PackedCollection of size (outputSize)
	 */
	static PackedCollection linearForward(PackedCollection input, int inputSize,
										  PackedCollection weight, int outputSize,
										  PackedCollection bias) {
		double[] inputArr = input.toArray();
		double[] weightArr = weight.toArray();
		double[] biasArr = bias.toArray();

		double[] resultArr = new double[outputSize];
		for (int i = 0; i < outputSize; i++) {
			double sum = biasArr[i];
			int rowOffset = i * inputSize;
			for (int j = 0; j < inputSize; j++) {
				sum += weightArr[rowOffset + j] * inputArr[j];
			}
			resultArr[i] = sum;
		}

		PackedCollection result = new PackedCollection(outputSize);
		result.setMem(0, resultArr, 0, outputSize);
		return result;
	}

	/**
	 * Find the index of the maximum value in a collection using bulk read.
	 */
	private static int argmax(PackedCollection collection, int size) {
		double[] data = collection.toArray(0, size);
		int maxIdx = 0;
		double maxVal = data[0];
		for (int i = 1; i < size; i++) {
			if (data[i] > maxVal) {
				maxVal = data[i];
				maxIdx = i;
			}
		}
		return maxIdx;
	}

	/**
	 * Create a copy of a PackedCollection using bulk array transfer.
	 */
	private static PackedCollection copyCollection(PackedCollection source, int size) {
		PackedCollection copy = new PackedCollection(size);
		double[] data = source.toArray(0, size);
		copy.setMem(0, data, 0, size);
		return copy;
	}
}
