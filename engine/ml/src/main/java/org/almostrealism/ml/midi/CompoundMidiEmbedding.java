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
import org.almostrealism.ml.StateDictionary;

import java.util.List;

/**
 * Compound MIDI embedding layer that embeds 6-attribute compound tokens
 * into hidden-size vectors by concatenating 6 parallel embeddings.
 *
 * <p>Five attributes (onset, duration, octave, pitch class, velocity) use
 * {@link FundamentalMusicEmbedding} (sinusoidal continuous embedding).
 * The instrument attribute uses a standard lookup embedding since it is
 * categorical rather than numerical.</p>
 *
 * <p>Output dimension = 6 * embeddingDim = hiddenSize (e.g., 6 * 320 = 1920).</p>
 *
 * <h2>Special Token Handling</h2>
 * <p>SOS and EOS tokens are handled by a separate supplementary embedding
 * (a learned lookup table of size 2 * hiddenSize) plus a 2-layer MLP.
 * When a special token is encountered, the supplementary embedding is used
 * instead of the per-attribute FME embeddings.</p>
 *
 * <h2>Weight Keys (StateDictionary)</h2>
 * <ul>
 *   <li>{@code onset_embedding.{linear.weight,linear.bias,translation_bias}}</li>
 *   <li>{@code duration_embedding.{...}}</li>
 *   <li>{@code octave_embedding.{...}}</li>
 *   <li>{@code pitch_embedding.{...}}</li>
 *   <li>{@code instrument_embedding.weight} -- shape (131, embeddingDim)</li>
 *   <li>{@code velocity_embedding.{...}}</li>
 *   <li>{@code supplementary_embedding.weight} -- shape (supplementaryVocabSize, hiddenSize)</li>
 *   <li>{@code supplementary_mlp.0.{weight,bias}} -- first MLP layer</li>
 *   <li>{@code supplementary_mlp.2.{weight,bias}} -- second MLP layer</li>
 * </ul>
 *
 * @see FundamentalMusicEmbedding
 * @see MidiCompoundToken
 * @see MoonbeamConfig
 */
public class CompoundMidiEmbedding implements LayerFeatures {

	private static final String[] FME_PREFIXES = {
			"onset_embedding", "duration_embedding", "octave_embedding",
			"pitch_embedding", null, "velocity_embedding"
	};

	private static final int INSTRUMENT_INDEX = 4;

	private final MoonbeamConfig config;

	/** FME embeddings for onset, duration, octave, pitch, velocity (indices 0-3 and 5). */
	private final FundamentalMusicEmbedding[] fmeEmbeddings;

	/** Standard lookup embedding for instrument attribute. */
	private final PackedCollection instrumentEmbedding;

	/** Supplementary embedding for special tokens (SOS, EOS), shape (vocabSize, hiddenSize). */
	private final PackedCollection supplementaryEmbedding;

	/** Supplementary MLP first layer weight, shape (mlpIntermediateSize, hiddenSize). */
	private final PackedCollection supplementaryMlp0Weight;

	/** Supplementary MLP first layer bias, shape (mlpIntermediateSize,). */
	private final PackedCollection supplementaryMlp0Bias;

	/** Supplementary MLP second layer weight, shape (hiddenSize, mlpIntermediateSize). */
	private final PackedCollection supplementaryMlp2Weight;

	/** Supplementary MLP second layer bias, shape (hiddenSize,). */
	private final PackedCollection supplementaryMlp2Bias;

	/** Supplementary MLP intermediate size (may differ from hiddenSize). */
	private final int supplementaryMlpIntermediateSize;

	/**
	 * Create a CompoundMidiEmbedding from a {@link StateDictionary}.
	 *
	 * @param stateDict the weight dictionary
	 * @param config    model configuration
	 */
	public CompoundMidiEmbedding(StateDictionary stateDict, MoonbeamConfig config) {
		this.config = config;
		int dim = config.embeddingDim;

		this.fmeEmbeddings = new FundamentalMusicEmbedding[MoonbeamConfig.NUM_ATTRIBUTES];
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			if (i == INSTRUMENT_INDEX) continue;
			String prefix = FME_PREFIXES[i];
			this.fmeEmbeddings[i] = new FundamentalMusicEmbedding(
					config.fmeBases[i], dim,
					stateDict.get(prefix + ".linear.weight"),
					stateDict.get(prefix + ".linear.bias"),
					stateDict.get(prefix + ".translation_bias"));
		}

		this.instrumentEmbedding = stateDict.get("instrument_embedding.weight");

