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

package org.almostrealism.studio.midi;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.almostrealism.music.midi.MidiNoteEvent;

/**
 * SkyTNT midi-model (tv2o-medium) inference implementation.
 *
 * <p>This class implements the dual-transformer LLaMA generation loop described in
 * the SkyTNT architecture. All transformer computation is defined in PDSL files;
 * this class is a pure orchestrator that:</p>
 * <ol>
 *   <li>Loads model weights from a {@link StateDictionary}</li>
 *   <li>Builds two PDSL-compiled transformers ({@code net} and {@code net_token})</li>
 *   <li>Implements the outer event-generation loop (net) and inner token-generation
 *       loop (net_token)</li>
 * </ol>
 *
 * <h2>Architecture Overview</h2>
 * <ul>
 *   <li>{@code net} — 12-layer main transformer; encodes the event sequence (one
 *       summed-embedding position per event).</li>
 *   <li>{@code net_token} — 3-layer token transformer; autoregressively generates
 *       up to 8 tokens per event.  Its KV cache is reset for every new event.</li>
 *   <li>{@code lm_head} — shared projection (no bias) applied after {@code net_token}'s
 *       final norm to produce vocabulary logits.</li>
 * </ul>
 *
 * <h2>PDSL Files</h2>
 * <ul>
 *   <li>{@code /pdsl/midi/skytnt_block.pdsl} — defines {@code skytnt_block} (shared
 *       by both transformers) and its helper {@code skytnt_ffn}.</li>
 *   <li>{@code /pdsl/midi/skytnt_lm_head.pdsl} — defines {@code skytnt_norm} (final
 *       norm for {@code net}) and {@code skytnt_lm_head} (final norm + projection for
 *       {@code net_token}).</li>
 * </ul>
 *
 * @see SkyTntConfig
 * @see SkyTntTokenizerV2
 * @see MidiNoteEvent
 */
public class SkyTntMidi implements AttentionFeatures, ConsoleFeatures {

	/** Default temperature for sampling (1.0 = no temperature scaling). */
	public static final double DEFAULT_TEMPERATURE = 1.0;

	/** Default nucleus sampling threshold. */
	public static final double DEFAULT_TOP_P = 0.98;

	/** Default top-k limit for sampling. */
	public static final int DEFAULT_TOP_K = 20;

	/** Default MIDI PPQ resolution (ticks per quarter-note beat). */
	public static final int DEFAULT_TICKS_PER_BEAT = 480;

	/** Model hyperparameters. */
	private final SkyTntConfig config;

	/** Tokenizer for converting between MIDI events and token sequences. */
	private final SkyTntTokenizerV2 tokenizer;

	/** Token embedding table for the main transformer (net). Shape: [vocabSize, hiddenSize]. */
	private final PackedCollection netEmbedTokens;

	/** Token embedding table for the token transformer (net_token). Shape: [vocabSize, hiddenSize]. */
	private final PackedCollection netTokenEmbedTokens;

	/**
	 * Compiled main transformer (net).
	 *
	 * <p>Input: {@code [1, hiddenSize]} (summed token embeddings for one event position).
	 * Output: {@code [1, hiddenSize]} (hidden state after N LLaMA blocks + final RMSNorm).</p>
	 */
	private final CompiledModel netCompiledModel;

	/**
	 * Compiled token transformer (net_token) with LM head.
	 *
	 * <p>Input: {@code [1, hiddenSize]} (token embedding or hidden state from net).
	 * Output: {@code [1, vocabSize]} (logits after N LLaMA blocks + final norm + lm_head).</p>
	 */
	private final CompiledModel netTokenCompiledModel;

	/**
	 * Scalar position for the main transformer KV cache.
	 *
	 * <p>Increments by one for each new event position processed by {@code net}.
	 * Set via {@code netPosition.setMem(0, pos)} before each {@link #netCompiledModel}
	 * forward pass.</p>
	 */
	private final PackedCollection netPosition;

	/**
	 * Scalar position for the token transformer KV cache.
	 *
	 * <p>Increments by one for each token step in the inner loop.
	 * Reset to 0 at the start of every new event position (clears the logical KV cache
	 * by overwriting positions 0..7 again on the next event).</p>
	 */
	private final PackedCollection netTokenPosition;

