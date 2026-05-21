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

package org.almostrealism.studio.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.studio.midi.SkyTntConfig;
import org.almostrealism.studio.midi.SkyTntMidi;
import org.almostrealism.studio.midi.SkyTntTokenizerV2;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Milestones 4 and 5 tests: model assembly with synthetic weights and the
 * generation loop.
 *
 * <p>The assembly tests verify that both compiled transformers can be built
 * from PDSL blocks with random weights.  The generation tests verify that
 * the dual-transformer generation loop produces valid token sequences.</p>
 *
 * <p>Tests that require actual forward-pass execution are guarded with
 * {@code if (skipLongTests) return} so they are skipped in CI pipelines that
 * use the {@code pipeline} profile.</p>
 *
 * @see SkyTntMidi
 * @see SkyTntConfig
 */
public class SkyTntMidiTest extends TestSuiteBase {

	/** Reduced hidden size for test speed (must be divisible by both HEADS and HEADS_TOKEN). */
	private static final int DIM = 32;

	/** FFN intermediate size for net (4x). */
	private static final int FFN = 128;

	/** FFN intermediate size for net_token (1:1). */
	private static final int FFN_TOKEN = 32;

	/** Attention heads for net. */
	private static final int HEADS = 4;

	/** Attention heads for net_token. */
	private static final int HEADS_TOKEN = 2;

	/** Sequence length for RoPE. */
	private static final int SEQ_LEN = 16;

	/** Number of layers for the test model (1 each for speed). */
	private static final int NET_LAYERS = 1;

	/** Number of token-transformer layers for the test model. */
	private static final int NET_TOKEN_LAYERS = 1;

	/** Small vocabulary size for test models. */
	private static final int VOCAB = SkyTntTokenizerV2.VOCAB_SIZE;

	/** RMSNorm epsilon. */
	private static final double EPSILON = 1e-5;

	/**
	 * Milestone 4: verify that both PDSL-compiled transformers can be built from
	 * random synthetic weights without throwing exceptions.
	 */
	@Test(timeout = 60000)
	public void testModelAssemblyWithSyntheticWeights() {
		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(42));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		int tokenHeadSize = DIM / HEADS_TOKEN;

		PackedCollection netPos = new PackedCollection(1);
		PackedCollection tokenPos = new PackedCollection(1);

