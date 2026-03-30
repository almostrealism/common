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
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.util.Random;

/**
 * GRU-based output decoder for compound MIDI token generation using
 * hardware-accelerated Producer operations.
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
 *       <li>Forward through all GRU layers via compiled DSL sub-models</li>
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
 * @see GRUBlock
 * @see MoonbeamConfig
 */
public class GRUDecoder implements LayerFeatures {
	/** Number of output tokens generated per position. */
	public static final int TOKENS_PER_NOTE = 7;

	private final MoonbeamConfig config;
	private final GRUBlock[] layers;
	private final PackedCollection summaryWeight;
	private final PackedCollection summaryBias;
	private final PackedCollection fcOutWeight;
	private final PackedCollection fcOutBias;
	private final PackedCollection lmHeadWeight;
	private final PackedCollection lmHeadBias;
	private final PackedCollection decoderEmbedding;
	private final int[] vocabOffsets;
	private final int[] vocabSizesPerStep;

	/**
	 * Compiled DSL sub-models for each GRU layer — one array per gate,
	 * indexed by layer. Populated in the constructor by compiling the
	 * uncompiled {@link Block} objects from each {@link GRUBlock}.
	 */
	private final CompiledModel[] rGateModels;
	private final CompiledModel[] zGateModels;
	private final CompiledModel[] nGateModels;
	private final CompiledModel[] hNewModels;

	/**
	 * Create a GRU decoder with explicit weights.
	 *
	 * @param config model configuration
	 * @param layers array of GRU blocks (one per layer)
	 * @param summaryWeight summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias summary projection bias (decoderHiddenSize)
	 * @param fcOutWeight fc_out projection weights (decoderHiddenSize, decoderHiddenSize), may be null
	 * @param fcOutBias fc_out projection bias (decoderHiddenSize), may be null
	 * @param lmHeadWeight output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias output projection bias (decodeVocabSize)
	 * @param decoderEmbedding output token embedding (decodeVocabSize, decoderHiddenSize)
	 */
	public GRUDecoder(MoonbeamConfig config, GRUBlock[] layers,
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
		this.vocabSizesPerStep = computeVocabSizesPerStep(config);

		// Compile DSL sub-blocks from each GRUBlock layer
		this.rGateModels = new CompiledModel[layers.length];
		this.zGateModels = new CompiledModel[layers.length];
		this.nGateModels = new CompiledModel[layers.length];
		this.hNewModels  = new CompiledModel[layers.length];
		for (int l = 0; l < layers.length; l++) {
			rGateModels[l] = compileBlock(layers[l].rGateBlock);
			zGateModels[l] = compileBlock(layers[l].zGateBlock);
			nGateModels[l] = compileBlock(layers[l].nGateBlock);
			hNewModels[l]  = compileBlock(layers[l].hNewBlock);
		}
	}

	/**
	 * Compile an uncompiled DSL {@link Block} into a {@link CompiledModel}
	 * ready for forward pass execution.
	 *
	 * @param block an uncompiled block from {@link GRUBlock}
	 * @return compiled model
	 */
	private static CompiledModel compileBlock(Block block) {
		Model m = new Model(block.getInputShape());
		m.add(block);
		return m.compile(false);
	}

