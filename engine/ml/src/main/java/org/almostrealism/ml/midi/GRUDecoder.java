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
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * GRU decoder for compound MIDI token generation.
 *
 * <p>The decoder's structure is the {@link #GRU_DECODER_ASSET} asset: a start model, run once per
 * note, that projects the transformer hidden state to the decoder hidden size and makes it every
 * GRU layer's initial hidden state; and a step model, run once per decode step, that advances every
 * GRU layer from the embedding of the previous token and projects the last layer's hidden state
 * (through {@code fc_out} when the checkpoint has it) to logits over the flat decode vocabulary.
 * The hidden state is a {@code [layers, decoderHiddenSize]} collection that both models read and
 * write, so it persists from one step of a note to the next.</p>
 *
 * <p>This class binds the weights, allocates the hidden state, builds and compiles the two models,
 * and runs the decode loop: choosing each token from the logits and looking up its embedding for
 * the next step happen between forward passes. Because the hidden state belongs to the decoder, a
 * decoder decodes one note at a time.</p>
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
public class GRUDecoder implements LayerFeatures {

	/** Number of output tokens generated per position by the GRU decoder. */
	public static final int TOKENS_PER_NOTE = 7;

	/**
	 * Classpath location of the asset describing the decoder's structure. Its
	 * {@code gru_decoder_start} layer is the start model; its {@code gru_decoder_layer} layers, one
	 * per GRU layer, followed by {@code gru_decoder_logits} or {@code gru_decoder_logits_fc_out},
	 * are the step model.
	 */
	public static final String GRU_DECODER_ASSET = "/pdsl/midi/gru_decoder.pdsl";

	/** Model hyperparameters (hidden size, decoder hidden size, vocab sizes, etc.). */
	private final MoonbeamConfig config;
	/** Number of stacked GRU layers in the decoder. */
	private final int numLayers;
	/**
	 * Input size of each GRU layer: the decoder embedding's width for layer 0, the decoder hidden
	 * size for every later layer.
	 */
	private final int[] inputSizes;
	/** Input-hidden weight matrices ({@code [3*dh, inputSize]}) for each layer. */
	private final PackedCollection[] weightIh;
	/** Hidden-hidden weight matrices ({@code [3*dh, dh]}) for each layer. */
	private final PackedCollection[] weightHh;
	/** Input-hidden bias vectors ({@code [3*dh]}) for each layer. */
	private final PackedCollection[] biasIh;
	/** Hidden-hidden bias vectors ({@code [3*dh]}) for each layer. */
	private final PackedCollection[] biasHh;
	/** Summary projection weight: maps transformer hidden state to initial decoder hidden state. */
	private final PackedCollection summaryWeight;
	/** Summary projection bias. */
	private final PackedCollection summaryBias;
	/** First-pass output projection weight (intermediate step before lm_head). */
	private final PackedCollection fcOutWeight;
	/** First-pass output projection bias. */
	private final PackedCollection fcOutBias;
	/** Language model head weight: maps decoder hidden state to flat vocabulary logits. */
	private final PackedCollection lmHeadWeight;
	/** Language model head bias. */
	private final PackedCollection lmHeadBias;
	/** Token embedding table for the decoder input at each generation step. */
	private final PackedCollection decoderEmbedding;
	/** Cumulative vocabulary offsets per step, used to map flat-vocab indices to per-attribute indices. */
	private final int[] vocabOffsets;
	/** Per-step vocabulary sizes (one per token in {@link #TOKENS_PER_NOTE}). */
	private final int[] vocabSizesPerStep;

	/**
	 * The GRU hidden state, {@code [layers, decoderHiddenSize]}: row {@code l} holds layer
	 * {@code l}'s hidden state. The start model writes every row; each step reads and rewrites them.
	 */
	private final PackedCollection hiddenState;

	/**
	 * The compiled start model: transformer hidden state in, every row of {@link #hiddenState}
	 * written. Lazily initialised by {@link #ensureCompiled()} together with {@link #stepModel}.
	 */
	private volatile CompiledModel startModel;

	/**
	 * The compiled step model: embedding of the previous token in, logits over the flat decode
	 * vocabulary out, {@link #hiddenState} advanced. Lazily initialised by {@link #ensureCompiled()},
	 * after {@link #startModel}.
	 */
	private volatile CompiledModel stepModel;

