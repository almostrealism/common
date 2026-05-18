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
 * Configuration class for the Moonbeam MIDI Foundation Model.
 *
 * <p>Moonbeam is a ~200M parameter LLaMA-style transformer for symbolic
 * music (MIDI) with compound tokenization and Multidimensional Relative
 * Attention (MRA). This configuration holds all architectural parameters.</p>
 *
 * <h2>Architecture Summary</h2>
 * <table>
 * <caption>Model Configuration Parameters</caption>
 *   <tr><th>Parameter</th><th>Value</th><th>Description</th></tr>
 *   <tr><td>hiddenSize</td><td>1920</td><td>Transformer hidden dimension (6 * 320)</td></tr>
 *   <tr><td>intermediateSize</td><td>6720</td><td>FFN hidden dimension</td></tr>
 *   <tr><td>numLayers</td><td>15</td><td>Transformer blocks</td></tr>
 *   <tr><td>numHeads</td><td>12</td><td>Query attention heads</td></tr>
 *   <tr><td>numKvHeads</td><td>6</td><td>Key/value heads (GQA)</td></tr>
 *   <tr><td>headDim</td><td>160</td><td>Per-head dimension</td></tr>
 *   <tr><td>embeddingDim</td><td>320</td><td>Per-attribute embedding dimension (hiddenSize / 6)</td></tr>
 * </table>
 *
 * <h2>Compound Token Vocabulary Sizes</h2>
 * <table>
 * <caption>Attribute Vocabulary Sizes</caption>
 *   <tr><th>Attribute</th><th>Vocab Size</th></tr>
 *   <tr><td>Onset</td><td>4099</td></tr>
 *   <tr><td>Duration</td><td>4099</td></tr>
 *   <tr><td>Octave</td><td>13</td></tr>
 *   <tr><td>Pitch class</td><td>14</td></tr>
 *   <tr><td>Instrument</td><td>131</td></tr>
 *   <tr><td>Velocity</td><td>130</td></tr>
 * </table>
 *
 * @see MidiCompoundToken
 * @see MidiTokenizer
 */
public class MoonbeamConfig {
	/** Number of compound token attributes. */
	public static final int NUM_ATTRIBUTES = 6;

	/** Transformer hidden dimension. */
	public final int hiddenSize;

	/** FFN intermediate dimension. */
	public final int intermediateSize;

	/** Number of transformer layers. */
	public final int numLayers;

	/** Number of query attention heads. */
	public final int numHeads;

	/** Number of key/value attention heads (Grouped Query Attention). */
	public final int numKvHeads;

	/** Per-head dimension (hiddenSize / numHeads). */
	public final int headDim;

	/** Per-attribute embedding dimension (hiddenSize / NUM_ATTRIBUTES). */
	public final int embeddingDim;

	/** GRU decoder hidden size. */
	public final int decoderHiddenSize;

	/** Number of GRU decoder layers. */
	public final int decoderLayers;

	/** Flat decode vocabulary size (sum of all attribute vocabs + offsets). */
	public final int decodeVocabSize;

	/** Maximum sequence length. */
	public final int maxSeqLen;

	/** RMSNorm epsilon. */
	public final double rmsNormEps;

	/** Per-attribute RoPE theta values for Multidimensional Relative Attention. */
	public final double[] ropeThetas;

	/** Number of attention heads per MRA group. */
	public final int[] headsPerGroup;

	/** Vocabulary sizes per attribute: onset, duration, octave, pitchClass, instrument, velocity. */
	public final int[] vocabSizes;

	/** FME base values per attribute (same as ropeThetas for attributes using FME). */
	public final double[] fmeBases;

	/** Number of supplementary embedding tokens (SOS=0, EOS=1). */
	public final int supplementaryVocabSize;

	/**
	 * Create a MoonbeamConfig with explicit parameters.
	 */
	public MoonbeamConfig(int hiddenSize, int intermediateSize, int numLayers,
						  int numHeads, int numKvHeads, int headDim,
						  int decoderHiddenSize, int decoderLayers,
						  int decodeVocabSize, int maxSeqLen, double rmsNormEps,
						  double[] ropeThetas, int[] headsPerGroup,
						  int[] vocabSizes, double[] fmeBases,
						  int supplementaryVocabSize) {
		this.hiddenSize = hiddenSize;
		this.intermediateSize = intermediateSize;
		this.numLayers = numLayers;
		this.numHeads = numHeads;
		this.numKvHeads = numKvHeads;
		this.headDim = headDim;
		this.embeddingDim = hiddenSize / NUM_ATTRIBUTES;
		this.decoderHiddenSize = decoderHiddenSize;
		this.decoderLayers = decoderLayers;
		this.decodeVocabSize = decodeVocabSize;
		this.maxSeqLen = maxSeqLen;
		this.rmsNormEps = rmsNormEps;
		this.ropeThetas = ropeThetas;
		this.headsPerGroup = headsPerGroup;
		this.vocabSizes = vocabSizes;
		this.fmeBases = fmeBases;
		this.supplementaryVocabSize = supplementaryVocabSize;
	}

