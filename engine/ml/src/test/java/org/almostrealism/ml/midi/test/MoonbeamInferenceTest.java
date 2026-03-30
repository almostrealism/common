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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.GRUBlock;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MidiAutoregressiveModel;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MidiNoteEvent;
import org.almostrealism.ml.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end inference test for the Moonbeam MIDI model using real weights.
 *
 * <p>Tests embedding, GRU decoder, and full model inference with the
 * pretrained 309M checkpoint. Skipped if weights are not available.</p>
 *
 * @see MoonbeamMidi
 * @see MidiAutoregressiveModel
 */
public class MoonbeamInferenceTest extends TestSuiteBase {

	private static final String WEIGHTS_DIR = "/workspace/project/moonbeam-weights-protobuf";

	/**
	 * Test that real-weight embeddings produce non-trivial outputs for SOS,
	 * normal tokens, and EOS.
	 */
	@Test
	@TestDepth(2)
	public void testRealWeightEmbedding() throws IOException {
		Assume.assumeTrue("Weights directory not found", new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(stateDict, config);

		// Embed SOS token
		PackedCollection sosEmb = embedding.embed(MidiCompoundToken.sos());
		Assert.assertEquals("SOS embedding should be hiddenSize",
				config.hiddenSize, sosEmb.getMemLength());
		assertNotAllZeros(sosEmb, "SOS embedding");

		// Embed a normal token (middle C, quarter note, piano, medium velocity)
		MidiCompoundToken normalToken = new MidiCompoundToken(100, 50, 5, 0, 0, 64);
		PackedCollection normalEmb = embedding.embed(normalToken);
		Assert.assertEquals("Normal embedding should be hiddenSize",
				config.hiddenSize, normalEmb.getMemLength());
		assertNotAllZeros(normalEmb, "Normal token embedding");

		// Embed EOS token
		PackedCollection eosEmb = embedding.embed(MidiCompoundToken.eos());
		assertNotAllZeros(eosEmb, "EOS embedding");

		// SOS and EOS should produce different embeddings
		boolean differ = false;
		for (int i = 0; i < config.hiddenSize; i++) {
			if (Math.abs(sosEmb.toDouble(i) - eosEmb.toDouble(i)) > 1e-6) {
				differ = true;
				break;
			}
		}
		Assert.assertTrue("SOS and EOS should produce different embeddings", differ);

		System.out.println("Real-weight embedding test passed.");
	}

	/**
	 * Test that the GRU decoder with real weights produces valid output tokens.
	 */
	@Test
	@TestDepth(2)
	public void testRealWeightGruDecoder() throws IOException {
		Assume.assumeTrue("Weights directory not found", new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		GRUDecoder decoder = buildDecoder(stateDict, config);

		// Create a synthetic hidden state (all 0.1) and decode
		PackedCollection fakeHidden = new PackedCollection(config.hiddenSize);
		for (int i = 0; i < config.hiddenSize; i++) {
			fakeHidden.setMem(i, 0.1);
		}

		int[] decodeTokens = decoder.decode(fakeHidden);

		Assert.assertEquals("Should produce 7 tokens", GRUDecoder.TOKENS_PER_NOTE, decodeTokens.length);

		// All tokens should be within decode vocabulary range
		for (int i = 0; i < decodeTokens.length; i++) {
			Assert.assertTrue("Token " + i + " should be >= 0, got " + decodeTokens[i],
					decodeTokens[i] >= 0);
			Assert.assertTrue("Token " + i + " should be < decodeVocabSize, got " + decodeTokens[i],
					decodeTokens[i] < config.decodeVocabSize);
		}

		// Convert to attribute values
		int[] attrValues = decoder.toAttributeValues(decodeTokens);
		System.out.println("Decoded tokens (flat vocab): ");
		for (int i = 0; i < decodeTokens.length; i++) {
			System.out.printf("  [%d] flat=%d, attr=%d%n", i, decodeTokens[i], attrValues[i]);
		}

		System.out.println("Real-weight GRU decoder test passed.");
	}

	/**
	 * Attempt full model load and a short unconditional generation.
	 *
	 * <p>This test loads the full 309M model, compiles the transformer,
	 * and generates a few tokens from SOS. It is the most resource-intensive
	 * test and may require significant memory.</p>
	 */
	@Test
	@TestDepth(2)
	public void testFullModelInference() throws Exception {
		Assume.assumeTrue("Weights directory not found", new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();

		System.out.println("Loading Moonbeam 309M model...");
		long startLoad = System.currentTimeMillis();
		MoonbeamMidi model = new MoonbeamMidi(WEIGHTS_DIR, config);
		long loadTime = System.currentTimeMillis() - startLoad;
		System.out.printf("Model loaded and compiled in %.1f seconds%n", loadTime / 1000.0);

		Assert.assertNotNull("Compiled transformer should exist",
				model.getCompiledTransformer());

		MidiAutoregressiveModel gen = model.createAutoregressiveModel();
		gen.setTemperature(0.8);
		gen.setTopP(0.95);
		gen.setSeed(42);

		System.out.println("Generating tokens from SOS...");
		long startGen = System.currentTimeMillis();
		List<MidiCompoundToken> generated = gen.generate(5);
		long genTime = System.currentTimeMillis() - startGen;
		System.out.printf("Generated %d tokens in %.1f seconds%n",
				generated.size(), genTime / 1000.0);

		Assert.assertFalse("Should generate at least one token", generated.isEmpty());

		for (int i = 0; i < generated.size(); i++) {
			MidiCompoundToken token = generated.get(i);
			System.out.printf("  Token %d: onset=%d, dur=%d, oct=%d, pc=%d, inst=%d, vel=%d%n",
					i, token.getOnset(), token.getDuration(), token.getOctave(),
					token.getPitchClass(), token.getInstrument(), token.getVelocity());
		}

		// Try converting to MIDI note events
		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiCompoundToken> withoutEos = new ArrayList<>();
		for (MidiCompoundToken token : generated) {
			if (!token.isEOS() && !token.isSOS()) {
				withoutEos.add(token);
			}
		}

		if (!withoutEos.isEmpty()) {
			List<MidiNoteEvent> events = tokenizer.detokenize(withoutEos);
			System.out.println("Converted to " + events.size() + " MIDI note events.");

			for (MidiNoteEvent event : events) {
				System.out.printf("  Note: pitch=%d, onset=%d, dur=%d, vel=%d, inst=%d%n",
						event.getPitch(), event.getOnset(), event.getDuration(),
						event.getVelocity(), event.getInstrument());
			}
		}

		System.out.println("Full model inference test passed.");
	}

	/** Build a GRU decoder from the state dictionary. */
	private static GRUDecoder buildDecoder(StateDictionary stateDict, MoonbeamConfig config) {
		GRUBlock[] layers = new GRUBlock[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUBlock(
					config.decoderHiddenSize, config.decoderHiddenSize,
					stateDict.get(String.format("decoder.weight_ih_l%d", l)),
					stateDict.get(String.format("decoder.weight_hh_l%d", l)),
					stateDict.get(String.format("decoder.bias_ih_l%d", l)),
					stateDict.get(String.format("decoder.bias_hh_l%d", l)));
		}

		return new GRUDecoder(config, layers,
				stateDict.get("summary_projection.weight"),
				stateDict.get("summary_projection.bias"),
				stateDict.get("decoder.fc_out.weight"),
				stateDict.get("decoder.fc_out.bias"),
				stateDict.get("lm_head.weight"),
				stateDict.get("lm_head.bias"),
				stateDict.get("decoder_embedding.weight"));
	}

	/** Assert a PackedCollection is not all zeros. */
	private void assertNotAllZeros(PackedCollection collection, String label) {
		int size = collection.getMemLength();
		boolean hasNonZero = false;
		for (int i = 0; i < size; i++) {
			if (collection.toDouble(i) != 0.0) {
				hasNonZero = true;
				break;
			}
		}
		Assert.assertTrue(label + " should not be all zeros", hasNonZero);
	}
}
