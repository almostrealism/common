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
	}

	/**
	 * Embed a single compound token into a hidden-size vector, returning a
	 * {@link CollectionProducer} pipeline for further composition.
	 *
	 * <p>For normal tokens, each attribute is embedded independently and the
	 * results are concatenated. For special tokens (SOS/EOS), the
	 * supplementary embedding + MLP is used instead.</p>
	 *
	 * @param token the compound token to embed
	 * @return CollectionProducer of shape (hiddenSize,) producing the embedding
	 */
	public CollectionProducer embed(MidiCompoundToken token) {
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
			return zeros(shape(hidden));
		}

		int[] values = token.toArray();
		int dim = config.embeddingDim;
		CollectionProducer[] attrEmbs = new CollectionProducer[MoonbeamConfig.NUM_ATTRIBUTES];
		for (int attr = 0; attr < MoonbeamConfig.NUM_ATTRIBUTES; attr++) {
			if (attr == INSTRUMENT_INDEX) {
				attrEmbs[attr] = embedInstrument(values[attr]).reshape(shape(dim));
			} else {
				attrEmbs[attr] = fmeEmbeddings[attr].embed(values[attr]).reshape(shape(dim));
			}
		}
		return concat(attrEmbs).reshape(shape(hidden));
	}

	/**
	 * Embed a sequence of compound tokens, returning a {@link CollectionProducer}
	 * of shape (seqLen, hiddenSize).
	 *
	 * @param tokens the token sequence
	 * @return CollectionProducer of shape (seqLen, hiddenSize)
	 */
	public CollectionProducer embedSequence(List<MidiCompoundToken> tokens) {
		int hidden = config.hiddenSize;
		CollectionProducer[] embeddings = new CollectionProducer[tokens.size()];
		for (int t = 0; t < tokens.size(); t++) {
			embeddings[t] = embed(tokens.get(t));
		}
		return concat(embeddings).reshape(shape(tokens.size(), hidden));
	}

	/**
	 * Embed an instrument value using standard lookup embedding, returning a
	 * {@link CollectionProducer} of shape (embeddingDim,).
	 */
	private CollectionProducer embedInstrument(int instrumentId) {
		int dim = config.embeddingDim;
		return cp(instrumentEmbedding).subset(shape(1, dim), instrumentId, 0).reshape(shape(dim));
	}

	/**
	 * Embed a special token (SOS or EOS) using the supplementary embedding + MLP,
	 * returning a {@link CollectionProducer} of shape (hiddenSize,).
	 *
	 * <p>Pipeline: lookup -&gt; Linear -&gt; GELU -&gt; Linear</p>
	 */
	private CollectionProducer embedSupplementary(int tokenIndex) {
		int hidden = config.hiddenSize;

		CollectionProducer lookup = cp(supplementaryEmbedding)
				.subset(shape(1, hidden), tokenIndex, 0).reshape(shape(hidden));

		CollectionProducer mlp0Out = add(matmul(cp(supplementaryMlp0Weight), lookup),
				cp(supplementaryMlp0Bias));

		// GELU activation: 0.5 * x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 * x^3)))
		CollectionProducer geluArg = mlp0Out.multiply(c(Math.sqrt(2.0 / Math.PI)))
				.multiply(c(1.0).add(mlp0Out.multiply(mlp0Out).multiply(mlp0Out).multiply(c(0.044715))));
		CollectionProducer geluOut = mlp0Out.multiply(c(0.5)).multiply(c(1.0).add(tanh(geluArg)));

		return add(matmul(cp(supplementaryMlp2Weight), geluOut), cp(supplementaryMlp2Bias))
				.reshape(shape(hidden));
	}

	/** Returns the FME embedding for the given attribute index. */
	public FundamentalMusicEmbedding getFmeEmbedding(int attributeIndex) {
		return fmeEmbeddings[attributeIndex];
	}

	/** Returns the instrument embedding weight. */
	public CollectionProducer getInstrumentEmbedding() { return cp(instrumentEmbedding); }

	/** Returns the model configuration. */
	public MoonbeamConfig getConfig() { return config; }
}