		this.supplementaryEmbedding = stateDict.get("supplementary_embedding.weight");
		this.supplementaryMlp0Weight = stateDict.get("supplementary_mlp.0.weight");
		this.supplementaryMlp0Bias = stateDict.get("supplementary_mlp.0.bias");
		this.supplementaryMlp2Weight = stateDict.get("supplementary_mlp.2.weight");
		this.supplementaryMlp2Bias = stateDict.get("supplementary_mlp.2.bias");
		this.supplementaryMlpIntermediateSize = supplementaryMlp0Bias.getMemLength();
	}

	/**
	 * Create a CompoundMidiEmbedding with uninitialized weights for testing.
	 *
	 * @param config model configuration
	 */
	public CompoundMidiEmbedding(MoonbeamConfig config) {
		this.config = config;
		int dim = config.embeddingDim;
		int hidden = config.hiddenSize;

		this.fmeEmbeddings = new FundamentalMusicEmbedding[MoonbeamConfig.NUM_ATTRIBUTES];
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			if (i == INSTRUMENT_INDEX) continue;
			this.fmeEmbeddings[i] = new FundamentalMusicEmbedding(config.fmeBases[i], dim);
		}

		this.instrumentEmbedding = new PackedCollection(
				new TraversalPolicy(config.vocabSizes[INSTRUMENT_INDEX], dim));

		this.supplementaryEmbedding = new PackedCollection(
				new TraversalPolicy(config.supplementaryVocabSize, hidden));
		int mlpIntermediate = hidden / 2;
		this.supplementaryMlp0Weight = new PackedCollection(new TraversalPolicy(mlpIntermediate, hidden));
		this.supplementaryMlp0Bias = new PackedCollection(new TraversalPolicy(mlpIntermediate));
		this.supplementaryMlp2Weight = new PackedCollection(new TraversalPolicy(hidden, mlpIntermediate));
		this.supplementaryMlp2Bias = new PackedCollection(new TraversalPolicy(hidden));
		this.supplementaryMlpIntermediateSize = mlpIntermediate;
	}

	/**
	 * Embed a single compound token into a hidden-size vector.
	 *
	 * <p>For normal tokens, each attribute is embedded independently and the
	 * results are concatenated. For special tokens (SOS/EOS), the
	 * supplementary embedding + MLP is used instead.</p>
	 *
	 * @param token the compound token to embed
	 * @return PackedCollection of shape (hiddenSize,) containing the embedding
	 */
	public PackedCollection embed(MidiCompoundToken token) {
		int hidden = config.hiddenSize;

		if (token.isSOS()) {
			return embedSupplementary(0);
		} else if (token.isEOS()) {
			return embedSupplementary(1);
		} else if (token.isFillStart()) {
			return embedSupplementary(0);
		} else if (token.isFillEnd()) {
			return embedSupplementary(1);
		} else if (token.isPAD()) {
			return new PackedCollection(new TraversalPolicy(hidden));
		}

		int dim = config.embeddingDim;
		int[] values = token.toArray();
		PackedCollection result = new PackedCollection(new TraversalPolicy(hidden));

		for (int attr = 0; attr < MoonbeamConfig.NUM_ATTRIBUTES; attr++) {
			PackedCollection attrEmb;
			if (attr == INSTRUMENT_INDEX) {
				attrEmb = embedInstrument(values[attr]);
			} else {
				attrEmb = fmeEmbeddings[attr].embed(values[attr]);
			}

			int offset = attr * dim;
			double[] attrData = attrEmb.toArray(0, dim);
			result.setMem(offset, attrData, 0, dim);
		}

		return result;
	}

	/**
	 * Embed a sequence of compound tokens.
	 *
	 * @param tokens the token sequence
	 * @return PackedCollection of shape (seqLen, hiddenSize)
	 */
	public PackedCollection embedSequence(List<MidiCompoundToken> tokens) {
		int hidden = config.hiddenSize;
		PackedCollection result = new PackedCollection(
				new TraversalPolicy(tokens.size(), hidden));

		for (int t = 0; t < tokens.size(); t++) {
			PackedCollection tokenEmb = embed(tokens.get(t));
			int offset = t * hidden;
			for (int j = 0; j < hidden; j++) {
				result.setMem(offset + j, tokenEmb.toDouble(j));
			}
		}

		return result;
	}

	/**
	 * Embed an instrument value using standard lookup embedding.
	 */
	private PackedCollection embedInstrument(int instrumentId) {
		int dim = config.embeddingDim;
		PackedCollection result = new PackedCollection(new TraversalPolicy(dim));
		int offset = instrumentId * dim;
		double[] slice = instrumentEmbedding.toArray(offset, dim);
		result.setMem(0, slice, 0, dim);
		return result;
	}

	/**
	 * Embed a special token (SOS or EOS) using the supplementary embedding + MLP.
	 *
	 * <p>Pipeline: lookup -> Linear -> GELU -> Linear</p>
	 */
	private PackedCollection embedSupplementary(int tokenIndex) {
		int hidden = config.hiddenSize;

		PackedCollection lookup = new PackedCollection(new TraversalPolicy(hidden));
		int offset = tokenIndex * hidden;
		double[] slice = supplementaryEmbedding.toArray(offset, hidden);
		lookup.setMem(0, slice, 0, hidden);

		int intermediate = supplementaryMlpIntermediateSize;

		CollectionProducer mlp0Out = add(matmul(p(supplementaryMlp0Weight), p(lookup)),
				c(supplementaryMlp0Bias));

		double[] mlp0Data = mlp0Out.evaluate().toArray(0, intermediate);
		double[] geluData = new double[intermediate];
		for (int i = 0; i < intermediate; i++) {
			geluData[i] = gelu(mlp0Data[i]);
		}
		PackedCollection geluOut = new PackedCollection(new TraversalPolicy(intermediate));
		geluOut.setMem(0, geluData, 0, intermediate);

		CollectionProducer mlp2Out = add(matmul(p(supplementaryMlp2Weight), p(geluOut)),
				c(supplementaryMlp2Bias));
		return mlp2Out.evaluate();
	}

	/**
	 * GELU activation function (approximate).
	 */
	private static double gelu(double x) {
		return 0.5 * x * (1.0 + Math.tanh(Math.sqrt(2.0 / Math.PI) * (x + 0.044715 * x * x * x)));
	}

	/** Returns the FME embedding for the given attribute index. */
	public FundamentalMusicEmbedding getFmeEmbedding(int attributeIndex) {
		return fmeEmbeddings[attributeIndex];
	}

	/** Returns the instrument embedding weight. */
	public PackedCollection getInstrumentEmbedding() { return instrumentEmbedding; }

	/** Returns the model configuration. */
	public MoonbeamConfig getConfig() { return config; }
}
