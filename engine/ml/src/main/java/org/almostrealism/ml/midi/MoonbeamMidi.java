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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.io.IOException;

/**
 * Moonbeam MIDI Foundation Model implementation using the Almost Realism framework.
 *
 * <p>This class implements the Moonbeam transformer architecture for symbolic music
 * (MIDI) generation. It loads model weights from protobuf format (exported via
 * extract_moonbeam_weights.py) and provides the compiled transformer for use with
 * {@link MidiAutoregressiveModel}.</p>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li><strong>Embedding:</strong> {@link CompoundMidiEmbedding} with 6 parallel FME embeddings</li>
 *   <li><strong>Transformer:</strong> 15 layers with Multidimensional Relative Attention (MRA)</li>
 *   <li><strong>Final norm:</strong> RMSNorm</li>
 *   <li><strong>Decoder:</strong> {@link GRUDecoder} producing 7 tokens per position</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MoonbeamMidi model = new MoonbeamMidi("/path/to/weights");
 * MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
 * autoregressive.setPrompt(promptTokens);
 * MidiCompoundToken next = autoregressive.next();
 * }</pre>
 *
 * @see MidiAutoregressiveModel
 * @see MoonbeamConfig
 * @see CompoundMidiEmbedding
 * @see GRUDecoder
 * @see AttentionFeatures
 */
public class MoonbeamMidi implements AttentionFeatures {
	static {
		System.setProperty("AR_HARDWARE_OFF_HEAP_SIZE", "0");
		System.setProperty("AR_EXPRESSION_WARNINGS", "disabled");
		System.setProperty("AR_GRAPH_PROPAGATION_WARNINGS", "disabled");
	}

	private final MoonbeamConfig config;
	private final CompoundMidiEmbedding embedding;
	private final GRUDecoder decoder;
	private final CompiledModel compiledTransformer;
	private final OperationProfileNode profile;

	/** Sequential position counter for KV cache indexing. */
	private final PackedCollection position;

	/** Per-attribute position values for MRA RoPE (6 collections, one per attribute). */
	private final PackedCollection[] attributePositions;

	/**
	 * Create a MoonbeamMidi model with explicit components for testing.
	 *
	 * <p>This constructor allows injecting a custom config, embedding, decoder,
	 * and StateDictionary, which is useful for testing with synthetic weights.</p>
	 *
	 * @param config    model configuration
	 * @param stateDict weight dictionary containing transformer layer weights
	 * @param embedding compound MIDI embedding layer
	 * @param decoder   GRU output decoder
	 */
	public MoonbeamMidi(MoonbeamConfig config, StateDictionary stateDict,
						CompoundMidiEmbedding embedding, GRUDecoder decoder) {
		this.config = config;
		this.embedding = embedding;
		this.decoder = decoder;
		this.profile = new OperationProfileNode("moonbeam");
		this.position = new PackedCollection(1);
		this.attributePositions = createAttributePositions();
		this.compiledTransformer = buildTransformer(stateDict, profile);
	}

	/**
	 * Load a MoonbeamMidi model from a weights directory.
	 *
	 * @param weightsDirectory directory containing protobuf weight files
	 * @throws IOException if weight loading fails
	 */
	public MoonbeamMidi(String weightsDirectory) throws IOException {
		this(weightsDirectory, MoonbeamConfig.defaultConfig());
	}

	/**
	 * Load a MoonbeamMidi model from a weights directory with explicit config.
	 *
	 * @param weightsDirectory directory containing protobuf weight files
	 * @param config           explicit model configuration
	 * @throws IOException if weight loading fails
	 */
	public MoonbeamMidi(String weightsDirectory, MoonbeamConfig config) throws IOException {
		this.config = config;
		config.validate();

		StateDictionary stateDict = new StateDictionary(weightsDirectory);
		this.embedding = new CompoundMidiEmbedding(stateDict, config);
		this.decoder = buildDecoder(stateDict, config);
		this.profile = new OperationProfileNode("moonbeam");
		this.position = new PackedCollection(1);
		this.attributePositions = createAttributePositions();
		this.compiledTransformer = buildTransformer(stateDict, profile);
	}

	/**
	 * Build the compiled transformer model (embedding and decoder are external).
	 *
	 * <p>The transformer consists of {@code numLayers} transformer blocks with
	 * MRA attention, followed by a final RMSNorm. Input shape is (1, hiddenSize)
	 * and output shape is (1, hiddenSize).</p>
	 */
	private CompiledModel buildTransformer(StateDictionary stateDict,
										   OperationProfile profile,
										   ComputeRequirement... requirements) {
		int dim = config.hiddenSize;
		Model transformer = new Model(shape(1, dim));

		Producer<PackedCollection>[] attrProducers = createProducerArray(MoonbeamConfig.NUM_ATTRIBUTES);
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			attrProducers[i] = p(attributePositions[i]);
		}

