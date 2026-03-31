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
	 * Decode GRU output tokens from a transformer hidden state.
	 *
	 * @throws UnsupportedOperationException always — use {@code GruDecoderPdslInferenceTest}
	 *         for PDSL-based inference
	 */
	public int[] decode(PackedCollection transformerHidden) {
		throw new UnsupportedOperationException(
				"GRU decode is implemented in /pdsl/midi/gru_decoder.pdsl. "
						+ "Use GruDecoderPdslInferenceTest for inference.");
	}

	/**
	 * Decode GRU output tokens with temperature and top-p sampling.
	 *
	 * @throws UnsupportedOperationException always — use {@code GruDecoderPdslInferenceTest}
	 *         for PDSL-based inference
	 */
	public int[] decode(PackedCollection transformerHidden, double temperature,
						double topP, Random random) {
		throw new UnsupportedOperationException(
				"GRU decode is implemented in /pdsl/midi/gru_decoder.pdsl. "
						+ "Use GruDecoderPdslInferenceTest for inference.");
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
	 * Execute one GRU step for a block.
	 *
	 * @throws UnsupportedOperationException always — use {@code GruDecoderPdslInferenceTest}
	 *         for PDSL-based inference
	 */
	public static PackedCollection gruStep(GRUBlock block,
										   PackedCollection x, PackedCollection h) {
		throw new UnsupportedOperationException(
				"GRU step is implemented in /pdsl/midi/gru_decoder.pdsl. "
						+ "Use GruDecoderPdslInferenceTest for inference.");
	}

	/**
	 * Sample a token from logits.
	 *
	 * @throws UnsupportedOperationException always — use {@code GruDecoderPdslInferenceTest}
	 *         for PDSL-based inference
	 */
	public static int sampleFromLogits(PackedCollection logits, int vocabSize,
										double temperature, double topP, Random random) {
		throw new UnsupportedOperationException(
				"Sampling is implemented in GruDecoderPdslInferenceTest.");
	}
}