	/**
	 * Create a GRU decoder without fc_out layer (backward compatibility).
	 *
	 * @param config model configuration
	 * @param layers array of GRU blocks (one per layer)
	 * @param summaryWeight summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias summary projection bias (decoderHiddenSize)
	 * @param lmHeadWeight output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias output projection bias (decodeVocabSize)
	 * @param decoderEmbedding output token embedding (decodeVocabSize, decoderHiddenSize)
	 */
	public GRUDecoder(MoonbeamConfig config, GRUBlock[] layers,
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
				(logits, step) -> argmax(logits, config.decodeVocabSize));
	}

	/**
	 * Core GRU decode loop shared by greedy and sampling decode methods.
	 *
	 * <p>Performs the summary projection, initializes per-layer hidden states,
	 * and autoregressively generates {@link #TOKENS_PER_NOTE} tokens. The
	 * token selection strategy (argmax vs. sampling) is delegated to the
	 * provided selector function.</p>
	 *
	 * <p>All GRU math (matmul, sigmoid, tanh, element-wise multiply, lerp) is
	 * defined in {@code gru_block.pdsl} and executed through the compiled models
	 * in each {@link GRUBlock}. This method orchestrates data routing: vector
	 * concatenation using {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}
	 * and dispatch to the compiled sub-models in each GRU layer.</p>
	 *
	 * @param transformerHidden the transformer output hidden state of size (hiddenSize)
	 * @param selector function that selects a token index from logits
	 * @return array of 7 output token indices in the flat decode vocabulary
	 */
	private int[] runGruLoop(PackedCollection transformerHidden, TokenSelector selector) {
		int decoderHidden = config.decoderHiddenSize;

		// Summary projection: hidden_size -> decoder_hidden_size (hardware-accelerated)
		PackedCollection initialHidden = linearForward(transformerHidden,
				summaryWeight, summaryBias);

		// Initialize hidden state for each GRU layer
		PackedCollection[] h = new PackedCollection[layers.length];
		for (int l = 0; l < layers.length; l++) {
			h[l] = copyCollection(initialHidden, decoderHidden);
		}

		// Start with SOS output embedding (token 0)
		PackedCollection x = getEmbedding(0);
		int[] outputTokens = new int[TOKENS_PER_NOTE];

		for (int step = 0; step < TOKENS_PER_NOTE; step++) {
			// Forward through all GRU layers via compiled DSL sub-models
			PackedCollection layerInput = x;
			for (int l = 0; l < layers.length; l++) {
				h[l] = gruStep(l, layerInput, h[l]);
				layerInput = h[l];
			}

			// fc_out: optional intermediate projection after GRU
			PackedCollection gruOutput = h[layers.length - 1];
			if (fcOutWeight != null) {
				gruOutput = linearForward(gruOutput, fcOutWeight, fcOutBias);
			}

			// lm_head: project to decode vocabulary logits (hardware-accelerated)
			PackedCollection logits = linearForward(gruOutput, lmHeadWeight, lmHeadBias);

			// Mask logits to the valid sub-range for this decode step
			maskLogitsForStep(logits, step);

			// Select token via the provided strategy
			int token = selector.select(logits, step);
			outputTokens[step] = token;

			// Embed selected token as input for next step
			x = getEmbedding(token);
		}

		return outputTokens;
	}

	/**
	 * Execute one GRU step for a specific layer using the compiled DSL sub-models.
	 *
	 * <p>All GRU math is defined in {@code gru_block.pdsl}. This method performs
	 * only data routing: concatenating input vectors using
	 * {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}
	 * and calling {@link CompiledModel#forward} on the compiled sub-models for
	 * the given layer index.</p>
	 *
	 * @param layerIndex the GRU layer index (0-based)
	 * @param x          input vector of size (inputSize)
	 * @param h          previous hidden state of size (hiddenSize)
	 * @return new hidden state h' of size (hiddenSize)
	 */
	private PackedCollection gruStep(int layerIndex, PackedCollection x, PackedCollection h) {
		int inputSz  = layers[layerIndex].getInputSize();
		int hiddenSz = layers[layerIndex].getHiddenSize();

		// [x | h] for r and z gates
		PackedCollection xh = concatTwo(x, inputSz, h, hiddenSz);
		PackedCollection r  = rGateModels[layerIndex].forward(xh);
		PackedCollection z  = zGateModels[layerIndex].forward(xh);

		// [x | h | r] for n gate
		PackedCollection xhr = concatTwo(xh, inputSz + hiddenSz, r, hiddenSz);
		PackedCollection n   = nGateModels[layerIndex].forward(xhr);

		// [n | z | h] for h_new (lerp)
		PackedCollection nzh = concatThree(n, z, h, hiddenSz);
		return hNewModels[layerIndex].forward(nzh);
	}

	/**
	 * Execute one GRU step for a single layer using on-the-fly compilation.
	 *
	 * <p>This convenience overload is intended for test and diagnostic use.
	 * The four DSL sub-blocks from {@link GRUBlock} are compiled into
	 * {@link CompiledModel} instances on each call. For production inference,
	 * use a {@link GRUDecoder} instance where sub-models are compiled once
	 * in the constructor.</p>
	 *
	 * @param block the GRU block whose sub-blocks will be compiled and run
	 * @param x     input vector of size (inputSize)
	 * @param h     previous hidden state of size (hiddenSize)
	 * @return new hidden state h' of size (hiddenSize)
	 */
	public static PackedCollection gruStep(GRUBlock block, PackedCollection x, PackedCollection h) {
		int inputSz  = block.getInputSize();
		int hiddenSz = block.getHiddenSize();

		CompiledModel rModel  = compileBlock(block.rGateBlock);
		CompiledModel zModel  = compileBlock(block.zGateBlock);
		CompiledModel nModel  = compileBlock(block.nGateBlock);
		CompiledModel hnModel = compileBlock(block.hNewBlock);

		// [x | h] for r and z gates
		PackedCollection xh = concatTwo(x, inputSz, h, hiddenSz);
		PackedCollection r  = rModel.forward(xh);
		PackedCollection z  = zModel.forward(xh);

		// [x | h | r] for n gate
		PackedCollection xhr = concatTwo(xh, inputSz + hiddenSz, r, hiddenSz);
		PackedCollection n   = nModel.forward(xhr);

		// [n | z | h] for h_new (lerp)
		PackedCollection nzh = concatThree(n, z, h, hiddenSz);
		return hnModel.forward(nzh);
	}

	/**
	 * Functional interface for token selection from logits.
	 *
	 * <p>Used by {@link #runGruLoop} to abstract the token selection
	 * strategy (greedy argmax vs. temperature/nucleus sampling).
	 * The step index is provided so that selection can be restricted
	 * to the valid vocabulary sub-range for that decode step.</p>
	 */
	@FunctionalInterface
	interface TokenSelector {
		/**
		 * Select a token index from a logits vector.
		 *
		 * @param logits raw logits of shape (decodeVocabSize)
		 * @param step the current decode step (0-6)
		 * @return selected token index
		 */
		int select(PackedCollection logits, int step);
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
	 * Compute the vocabulary size for each of the 7 decode steps.
	 * Step 0 is the SOS token (size 1), steps 1-6 correspond to the
	 * 6 compound token attributes.
	 */
	public static int[] computeVocabSizesPerStep(MoonbeamConfig config) {
		int[] sizes = new int[TOKENS_PER_NOTE];
		sizes[0] = 1; // SOS output token
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			sizes[i + 1] = config.vocabSizes[i];
		}
		return sizes;
	}

	/**
	 * Mask logits so only the valid sub-range for the given decode step
	 * can be selected. All logits outside [offset, offset + vocabSize) are
	 * set to negative infinity.
	 *
	 * @param logits the full decode vocabulary logits (decodeVocabSize)
	 * @param step the current decode step (0-6)
	 */
	private void maskLogitsForStep(PackedCollection logits, int step) {
		int offset = vocabOffsets[step];
		int size = vocabSizesPerStep[step];
		int totalVocab = config.decodeVocabSize;

		for (int i = 0; i < totalVocab; i++) {
			if (i < offset || i >= offset + size) {
				logits.setMem(i, Double.NEGATIVE_INFINITY);
			}
		}
	}

	/**
	 * Returns the vocabulary size for each decode step.
	 */
	public int[] getVocabSizesPerStep() {
		return vocabSizesPerStep.clone();
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
				(logits, step) -> sampleFromLogits(logits, config.decodeVocabSize,
						temperature, topP, random));
	}

	/**
	 * Sample a token index from logits using temperature scaling and top-p filtering.
	 *
	 * <p>Uses {@link PackedCollection} for probability storage.</p>
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
		PackedCollection probs = new PackedCollection(vocabSize);
		double maxLogit = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < vocabSize; i++) {
			double scaled = logits.toDouble(i) / temperature;
			if (scaled > maxLogit) maxLogit = scaled;
			probs.setMem(i, scaled);
		}

		double sumExp = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			double expVal = Math.exp(probs.toDouble(i) - maxLogit);
			probs.setMem(i, expVal);
			sumExp += expVal;
		}
		for (int i = 0; i < vocabSize; i++) {
			probs.setMem(i, probs.toDouble(i) / sumExp);
		}

		if (topP < 1.0) {
			applyTopP(probs, vocabSize, topP);
		}

		double r = random.nextDouble();
		double cumulative = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			cumulative += probs.toDouble(i);
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
	private static void applyTopP(PackedCollection probs, int vocabSize, double topP) {
		int[] sortedIndices = new int[vocabSize];
		for (int i = 0; i < vocabSize; i++) sortedIndices[i] = i;

		for (int i = 0; i < vocabSize - 1; i++) {
			int maxIdx = i;
			for (int j = i + 1; j < vocabSize; j++) {
				if (probs.toDouble(sortedIndices[j]) > probs.toDouble(sortedIndices[maxIdx])) {
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
				cumProb += probs.toDouble(sortedIndices[k]);
			}
			if (cumProb >= topP) {
				for (int j = i + 1; j < vocabSize; j++) {
					probs.setMem(sortedIndices[j], 0.0);
				}
				break;
			}
		}

		double sum = 0.0;
		for (int i = 0; i < vocabSize; i++) sum += probs.toDouble(i);
		if (sum > 0.0) {
			for (int i = 0; i < vocabSize; i++) probs.setMem(i, probs.toDouble(i) / sum);
		}
	}

	/**
	 * Look up a token embedding from the decoder embedding table.
	 *
	 * <p>Uses the Producer pattern to extract a row from the embedding
	 * matrix via subset operation.</p>
	 *
	 * @param tokenIndex index in the flat decode vocabulary
	 * @return embedding vector of size (decoderHiddenSize)
	 */
	private PackedCollection getEmbedding(int tokenIndex) {
		int decoderHidden = config.decoderHiddenSize;
		TraversalPolicy rowShape = shape(decoderHidden);
		int offset = tokenIndex * decoderHidden;
		return subset(rowShape, p(decoderEmbedding), offset).evaluate();
	}

	/**
	 * Compute a linear projection using hardware-accelerated matmul:
	 * result = weight @ input + bias.
	 *
	 * @param input input vector
	 * @param weight weight matrix, row-major (outputSize, inputSize)
	 * @param bias bias vector of size (outputSize)
	 * @return result as PackedCollection
	 */
	private PackedCollection linearForward(PackedCollection input,
											PackedCollection weight,
											PackedCollection bias) {
		CollectionProducer result = add(matmul(p(weight), p(input)), c(bias));
		return result.evaluate();
	}

	/**
	 * Find the index of the maximum value in a collection.
	 * Uses element-wise access via {@link PackedCollection#toDouble(int)}.
	 */
	private static int argmax(PackedCollection collection, int size) {
		int maxIdx = 0;
		double maxVal = collection.toDouble(0);
		for (int i = 1; i < size; i++) {
			double val = collection.toDouble(i);
			if (val > maxVal) {
				maxVal = val;
				maxIdx = i;
			}
		}
		return maxIdx;
	}

	/**
	 * Create a copy of a PackedCollection using
	 * {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}.
	 */
	private static PackedCollection copyCollection(PackedCollection source, int size) {
		PackedCollection copy = new PackedCollection(size);
		copy.setMem(0, source, 0, size);
		return copy;
	}

	/**
	 * Concatenate two vectors [a | b] into a new PackedCollection using
	 * {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}.
	 * Uses {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)} only.
	 *
	 * @param a     first vector
	 * @param sizeA number of elements in a
	 * @param b     second vector
	 * @param sizeB number of elements in b
	 * @return concatenated PackedCollection of shape (sizeA + sizeB)
	 */
	private static PackedCollection concatTwo(PackedCollection a, int sizeA,
											   PackedCollection b, int sizeB) {
		PackedCollection result = new PackedCollection(sizeA + sizeB);
		result.setMem(0, a, 0, sizeA);
		result.setMem(sizeA, b, 0, sizeB);
		return result;
	}

	/**
	 * Concatenate three equal-sized vectors [a | b | c] into a new PackedCollection
	 * using {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}.
	 * Uses {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)} only.
	 *
	 * @param a    first vector
	 * @param b    second vector
	 * @param c    third vector
	 * @param size number of elements in each vector
	 * @return concatenated PackedCollection of shape (3 * size)
	 */
	private static PackedCollection concatThree(PackedCollection a, PackedCollection b,
												  PackedCollection c, int size) {
		PackedCollection result = new PackedCollection(3 * size);
		result.setMem(0,        a, 0, size);
		result.setMem(size,     b, 0, size);
		result.setMem(2 * size, c, 0, size);
		return result;
	}
}