		HeadGroupConfig[] headGroups = HeadGroupConfig.fromConfig(config, attrProducers);

		for (int i = 0; i < config.numLayers; i++) {
			String prefix = String.format("model.layers.%d", i);

			PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
			PackedCollection layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");
			PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
			PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
			PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
			PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");
			PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
			PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
			PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

			transformer.add(transformer(
					config.numHeads, config.numKvHeads,
					layerRmsAtt,
					layerWk, layerWv, layerWq, layerWo,
					headGroups,
					layerRmsFfn,
					layerW1, layerW2, layerW3,
					p(position),
					config.rmsNormEps,
					requirements));
		}

		PackedCollection rmsFinalWeight = stateDict.get("model.norm.weight");
		transformer.add(rmsnorm(shape(1, dim), rmsFinalWeight, config.rmsNormEps));

		return transformer.compile(false, profile);
	}

	/**
	 * Create a {@link MidiAutoregressiveModel} for compound token generation.
	 *
	 * @return a new autoregressive model ready for inference
	 */
	public MidiAutoregressiveModel createAutoregressiveModel() {
		return new MidiAutoregressiveModel(this);
	}

	/**
	 * Run a forward pass through the compiled transformer.
	 *
	 * <p>Before calling this method, the caller must update {@link #position}
	 * and {@link #attributePositions} with the current values.</p>
	 *
	 * @param embeddedInput embedded token of shape (1, hiddenSize)
	 * @return transformer hidden state of shape (1, hiddenSize)
	 */
	PackedCollection forward(PackedCollection embeddedInput) {
		return compiledTransformer.forward(embeddedInput);
	}

	/**
	 * Update the sequential position for the current step.
	 *
	 * @param step the current position in the sequence
	 */
	void setPosition(int step) {
		position.setMem(0, (double) step);
	}

	/**
	 * Update per-attribute positions for MRA RoPE from a compound token.
	 *
	 * <p>For normal tokens, each attribute position is set to the attribute value.
	 * For special tokens (SOS/EOS/PAD), all positions are set to 0.</p>
	 *
	 * @param token the compound token providing attribute values
	 */
	void setAttributePositions(MidiCompoundToken token) {
		if (token.isSpecial()) {
			for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
				attributePositions[i].setMem(0, 0.0);
			}
		} else {
			int[] values = token.toArray();
			for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
				attributePositions[i].setMem(0, (double) values[i]);
			}
		}
	}

	/** Returns the compound MIDI embedding layer. */
	public CompoundMidiEmbedding getEmbedding() { return embedding; }

	/** Returns the GRU output decoder. */
	public GRUDecoder getDecoder() { return decoder; }

	/** Returns the model configuration. */
	public MoonbeamConfig getConfig() { return config; }

	/** Returns the operation profile for performance tracking. */
	public OperationProfileNode getProfile() { return profile; }

	/** Returns the compiled transformer model (for testing). */
	public CompiledModel getCompiledTransformer() { return compiledTransformer; }

	/**
	 * Create 6 position collections for per-attribute MRA RoPE.
	 */
	private PackedCollection[] createAttributePositions() {
		PackedCollection[] positions = new PackedCollection[MoonbeamConfig.NUM_ATTRIBUTES];
		for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {
			positions[i] = new PackedCollection(1);
		}
		return positions;
	}

	/**
	 * Create a typed Producer array, isolating the unavoidable unchecked cast
	 * required by Java's lack of generic array creation.
	 */
	private static Producer<PackedCollection>[] createProducerArray(int size) {
		return new Producer[size];
	}

	/**
	 * Build a GRU decoder from a StateDictionary.
	 *
	 * @param stateDict weight dictionary
	 * @param config    model configuration
	 * @return initialized GRU decoder
	 */
	private static GRUDecoder buildDecoder(StateDictionary stateDict, MoonbeamConfig config) {
		GRUCell[] layers = new GRUCell[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUCell(
					config.decoderHiddenSize, config.decoderHiddenSize,
					stateDict.get(String.format("decoder.weight_ih_l%d", l)),
					stateDict.get(String.format("decoder.weight_hh_l%d", l)),
					stateDict.get(String.format("decoder.bias_ih_l%d", l)),
					stateDict.get(String.format("decoder.bias_hh_l%d", l)));
		}

		return new GRUDecoder(config, layers,
				stateDict.get("summary_projection.weight"),
				stateDict.get("summary_projection.bias"),
				stateDict.get("lm_head.weight"),
				stateDict.get("lm_head.bias"),
				stateDict.get("decoder_embedding.weight"));
	}
}