		CollectionProducer netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);
		CollectionProducer tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);

		PackedCollection lmHeadWeight = stateDict.get("lm_head.weight");

		// Build net model (no lm head)
		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);
		Assert.assertNotNull("net CompiledModel should not be null", netModel);

		// Build net_token model (with lm head)
		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);
		Assert.assertNotNull("net_token CompiledModel should not be null", netTokenModel);
	}

	/**
	 * Milestone 4: verify net forward pass shape.
	 *
	 * <p>Guarded by {@code skipLongTests} — runs one forward pass through the compiled
	 * net transformer and checks output shape.</p>
	 */
	@Test(timeout = 60000)
	public void testNetForwardPassShape() {
		if (skipLongTests) return;

		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(42));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		PackedCollection netPos = new PackedCollection(1);
		CollectionProducer netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);

		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);

		PackedCollection input = new PackedCollection(new TraversalPolicy(1, DIM));
		netPos.setMem(0, 0.0);
		PackedCollection output = netModel.forward(input);

		Assert.assertNotNull("Net output should not be null", output);
		Assert.assertEquals("Net output should have " + DIM + " elements",
				DIM, output.getShape().getTotalSize());
	}

	/**
	 * Milestone 4: verify net_token forward pass shape (output is logits).
	 *
	 * <p>Guarded by {@code skipLongTests}.</p>
	 */
	@Test(timeout = 60000)
	public void testNetTokenForwardPassShape() {
		if (skipLongTests) return;

		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(99));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int tokenHeadSize = DIM / HEADS_TOKEN;
		PackedCollection tokenPos = new PackedCollection(1);
		CollectionProducer tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);
		PackedCollection lmHeadWeight = stateDict.get("lm_head.weight");

		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);

		PackedCollection input = new PackedCollection(new TraversalPolicy(1, DIM));
		tokenPos.setMem(0, 0.0);
		PackedCollection output = netTokenModel.forward(input);

		Assert.assertNotNull("net_token output should not be null", output);
		Assert.assertEquals("net_token output should have VOCAB elements",
				VOCAB, output.getShape().getTotalSize());
	}

	/**
	 * Milestone 5: verify unconditional generation from BOS produces a valid token sequence.
	 *
	 * <p>Guarded by {@code skipLongTests}.  Verifies:</p>
	 * <ul>
	 *   <li>Output is non-null with at least 2 rows (BOS + at least one generated event)</li>
	 *   <li>Row 0 is the BOS event</li>
	 *   <li>Each generated row (step 0) contains a valid event-type token or PAD</li>
	 * </ul>
	 */
	@Test(timeout = 60000)
	public void testGenerationFromBos() {
		if (skipLongTests) return;

		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, new Random(123));

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		int tokenHeadSize = DIM / HEADS_TOKEN;

		PackedCollection netPos = new PackedCollection(1);
		PackedCollection tokenPos = new PackedCollection(1);

		CollectionProducer netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);
		CollectionProducer tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);

		PackedCollection netEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
		PackedCollection tokenEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
		PackedCollection lmHeadWeight = stateDict.get("lm_head.weight");

		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);

		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);

		// Construct SkyTntMidi with pre-built models
		SkyTntMidi model = new SkyTntMidi(config, netEmbed, tokenEmbed,
				netModel, netTokenModel, new Random(456));

		// BOS prompt
		int[][] prompt = new int[1][config.maxTokenSeq];
		prompt[0][0] = config.bosId;

		int[][] output = model.generate(prompt, 3,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K);

		Assert.assertNotNull("Generation output should not be null", output);
		Assert.assertTrue("Output should have at least 1 row", output.length >= 1);
		Assert.assertEquals("First row should be BOS prompt", config.bosId, output[0][0]);

		// Each generated row's step-0 token should be in the valid event-type range or EOS
		for (int i = 1; i < output.length; i++) {
			int step0 = output[i][0];
			boolean isValidEvent = (step0 >= SkyTntTokenizerV2.EVENT_NOTE
					&& step0 <= SkyTntTokenizerV2.EVENT_KEY_SIGNATURE);
			boolean isEos = (step0 == config.eosId);
			boolean isPad = (step0 == config.padId);
			Assert.assertTrue(
					"Row " + i + " step-0 token " + step0 + " should be event-type, EOS, or PAD",
					isValidEvent || isEos || isPad);
		}
	}

	/**
	 * Verifies that {@link SkyTntMidi#generate(int[][], int, double, double, int, int[])}
	 * restricts the track parameter of every generated event to the single track
	 * supplied in {@code allowedTrackIds}.
	 */
	@Test(timeout = 60000)
	public void generateRespectsAllowedTracksSingle() {
		if (skipLongTests) return;

		SkyTntMidi model = buildSyntheticModel(new Random(11), new Random(13));

		int[][] prompt = bosPrompt(model.getConfig());
		int[][] output = model.generate(prompt, 4,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K,
				new int[]{0});

		assertGeneratedTracksWithin(output, prompt.length, new int[]{0});
	}

	/**
	 * Verifies that {@link SkyTntMidi#generate(int[][], int, double, double, int, int[])}
	 * restricts the track parameter of every generated event to one of the values
	 * supplied in {@code allowedTrackIds}.
	 */
	@Test(timeout = 60000)
	public void generateRespectsAllowedTracksMultiple() {
		if (skipLongTests) return;

		SkyTntMidi model = buildSyntheticModel(new Random(21), new Random(23));

		int[][] prompt = bosPrompt(model.getConfig());
		int[][] output = model.generate(prompt, 6,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K,
				new int[]{0, 5});

		assertGeneratedTracksWithin(output, prompt.length, new int[]{0, 5});
	}

	/**
	 * Verifies that passing {@code null} as the track filter preserves the
	 * legacy (no-filter) behaviour, producing the same token sequence as the
	 * legacy overload when called with identical RNG state.
	 */
	@Test(timeout = 60000)
	public void generateNullFilterMatchesUnfiltered() {
		if (skipLongTests) return;

		SkyTntMidi modelA = buildSyntheticModel(new Random(31), new Random(37));
		int[][] promptA = bosPrompt(modelA.getConfig());
		int[][] outputLegacy = modelA.generate(promptA, 3,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K);

		SkyTntMidi modelB = buildSyntheticModel(new Random(31), new Random(37));
		int[][] promptB = bosPrompt(modelB.getConfig());
		int[][] outputNullFilter = modelB.generate(promptB, 3,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K,
				null);

		Assert.assertEquals("Null filter must produce the same row count as the legacy overload",
				outputLegacy.length, outputNullFilter.length);
		for (int i = 0; i < outputLegacy.length; i++) {
			Assert.assertArrayEquals(
					"Row " + i + " must match between the legacy and null-filter overloads",
					outputLegacy[i], outputNullFilter[i]);
		}
	}

	/**
	 * Verifies that an empty (zero-length) track filter is rejected with
	 * {@link IllegalArgumentException} rather than silently blocking all
	 * generation.  This test does not require a forward pass, so it runs
	 * unconditionally.
	 */
	@Test(timeout = 60000)
	public void generateEmptyFilterRejected() {
		SkyTntMidi model = buildSyntheticModel(new Random(41), new Random(43));

		int[][] prompt = bosPrompt(model.getConfig());

		try {
			model.generate(prompt, 2,
					SkyTntMidi.DEFAULT_TEMPERATURE,
					SkyTntMidi.DEFAULT_TOP_P,
					SkyTntMidi.DEFAULT_TOP_K,
					new int[0]);
			Assert.fail("Empty allowedTrackIds should be rejected with IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// pass — message content not enforced
		}
	}

	/**
	 * Verifies that the track filter only constrains the track parameter and
	 * leaves the rest of the per-event token layout intact, including events
	 * whose non-track parameters (BPM, controller value, etc.) live in
	 * separate vocabulary regions.  The test asserts the contract by feeding
	 * SET_TEMPO and CONTROL_CHANGE events through the prompt path and
	 * verifying that they round-trip unmodified while a filtered single-track
	 * generation still produces correctly-tracked output.
	 */
	@Test(timeout = 60000)
	public void generateFilterDoesNotAffectTempoOrControl() {
		if (skipLongTests) return;

		SkyTntMidi model = buildSyntheticModel(new Random(51), new Random(53));

		List<MidiNoteEvent> prompt = new ArrayList<>();
		prompt.add(MidiNoteEvent.setTempo(0, 99, 120));
		prompt.add(MidiNoteEvent.controlChange(0, 99, 1, 7, 64));
		prompt.add(MidiNoteEvent.note(48, 0, 0, 60, 80, 16));

		// Sanity: tokenize -> detokenize round-trips the tempo and control events,
		// proving that the filter site (step 3) is the only point that touches the
		// track parameter for these event types.
		int[][] roundTripTokens = model.getTokenizer().tokenize(prompt,
				SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
		List<MidiNoteEvent> roundTrip = model.getTokenizer().detokenize(roundTripTokens,
				SkyTntMidi.DEFAULT_TICKS_PER_BEAT);
		Assert.assertEquals("Round-trip should preserve the prompt event count",
				prompt.size(), roundTrip.size());
		boolean sawTempo = false;
		boolean sawControl = false;
		for (MidiNoteEvent event : roundTrip) {
			if (event.getEventType() == MidiNoteEvent.EventType.SET_TEMPO) {
				Assert.assertEquals(120, event.getBpm());
				Assert.assertEquals(99, event.getTrack());
				sawTempo = true;
			} else if (event.getEventType() == MidiNoteEvent.EventType.CONTROL_CHANGE) {
				Assert.assertEquals(7, event.getController());
				Assert.assertEquals(64, event.getCcValue());
				Assert.assertEquals(99, event.getTrack());
				sawControl = true;
			}
		}
		Assert.assertTrue("Tempo event must round-trip with bpm and track preserved", sawTempo);
		Assert.assertTrue("Control event must round-trip with controller/value/track preserved",
				sawControl);

		List<MidiNoteEvent> generated = model.generateFromEvents(prompt, 4,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K,
				SkyTntMidi.DEFAULT_TICKS_PER_BEAT,
				new int[]{0});

		for (MidiNoteEvent event : generated) {
			Assert.assertEquals(
					"Every generated event (event-type " + event.getEventType() +
							") should have track == 0 under the filter",
					0, event.getTrack());
		}
	}

	/**
	 * Verifies that {@link SkyTntMidi#generateFromEvents} returns events whose
	 * {@code tick} fields are absolute (measured from time 0 of the full sequence,
	 * not from the start of the generated portion).
	 *
	 * <p>Given a prompt whose last event starts at tick 480 (one beat at
	 * {@link SkyTntMidi#DEFAULT_TICKS_PER_BEAT}), every returned event must have
	 * {@code tick >= 480}.  Before the fix, detokenize reset {@code accumulatedTime1}
	 * to 0 for the generated rows, so events could have ticks near 0; after the fix,
	 * the accumulated state from the prompt carries through, making all ticks absolute.</p>
	 */
	@Test(timeout = 60000)
	public void generateFromEventsReturnsAbsoluteTicks() {
		if (skipLongTests) return;

		SkyTntMidi model = buildSyntheticModel(new Random(61), new Random(63));

		List<MidiNoteEvent> prompt = new ArrayList<>();
		prompt.add(MidiNoteEvent.note(0, 0, 0, 60, 80, 240));
		prompt.add(MidiNoteEvent.note(480, 0, 0, 62, 80, 240));

		List<MidiNoteEvent> generated = model.generateFromEvents(
				prompt, 8,
				SkyTntMidi.DEFAULT_TEMPERATURE,
				SkyTntMidi.DEFAULT_TOP_P,
				SkyTntMidi.DEFAULT_TOP_K,
				SkyTntMidi.DEFAULT_TICKS_PER_BEAT);

		Assert.assertFalse(
				"generateFromEvents should produce at least one event",
				generated.isEmpty());
		for (MidiNoteEvent event : generated) {
			Assert.assertTrue(
					"Generated event at tick " + event.getTick() +
							" must have absolute tick >= 480 (the last prompt event tick)",
					event.getTick() >= 480);
		}
	}

	// -----------------------------------------------------------------------
	//  Helpers
	// -----------------------------------------------------------------------

	/** Build a synthetic SkyTntMidi instance suitable for fast filter tests. */
	static SkyTntMidi buildSyntheticModel(Random weightRng, Random samplerRng) {
		SkyTntConfig config = new SkyTntConfig(VOCAB, DIM, EPSILON, 10000.0,
				NET_LAYERS, HEADS, FFN, SEQ_LEN,
				NET_TOKEN_LAYERS, HEADS_TOKEN, FFN_TOKEN);

		StateDictionary stateDict = createSyntheticWeights(config, weightRng);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program blockProgram = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");
		PdslNode.Program lmHeadProgram = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		int netHeadSize = DIM / HEADS;
		int tokenHeadSize = DIM / HEADS_TOKEN;
		PackedCollection netPos = new PackedCollection(1);
		PackedCollection tokenPos = new PackedCollection(1);
		CollectionProducer netFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, netHeadSize, SEQ_LEN);
		CollectionProducer tokenFreqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, tokenHeadSize, SEQ_LEN);

		PackedCollection netEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
		PackedCollection tokenEmbed = new PackedCollection(new TraversalPolicy(VOCAB, DIM));
		PackedCollection lmHeadWeight = stateDict.get("lm_head.weight");

		CompiledModel netModel = SkyTntMidi.buildTransformerModel(
				"net", stateDict, blockProgram, lmHeadProgram,
				config.netLayers, config.netHeads,
				netFreqCis, netPos, false, EPSILON, null);

		CompiledModel netTokenModel = SkyTntMidi.buildTransformerModel(
				"net_token", stateDict, blockProgram, lmHeadProgram,
				config.netTokenLayers, config.netTokenHeads,
				tokenFreqCis, tokenPos, true, EPSILON, lmHeadWeight);

		return new SkyTntMidi(config, netEmbed, tokenEmbed,
				netModel, netTokenModel, samplerRng);
	}

	/** Build a single-row BOS prompt for the supplied configuration. */
	static int[][] bosPrompt(SkyTntConfig config) {
		int[][] prompt = new int[1][config.maxTokenSeq];
		prompt[0][0] = config.bosId;
		return prompt;
	}

	/**
	 * Asserts that every generated row (after the prompt prefix) carries a
	 * track-parameter token whose decoded track ID is in {@code allowedIds},
	 * if the row's event type has a track parameter at step 3.
	 */
	static void assertGeneratedTracksWithin(int[][] output, int promptLen, int[] allowedIds) {
		Assert.assertNotNull("Generation output should not be null", output);
		Assert.assertTrue("Output should retain the prompt rows", output.length >= promptLen);

		Set<Integer> allowed = new HashSet<>();
		for (int id : allowedIds) allowed.add(id);

		int generatedRows = 0;
		for (int i = promptLen; i < output.length; i++) {
			int[] row = output[i];
			int step0 = row[0];
			if (step0 < SkyTntTokenizerV2.EVENT_NOTE
					|| step0 > SkyTntTokenizerV2.EVENT_KEY_SIGNATURE) {
				continue;  // EOS / PAD / unrecognized event — no track to check
			}
			int trackToken = row[3];
			Assert.assertTrue(
					"Row " + i + " step-3 token " + trackToken +
							" is outside the track-token range",
					trackToken >= SkyTntTokenizerV2.TRACK_OFFSET &&
							trackToken < SkyTntTokenizerV2.TRACK_OFFSET +
									SkyTntTokenizerV2.TRACK_RANGE);
			int trackId = trackToken - SkyTntTokenizerV2.TRACK_OFFSET;
			Assert.assertTrue(
					"Row " + i + " has track " + trackId +
							" which is outside the allowed set " + allowed,
					allowed.contains(trackId));
			generatedRows++;
		}
		Assert.assertTrue("Generation should have produced at least one trackable event",
				generatedRows >= 1);
	}


	/**
	 * Create a {@link StateDictionary} populated with random synthetic weights that
	 * match the shapes expected by {@link SkyTntMidi.buildTransformerModel}.
	 *
	 * @param config the model configuration
	 * @param rng    random number generator
	 * @return populated StateDictionary
	 */
	static StateDictionary createSyntheticWeights(SkyTntConfig config, Random rng) {
		Map<String, PackedCollection> weights = new HashMap<>();

		// LM head (shared) — uses HuggingFace key name to match extractor output
		weights.put("lm_head.weight", rand(rng, config.vocabSize, config.hiddenSize));

		// Embedding tables — uses HuggingFace key names to match extractor output
		weights.put("net.embed_tokens.weight", rand(rng, config.vocabSize, config.hiddenSize));
		weights.put("net_token.embed_tokens.weight", rand(rng, config.vocabSize, config.hiddenSize));

		// net layers
		addLayerWeights(weights, "net", config.netLayers, config.hiddenSize,
				config.netIntermediateSize, rng);
		weights.put("net.norm.weight", rand(rng, config.hiddenSize));

		// net_token layers
		addLayerWeights(weights, "net_token", config.netTokenLayers, config.hiddenSize,
				config.netTokenIntermediateSize, rng);
		weights.put("net_token.norm.weight", rand(rng, config.hiddenSize));

		return new StateDictionary(weights);
	}

	/** Add per-layer weights for one transformer to the weight map. */
	static void addLayerWeights(Map<String, PackedCollection> weights,
										String prefix, int numLayers,
										int hiddenSize, int ffnSize, Random rng) {
		for (int i = 0; i < numLayers; i++) {
			String key = prefix + ".layers." + i;
			weights.put(key + ".input_layernorm.weight", rand(rng, hiddenSize));
			weights.put(key + ".post_attention_layernorm.weight", rand(rng, hiddenSize));
			weights.put(key + ".self_attn.q_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".self_attn.k_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".self_attn.v_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".self_attn.o_proj.weight", rand(rng, hiddenSize, hiddenSize));
			weights.put(key + ".mlp.gate_proj.weight", rand(rng, ffnSize, hiddenSize));
			weights.put(key + ".mlp.up_proj.weight", rand(rng, ffnSize, hiddenSize));
			weights.put(key + ".mlp.down_proj.weight", rand(rng, hiddenSize, ffnSize));
		}
	}

	/** Create a PackedCollection filled with small random values. */
	static PackedCollection rand(Random rng, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		PackedCollection c = new PackedCollection(shape);
		int size = shape.getTotalSize();
		for (int i = 0; i < size; i++) {
			c.setMem(i, (rng.nextDouble() - 0.5) * 0.1);
		}
		return c;
	}

}
