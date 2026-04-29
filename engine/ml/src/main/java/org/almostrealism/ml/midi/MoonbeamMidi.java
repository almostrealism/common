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
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.ModelBundle;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Moonbeam MIDI Foundation Model implementation using the Almost Realism framework.
 *
 * <p>This class implements the Moonbeam transformer architecture for symbolic music
 * (MIDI) generation. It loads model weights from protobuf format (exported via
 * extract_moonbeam_weights.py) and provides the compiled transformer for use with
 * {@link MoonbeamMidiGenerator}.</p>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li><strong>Embedding:</strong> {@link CompoundMidiEmbedding} with 6 parallel FME embeddings</li>
 *   <li><strong>Transformer:</strong> 15 layers with Multidimensional Relative Attention (MRA)</li>
 *   <li><strong>Final norm:</strong> RMSNorm</li>
 *   <li><strong>Decoder:</strong> {@link GRUDecoder} producing 7 tokens per position</li>
 * </ul>
 *
 * <h2>Usage: Inference</h2>
 * <pre>{@code
 * // Load model
 * MoonbeamMidi model = new MoonbeamMidi("/path/to/weights");
 *
 * // Generate unconditionally (via MoonbeamMidiGenerator in studio/compose)
 * MoonbeamMidiGenerator gen = new MoonbeamMidiGenerator(model);
 * gen.setTemperature(0.8);
 * gen.setTopP(0.95);
 * gen.generateUnconditional(new File("output.mid"), 100);
 *
 * // Or generate from a MIDI prompt
 * gen.generateFromFile(new File("input.mid"), new File("output.mid"), 100);
 * }</pre>
 *
 * <h2>Usage: LoRA Fine-tuning</h2>
 * <pre>{@code
 * MoonbeamMidi model = new MoonbeamMidi("/path/to/weights");
 * MidiTrainingConfig trainConfig = MidiTrainingConfig.defaultConfig();
 *
 * // Create adapter config for attention projections
 * AdapterConfig adapter = model.createLoraConfig(trainConfig);
 *
 * // Train with ModelOptimizer
 * MidiDataset dataset = new MidiDataset(new File("/midi/dir"), model.getConfig(), trainConfig);
 * // ... wire into ModelOptimizer
 *
 * // Save LoRA adapter
 * model.saveLoraAdapter(Paths.get("lora.pb"), trainConfig);
 * }</pre>
 *
 * @see org.almostrealism.studio.midi.MoonbeamMidiGenerator
 * @see MoonbeamConfig
 * @see CompoundMidiEmbedding
 * @see GRUDecoder
 * @see AttentionFeatures
 */
public class MoonbeamMidi implements AttentionFeatures {
	/** Model hyperparameters (hidden size, heads, vocab sizes, etc.). */
	private final MoonbeamConfig config;
	/** Compound token embedding for all six MIDI attributes. */
	private final CompoundMidiEmbedding embedding;
	/** GRU-based autoregressive decoder that converts transformer output to token predictions. */
	private final GRUDecoder decoder;
	/** The compiled transformer model used for the encoder forward pass. */
	private final CompiledModel compiledTransformer;
	/** Optional profiling node for performance diagnostics. */
	private final OperationProfileNode profile;

	/** Sequential position counter for KV cache indexing. */
	private final PackedCollection position;

