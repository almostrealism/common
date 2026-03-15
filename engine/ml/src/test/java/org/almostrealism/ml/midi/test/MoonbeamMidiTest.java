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

package org.almostrealism.ml.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.GRUCell;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MidiAutoregressiveModel;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MidiFileReader;
import org.almostrealism.ml.midi.MidiNoteEvent;
import org.almostrealism.ml.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.sound.midi.InvalidMidiDataException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the Moonbeam MIDI model assembly and end-to-end inference.
 *
 * <p>Uses synthetic (random) weights with the test configuration to verify
 * that the model assembles, runs a forward pass, and produces compound
 * tokens without crashing.</p>
 *
 * @see MoonbeamMidi
 * @see MidiAutoregressiveModel
 */
public class MoonbeamMidiTest extends TestSuiteBase {

	/**
	 * Verify that the model assembles with synthetic weights and the
	 * compiled transformer can execute a forward pass.
	 */
	@Test
	public void testModelAssembly() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		Assert.assertNotNull("Compiled transformer should not be null",
				model.getCompiledTransformer());
		Assert.assertNotNull("Embedding should not be null",
				model.getEmbedding());
		Assert.assertNotNull("Decoder should not be null",
				model.getDecoder());
	}

	/**
	 * Verify that creating an autoregressive model and processing a single
	 * prompt token exercises the compiled transformer forward pass.
	 */
	@Test
	public void testTransformerForwardPass() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		MidiCompoundToken[] prompt = new MidiCompoundToken[]{
				MidiCompoundToken.sos()
		};
		autoregressive.setPrompt(prompt);

		MidiCompoundToken result = autoregressive.next();
		Assert.assertNotNull("Forward pass should produce a token", result);
	}

	/**
	 * Verify that the autoregressive model produces compound tokens.
	 */
	@Test
	public void testAutoregressiveGeneration() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		MidiCompoundToken[] prompt = new MidiCompoundToken[]{
				MidiCompoundToken.sos(),
				new MidiCompoundToken(100, 50, 5, 7, 0, 80)
		};
		autoregressive.setPrompt(prompt);

		for (int i = 0; i < prompt.length; i++) {
			MidiCompoundToken token = autoregressive.next();
			Assert.assertNotNull("Prompt token " + i + " should not be null", token);
		}

		MidiCompoundToken generated = autoregressive.next();
		Assert.assertNotNull("Generated token should not be null", generated);
		Assert.assertEquals("Step should be prompt length + 1",
				prompt.length + 1, autoregressive.getCurrentStep());
	}

	/**
	 * Verify that the autoregressive loop can generate multiple tokens.
	 */
	@Test
	public void testMultipleTokenGeneration() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		MidiCompoundToken[] prompt = new MidiCompoundToken[]{
				MidiCompoundToken.sos()
		};
		autoregressive.setPrompt(prompt);

		List<MidiCompoundToken> generated = autoregressive.generate(3);
		Assert.assertFalse("Should generate at least one token", generated.isEmpty());
		Assert.assertTrue("Should generate up to 3 tokens", generated.size() <= 3);
	}

	/**
	 * Verify the end-to-end flow: tokens can be detokenized back to note events.
	 */
	@Test
	public void testDetokenizeGeneratedOutput() {
		MidiTokenizer tokenizer = new MidiTokenizer();

		List<MidiCompoundToken> tokens = new ArrayList<>();
		tokens.add(MidiCompoundToken.sos());
		tokens.add(new MidiCompoundToken(100, 50, 5, 0, 0, 80));
		tokens.add(new MidiCompoundToken(50, 25, 5, 4, 0, 64));
		tokens.add(MidiCompoundToken.eos());

		List<MidiNoteEvent> events = tokenizer.detokenize(tokens);
		Assert.assertEquals("Should produce 2 note events", 2, events.size());
		Assert.assertEquals("First note onset", 100, events.get(0).getOnset());
		Assert.assertEquals("Second note onset", 150, events.get(1).getOnset());
	}

	/**
	 * Verify that output can be written to a MIDI file and read back.
	 */
	@Test
	public void testMidiRoundTrip() throws IOException, InvalidMidiDataException {
		MidiTokenizer tokenizer = new MidiTokenizer();
		MidiFileReader reader = new MidiFileReader();

		List<MidiCompoundToken> tokens = new ArrayList<>();
		tokens.add(MidiCompoundToken.sos());
		tokens.add(new MidiCompoundToken(0, 100, 5, 0, 0, 80));
		tokens.add(new MidiCompoundToken(100, 50, 5, 4, 0, 64));
		tokens.add(MidiCompoundToken.eos());

		List<MidiNoteEvent> events = tokenizer.detokenize(tokens);
		Assert.assertEquals(2, events.size());

		File tempFile = File.createTempFile("moonbeam_test", ".mid");
		tempFile.deleteOnExit();

		reader.write(events, tempFile);
		Assert.assertTrue("MIDI file should be written", tempFile.exists());

		List<MidiNoteEvent> readBack = reader.read(tempFile);
		Assert.assertEquals("Should read back 2 events", 2, readBack.size());
	}

	/**
	 * Verify that temperature and top-p settings are applied.
	 */
	@Test
	public void testSamplingParameters() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		autoregressive.setTemperature(0.7);
		Assert.assertEquals(0.7, autoregressive.getTemperature(), 1e-10);

		autoregressive.setTopP(0.9);
		Assert.assertEquals(0.9, autoregressive.getTopP(), 1e-10);
	}

	/**
	 * Create a StateDictionary with synthetic (zero-initialized) weights
	 * for all transformer layer parameters.
	 */
	private static StateDictionary createSyntheticWeights(MoonbeamConfig config) {
		Map<String, PackedCollection> weights = new HashMap<>();
		int dim = config.hiddenSize;
		int kvDim = dim * config.numKvHeads / config.numHeads;
		int ffnDim = config.intermediateSize;

		for (int i = 0; i < config.numLayers; i++) {
			String prefix = String.format("model.layers.%d", i);
			weights.put(prefix + ".input_layernorm.weight",
					onesCollection(dim));
			weights.put(prefix + ".post_attention_layernorm.weight",
					onesCollection(dim));
			weights.put(prefix + ".self_attn.q_proj.weight",
					new PackedCollection(new TraversalPolicy(dim, dim)));
			weights.put(prefix + ".self_attn.k_proj.weight",
					new PackedCollection(new TraversalPolicy(kvDim, dim)));
			weights.put(prefix + ".self_attn.v_proj.weight",
					new PackedCollection(new TraversalPolicy(kvDim, dim)));
			weights.put(prefix + ".self_attn.o_proj.weight",
					new PackedCollection(new TraversalPolicy(dim, dim)));
			weights.put(prefix + ".mlp.gate_proj.weight",
					new PackedCollection(new TraversalPolicy(ffnDim, dim)));
			weights.put(prefix + ".mlp.down_proj.weight",
					new PackedCollection(new TraversalPolicy(dim, ffnDim)));
			weights.put(prefix + ".mlp.up_proj.weight",
					new PackedCollection(new TraversalPolicy(ffnDim, dim)));
		}

		weights.put("model.norm.weight", onesCollection(dim));

		return new StateDictionary(weights);
	}

	/**
	 * Create a GRU decoder with synthetic (zero-initialized) weights.
	 */
	private static GRUDecoder createSyntheticDecoder(MoonbeamConfig config) {
		int hidden = config.hiddenSize;
		int decoderHidden = config.decoderHiddenSize;
		int vocabSize = config.decodeVocabSize;

		GRUCell[] layers = new GRUCell[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUCell(
					decoderHidden, decoderHidden,
					new PackedCollection(new TraversalPolicy(3 * decoderHidden, decoderHidden)),
					new PackedCollection(new TraversalPolicy(3 * decoderHidden, decoderHidden)),
					new PackedCollection(new TraversalPolicy(3 * decoderHidden)),
					new PackedCollection(new TraversalPolicy(3 * decoderHidden)));
		}

		return new GRUDecoder(config, layers,
				new PackedCollection(new TraversalPolicy(decoderHidden, hidden)),
				new PackedCollection(new TraversalPolicy(decoderHidden)),
				new PackedCollection(new TraversalPolicy(vocabSize, decoderHidden)),
				new PackedCollection(new TraversalPolicy(vocabSize)),
				new PackedCollection(new TraversalPolicy(vocabSize, decoderHidden)));
	}

	/**
	 * Create a PackedCollection of the given size filled with ones.
	 * Used for RMSNorm weights so the norm doesn't zero out activations.
	 */
	private static PackedCollection onesCollection(int size) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(size));
		for (int i = 0; i < size; i++) {
			collection.setMem(i, 1.0);
		}
		return collection;
	}
}