	/**
	 * Create a GRU decoder with explicit weights.
	 *
	 * <p>Weight tensors are provided in the stacked layout used by the model checkpoint (each
	 * layer's reset, update and candidate gates stacked in that order), which is the layout the
	 * asset's GRU cell consumes directly.</p>
	 *
	 * @param config           model configuration
	 * @param inputSizes       input size for each layer: the decoder embedding's width for layer 0,
	 *                         the decoder hidden size for deeper layers
	 * @param weightIh         stacked input-hidden weights per layer, shape (3*hiddenSize, inputSize)
	 * @param weightHh         stacked hidden-hidden weights per layer, shape (3*hiddenSize, hiddenSize)
	 * @param biasIh           stacked input-hidden biases per layer, shape (3*hiddenSize)
	 * @param biasHh           stacked hidden-hidden biases per layer, shape (3*hiddenSize)
	 * @param summaryWeight    summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias      summary projection bias (decoderHiddenSize)
	 * @param fcOutWeight      fc_out projection weights (may be null)
	 * @param fcOutBias        fc_out projection bias (may be null)
	 * @param lmHeadWeight     output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias       output projection bias (decodeVocabSize)
	 * @param decoderEmbedding output token embedding (decodeVocabSize, decoderHiddenSize)
	 */
	public GRUDecoder(MoonbeamConfig config,
					  int[] inputSizes, PackedCollection[] weightIh, PackedCollection[] weightHh,
					  PackedCollection[] biasIh, PackedCollection[] biasHh,
					  PackedCollection summaryWeight, PackedCollection summaryBias,
					  PackedCollection fcOutWeight, PackedCollection fcOutBias,
					  PackedCollection lmHeadWeight, PackedCollection lmHeadBias,
					  PackedCollection decoderEmbedding) {
		this.config = config;
		this.numLayers = inputSizes.length;
		this.inputSizes = inputSizes;
		this.weightIh = weightIh;
		this.weightHh = weightHh;
		this.biasIh = biasIh;
		this.biasHh = biasHh;
		this.summaryWeight = summaryWeight;
		this.summaryBias = summaryBias;
		this.fcOutWeight = fcOutWeight;
		this.fcOutBias = fcOutBias;
		this.lmHeadWeight = lmHeadWeight;
		this.lmHeadBias = lmHeadBias;
		this.decoderEmbedding = decoderEmbedding;
		this.vocabOffsets = computeVocabOffsets(config);
		this.vocabSizesPerStep = computeVocabSizesPerStep(config);
		validateLayers();
		this.hiddenState = new PackedCollection(shape(numLayers, config.decoderHiddenSize));
	}

	/**
	 * Create a GRU decoder without fc_out layer.
	 *
	 * @param config           model configuration
	 * @param inputSizes       input size for each layer
	 * @param weightIh         stacked input-hidden weights per layer
	 * @param weightHh         stacked hidden-hidden weights per layer
	 * @param biasIh           stacked input-hidden biases per layer
	 * @param biasHh           stacked hidden-hidden biases per layer
	 * @param summaryWeight    summary projection weights (decoderHiddenSize, hiddenSize)
	 * @param summaryBias      summary projection bias (decoderHiddenSize)
	 * @param lmHeadWeight     output projection weights (decodeVocabSize, decoderHiddenSize)
	 * @param lmHeadBias       output projection bias (decodeVocabSize)
	 * @param decoderEmbedding output token embedding (decodeVocabSize, decoderHiddenSize)
	 */
	public GRUDecoder(MoonbeamConfig config,
					  int[] inputSizes, PackedCollection[] weightIh, PackedCollection[] weightHh,
					  PackedCollection[] biasIh, PackedCollection[] biasHh,
					  PackedCollection summaryWeight, PackedCollection summaryBias,
					  PackedCollection lmHeadWeight, PackedCollection lmHeadBias,
					  PackedCollection decoderEmbedding) {
		this(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
				summaryWeight, summaryBias, null, null, lmHeadWeight, lmHeadBias, decoderEmbedding);
	}

