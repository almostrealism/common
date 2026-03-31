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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.dsl.PdslParseException;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * GRU decoder constants and vocabulary layout utilities for compound MIDI
 * token generation.
 *
 * <p>All GRU computation is defined in {@code /pdsl/midi/gru_decoder.pdsl}.
 * Use {@code GruDecoderPdslInferenceTest} to run end-to-end inference via the
 * PDSL pipeline.</p>
 *
 * <h2>Decode Vocabulary Layout</h2>
 * <table>
 * <caption>Flat decode vocabulary layout (total = 8487 tokens)</caption>
 *   <tr><th>Step</th><th>Attribute</th><th>Offset</th><th>Size</th></tr>
 *   <tr><td>0</td><td>sos_out</td><td>0</td><td>1</td></tr>
 *   <tr><td>1</td><td>onset</td><td>1</td><td>4099</td></tr>
 *   <tr><td>2</td><td>duration</td><td>4100</td><td>4099</td></tr>
 *   <tr><td>3</td><td>octave</td><td>8199</td><td>13</td></tr>
 *   <tr><td>4</td><td>pitch class</td><td>8212</td><td>14</td></tr>
 *   <tr><td>5</td><td>instrument</td><td>8226</td><td>131</td></tr>
 *   <tr><td>6</td><td>velocity</td><td>8357</td><td>130</td></tr>
 * </table>
 */
public class GRUDecoder {

	/** Number of output tokens generated per position by the GRU decoder. */
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

	/** Classpath resource path for the GRU decoder PDSL definition. */
	private static final String GRU_DECODER_PDSL_RESOURCE = "/pdsl/midi/gru_decoder.pdsl";

	/** Lazily-compiled PDSL summary projection model. */
	private volatile CompiledModel summaryPdslModel;

	/** Lazily-compiled PDSL lm_head model. */
	private volatile CompiledModel lmHeadPdslModel;

	/** Lazily-compiled PDSL fc_out model (null when checkpoint has no fc_out). */
	private volatile CompiledModel fcOutPdslModel;

	/** Flag to indicate PDSL compilation has completed. */
	private volatile boolean pdslCompiled;

	/**
	 * Create a GRU decoder with explicit weights.
	 *
	 * @param config           model configuration
	 * @param layers           array of GRU blocks (one per layer)
	 * @param summaryWeight    summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias      summary projection bias (decoderHiddenSize)
	 * @param fcOutWeight      fc_out projection weights (may be null)
	 * @param fcOutBias        fc_out projection bias (may be null)
	 * @param lmHeadWeight     output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias       output projection bias (decodeVocabSize)
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
	}

	/**
	 * Create a GRU decoder without fc_out layer.
	 *
	 * @param config           model configuration
	 * @param layers           array of GRU blocks (one per layer)
	 * @param summaryWeight    summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias      summary projection bias (decoderHiddenSize)
	 * @param lmHeadWeight     output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias       output projection bias (decodeVocabSize)
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
	 * Decode GRU output tokens from a transformer hidden state using greedy argmax.
	 *
	 * <p>All GRU neural-network math is performed by PDSL-compiled blocks loaded
	 * from {@value GRU_DECODER_PDSL_RESOURCE}. The only Java code here is the
	 * argmax token selection and data routing between steps.</p>
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 * @return array of {@link #TOKENS_PER_NOTE} token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden) {
		ensurePdslCompiled();
		return runGruDecode(transformerHidden, 0.0, 1.0, null);
	}

	/**
	 * Decode GRU output tokens with temperature and top-p sampling.
	 *
	 * <p>All GRU neural-network math is performed by PDSL-compiled blocks loaded
	 * from {@value GRU_DECODER_PDSL_RESOURCE}. The only Java code here is the
	 * token selection (argmax or nucleus sampling) and data routing between steps.</p>
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy argmax)
	 * @param topP              nucleus sampling threshold (1.0 = no filtering)
	 * @param random            random number generator for sampling
	 * @return array of {@link #TOKENS_PER_NOTE} token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden, double temperature,
						double topP, Random random) {
		ensurePdslCompiled();
		return runGruDecode(transformerHidden, temperature, topP, random);
	}

	/**
	 * Convert flat decode vocabulary indices to per-attribute values.
	 *
	 * @param decodeTokens array of 7 tokens in flat decode vocabulary
	 * @return per-attribute values for each position
	 */
	public int[] toAttributeValues(int[] decodeTokens) {
		int[] attributeValues = new int[TOKENS_PER_NOTE];
		for (int i = 0; i < TOKENS_PER_NOTE; i++) {
			attributeValues[i] = decodeTokens[i] - vocabOffsets[i];
		}
		return attributeValues;
	}

