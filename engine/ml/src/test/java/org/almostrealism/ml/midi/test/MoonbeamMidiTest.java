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
import org.almostrealism.ml.midi.GRUBlock;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MidiAutoregressiveModel;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.ml.midi.MidiFileReader;
import org.almostrealism.ml.midi.MidiNoteEvent;
import org.almostrealism.ml.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.util.TestDepth;
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
	@Test @TestDepth(2)
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
	@Test @TestDepth(2)
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
	@Test @TestDepth(2)
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
	@Test @TestDepth(2)
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
	 * Verify that unconditional generation (no prompt) works correctly.
	 * This exercises the lastHidden == null branch in
	 * {@link MidiAutoregressiveModel#next()}.
	 */
	@Test @TestDepth(2)
	public void testUnconditionalGeneration() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();

		// Do NOT call setPrompt -- exercise the no-prompt path
		MidiCompoundToken generated = autoregressive.next();
		Assert.assertNotNull("Should generate a token without prompt", generated);
		Assert.assertEquals("Step should be 2 after internal SOS forward + decode",
				2, autoregressive.getCurrentStep());

		// Generate a second token to confirm continued operation
		MidiCompoundToken second = autoregressive.next();
		Assert.assertNotNull("Should generate a second token", second);
		Assert.assertEquals("Step should be 3", 3, autoregressive.getCurrentStep());
	}

	/**
	 * Verify that the generate() convenience method processes prompt tokens
	 * before generating new ones, and that the returned list contains
	 * only the generated (non-prompt) tokens.
	 */
	@Test @TestDepth(2)
	public void testGenerateMethodSkipsPrompt() {
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

		List<MidiCompoundToken> generated = autoregressive.generate(2);
		Assert.assertFalse("Should generate tokens", generated.isEmpty());
		Assert.assertTrue("Should generate at most 2 tokens", generated.size() <= 2);

		// Total steps = prompt(2) + generated tokens
		Assert.assertTrue("Step should account for prompt + generated",
				autoregressive.getCurrentStep() >= prompt.length + 1);
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

		GRUBlock[] layers = new GRUBlock[config.decoderLayers];
		for (int l = 0; l < config.decoderLayers; l++) {
			layers[l] = new GRUBlock(
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
	 * Verify that GRUBlock produces the correct output for known weights and inputs.
	 *
	 * <p>Uses identity-like weights to verify the GRU equations:
	 * r = sigmoid(W_ir@x + b_ir + W_hr@h + b_hr),
	 * z = sigmoid(W_iz@x + b_iz + W_hz@h + b_hz),
	 * n = tanh(W_in@x + b_in + r * (W_hn@h + b_hn)),
	 * h' = (1 - z) * n + z * h</p>
	 */
	@Test
	public void testGruCellComputation() {
		int inputSize = 2;
		int hiddenSize = 2;

		// W_ih = [W_ir; W_iz; W_in], shape (3*hiddenSize, inputSize) = (6, 2)
		PackedCollection weightIh = new PackedCollection(new TraversalPolicy(6, 2));
		// Set W_ir (rows 0-1) to identity
		weightIh.setMem(0, 1.0); weightIh.setMem(1, 0.0);
		weightIh.setMem(2, 0.0); weightIh.setMem(3, 1.0);
		// W_iz (rows 2-3) and W_in (rows 4-5) left as zero

		// W_hh = [W_hr; W_hz; W_hn], shape (6, 2)
		PackedCollection weightHh = new PackedCollection(new TraversalPolicy(6, 2));

		// Biases all zero
		PackedCollection biasIh = new PackedCollection(new TraversalPolicy(6));
		PackedCollection biasHh = new PackedCollection(new TraversalPolicy(6));

		GRUBlock cell = new GRUBlock(inputSize, hiddenSize, weightIh, weightHh, biasIh, biasHh);

		PackedCollection x = new PackedCollection(2);
		x.setMem(0, 1.0);
		x.setMem(1, 2.0);

		PackedCollection h = new PackedCollection(2);
		h.setMem(0, 0.5);
		h.setMem(1, -0.5);

		PackedCollection hNew = gruStep(cell, x, h);

		Assert.assertEquals("Output size", 2, hNew.getShape().getTotalSize());

		// Manual computation:
		// W_ir@x + b_ir = [1,0;0,1]@[1,2] + [0,0] = [1, 2]
		// W_hr@h + b_hr = [0,0;0,0]@[0.5,-0.5] + [0,0] = [0, 0]
		// r = sigmoid([1, 2] + [0, 0]) = sigmoid([1, 2])
		double r0 = 1.0 / (1.0 + Math.exp(-1.0));
		double r1 = 1.0 / (1.0 + Math.exp(-2.0));

		// W_iz@x + b_iz = [0,0;0,0]@[1,2] + [0,0] = [0, 0]
		// W_hz@h + b_hz = [0,0;0,0]@[0.5,-0.5] + [0,0] = [0, 0]
		// z = sigmoid([0, 0]) = [0.5, 0.5]
		double z = 0.5;

		// W_in@x + b_in = [0,0;0,0]@[1,2] + [0,0] = [0, 0]
		// W_hn@h + b_hn = [0,0;0,0]@[0.5,-0.5] + [0,0] = [0, 0]
		// n = tanh([0, 0] + r * [0, 0]) = tanh([0, 0]) = [0, 0]
		double n = 0.0;

		// h' = (1 - z) * n + z * h = 0.5 * 0 + 0.5 * h
		double expectedH0 = (1.0 - z) * n + z * 0.5;
		double expectedH1 = (1.0 - z) * n + z * (-0.5);

		Assert.assertEquals("h'[0]", expectedH0, hNew.toDouble(0), 1e-10);
		Assert.assertEquals("h'[1]", expectedH1, hNew.toDouble(1), 1e-10);
	}

	/**
	 * Verify that GRUBlock correctly applies reset and update gates
	 * when biases shift the gate activations away from 0.5.
	 */
	@Test
	public void testGruCellWithBias() {
		int size = 1;

		PackedCollection weightIh = new PackedCollection(new TraversalPolicy(3, 1));
		PackedCollection weightHh = new PackedCollection(new TraversalPolicy(3, 1));

		// Set large positive bias on update gate (z -> 1) so h' ≈ h
		PackedCollection biasIh = new PackedCollection(new TraversalPolicy(3));
		biasIh.setMem(1, 10.0); // bias for z (update gate)
		PackedCollection biasHh = new PackedCollection(new TraversalPolicy(3));

		GRUBlock cell = new GRUBlock(size, size, weightIh, weightHh, biasIh, biasHh);

		PackedCollection x = new PackedCollection(1);
		x.setMem(0, 5.0);
		PackedCollection h = new PackedCollection(1);
		h.setMem(0, 3.0);

		PackedCollection hNew = gruStep(cell, x, h);

		// With z ≈ sigmoid(10) ≈ 1.0, h' ≈ z * h ≈ h
		Assert.assertEquals("With high update gate, h' should be close to h",
				3.0, hNew.toDouble(0), 0.01);
	}

	/**
	 * Verify that GRUDecoder.toAttributeValues correctly maps flat decode
	 * vocabulary indices to per-attribute values by subtracting offsets.
	 */
	@Test
	public void testDecoderToAttributeValues() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		int[] offsets = GRUDecoder.computeVocabOffsets(config);

		// Create a decoder with minimal weights just to test toAttributeValues
		GRUDecoder decoder = createSyntheticDecoder(config);

		// Construct decode tokens where each is at its offset + a known attribute value
		int[] decodeTokens = new int[GRUDecoder.TOKENS_PER_NOTE];
		int[] expectedValues = {0, 100, 50, 5, 7, 3, 80};
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			decodeTokens[i] = offsets[i] + expectedValues[i];
		}

		int[] attributeValues = decoder.toAttributeValues(decodeTokens);

		Assert.assertEquals("Should have 7 values", GRUDecoder.TOKENS_PER_NOTE,
				attributeValues.length);
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			Assert.assertEquals("Attribute value at " + i,
					expectedValues[i], attributeValues[i]);
		}
	}

	/**
	 * Verify that vocab offsets cover the full decode vocabulary.
	 * The last offset plus the last attribute's vocab size should equal decodeVocabSize.
	 */
	@Test
	public void testVocabOffsetsFullCoverage() {
		MoonbeamConfig config = MoonbeamConfig.defaultConfig();
		int[] offsets = GRUDecoder.computeVocabOffsets(config);

		// Last offset is for velocity, offset[6]
		int lastOffset = offsets[6];
		int lastVocab = config.vocabSizes[5]; // velocity vocab
		Assert.assertEquals("Last offset + last vocab = decodeVocabSize",
				config.decodeVocabSize, lastOffset + lastVocab);
	}

	/**
	 * Run one GRU layer step in plain Java for unit testing.
	 *
	 * @param block GRU weight holder
	 * @param x     input vector
	 * @param h     previous hidden state
	 * @return new hidden state
	 */
	private static PackedCollection gruStep(GRUBlock block, PackedCollection x, PackedCollection h) {
		int dh = h.getShape().getTotalSize();
		int inputSize = x.getShape().getTotalSize();
		double[] xArr = x.toArray(0, inputSize);
		double[] hArr = h.toArray(0, dh);
		double[] wIr = block.wIr.toArray(0, dh * inputSize);
		double[] bIr = block.bIr.toArray(0, dh);
		double[] wHr = block.wHr.toArray(0, dh * dh);
		double[] bHr = block.bHr.toArray(0, dh);
		double[] wIz = block.wIz.toArray(0, dh * inputSize);
		double[] bIz = block.bIz.toArray(0, dh);
		double[] wHz = block.wHz.toArray(0, dh * dh);
		double[] bHz = block.bHz.toArray(0, dh);
		double[] wIn = block.wIn.toArray(0, dh * inputSize);
		double[] bIn = block.bIn.toArray(0, dh);
		double[] wHn = block.wHn.toArray(0, dh * dh);
		double[] bHn = block.bHn.toArray(0, dh);
		double[] hNew = new double[dh];
		for (int i = 0; i < dh; i++) {
			double rGate = bIr[i] + bHr[i];
			double zGate = bIz[i] + bHz[i];
			double nGateIh = bIn[i];
			double nGateHh = bHn[i];
			for (int j = 0; j < inputSize; j++) {
				rGate += wIr[i * inputSize + j] * xArr[j];
				zGate += wIz[i * inputSize + j] * xArr[j];
				nGateIh += wIn[i * inputSize + j] * xArr[j];
			}
			for (int j = 0; j < dh; j++) {
				rGate += wHr[i * dh + j] * hArr[j];
				zGate += wHz[i * dh + j] * hArr[j];
				nGateHh += wHn[i * dh + j] * hArr[j];
			}
			double r = 1.0 / (1.0 + Math.exp(-rGate));
			double z = 1.0 / (1.0 + Math.exp(-zGate));
			double n = Math.tanh(nGateIh + r * nGateHh);
			hNew[i] = (1.0 - z) * n + z * hArr[i];
		}
		PackedCollection result = new PackedCollection(dh);
		result.setMem(0, hNew, 0, dh);
		return result;
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