	/**
	 * Loads a GRU decoder from the weights of a Moonbeam checkpoint: the stacked
	 * {@code decoder.weight_ih_l*}, {@code decoder.weight_hh_l*}, {@code decoder.bias_ih_l*} and
	 * {@code decoder.bias_hh_l*} tensors of every GRU layer, {@code summary_projection.*},
	 * {@code lm_head.*}, {@code decoder_embedding.weight}, and {@code decoder.fc_out.*} when the
	 * checkpoint has that projection.
	 *
	 * <p>Each layer's input size is the one its input-hidden weights take. That is the decoder
	 * hidden size for every layer, the first included: the first layer's input is the decoder
	 * embedding of the previous token, not the transformer hidden state, which reaches the
	 * decoder only through the summary projection.</p>
	 *
	 * @param stateDict checkpoint weights
	 * @param config    model configuration
	 * @return the decoder
	 * @throws IllegalArgumentException if the checkpoint's decoder weights do not fit together
	 */
	public static GRUDecoder load(StateDictionary stateDict, MoonbeamConfig config) {
		int n = config.decoderLayers;
		int[] inputSizes = new int[n];
		PackedCollection[] weightIh = new PackedCollection[n];
		PackedCollection[] weightHh = new PackedCollection[n];
		PackedCollection[] biasIh = new PackedCollection[n];
		PackedCollection[] biasHh = new PackedCollection[n];
		for (int l = 0; l < n; l++) {
			weightIh[l] = stateDict.get(String.format("decoder.weight_ih_l%d", l));
			weightHh[l] = stateDict.get(String.format("decoder.weight_hh_l%d", l));
			biasIh[l] = stateDict.get(String.format("decoder.bias_ih_l%d", l));
			biasHh[l] = stateDict.get(String.format("decoder.bias_hh_l%d", l));
			inputSizes[l] = weightIh[l].getShape().length(1);
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

	/**
	 * Decode GRU output tokens from a transformer hidden state using greedy argmax.
	 *
	 * <p>All GRU neural-network math is performed by the start and step models built from
	 * {@link #GRU_DECODER_ASSET}. The only Java code here is the argmax token selection and the
	 * embedding lookup of the chosen token between steps.</p>
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 * @return array of {@link #TOKENS_PER_NOTE} token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden) {
		return runGruDecode(transformerHidden, 0.0, 1.0, null);
	}

	/**
	 * Decode GRU output tokens with temperature and top-p sampling.
	 *
	 * <p>All GRU neural-network math is performed by the start and step models built from
	 * {@link #GRU_DECODER_ASSET}. The only Java code here is the token selection and the
	 * embedding lookup of the chosen token between steps.</p>
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy argmax)
	 * @param topP              nucleus sampling threshold (1.0 = no filtering)
	 * @param random            random number generator for sampling
	 * @return array of {@link #TOKENS_PER_NOTE} token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden, double temperature,
						double topP, Random random) {
		return runGruDecode(transformerHidden, temperature, topP, random);
	}

	/**
	 * Starts the decode of one note: runs the start model, which projects
	 * {@code transformerHidden} to the decoder hidden size and makes the projection every GRU
	 * layer's hidden state.
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 */
	public void start(PackedCollection transformerHidden) {
		ensureCompiled();
		startModel.forward(transformerHidden);
	}

	/**
	 * Runs one decode step: advances every GRU layer's hidden state from {@code input} and
	 * returns the logits over the flat decode vocabulary. The first step of a note follows
	 * {@link #start}.
	 *
	 * @param input the decoder embedding of the previous token, shape (decoderHiddenSize)
	 * @return the logits, shape (decodeVocabSize); the collection is the step model's output and
	 *         is overwritten by the next step
	 */
	public PackedCollection step(PackedCollection input) {
		ensureCompiled();
		return stepModel.forward(input);
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
	public int getNumLayers() { return numLayers; }

	/** Returns the decoder hidden size. */
	public int getDecoderHiddenSize() { return config.decoderHiddenSize; }

	/** Returns the vocabulary size for each decode step. */
	public int[] getVocabSizesPerStep() { return vocabSizesPerStep.clone(); }

	// -----------------------------------------------------------------------
	//  Model compilation
	// -----------------------------------------------------------------------

	/**
	 * Checks that every GRU layer is declared to take, and has input-hidden weights that take,
	 * the input the decode feeds it: the decoder embedding of the previous token for the first
	 * layer, and the previous layer's hidden state for every later one.
	 *
	 * @throws IllegalArgumentException if a layer's declared input size or its input-hidden
	 *                                  weights do not match the input it is fed
	 */
	private void validateLayers() {
		for (int l = 0; l < numLayers; l++) {
			int fed = l == 0 ? decoderEmbedding.getShape().length(1) : config.decoderHiddenSize;
			int weightInput = weightIh[l].getShape().length(1);
			if (inputSizes[l] != fed || weightInput != fed) {
				throw new IllegalArgumentException("GRU layer " + l + " is fed "
						+ (l == 0 ? "the decoder embedding of the previous token" : "the previous layer's hidden state")
						+ ", of size " + fed + ", but is declared to take inputs of size " + inputSizes[l]
						+ " and its input-hidden weights " + weightIh[l].getShape()
						+ " take inputs of size " + weightInput);
			}
		}
	}

	/**
	 * Builds the start and step models from {@link #GRU_DECODER_ASSET} and compiles them.
	 *
	 * <p>The start model is the asset's {@code gru_decoder_start} layer. The step model is one
	 * {@code gru_decoder_layer} per GRU layer, bound to that layer's weights and row of the hidden
	 * state, followed by the logits head: {@code gru_decoder_logits_fc_out} when the checkpoint
	 * has an fc_out projection, {@code gru_decoder_logits} otherwise.</p>
	 *
	 * <p>Thread-safe via double-checked locking on {@link #stepModel}, which is assigned last.</p>
	 */
	private void ensureCompiled() {
		if (stepModel != null) return;
		synchronized (this) {
			if (stepModel != null) return;

			PdslLoader loader = new PdslLoader();
			PdslNode.Program program = loader.parseResource(GRU_DECODER_ASSET);

			Map<String, Object> startArgs = new HashMap<>();
			startArgs.put("hidden", hiddenState);
			startArgs.put("num_layers", numLayers);
			startArgs.put("summary_weight", summaryWeight);
			startArgs.put("summary_bias", summaryBias);
			TraversalPolicy transformerShape = shape(summaryWeight.getShape().length(1));
			Model start = new Model(transformerShape);
			start.add(loader.buildLayer(program, "gru_decoder_start", transformerShape, startArgs));

			Model step = new Model(shape(inputSizes[0]));
			for (int l = 0; l < numLayers; l++) {
				Map<String, Object> args = new HashMap<>();
				args.put("hidden", hiddenState);
				args.put("layer_index", l);
				args.put("input_size", inputSizes[l]);
				args.put("hidden_size", config.decoderHiddenSize);
				args.put("weight_ih", weightIh[l]);
				args.put("weight_hh", weightHh[l]);
				args.put("bias_ih", biasIh[l]);
				args.put("bias_hh", biasHh[l]);
				step.add(loader.buildLayer(program, "gru_decoder_layer", shape(inputSizes[l]), args));
			}

			Map<String, Object> headArgs = new HashMap<>();
			headArgs.put("lm_head_weight", lmHeadWeight);
			headArgs.put("lm_head_bias", lmHeadBias);
			String head = "gru_decoder_logits";
			if (fcOutWeight != null) {
				headArgs.put("fc_out_weight", fcOutWeight);
				headArgs.put("fc_out_bias", fcOutBias);
				head = "gru_decoder_logits_fc_out";
			}
			step.add(loader.buildLayer(program, head, shape(config.decoderHiddenSize), headArgs));

			this.startModel = start.compile(false);
			this.stepModel = step.compile(false);
		}
	}

	// -----------------------------------------------------------------------
	//  Core decode loop
	// -----------------------------------------------------------------------

	/**
	 * Run the autoregressive GRU decode loop for one note.
	 *
	 * <p>{@link #start} sets every layer's hidden state from the transformer hidden state. Each of
	 * the {@link #TOKENS_PER_NOTE} steps then runs {@link #step} on the decoder embedding of the
	 * previous token (token 0, the start token, for the first step) and delegates choosing the next
	 * token from the logits to {@link AutoregressiveModel#sampleToken}.</p>
	 *
	 * @param transformerHidden transformer hidden state, shape (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy)
	 * @param topP              nucleus sampling threshold
	 * @param random            RNG for sampling (null = greedy)
	 * @return {@link #TOKENS_PER_NOTE} output token indices
	 */
	private int[] runGruDecode(PackedCollection transformerHidden,
								double temperature, double topP, Random random) {
		int embeddingSize = inputSizes[0];
		start(transformerHidden);

		int[] outputTokens = new int[TOKENS_PER_NOTE];
		int token = 0;
		for (int i = 0; i < TOKENS_PER_NOTE; i++) {
			PackedCollection input = cp(decoderEmbedding).subset(shape(1, embeddingSize), token, 0)
					.reshape(shape(embeddingSize)).evaluate();
			token = AutoregressiveModel.sampleToken(step(input), config.decodeVocabSize,
					temperature, topP, random);
			outputTokens[i] = token;
		}

		return outputTokens;
	}

}