	/**
	 * Compute vocabulary offsets for the flat decode vocabulary.
	 *
	 * <p>Layout: [sos_out(1), onset(4099), duration(4099), octave(13),
	 * pitchClass(14), instrument(131), velocity(130)] = 8487 total.</p>
	 *
	 * @param config model configuration providing vocab sizes
	 * @return per-step offsets into the flat decode vocabulary
	 */
	public static int[] computeVocabOffsets(MoonbeamConfig config) {
		int[] offsets = new int[TOKENS_PER_NOTE];
		offsets[0] = 0;
		int cumulative = 1;
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			offsets[i + 1] = cumulative;
			cumulative += config.vocabSizes[i];
		}
		return offsets;
	}

	/**
	 * Compute the vocabulary size for each of the 7 decode steps.
	 *
	 * @param config model configuration providing vocab sizes
	 * @return per-step vocabulary sizes
	 */
	public static int[] computeVocabSizesPerStep(MoonbeamConfig config) {
		int[] sizes = new int[TOKENS_PER_NOTE];
		sizes[0] = 1;
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			sizes[i + 1] = config.vocabSizes[i];
		}
		return sizes;
	}

	/** Returns the vocabulary offset for each output token position. */
	public int[] getVocabOffsets() { return vocabOffsets.clone(); }

	/** Returns the number of GRU layers. */
	public int getNumLayers() { return layers.length; }

	/** Returns the decoder hidden size. */
	public int getDecoderHiddenSize() { return config.decoderHiddenSize; }

	/** Returns the vocabulary size for each decode step. */
	public int[] getVocabSizesPerStep() { return vocabSizesPerStep.clone(); }

	/**
	 * Execute one GRU step using the PDSL-compiled blocks in a {@link GRUBlock}.
	 *
	 * <p>All gate computations (reset, update, candidate, hidden-update) are
	 * performed by PDSL-compiled models. The only Java code is the vector
	 * concatenation for input preparation.</p>
	 *
	 * @param block the GRU block providing weights and compiled PDSL gate models
	 * @param x     input vector, shape (inputSize)
	 * @param h     previous hidden state, shape (hiddenSize)
	 * @return new hidden state h', shape (hiddenSize)
	 */
	public static PackedCollection gruStep(GRUBlock block,
										   PackedCollection x, PackedCollection h) {
		block.ensureCompiled();
		int is = block.getInputSize();
		int hs = block.getHiddenSize();

		// [x | h] for reset and update gates
		PackedCollection xh = concat(x, is, h, hs);
		PackedCollection r = block.getRGateModel().forward(xh);
		PackedCollection z = block.getZGateModel().forward(xh);

		// [x | h | r] for candidate gate
		PackedCollection xhr = concatThree(x, h, r, is, hs, hs);
		PackedCollection n = block.getNGateModel().forward(xhr);

		// [n | z | h] for hidden-state update (lerp)
		PackedCollection nzh = concatThree(n, z, h, hs, hs, hs);
		return block.getHNewModel().forward(nzh);
	}

	/**
	 * Sample a token from logits using temperature scaling and nucleus (top-p) sampling.
	 *
	 * <p>This is token-selection code: it reads the PDSL-computed logits and applies
	 * standard sampling heuristics to choose a token index.</p>
	 *
	 * @param logits      logit collection of shape (vocabSize)
	 * @param vocabSize   number of entries to consider
	 * @param temperature scaling factor (0 = greedy argmax)
	 * @param topP        nucleus sampling threshold (1.0 = no filtering)
	 * @param random      random number generator
	 * @return selected token index
	 */
	public static int sampleFromLogits(PackedCollection logits, int vocabSize,
										double temperature, double topP, Random random) {
		if (temperature <= 0.0 || random == null) {
			return argmax(logits, vocabSize);
		}

		// Read logits and apply temperature scaling
		double[] probs = new double[vocabSize];
		double maxLogit = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < vocabSize; i++) {
			probs[i] = logits.toDouble(i) / temperature;
			if (probs[i] > maxLogit) maxLogit = probs[i];
		}

		// Numerically stable softmax
		double sum = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			probs[i] = Math.exp(probs[i] - maxLogit);
			sum += probs[i];
		}
		for (int i = 0; i < vocabSize; i++) {
			probs[i] /= sum;
		}

		// Nucleus (top-p) filtering
		if (topP < 1.0) {
			Integer[] indices = new Integer[vocabSize];
			for (int i = 0; i < vocabSize; i++) indices[i] = i;
			Arrays.sort(indices, (a, b) -> Double.compare(probs[b], probs[a]));

			double cumProb = 0.0;
			double topSum = 0.0;
			int cutoff = vocabSize;
			for (int i = 0; i < vocabSize; i++) {
				cumProb += probs[indices[i]];
				topSum += probs[indices[i]];
				if (cumProb >= topP) {
					cutoff = i + 1;
					break;
				}
			}
			for (int i = cutoff; i < vocabSize; i++) {
				probs[indices[i]] = 0.0;
			}
			// Renormalize over the kept tokens
			for (int i = 0; i < vocabSize; i++) {
				probs[i] /= topSum;
			}
		}

		// Categorical sample
		double r = random.nextDouble();
		double cumulative = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			cumulative += probs[i];
			if (r < cumulative) return i;
		}
		return vocabSize - 1;
	}

	// -----------------------------------------------------------------------
	//  PDSL compilation
	// -----------------------------------------------------------------------

	/**
	 * Lazily compile all PDSL blocks needed for the decode loop.
	 *
	 * <p>Loads {@value GRU_DECODER_PDSL_RESOURCE} for the summary projection and
	 * lm_head, and delegates GRU gate compilation to each {@link GRUBlock}.</p>
	 */
	private void ensurePdslCompiled() {
		if (pdslCompiled) return;
		synchronized (this) {
			if (pdslCompiled) return;

			PdslNode.Program program = loadPdslProgram(GRU_DECODER_PDSL_RESOURCE);
			PdslLoader loader = new PdslLoader();

			// summary_proj: (hiddenSize) -> (decoderHiddenSize)
			Map<String, Object> summaryArgs = new HashMap<>();
			summaryArgs.put("w", summaryWeight);
			summaryArgs.put("b", summaryBias);
			Block summaryBlock = loader.buildLayer(program, "summary_proj",
					new TraversalPolicy(config.hiddenSize), summaryArgs);
			Model summaryModel = new Model(new TraversalPolicy(config.hiddenSize));
			summaryModel.add(summaryBlock);
			summaryPdslModel = summaryModel.compile(false);

			// lm_head: (decoderHiddenSize) -> (decodeVocabSize)
			Map<String, Object> lmArgs = new HashMap<>();
			lmArgs.put("w", lmHeadWeight);
			lmArgs.put("b", lmHeadBias);
			Block lmBlock = loader.buildLayer(program, "lm_head",
					new TraversalPolicy(config.decoderHiddenSize), lmArgs);
			Model lmModel = new Model(new TraversalPolicy(config.decoderHiddenSize));
			lmModel.add(lmBlock);
			lmHeadPdslModel = lmModel.compile(false);

			// fc_out (optional): (decoderHiddenSize) -> (decoderHiddenSize)
			if (fcOutWeight != null) {
				Map<String, Object> fcArgs = new HashMap<>();
				fcArgs.put("w", fcOutWeight);
				fcArgs.put("b", fcOutBias);
				Block fcBlock = loader.buildLayer(program, "fc_out",
						new TraversalPolicy(config.decoderHiddenSize), fcArgs);
				Model fcModel = new Model(new TraversalPolicy(config.decoderHiddenSize));
				fcModel.add(fcBlock);
				fcOutPdslModel = fcModel.compile(false);
			}

			// GRU gate blocks: compiled lazily in each GRUBlock
			for (GRUBlock layer : layers) {
				layer.ensureCompiled();
			}

			pdslCompiled = true;
		}
	}

	// -----------------------------------------------------------------------
	//  Core decode loop (all neural-network math via PDSL)
	// -----------------------------------------------------------------------

	/**
	 * Run the autoregressive GRU decode loop.
	 *
	 * <p>All gate computations use PDSL-compiled blocks. The only Java
	 * logic here is vector concatenation for input preparation, token selection
	 * (argmax or nucleus sampling), and embedding lookup.</p>
	 *
	 * @param transformerHidden transformer hidden state, shape (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy)
	 * @param topP              nucleus sampling threshold
	 * @param random            RNG for sampling (null = greedy)
	 * @return {@link #TOKENS_PER_NOTE} output token indices
	 */
	private int[] runGruDecode(PackedCollection transformerHidden,
								double temperature, double topP, Random random) {
		int dh = config.decoderHiddenSize;
		int numLayers = layers.length;

		// Summary projection via PDSL: transformer hidden -> decoder hidden
		PackedCollection initialHidden = summaryPdslModel.forward(transformerHidden);

		// Initialize per-layer hidden states (all from summary projection output)
		PackedCollection[] h = new PackedCollection[numLayers];
		for (int l = 0; l < numLayers; l++) {
			h[l] = initialHidden.range(new TraversalPolicy(dh), 0);
		}

		// SOS output token embedding (index 0) as initial decoder input
		PackedCollection x = decoderEmbedding.range(new TraversalPolicy(dh), 0);

		int[] outputTokens = new int[TOKENS_PER_NOTE];

		for (int step = 0; step < TOKENS_PER_NOTE; step++) {
			// Forward through all GRU layers via PDSL
			for (int l = 0; l < numLayers; l++) {
				GRUBlock block = layers[l];

				// Concatenate [x | h[l]] for reset and update gates
				PackedCollection xh = concat(x, dh, h[l], dh);
				PackedCollection r = block.getRGateModel().forward(xh);
				PackedCollection z = block.getZGateModel().forward(xh);

				// Concatenate [x | h[l] | r] for candidate gate
				PackedCollection xhr = concatThree(x, h[l], r, dh, dh, dh);
				PackedCollection n = block.getNGateModel().forward(xhr);

				// Concatenate [n | z | h[l]] for hidden-state update (lerp)
				PackedCollection nzh = concatThree(n, z, h[l], dh, dh, dh);
				h[l] = block.getHNewModel().forward(nzh);

				// Pass this layer's output as input to the next
				x = h[l];
			}

			// Optional fc_out projection before lm_head
			PackedCollection lmInput = (fcOutPdslModel != null)
					? fcOutPdslModel.forward(h[numLayers - 1])
					: h[numLayers - 1];

			// lm_head: project to decode vocabulary logits via PDSL
			PackedCollection logits = lmHeadPdslModel.forward(lmInput);

			// Token selection (argmax or nucleus sampling) — the one allowed Java step
			int token = sampleFromLogits(logits, config.decodeVocabSize,
					temperature, topP, random);
			outputTokens[step] = token;

			// Embedding lookup for next step input (zero-copy range view)
			x = decoderEmbedding.range(new TraversalPolicy(dh), token * dh);
		}

		return outputTokens;
	}

	// -----------------------------------------------------------------------
	//  Data-routing helpers (concatenation, argmax) — no neural-network math
	// -----------------------------------------------------------------------

	/**
	 * Concatenate two vectors into a new {@link PackedCollection}.
	 *
	 * @param a     first vector
	 * @param sizeA number of elements in {@code a}
	 * @param b     second vector
	 * @param sizeB number of elements in {@code b}
	 * @return new collection {@code [a | b]}
	 */
	private static PackedCollection concat(PackedCollection a, int sizeA,
											PackedCollection b, int sizeB) {
		PackedCollection result = new PackedCollection(sizeA + sizeB);
		result.setMem(0, a, 0, sizeA);
		result.setMem(sizeA, b, 0, sizeB);
		return result;
	}

	/**
	 * Concatenate three vectors into a new {@link PackedCollection}.
	 *
	 * @param a     first vector
	 * @param b     second vector
	 * @param c     third vector
	 * @param sizeA number of elements in {@code a}
	 * @param sizeB number of elements in {@code b}
	 * @param sizeC number of elements in {@code c}
	 * @return new collection {@code [a | b | c]}
	 */
	private static PackedCollection concatThree(PackedCollection a, PackedCollection b,
												  PackedCollection c,
												  int sizeA, int sizeB, int sizeC) {
		PackedCollection result = new PackedCollection(sizeA + sizeB + sizeC);
		result.setMem(0, a, 0, sizeA);
		result.setMem(sizeA, b, 0, sizeB);
		result.setMem(sizeA + sizeB, c, 0, sizeC);
		return result;
	}

	/**
	 * Find the index of the maximum value in a collection (greedy argmax).
	 *
	 * @param collection logit collection
	 * @param size       number of elements to consider
	 * @return index of the maximum element
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

	// -----------------------------------------------------------------------
	//  PDSL loading helper
	// -----------------------------------------------------------------------

	/**
	 * Load and parse a PDSL program from the classpath.
	 *
	 * @param resource classpath resource path
	 * @return parsed PDSL program
	 * @throws PdslParseException if the resource is missing or cannot be parsed
	 */
	private static PdslNode.Program loadPdslProgram(String resource) {
		try (InputStream is = GRUDecoder.class.getResourceAsStream(resource)) {
			if (is == null) {
				throw new PdslParseException(
						"PDSL resource not found on classpath: " + resource);
			}
			PdslLoader loader = new PdslLoader();
			return loader.parse(new String(is.readAllBytes()));
		} catch (IOException e) {
			throw new PdslParseException(
					"Failed to load PDSL from " + resource, e);
		}
	}
}
