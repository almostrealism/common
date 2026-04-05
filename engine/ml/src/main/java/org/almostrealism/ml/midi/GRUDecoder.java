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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.AutoregressiveModel;
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
 * <p>All GRU neural-network math is expressed as a single {@link CompiledModel}
 * decode-step model built in {@link #ensureCompiled()}. One forward pass of that
 * model covers all GRU layers, the optional {@code fc_out} projection, and the
 * {@code lm_head}, taking input {@code [x | h0 | ... | hL]} and producing
 * {@code [h0' | ... | hL' | logits]}.</p>
 *
 * <p>The {@code summary_proj} (transformer hidden to initial decoder hidden) is
 * expressed as a {@link CollectionProducer} computation at the start of each
 * decode call and is evaluated once per note to initialise the hidden states.
 * All per-token work runs through the single compiled model.</p>
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

	/** Model hyperparameters (hidden size, decoder hidden size, vocab sizes, etc.). */
	private final MoonbeamConfig config;
	/** Number of stacked GRU layers in the decoder. */
	private final int numLayers;
	/** Input size for each GRU layer (layer 0 takes transformer hidden; others take decoder hidden). */
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
	 * The single compiled decode-step model.
	 *
	 * <p>Input shape: {@code (1 + numLayers) * decoderHiddenSize}
	 * ({@code [x | h0 | ... | hL]}).
	 * Output shape: {@code numLayers * decoderHiddenSize + decodeVocabSize}
	 * ({@code [h0' | ... | hL' | logits]}).</p>
	 *
	 * <p>Lazily initialised by {@link #ensureCompiled()} on first use.</p>
	 */
	private volatile CompiledModel decodeStepModel;

	/**
	 * Create a GRU decoder with explicit weights.
	 *
	 * <p>Weight tensors are provided in the stacked layout used by the model checkpoint.
	 * The {@code gru_block.pdsl} data block is used at compile time to derive the
	 * per-gate sub-views from these stacked tensors, eliminating the need for a
	 * separate Java weight-holder class.</p>
	 *
	 * @param config           model configuration
	 * @param inputSizes       input size for each layer (differs between layer 0 and deeper layers)
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
	 * Decode GRU output tokens from a transformer hidden state using greedy argmax.
	 *
	 * <p>All GRU neural-network math is performed by the single compiled decode-step
	 * model. The only Java code here is the argmax token selection and data routing
	 * between steps.</p>
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 * @return array of {@link #TOKENS_PER_NOTE} token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden) {
		ensureCompiled();
		return runGruDecode(transformerHidden, 0.0, 1.0, null);
	}

	/**
	 * Decode GRU output tokens with temperature and top-p sampling.
	 *
	 * <p>All GRU neural-network math is performed by the single compiled decode-step
	 * model. The only Java code here is the token selection and data routing
	 * between steps.</p>
	 *
	 * @param transformerHidden transformer output hidden state, shape (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy argmax)
	 * @param topP              nucleus sampling threshold (1.0 = no filtering)
	 * @param random            random number generator for sampling
	 * @return array of {@link #TOKENS_PER_NOTE} token indices in the flat decode vocabulary
	 */
	public int[] decode(PackedCollection transformerHidden, double temperature,
						double topP, Random random) {
		ensureCompiled();
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
	public int getNumLayers() { return numLayers; }

	/** Returns the decoder hidden size. */
	public int getDecoderHiddenSize() { return config.decoderHiddenSize; }

	/** Returns the vocabulary size for each decode step. */
	public int[] getVocabSizesPerStep() { return vocabSizesPerStep.clone(); }

	// -----------------------------------------------------------------------
	//  Model compilation
	// -----------------------------------------------------------------------

	/**
	 * Build and compile the decode-step {@link Model}.
	 *
	 * <p>The model uses one {@link CellularLayer} per GRU layer plus a final
	 * {@code lm_head} layer, all added to a single {@link Model} in sequence.
	 * State is threaded through as a flat vector of shape
	 * {@code (1 + numLayers) * decoderHiddenSize}: slot 0 holds the current
	 * input {@code x} (updated to {@code hNew} after each GRU layer) and
	 * slots {@code 1..numLayers} hold the per-layer hidden states
	 * {@code h0..hL} from the previous time step.  Each GRU block is compiled
	 * independently, preventing the symbolic expression-tree explosion that
	 * occurs when all layers are chained inside a single lambda.</p>
	 *
	 * <p>Thread-safe via double-checked locking on {@link #decodeStepModel}.</p>
	 */
	private void ensureCompiled() {
		if (decodeStepModel != null) return;
		synchronized (this) {
			if (decodeStepModel != null) return;

			// Load gru_block.pdsl to derive per-gate weight sub-views
			PdslLoader loader = new PdslLoader();
			PdslNode.Program gruBlockProgram = loader.parseResource("/pdsl/gru_block.pdsl");

			final int dh = config.decoderHiddenSize;
			// State layout: [x | h0 | h1 | ... | hL]  (slot 0 = x, slot l+1 = h_l)
			final int stateSize = (1 + numLayers) * dh;
			// Output layout: [h0' | h1' | ... | hL' | logits]
			final int outputSize = numLayers * dh + config.decodeVocabSize;

			Model model = new Model(shape(stateSize));

			// One CellularLayer per GRU layer — each compiled independently to
			// prevent symbolic substitution across layers for large hidden sizes.
			for (int l = 0; l < numLayers; l++) {
				final int layerIdx = l;

				// Evaluate gru_weights data block from PDSL to get per-gate sub-views
				Map<String, Object> args = new HashMap<>();
				args.put("weight_ih", weightIh[l]);
				args.put("weight_hh", weightHh[l]);
				args.put("bias_ih", biasIh[l]);
				args.put("bias_hh", biasHh[l]);
				args.put("input_size", inputSizes[l]);
				args.put("hidden_size", dh);
				Map<String, Object> w = loader.evaluateDataDef(gruBlockProgram, "gru_weights", args);

				final PackedCollection wIr = (PackedCollection) w.get("w_ir");
				final PackedCollection bIr = (PackedCollection) w.get("b_ir");
				final PackedCollection wHr = (PackedCollection) w.get("w_hr");
				final PackedCollection bHr = (PackedCollection) w.get("b_hr");
				final PackedCollection wIz = (PackedCollection) w.get("w_iz");
				final PackedCollection bIz = (PackedCollection) w.get("b_iz");
				final PackedCollection wHz = (PackedCollection) w.get("w_hz");
				final PackedCollection bHz = (PackedCollection) w.get("b_hz");
				final PackedCollection wIn = (PackedCollection) w.get("w_in");
				final PackedCollection bIn = (PackedCollection) w.get("b_in");
				final PackedCollection wHn = (PackedCollection) w.get("w_hn");
				final PackedCollection bHn = (PackedCollection) w.get("b_hn");

				CellularLayer gruLayer = layer("gru_layer_" + l,
						shape(stateSize), shape(stateSize), input -> {
					// x = current layer input (slot 0); hl = hidden from last step (slot layerIdx+1)
					CollectionProducer x = c(input).subset(shape(dh), 0).reshape(shape(dh));
					CollectionProducer hl = c(input).subset(shape(dh), (layerIdx + 1) * dh)
							.reshape(shape(dh));

					// Reset gate: r = sigmoid(W_ir @ x + b_ir + W_hr @ h + b_hr)
					CollectionProducer r = sigmoid(
							add(add(matmul(cp(wIr), x), cp(bIr)),
								add(matmul(cp(wHr), hl), cp(bHr))));

					// Update gate: z = sigmoid(W_iz @ x + b_iz + W_hz @ h + b_hz)
					CollectionProducer z = sigmoid(
							add(add(matmul(cp(wIz), x), cp(bIz)),
								add(matmul(cp(wHz), hl), cp(bHz))));

					// Candidate gate: n = tanh(W_in @ x + b_in + r * (W_hn @ h + b_hn))
					CollectionProducer n = tanh(
							add(add(matmul(cp(wIn), x), cp(bIn)),
								r.multiply(add(matmul(cp(wHn), hl), cp(bHn)))));

					// Hidden state update: hNew = (1 - z) * n + z * h
					CollectionProducer hNew = add(
							c(1.0).subtract(z).multiply(n),
							z.multiply(hl)).reshape(shape(dh));

					// Propagate state: update slot 0 (x for next layer) and slot layerIdx+1 (h_l')
					CollectionProducer[] stateParts = new CollectionProducer[1 + numLayers];
					stateParts[0] = hNew;
					for (int s = 1; s <= numLayers; s++) {
						stateParts[s] = (s == layerIdx + 1)
								? hNew
								: c(input).subset(shape(dh), s * dh).reshape(shape(dh));
					}
					return concat(stateParts).reshape(shape(stateSize));
				});
				model.add(gruLayer);
			}

			// lm_head block: reads last GRU output from slot 0, emits [h0'|...|hL'|logits]
			CellularLayer lmHeadLayer = layer("lm_head",
					shape(stateSize), shape(outputSize), input -> {
				CollectionProducer lmIn = c(input).subset(shape(dh), 0).reshape(shape(dh));

				// Optional fc_out projection
				if (fcOutWeight != null) {
					lmIn = add(matmul(cp(fcOutWeight), lmIn), cp(fcOutBias)).reshape(shape(dh));
				}

				// lm_head: project to decode vocabulary logits
				CollectionProducer logits = add(matmul(cp(lmHeadWeight), lmIn), cp(lmHeadBias))
						.reshape(shape(config.decodeVocabSize));

				// Output [h0'|h1'|...|hL'|logits]: hidden slots are at state positions 1..numLayers
				CollectionProducer[] parts = new CollectionProducer[numLayers + 1];
				for (int l = 0; l < numLayers; l++) {
					parts[l] = c(input).subset(shape(dh), (l + 1) * dh).reshape(shape(dh));
				}
				parts[numLayers] = logits;
				return concat(parts).reshape(shape(outputSize));
			});
			model.add(lmHeadLayer);

			this.decodeStepModel = model.compile(false);
		}
	}

	// -----------------------------------------------------------------------
	//  Core decode loop
	// -----------------------------------------------------------------------

	/**
	 * Run the autoregressive GRU decode loop.
	 *
	 * <p>Before the loop: evaluates {@code summary_proj} once per note as a
	 * {@link CollectionProducer} computation to produce the initial decoder hidden state.
	 * Inside the loop: assembles the state vector from current producers, executes the
	 * single compiled decode-step model, extracts new hidden states and logits using
	 * producer subset operations, and delegates token selection to
	 * {@link AutoregressiveModel#sampleToken}.</p>
	 *
	 * @param transformerHidden transformer hidden state, shape (hiddenSize)
	 * @param temperature       sampling temperature (0 = greedy)
	 * @param topP              nucleus sampling threshold
	 * @param random            RNG for sampling (null = greedy)
	 * @return {@link #TOKENS_PER_NOTE} output token indices
	 */
	private int[] runGruDecode(PackedCollection transformerHidden,
								double temperature, double topP, Random random) {
		final int dh = config.decoderHiddenSize;
		final int numLayers = this.numLayers;
		final int inputSize = (1 + numLayers) * dh;

		// One-time initialisation: summary projection before the decode loop.
		// summary_proj = W_s @ transformerHidden + b_s
		PackedCollection initialHidden =
				add(matmul(cp(summaryWeight), cp(transformerHidden)), cp(summaryBias)).evaluate();

		// Initialise all per-layer hidden states from the summary projection
		PackedCollection[] h = new PackedCollection[numLayers];
		for (int l = 0; l < numLayers; l++) {
			h[l] = initialHidden;
		}

		// Initial decoder input: SOS embedding (token index 0)
		PackedCollection x = cp(decoderEmbedding).subset(shape(1, dh), 0, 0).reshape(shape(dh)).evaluate();

		int[] outputTokens = new int[TOKENS_PER_NOTE];

		for (int step = 0; step < TOKENS_PER_NOTE; step++) {
			// Assemble state vector [x | h0 | ... | hL] using producer concat
			CollectionProducer[] stateParts = new CollectionProducer[1 + numLayers];
			stateParts[0] = cp(x).reshape(shape(dh));
			for (int l = 0; l < numLayers; l++) {
				stateParts[l + 1] = cp(h[l]).reshape(shape(dh));
			}
			PackedCollection state = concat(stateParts).reshape(shape(inputSize)).evaluate();

			// Single compiled model forward pass
			PackedCollection output = decodeStepModel.forward(state);

			// Extract new hidden states from output [h0' | ... | hL' | logits]
			for (int l = 0; l < numLayers; l++) {
				h[l] = cp(output).subset(shape(dh), l * dh).evaluate();
			}

			// Extract logits from tail of output
			PackedCollection logits = cp(output)
					.subset(shape(config.decodeVocabSize), numLayers * dh).evaluate();

			// Token selection delegated to AutoregressiveModel
			int token = AutoregressiveModel.sampleToken(logits, config.decodeVocabSize,
					temperature, topP, random);
			outputTokens[step] = token;

			// Embedding lookup for next step input
			x = cp(decoderEmbedding).subset(shape(1, dh), token, 0).reshape(shape(dh)).evaluate();
		}

		return outputTokens;
	}

}