	/**
	 * Factory method for the default Moonbeam MIDI Foundation Model configuration.
	 * These values match the reference implementation at
	 * guozixunnicolas/Moonbeam-MIDI-Foundation-Model.
	 */
	public static MoonbeamConfig defaultConfig() {
		return new MoonbeamConfig(
				1920,                                       // hiddenSize (6 * 320)
				6720,                                       // intermediateSize
				15,                                         // numLayers
				12,                                         // numHeads
				6,                                          // numKvHeads
				160,                                        // headDim (1920 / 12)
				1536,                                       // decoderHiddenSize
				4,                                          // decoderLayers
				8487,                                       // decodeVocabSize
				8192,                                       // maxSeqLen
				1e-5,                                       // rmsNormEps
				new double[]{199999, 1031, 19, 20, 199999, 131},  // ropeThetas
				new int[]{2, 2, 2, 2, 2, 2},               // headsPerGroup (6 groups of 2)
				new int[]{4099, 4099, 13, 14, 131, 130},    // vocabSizes
				new double[]{199999, 1031, 19, 20, 199999, 131},  // fmeBases
				2                                           // supplementaryVocabSize (SOS, EOS)
		);
	}

	/**
	 * Factory method for the 309M parameter Moonbeam checkpoint
	 * (moonbeam_309M.pt from guozixunnicolas/moonbeam-midi-foundation-model).
	 *
	 * <p>This configuration matches the actual pretrained checkpoint dimensions,
	 * which differ from the paper's described architecture.</p>
	 */
	public static MoonbeamConfig checkpoint309M() {
		return new MoonbeamConfig(
				1536,                                       // hiddenSize (6 * 256)
				5376,                                       // intermediateSize (3.5x)
				9,                                          // numLayers
				12,                                         // numHeads
				6,                                          // numKvHeads
				128,                                        // headDim (1536 / 12)
				1024,                                       // decoderHiddenSize
				2,                                          // decoderLayers
				2341,                                       // decodeVocabSize
				8192,                                       // maxSeqLen
				1e-5,                                       // rmsNormEps
				new double[]{199999, 1031, 19, 20, 199999, 131},  // ropeThetas
				new int[]{2, 2, 2, 2, 2, 2},               // headsPerGroup (6 groups of 2)
				new int[]{1026, 1026, 13, 14, 131, 130},    // vocabSizes
				new double[]{199999, 1031, 19, 20, 199999, 131},  // fmeBases
				2                                           // supplementaryVocabSize (SOS, EOS)
		);
	}

	/**
	 * Factory method for a small test configuration.
	 * Uses reduced dimensions but maintains architectural proportions.
	 */
	public static MoonbeamConfig testConfig() {
		return new MoonbeamConfig(
				48,                                         // hiddenSize (6 * 8)
				144,                                        // intermediateSize (3x)
				2,                                          // numLayers
				6,                                          // numHeads
				6,                                          // numKvHeads
				8,                                          // headDim (48 / 6)
				32,                                         // decoderHiddenSize
				2,                                          // decoderLayers
				8487,                                       // decodeVocabSize (same vocab)
				128,                                        // maxSeqLen
				1e-5,                                       // rmsNormEps
				new double[]{199999, 1031, 19, 20, 199999, 131},  // ropeThetas
				new int[]{1, 1, 1, 1, 1, 1},               // headsPerGroup (6 groups of 1)
				new int[]{4099, 4099, 13, 14, 131, 130},    // vocabSizes
				new double[]{199999, 1031, 19, 20, 199999, 131},  // fmeBases
				2                                           // supplementaryVocabSize
		);
	}

	/**
	 * Validate configuration parameters.
	 *
	 * @throws IllegalStateException if configuration is invalid
	 */
	public void validate() {
		if (hiddenSize % numHeads != 0) {
			throw new IllegalStateException(
					String.format("hiddenSize (%d) must be divisible by numHeads (%d)",
							hiddenSize, numHeads));
		}
		if (numHeads % numKvHeads != 0) {
			throw new IllegalStateException(
					String.format("numHeads (%d) must be divisible by numKvHeads (%d)",
							numHeads, numKvHeads));
		}
		if (hiddenSize % NUM_ATTRIBUTES != 0) {
			throw new IllegalStateException(
					String.format("hiddenSize (%d) must be divisible by NUM_ATTRIBUTES (%d)",
							hiddenSize, NUM_ATTRIBUTES));
		}
		if (ropeThetas.length != NUM_ATTRIBUTES) {
			throw new IllegalStateException(
					String.format("ropeThetas length (%d) must equal NUM_ATTRIBUTES (%d)",
							ropeThetas.length, NUM_ATTRIBUTES));
		}
		if (headsPerGroup.length != NUM_ATTRIBUTES) {
			throw new IllegalStateException(
					String.format("headsPerGroup length (%d) must equal NUM_ATTRIBUTES (%d)",
							headsPerGroup.length, NUM_ATTRIBUTES));
		}

		int totalGroupHeads = 0;
		for (int h : headsPerGroup) {
			totalGroupHeads += h;
		}
		if (totalGroupHeads != numHeads) {
			throw new IllegalStateException(
					String.format("Sum of headsPerGroup (%d) must equal numHeads (%d)",
							totalGroupHeads, numHeads));
		}
	}

	/**
	 * Returns the number of query heads per KV head group.
	 */
	public int getHeadsPerKVGroup() {
		return numHeads / numKvHeads;
	}

	@Override
	public String toString() {
		return String.format(
				"MoonbeamConfig{hidden=%d, intermediate=%d, layers=%d, heads=%d/%d, " +
						"headDim=%d, embDim=%d, decoder=%d x %d, maxSeq=%d}",
				hiddenSize, intermediateSize, numLayers, numHeads, numKvHeads,
				headDim, embeddingDim, decoderHiddenSize, decoderLayers, maxSeqLen);
	}
}