	/** Random number generator for sampling. */
	private final Random random;

	/**
	 * Load a SkyTntMidi model from a weights directory.
	 *
	 * <p>The weights directory must contain protobuf files exported by
	 * {@code extract_skytnt_weights.py}.  Keys follow the HuggingFace naming convention:
	 * {@code net.layers.0.input_layernorm.weight}, etc.</p>
	 *
	 * @param weightsDirectory directory containing exported weight files
	 * @throws IOException if weight files cannot be read
	 */
	public SkyTntMidi(String weightsDirectory) throws IOException {
		this(weightsDirectory, new SkyTntConfig(), new Random());
	}

	/**
	 * Load a SkyTntMidi model with an explicit config and RNG.
	 *
	 * @param weightsDirectory directory containing exported weight files
	 * @param config           model hyperparameters
	 * @param random           random number generator for sampling
	 * @throws IOException if weight files cannot be read
	 */
	public SkyTntMidi(String weightsDirectory, SkyTntConfig config, Random random)
			throws IOException {
		this.config = config;
		this.random = random;
		this.tokenizer = new SkyTntTokenizerV2();

		StateDictionary stateDict = new StateDictionary(weightsDirectory);

		this.netEmbedTokens = stateDict.get("net.embed_tokens.weight");
		this.netTokenEmbedTokens = stateDict.get("net_token.embed_tokens.weight");

		this.netPosition = new PackedCollection(1);
		this.netTokenPosition = new PackedCollection(1);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		CollectionProducer netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, config.netHeadSize, config.maxEventSeqLen);
		CollectionProducer netTokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, config.netTokenHeadSize, config.maxEventSeqLen);

		PackedCollection lmHeadWeight = stateDict.get("lm_head.weight");

		log("Building net model (" + config.netLayers + " layers, hiddenSize=" +
				config.hiddenSize + ", heads=" + config.netHeads + ")");
		this.netCompiledModel = buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads, netFreqCis, netPosition, false,
				config.rmsNormEps, null);

		log("Building net_token model (" + config.netTokenLayers + " layers, hiddenSize=" +
				config.hiddenSize + ", heads=" + config.netTokenHeads + ")");
		this.netTokenCompiledModel = buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads, netTokenFreqCis, netTokenPosition,
				true, config.rmsNormEps, lmHeadWeight);

		log("SkyTntMidi model loaded successfully");
	}

	/**
	 * Create a SkyTntMidi instance with pre-built models for testing.
	 *
	 * <p>This constructor is intended for unit tests that verify output shapes and
	 * generation loop correctness without requiring real model weights.</p>
	 *
	 * @param config                  model hyperparameters
	 * @param netEmbedTokens          embedding table for net, shape [vocabSize, hiddenSize]
	 * @param netTokenEmbedTokens     embedding table for net_token, shape [vocabSize, hiddenSize]
	 * @param netCompiledModel        compiled main transformer (net)
	 * @param netTokenCompiledModel   compiled token transformer (net_token)
	 * @param random                  random number generator for sampling
	 */
	public SkyTntMidi(SkyTntConfig config,
			   PackedCollection netEmbedTokens,
			   PackedCollection netTokenEmbedTokens,
			   CompiledModel netCompiledModel,
			   CompiledModel netTokenCompiledModel,
			   Random random) {
		this.config = config;
		this.random = random;
		this.tokenizer = new SkyTntTokenizerV2();
		this.netEmbedTokens = netEmbedTokens;
		this.netTokenEmbedTokens = netTokenEmbedTokens;
		this.netPosition = new PackedCollection(1);
		this.netTokenPosition = new PackedCollection(1);
		this.netCompiledModel = netCompiledModel;
		this.netTokenCompiledModel = netTokenCompiledModel;
	}

	// -----------------------------------------------------------------------
	//  Public generation API
	// -----------------------------------------------------------------------

	/**
	 * Generate a MIDI event sequence from a prompt token sequence with no track filter.
	 *
	 * <p>Equivalent to calling
	 * {@link #generate(int[][], int, double, double, int, int[])} with
	 * {@code allowedTrackIds == null}.</p>
	 *
	 * @param promptTokens  token array of shape (promptLen x maxTokenSeq); must not be null
	 * @param maxNewEvents  maximum number of new events to generate
	 * @param temperature   sampling temperature (0 = greedy argmax)
	 * @param topP          nucleus sampling threshold (1.0 = no nucleus filtering)
	 * @param topK          top-k limit (0 = no top-k filtering)
	 * @return full token array including prompt, shape ((promptLen + generatedLen) x maxTokenSeq)
	 */
	public int[][] generate(int[][] promptTokens, int maxNewEvents,
							double temperature, double topP, int topK) {
		return generate(promptTokens, maxNewEvents, temperature, topP, topK, null);
	}

	/**
	 * Generate a MIDI event sequence from a prompt token sequence, optionally
	 * restricting generated events to a subset of MIDI track IDs.
	 *
	 * <p>The prompt is prefilled into the main transformer's KV cache before generation
	 * begins.  The first row of {@code promptTokens} should be the BOS event
	 * ({@code [1, 0, 0, 0, 0, 0, 0, 0]}).  Generation stops when the model produces
	 * an EOS token or {@code maxNewEvents} events have been generated.</p>
	 *
	 * <p>When {@code allowedTrackIds} is non-null the per-event track parameter
	 * (step 3 of the inner token loop) is constrained to the supplied set: only
	 * track values present in the array can be sampled.  Other event parameters
	 * (event-type, time, channel, pitch, velocity, duration, etc.) are unaffected.
	 * Prompt events are never filtered — they are prefilled verbatim — so events
	 * with disallowed tracks in the prompt remain in the returned sequence.</p>
	 *
	 * @param promptTokens    token array of shape (promptLen x maxTokenSeq); must not be null
	 * @param maxNewEvents    maximum number of new events to generate
	 * @param temperature     sampling temperature (0 = greedy argmax)
	 * @param topP            nucleus sampling threshold (1.0 = no nucleus filtering)
	 * @param topK            top-k limit (0 = no top-k filtering)
	 * @param allowedTrackIds when non-null, restricts generated events to the
	 *                        specified track IDs (0–127). {@code null} means
	 *                        no filter (preserves legacy behaviour). Empty array
	 *                        is rejected because it would block all generation.
	 * @return full token array including prompt, shape ((promptLen + generatedLen) x maxTokenSeq)
	 * @throws IllegalArgumentException if {@code allowedTrackIds} is a zero-length array
	 *         or contains a value outside the valid range [0, 127]
	 */
	public int[][] generate(int[][] promptTokens, int maxNewEvents,
							double temperature, double topP, int topK,
							int[] allowedTrackIds) {
		return generate(promptTokens, maxNewEvents, temperature, topP, topK,
				allowedTrackIds, false);
	}

	/**
	 * Variant of {@link #generate(int[][], int, double, double, int, int[])} that
	 * can additionally forbid the model from emitting {@code patch_change} events
	 * during generation.
	 *
	 * <p>When {@code disablePatchChange} is {@code true} the {@code PATCH_CHANGE}
	 * event type is masked out at the event-type step (step 0), so a channel's
	 * instrument binding established by a seeded prompt {@code patch_change} cannot
	 * be overwritten mid-generation.  This mirrors the SkyTNT reference's
	 * {@code disable_patch_change=True} behaviour.  All other filter semantics are
	 * identical to the {@code allowedTrackIds} overload.</p>
	 *
	 * @param promptTokens       token array of shape (promptLen x maxTokenSeq)
	 * @param maxNewEvents       maximum number of new events to generate
	 * @param temperature        sampling temperature (0 = greedy argmax)
	 * @param topP               nucleus sampling threshold (1.0 = no nucleus filtering)
	 * @param topK               top-k limit (0 = no top-k filtering)
	 * @param allowedTrackIds    when non-null, restricts generated events to the
	 *                           specified track IDs (0–127); {@code null} = no filter
	 * @param disablePatchChange when {@code true}, the model may not generate
	 *                           {@code patch_change} events
	 * @return full token array including prompt
	 * @throws IllegalArgumentException if {@code allowedTrackIds} is a zero-length
	 *         array or contains a value outside [0, 127]
	 */
	public int[][] generate(int[][] promptTokens, int maxNewEvents,
							double temperature, double topP, int topK,
							int[] allowedTrackIds, boolean disablePatchChange) {
		Set<Integer> allowedTrackTokens = validateAndExpandTrackFilter(allowedTrackIds);
		List<int[]> sequence = new ArrayList<>(Arrays.asList(promptTokens));

		// Prefill: process all prompt positions to populate the net KV cache.
		// The return value of the last call is the hidden state for the last prompt position.
		PackedCollection lastHidden = null;
		for (int pos = 0; pos < sequence.size(); pos++) {
			netPosition.setMem(0, pos);
			lastHidden = netCompiledModel.forward(embedAndSumNet(sequence.get(pos)));
		}

		// Generation loop: one iteration = one MIDI event
		for (int gen = 0; gen < maxNewEvents; gen++) {
			if (lastHidden == null) {
				break;
			}

			// ---- Inner loop: generate tokens for the new event ----
			int[] newEventTokens = new int[config.maxTokenSeq];  // initialized to PAD_ID
			netTokenPosition.setMem(0, 0.0);
			int eventTypeId = -1;
			boolean eosReached = false;

			for (int step = 0; step < config.maxTokenSeq; step++) {
				// At step 0: input = net's hidden state (initializes net_token KV cache)
				// At step > 0: input = embedding of previously generated token
				PackedCollection tokenInput = (step == 0)
						? lastHidden
						: embedNetToken(newEventTokens[step - 1]);

				netTokenPosition.setMem(0, step);
				PackedCollection rawLogits = netTokenCompiledModel.forward(tokenInput);

				// Apply validity mask: zero out logits for tokens that are not valid
				// at this step (given the event type chosen at step 0)
				int[] validIds = tokenizer.getValidTokenIds(step, eventTypeId);
				if (step == 0 && disablePatchChange) {
					validIds = removeToken(validIds, SkyTntTokenizerV2.EVENT_PATCH_CHANGE);
				}
				if (step == 3 && allowedTrackTokens != null) {
					validIds = intersectWithTokenSet(validIds, allowedTrackTokens);
				}
				PackedCollection maskedLogits = applyMask(rawLogits, validIds);

				int sampledToken = sampleWithTopK(maskedLogits, temperature, topP, topK);

				if (step == 0) {
					if (sampledToken == config.eosId) {
						eosReached = true;
						break;
					}
					eventTypeId = sampledToken;
				} else if (sampledToken == config.padId) {
					// No more parameters for this event type; fill rest with PAD
					break;
				}

				newEventTokens[step] = sampledToken;
			}

			if (eosReached) {
				break;
			}

			sequence.add(newEventTokens);

			// Advance net's KV cache to the newly generated event position
			int newPos = sequence.size() - 1;
			netPosition.setMem(0, newPos);
			lastHidden = netCompiledModel.forward(embedAndSumNet(newEventTokens));
		}

		return sequence.toArray(new int[0][]);
	}

	/**
	 * Generate complementary MIDI events from a list of existing events with no track filter.
	 *
	 * <p>Equivalent to calling
	 * {@link #generateFromEvents(List, int, double, double, int, int, int[])}
	 * with {@code allowedTrackIds == null}.</p>
	 *
	 * @param promptEvents   existing events to use as context (not returned in output)
	 * @param maxNewEvents   maximum number of new events to generate
	 * @param temperature    sampling temperature
	 * @param topP           nucleus sampling threshold
	 * @param topK           top-k limit (0 = disabled)
	 * @param ticksPerBeat   MIDI PPQ resolution for timing quantization
	 * @return newly generated MIDI events (does not include prompt events); {@code tick} fields
	 *         are measured from time 0 of the full sequence (prompt + generated).
	 *         Caller can locate the prompt/generated boundary at the last tick of the prompt's events.
	 */
	public List<MidiNoteEvent> generateFromEvents(List<MidiNoteEvent> promptEvents,
													int maxNewEvents, double temperature,
													double topP, int topK, int ticksPerBeat) {
		return generateFromEvents(promptEvents, maxNewEvents, temperature,
				topP, topK, ticksPerBeat, null);
	}

	/**
	 * Generate complementary MIDI events from a list of existing events, optionally
	 * restricting generated events to a subset of MIDI track IDs.
	 *
	 * <p>Converts the events to tokens (with BOS), generates up to
	 * {@code maxNewEvents} additional events, detokenizes the full token sequence
	 * (prompt + generated), and returns only the newly generated events (not the
	 * prompt events).</p>
	 *
	 * <p>The {@code tick} field of every returned event is measured from <em>time 0
	 * of the full sequence</em> (i.e., from the beginning of the prompt, not from the
	 * start of the generated portion).  Callers can locate the prompt/generated boundary
	 * at the last tick value in the prompt events.</p>
	 *
	 * <p>When {@code allowedTrackIds} is non-null, generated events are constrained
	 * to the supplied track IDs (see
	 * {@link #generate(int[][], int, double, double, int, int[])} for full filter
	 * semantics).  Prompt events are tokenized verbatim — the filter does not
	 * touch them — and are absent from the returned list either way.</p>
	 *
	 * @param promptEvents    existing events to use as context (not returned in output)
	 * @param maxNewEvents    maximum number of new events to generate
	 * @param temperature     sampling temperature
	 * @param topP            nucleus sampling threshold
	 * @param topK            top-k limit (0 = disabled)
	 * @param ticksPerBeat    MIDI PPQ resolution for timing quantization
	 * @param allowedTrackIds when non-null, restricts generated events to the
	 *                        specified track IDs (0–127). {@code null} means
	 *                        no filter. Empty array is rejected.
	 * @return newly generated MIDI events (does not include prompt events); {@code tick}
	 *         fields are absolute (measured from time 0 of the full sequence)
	 * @throws IllegalArgumentException if {@code allowedTrackIds} is a zero-length array
	 *         or contains a value outside the valid range [0, 127]
	 */
	public List<MidiNoteEvent> generateFromEvents(List<MidiNoteEvent> promptEvents,
													int maxNewEvents, double temperature,
													double topP, int topK, int ticksPerBeat,
													int[] allowedTrackIds) {
		return generateFromEvents(promptEvents, maxNewEvents, temperature, topP, topK,
				ticksPerBeat, allowedTrackIds, false);
	}

	/**
	 * Variant of
	 * {@link #generateFromEvents(List, int, double, double, int, int, int[])} that
	 * can additionally forbid the model from emitting {@code patch_change} events
	 * (see {@link #generate(int[][], int, double, double, int, int[], boolean)}).
	 *
	 * @param promptEvents       existing events to use as context (not returned)
	 * @param maxNewEvents       maximum number of new events to generate
	 * @param temperature        sampling temperature
	 * @param topP               nucleus sampling threshold
	 * @param topK               top-k limit (0 = disabled)
	 * @param ticksPerBeat       MIDI PPQ resolution for timing quantization
	 * @param allowedTrackIds    when non-null, restricts generated events to the
	 *                           supplied track IDs
	 * @param disablePatchChange when {@code true}, the model may not generate
	 *                           {@code patch_change} events
	 * @return newly generated MIDI events (does not include prompt events)
	 */
	public List<MidiNoteEvent> generateFromEvents(List<MidiNoteEvent> promptEvents,
													int maxNewEvents, double temperature,
													double topP, int topK, int ticksPerBeat,
													int[] allowedTrackIds,
													boolean disablePatchChange) {
		int[][] promptTokens = tokenizer.tokenize(promptEvents, ticksPerBeat);
		int promptLen = promptTokens.length;

		int[][] fullSequence = generate(promptTokens, maxNewEvents, temperature, topP, topK,
				allowedTrackIds, disablePatchChange);

		if (fullSequence.length <= promptLen) {
			return new ArrayList<>();
		}

		// Detokenize the full sequence so that the accumulated time1 state from the prompt
		// is carried through to the generated rows, producing absolute ticks throughout.
		List<MidiNoteEvent> allEvents = tokenizer.detokenize(fullSequence, ticksPerBeat);
		int promptEventCount = promptEvents.size();
		if (allEvents.size() <= promptEventCount) {
			return new ArrayList<>();
		}
		return new ArrayList<>(allEvents.subList(promptEventCount, allEvents.size()));
	}

	// -----------------------------------------------------------------------
	//  Accessors for testing
	// -----------------------------------------------------------------------

	/** Returns the model configuration. */
	public SkyTntConfig getConfig() { return config; }

	/** Returns the tokenizer. */
	public SkyTntTokenizerV2 getTokenizer() { return tokenizer; }

	// -----------------------------------------------------------------------
	//  Model construction (static to keep the class orchestration-only)
	// -----------------------------------------------------------------------

	/**
	 * Build and compile a LLaMA-style transformer using PDSL block definitions.
	 *
	 * <p>Each block is assembled via {@link PdslLoader#buildLayer} with layer-specific
	 * weights from the {@link StateDictionary}.  All neural-network computation
	 * (attention, RMSNorm, SwiGLU FFN, residual connections) is defined in the
	 * {@code skytnt_block.pdsl} and {@code skytnt_lm_head.pdsl} PDSL files; this
	 * method only assembles the block sequence and manages weight loading.</p>
	 *
	 * @param prefix        weight key prefix ({@code "net"} or {@code "net_token"})
	 * @param stateDict     weight source
	 * @param blockProgram  parsed {@code skytnt_block.pdsl}
	 * @param lmHeadProgram parsed {@code skytnt_lm_head.pdsl}
	 * @param numLayers     number of LLaMA blocks
	 * @param numHeads      number of attention heads
	 * @param freqCis       precomputed RoPE frequency table
	 * @param position      scalar position reference for KV cache indexing
	 * @param withLmHead    if true, append an LM head after the final norm
	 * @param epsilon       RMSNorm epsilon
	 * @param lmHeadWeight  LM head weight (only used when withLmHead is true)
	 * @return compiled model ready for inference
	 */
	public static CompiledModel buildTransformerModel(String prefix, StateDictionary stateDict,
											   PdslNode.Program blockProgram,
											   PdslNode.Program lmHeadProgram,
											   int numLayers, int numHeads,
											   CollectionProducer freqCis,
											   PackedCollection position,
											   boolean withLmHead,
											   double epsilon,
											   PackedCollection lmHeadWeight) {
		PdslLoader loader = new PdslLoader();
		int hiddenSize = freqCis.getShape().length(1) * 2 * numHeads;
		TraversalPolicy inputShape = new TraversalPolicy(1, hiddenSize);
		// NOTE: Model is intentionally not closed via try-with-resources here.
		// CompiledModel.forward() depends on the blocks added to this Model;
		// closing the Model would free those blocks and cause NullPointerExceptions
		// during inference. Lifecycle management is the caller's responsibility.
		Model model = new Model(inputShape);

		for (int i = 0; i < numLayers; i++) {
			String layerKey = prefix + ".layers." + i;

			Map<String, Object> args = new HashMap<>();
			args.put("heads", numHeads);
			args.put("rms_att_weight", stateDict.get(layerKey + ".input_layernorm.weight"));
			args.put("wq", stateDict.get(layerKey + ".self_attn.q_proj.weight"));
			args.put("wk", stateDict.get(layerKey + ".self_attn.k_proj.weight"));
			args.put("wv", stateDict.get(layerKey + ".self_attn.v_proj.weight"));
			args.put("wo", stateDict.get(layerKey + ".self_attn.o_proj.weight"));
			args.put("freq_cis", freqCis);
			args.put("position", position);
			args.put("rms_ffn_weight", stateDict.get(layerKey + ".post_attention_layernorm.weight"));
			args.put("gate_proj", stateDict.get(layerKey + ".mlp.gate_proj.weight"));
			args.put("up_proj", stateDict.get(layerKey + ".mlp.up_proj.weight"));
			args.put("down_proj", stateDict.get(layerKey + ".mlp.down_proj.weight"));
			args.put("epsilon", epsilon);

			Block block = loader.buildLayer(blockProgram, "skytnt_block", inputShape, args);
			model.add(block);
		}

		PackedCollection normWeight = stateDict.get(prefix + ".norm.weight");

		if (withLmHead) {
			Map<String, Object> headArgs = new HashMap<>();
			headArgs.put("norm_weights", normWeight);
			headArgs.put("lm_head_weight", lmHeadWeight);
			headArgs.put("epsilon", epsilon);

			Block lmHeadBlock = loader.buildLayer(lmHeadProgram, "skytnt_lm_head",
					inputShape, headArgs);
			model.add(lmHeadBlock);
		} else {
			Map<String, Object> normArgs = new HashMap<>();
			normArgs.put("norm_weights", normWeight);
			normArgs.put("epsilon", epsilon);

			Block normBlock = loader.buildLayer(lmHeadProgram, "skytnt_norm",
					inputShape, normArgs);
			model.add(normBlock);
		}

		return model.compile(false);
	}

	// -----------------------------------------------------------------------
	//  Embedding helpers (orchestration -- not model computation)
	// -----------------------------------------------------------------------

	/**
	 * Compute the summed token embedding for one event row, used as input to net.
	 *
	 * <p>Looks up the embedding vector for each non-PAD token in the event's
	 * {@code maxTokenSeq} slots and sums them element-wise.  This is the
	 * {@code embed_tokens(x).sum(dim=-2)} operation from the Python model.</p>
	 *
	 * <p>Builds a Producer DAG of per-token {@code subset} lookups into the
	 * embedding table and materializes it with a single {@code .evaluate()} at
	 * the step boundary (immediately before {@code netCompiledModel.forward}).
	 * This is the sole pipeline-boundary evaluation in this class.</p>
	 *
	 * @param tokenRow array of {@code maxTokenSeq} token IDs
	 * @return summed embedding, shape {@code [1, hiddenSize]}
	 */
	private PackedCollection embedAndSumNet(int[] tokenRow) {
		CollectionProducer sum = cp(netEmbedTokens)
				.subset(shape(1, config.hiddenSize), tokenRow[0], 0)
				.reshape(shape(config.hiddenSize));

		for (int j = 1; j < config.maxTokenSeq; j++) {
			if (tokenRow[j] != config.padId) {
				sum = sum.add(cp(netEmbedTokens)
						.subset(shape(1, config.hiddenSize), tokenRow[j], 0)
						.reshape(shape(config.hiddenSize)));
			}
		}

		return sum.reshape(shape(1, config.hiddenSize)).evaluate();
	}

	/**
	 * Look up the token embedding for a single token ID from the net_token embedding table.
	 *
	 * @param tokenId token index
	 * @return token embedding, shape {@code [1, hiddenSize]}
	 */
	private PackedCollection embedNetToken(int tokenId) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(1, config.hiddenSize));
		result.setMem(0, netTokenEmbedTokens, tokenId * config.hiddenSize, config.hiddenSize);
		return result;
	}

	// -----------------------------------------------------------------------
	//  Track-filter helpers (orchestration over int[] vocabulary positions)
	// -----------------------------------------------------------------------

	/**
	 * Validates a caller-supplied {@code allowedTrackIds} array and expands it
	 * to the corresponding vocabulary token positions used at step 3 of the
	 * inner token loop.
	 *
	 * <p>The returned set is built once per call to {@code generate()} and
	 * re-used on every iteration of the inner token loop, so the per-event
	 * filter intersection at step 3 is O(|validIds|) rather than O(|validIds|
	 * + |filter|).</p>
	 *
	 * @param allowedTrackIds the raw track ID filter from the caller; may be null
	 * @return null when {@code allowedTrackIds} is null (no filter); otherwise a
	 *         set of vocabulary positions {@code TRACK_OFFSET + id} for each
	 *         allowed track ID
	 * @throws IllegalArgumentException if the array is empty or contains values
	 *         outside [0, 127]
	 */
	static Set<Integer> validateAndExpandTrackFilter(int[] allowedTrackIds) {
		if (allowedTrackIds == null) {
			return null;
		}
		if (allowedTrackIds.length == 0) {
			throw new IllegalArgumentException(
					"empty track filter would block all generation");
		}
		Set<Integer> tokens = new HashSet<>(allowedTrackIds.length * 2);
		for (int i = 0; i < allowedTrackIds.length; i++) {
			int id = allowedTrackIds[i];
			if (id < 0 || id >= SkyTntTokenizerV2.TRACK_RANGE) {
				throw new IllegalArgumentException(
						"allowedTrackIds[" + i + "] = " + id +
								" is out of range [0, " +
								(SkyTntTokenizerV2.TRACK_RANGE - 1) + "]");
			}
			tokens.add(SkyTntTokenizerV2.TRACK_OFFSET + id);
		}
		return tokens;
	}

	/**
	 * Returns the intersection of a tokenizer validity mask with a
	 * caller-supplied allow-list expressed as a {@link Set} of token IDs.
	 * The result preserves the order of the input {@code validIds}, which
	 * keeps the per-step distribution shape predictable.
	 *
	 * @param validIds   the base validity mask from the tokenizer
	 * @param filterSet  the supplemental allow-list (e.g. allowed track token IDs)
	 * @return new array containing only token IDs present in both inputs
	 */
	static int[] intersectWithTokenSet(int[] validIds, Set<Integer> filterSet) {
		int[] tmp = new int[validIds.length];
		int n = 0;
		for (int id : validIds) {
			if (filterSet.contains(id)) {
				tmp[n++] = id;
			}
		}
		return Arrays.copyOf(tmp, n);
	}

	/**
	 * Returns a copy of {@code validIds} with every occurrence of
	 * {@code excludedId} removed.  Used to forbid a specific event type (e.g.
	 * {@code PATCH_CHANGE}) at the event-type step.
	 *
	 * @param validIds   the candidate token IDs
	 * @param excludedId the token ID to drop
	 * @return a new array without {@code excludedId}
	 */
	static int[] removeToken(int[] validIds, int excludedId) {
		int[] tmp = new int[validIds.length];
		int n = 0;
		for (int id : validIds) {
			if (id != excludedId) {
				tmp[n++] = id;
			}
		}
		return Arrays.copyOf(tmp, n);
	}

	// -----------------------------------------------------------------------
	//  Sampling helpers (orchestration -- double[] explicitly allowed here)
	// -----------------------------------------------------------------------

	/**
	 * Apply a validity mask to logits by setting disallowed token positions to
	 * a large negative value, leaving allowed positions unchanged.
	 *
	 * <p>This is pure Java orchestration over the logits output from the model;
	 * it does not invoke any model computation primitives.</p>
	 *
	 * @param logits   raw logits from net_token, shape [1, vocabSize]
	 * @param validIds token IDs that are allowed at this generation step
	 * @return masked logits collection, shape [vocabSize]
	 */
	private PackedCollection applyMask(PackedCollection logits, int[] validIds) {
		double[] values = new double[config.vocabSize];
		Arrays.fill(values, -1e9);
		for (int id : validIds) {
			values[id] = logits.toDouble(id);
		}
		PackedCollection masked = new PackedCollection(config.vocabSize);
		masked.setMem(0, values);
		return masked;
	}

	/**
	 * Sample a token from masked logits, applying top-k filtering (if requested)
	 * before top-p nucleus sampling.
	 *
	 * <p>Top-k is implemented by finding the k-th largest logit value and masking
	 * all tokens below that threshold.  The result is then passed to
	 * {@link AutoregressiveModel#sampleToken} which handles temperature scaling,
	 * softmax, and top-p nucleus sampling.</p>
	 *
	 * <p>Note: the use of a {@code double[]} here is explicitly allowed -- this
	 * method is token-selection orchestration, not model computation.</p>
	 *
	 * @param maskedLogits logits after validity mask, shape [vocabSize]
	 * @param temperature  sampling temperature (0 = greedy)
	 * @param topP         nucleus sampling threshold
	 * @param topK         top-k limit (0 or negative = disabled)
	 * @return sampled token ID
	 */
	private int sampleWithTopK(PackedCollection maskedLogits, double temperature,
							   double topP, int topK) {
		if (topK > 0 && topK < config.vocabSize) {
			double[] values = new double[config.vocabSize];
			for (int i = 0; i < config.vocabSize; i++) {
				values[i] = maskedLogits.toDouble(i);
			}
			double[] sorted = values.clone();
			Arrays.sort(sorted);
			double threshold = sorted[config.vocabSize - topK];

			double[] filtered = new double[config.vocabSize];
			for (int i = 0; i < config.vocabSize; i++) {
				filtered[i] = values[i] >= threshold ? values[i] : -1e9;
			}
			PackedCollection topKLogits = new PackedCollection(config.vocabSize);
			topKLogits.setMem(0, filtered);
			return AutoregressiveModel.sampleToken(topKLogits, config.vocabSize,
					temperature, topP, random);
		}
		return AutoregressiveModel.sampleToken(maskedLogits, config.vocabSize,
				temperature, topP, random);
	}

}