	/**
	 * Per-attribute position values for MRA RoPE, packed into a single collection
	 * of shape {@code (NUM_ATTRIBUTES)}. Each slot is exposed to the compiled
	 * transformer as a {@code subset(shape(1), i)} producer so all six
	 * attribute positions can be updated with a single bulk {@code setMem} call.
	 */
	private final PackedCollection attributePositions;

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
		this.attributePositions = new PackedCollection(MoonbeamConfig.NUM_ATTRIBUTES);
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
		this.attributePositions = new PackedCollection(MoonbeamConfig.NUM_ATTRIBUTES);
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
			attrProducers[i] = cp(attributePositions).subset(shape(1), i);
		}

		HeadGroupConfig[] headGroups = HeadGroupConfig.fromParams(
				config.ropeThetas, config.headDim, config.maxSeqLen,
				config.headsPerGroup, attrProducers);

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
	 * Run a forward pass through the compiled transformer.
	 *
	 * <p>Before calling this method, the caller must update {@link #position}
	 * and {@link #attributePositions} with the current values.</p>
	 *
	 * @param embeddedInput embedded token of shape (1, hiddenSize)
	 * @return transformer hidden state of shape (1, hiddenSize)
	 */
	public PackedCollection forward(PackedCollection embeddedInput) {
		return compiledTransformer.forward(embeddedInput);
	}

	/**
	 * Update the sequential position for the current step.
	 *
	 * @param step the current position in the sequence
	 */
	public void setPosition(int step) {
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
	public void setAttributePositions(MidiCompoundToken token) {
		double[] values = token.isSpecial()
				? new double[MoonbeamConfig.NUM_ATTRIBUTES]
				: token.toDoubleArray();
		attributePositions.setMem(0, values);
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
	 * Create a typed Producer array, isolating the unavoidable unchecked cast
	 * required by Java's lack of generic array creation.
	 */
	private static Producer<PackedCollection>[] createProducerArray(int size) {
		return new Producer[size];
	}

	/**
	 * Create a LoRA adapter configuration for fine-tuning attention projections.
	 *
	 * <p>Targets all self-attention QKV and output projections with the
	 * LoRA rank and alpha from the training config.</p>
	 *
	 * @param trainingConfig training configuration with LoRA hyperparameters
	 * @return adapter configuration
	 */
	public AdapterConfig createLoraConfig(MidiTrainingConfig trainingConfig) {
		return new AdapterConfig()
				.rank(trainingConfig.getLoraRank())
				.alpha(trainingConfig.getLoraAlpha())
				.targets(
						AdapterConfig.TargetLayer.SELF_ATTENTION_QKV,
						AdapterConfig.TargetLayer.SELF_ATTENTION_OUT);
	}

	/**
	 * Save the current model as a LoRA adapter bundle.
	 *
	 * <p>Creates a {@link ModelBundle} containing the adapter configuration
	 * and training metrics, suitable for later loading and merging.</p>
	 *
	 * @param outputPath     path to save the adapter bundle
	 * @param trainingConfig training configuration used
	 */
	public void saveLoraAdapter(Path outputPath, MidiTrainingConfig trainingConfig)
			throws IOException {
		Map<String, PackedCollection> adapterWeights = new HashMap<>();
		AdapterConfig adapterConfig = createLoraConfig(trainingConfig);

		ModelBundle.forAdapter(adapterWeights, adapterConfig, "moonbeam-midi")
				.config("hidden_size", String.valueOf(config.hiddenSize))
				.config("num_layers", String.valueOf(config.numLayers))
				.config("learning_rate", String.valueOf(trainingConfig.getLearningRate()))
				.withDescription("Moonbeam MIDI LoRA adapter")
				.save(outputPath);
	}

	/**
	 * Returns a formatted string with profiling information.
	 *
	 * @return profiling summary
	 */
	public String getProfilingSummary() {
		return profile.toString();
	}

	/**
	 * Build a GRU decoder from a StateDictionary.
	 *
	 * @param stateDict weight dictionary
	 * @param config    model configuration
	 * @return initialized GRU decoder
	 */
	private static GRUDecoder buildDecoder(StateDictionary stateDict, MoonbeamConfig config) {
		int n = config.decoderLayers;
		int[] inputSizes = new int[n];
		PackedCollection[] weightIh = new PackedCollection[n];
		PackedCollection[] weightHh = new PackedCollection[n];
		PackedCollection[] biasIh = new PackedCollection[n];
		PackedCollection[] biasHh = new PackedCollection[n];
		for (int l = 0; l < n; l++) {
			inputSizes[l] = (l == 0) ? config.hiddenSize : config.decoderHiddenSize;
			weightIh[l] = stateDict.get(String.format("decoder.weight_ih_l%d", l));
			weightHh[l] = stateDict.get(String.format("decoder.weight_hh_l%d", l));
			biasIh[l] = stateDict.get(String.format("decoder.bias_ih_l%d", l));
			biasHh[l] = stateDict.get(String.format("decoder.bias_hh_l%d", l));
		}

		return new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
				stateDict.get("summary_projection.weight"),
				stateDict.get("summary_projection.bias"),
				stateDict.get("decoder.fc_out.weight"),
				stateDict.get("decoder.fc_out.bias"),
				stateDict.get("lm_head.weight"),
				stateDict.get("lm_head.bias"),
				stateDict.get("decoder_embedding.weight"));
	}
}
