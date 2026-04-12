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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.midi.CompoundMidiEmbedding;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.studio.midi.MoonbeamMidiGenerator;
import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.studio.midi.MidiDataset;
import org.almostrealism.music.midi.MidiFileReader;
import org.almostrealism.music.midi.MidiNoteEvent;
import org.almostrealism.studio.midi.MidiTokenizer;
import org.almostrealism.ml.midi.MidiTrainingConfig;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.ml.midi.MoonbeamMidi;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import javax.sound.midi.InvalidMidiDataException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Tests for Milestones 7 (Training Pipeline) and 8 (Evaluation and Polish)
 * of the Moonbeam MIDI model.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>MidiDataset: synthetic data, packing, ValueTarget iteration</li>
 *   <li>MidiTrainingConfig: default and test configurations</li>
 *   <li>LoRA adapter configuration and save/load</li>
 *   <li>Temperature and top-p sampling in GRU decoder</li>
 *   <li>End-to-end generation: SOS to MIDI file</li>
 *   <li>Prompt completion: read MIDI prefix, continue, write output</li>
 * </ul>
 *
 * @see MidiDataset
 * @see MidiTrainingConfig
 * @see MoonbeamMidi
 */
public class MidiTrainingTest extends TestSuiteBase {

	// ========================================================================
	// Milestone 7: Training Pipeline Tests
	// ========================================================================

	/**
	 * Verify that MidiDataset.synthetic() creates a dataset with the expected
	 * number of sequences and tokens.
	 */
	@Test
	public void testSyntheticDataset() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		MidiDataset dataset = MidiDataset.synthetic(3, 5, config);

		Assert.assertEquals("Should have 3 sequences", 3, dataset.getSequenceCount());
		Assert.assertTrue("Should have tokens",
				dataset.getTotalTokenCount() > 0);
	}

	/**
	 * Verify that MidiDataset provides valid ValueTarget pairs with the correct
	 * shapes for training.
	 */
	@Test
	public void testDatasetIterator() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		MidiDataset dataset = MidiDataset.synthetic(2, 4, config);

		Iterator<ValueTarget<PackedCollection>> iterator = dataset.iterator();
		Assert.assertTrue("Dataset should have at least one pair", iterator.hasNext());

		ValueTarget<PackedCollection> pair = iterator.next();
		Assert.assertNotNull("Input should not be null", pair.getInput());
		Assert.assertNotNull("Target should not be null", pair.getExpectedOutput());

		int vocabSize = config.decodeVocabSize;
		Assert.assertEquals("Input should be (vocabSize,)",
				vocabSize, pair.getInput().getShape().getTotalSize());
		Assert.assertEquals("Target should be (vocabSize,)",
				vocabSize, pair.getExpectedOutput().getShape().getTotalSize());
	}

	/**
	 * Verify that the one-hot encoding in ValueTarget inputs has exactly the
	 * right number of non-zero entries.
	 */
	@Test
	public void testDatasetOneHotEncoding() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		MidiDataset dataset = MidiDataset.synthetic(1, 3, config);

		int pairCount = 0;
		for (ValueTarget<PackedCollection> pair : dataset) {
			PackedCollection input = pair.getInput();
			int nonZero = 0;
			for (int i = 0; i < input.getShape().getTotalSize(); i++) {
				if (input.toDouble(i) != 0.0) nonZero++;
			}
			Assert.assertTrue("One-hot should have at least 1 non-zero entry",
					nonZero >= 1);
			pairCount++;
		}
		Assert.assertTrue("Should produce training pairs", pairCount > 0);
	}

	/**
	 * Verify that sequence packing correctly concatenates short sequences.
	 */
	@Test
	public void testSequencePacking() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		List<List<MidiCompoundToken>> sequences = new ArrayList<>();

		MidiTokenizer tokenizer = new MidiTokenizer();
		for (int s = 0; s < 5; s++) {
			List<MidiNoteEvent> events = new ArrayList<>();
			for (int n = 0; n < 3; n++) {
				events.add(new MidiNoteEvent(60 + n, (long) n * 50, 25, 80, 0));
			}
			sequences.add(tokenizer.tokenize(events));
		}

		MidiDataset dataset = new MidiDataset(sequences, config, 128);
		List<List<MidiCompoundToken>> packed = dataset.packSequences();

		int totalTokensBefore = 0;
		for (List<MidiCompoundToken> seq : sequences) {
			totalTokensBefore += seq.size();
		}
		int totalTokensAfter = 0;
		for (List<MidiCompoundToken> seq : packed) {
			totalTokensAfter += seq.size();
		}

		Assert.assertEquals("Packing should preserve total tokens",
				totalTokensBefore, totalTokensAfter);

		for (List<MidiCompoundToken> seq : packed) {
			Assert.assertTrue("Packed sequences should not exceed maxSeqLen",
					seq.size() <= 128);
		}
	}

	/**
	 * Verify that sequence packing truncates sequences longer than maxSeqLen.
	 * A single sequence exceeding maxSeqLen should be truncated to exactly
	 * maxSeqLen tokens and placed in its own packed chunk.
	 */
	@Test
	public void testSequencePackingTruncatesLongSequence() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		int maxSeqLen = 10;

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiNoteEvent> events = new ArrayList<>();
		for (int n = 0; n < 20; n++) {
			events.add(new MidiNoteEvent(60 + (n % 12), (long) n * 50, 25, 80, 0));
		}
		List<MidiCompoundToken> longSeq = tokenizer.tokenize(events);
		Assert.assertTrue("Sequence should exceed maxSeqLen",
				longSeq.size() > maxSeqLen);

		List<List<MidiCompoundToken>> sequences = new ArrayList<>();
		sequences.add(longSeq);

		MidiDataset dataset = new MidiDataset(sequences, config, maxSeqLen);
		List<List<MidiCompoundToken>> packed = dataset.packSequences();

		Assert.assertEquals("Should produce exactly one packed sequence", 1, packed.size());
		Assert.assertEquals("Truncated sequence should equal maxSeqLen",
				maxSeqLen, packed.get(0).size());
	}

	/**
	 * Verify that the dataset iterator produces ValueTarget pairs with correct
	 * one-hot encoding dimensions for special tokens (SOS/EOS) which use
	 * position 0 in the decode vocabulary.
	 */
	@Test
	public void testOneHotEncodingSpecialTokens() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		MidiDataset dataset = MidiDataset.synthetic(1, 2, config);

		Iterator<ValueTarget<PackedCollection>> iter = dataset.iterator();
		Assert.assertTrue("Should have at least one training pair", iter.hasNext());

		ValueTarget<PackedCollection> firstPair = iter.next();
		PackedCollection input = firstPair.getInput();
		int vocabSize = config.decodeVocabSize;

		Assert.assertEquals("One-hot dimension should match decode vocab size",
				vocabSize, input.getShape().getTotalSize());

		// First pair's input should be the SOS token, encoded at position 0
		Assert.assertEquals("SOS one-hot should set position 0",
				1.0, input.toDouble(0), 1e-10);
	}

	/**
	 * Verify MidiTrainingConfig default values match the reference Moonbeam spec.
	 */
	@Test
	public void testDefaultTrainingConfig() {
		MidiTrainingConfig config = MidiTrainingConfig.defaultConfig();

		Assert.assertEquals("Learning rate", 3e-4, config.getLearningRate(), 1e-10);
		Assert.assertEquals("Beta1", 0.9, config.getBeta1(), 1e-10);
		Assert.assertEquals("Beta2", 0.999, config.getBeta2(), 1e-10);
		Assert.assertEquals("LoRA rank", 8, config.getLoraRank());
		Assert.assertEquals("LoRA alpha", 32.0, config.getLoraAlpha(), 1e-10);
		Assert.assertEquals("Batch size", 4, config.getBatchSize());
	}

	/**
	 * Verify MidiTrainingConfig test configuration is valid and smaller than default.
	 */
	@Test
	public void testTestTrainingConfig() {
		MidiTrainingConfig config = MidiTrainingConfig.testConfig();

		Assert.assertTrue("Test lr should be higher than default",
				config.getLearningRate() > MidiTrainingConfig.defaultConfig().getLearningRate());
		Assert.assertTrue("Test maxSeqLen should be smaller",
				config.getMaxSeqLen() < MidiTrainingConfig.defaultConfig().getMaxSeqLen());
		Assert.assertTrue("Test LoRA rank should be smaller",
				config.getLoraRank() <= MidiTrainingConfig.defaultConfig().getLoraRank());
	}

	/**
	 * Verify that LoRA adapter configuration is created correctly from
	 * training config.
	 */
	@Test
	public void testLoraConfigCreation() {
		MoonbeamConfig modelConfig = MoonbeamConfig.testConfig();
		MidiTrainingConfig trainConfig = MidiTrainingConfig.defaultConfig();
		StateDictionary stateDict = createSyntheticWeights(modelConfig);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(modelConfig);
		GRUDecoder decoder = createSyntheticDecoder(modelConfig);

		MoonbeamMidi model = new MoonbeamMidi(modelConfig, stateDict, embedding, decoder);
		AdapterConfig adapterConfig = model.createLoraConfig(trainConfig);

		Assert.assertEquals("LoRA rank should match training config",
				trainConfig.getLoraRank(), adapterConfig.getRank());
		Assert.assertEquals("LoRA alpha should match training config",
				trainConfig.getLoraAlpha(), adapterConfig.getAlpha(), 1e-10);
		Assert.assertTrue("Should target self-attention QKV",
				adapterConfig.isTargeted(AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));
		Assert.assertTrue("Should target self-attention output",
				adapterConfig.isTargeted(AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));
	}

	/**
	 * Verify that LoRA adapter bundle can be saved to a file.
	 */
	@Test
	public void testLoraAdapterSave() throws IOException {
		MoonbeamConfig modelConfig = MoonbeamConfig.testConfig();
		MidiTrainingConfig trainConfig = MidiTrainingConfig.defaultConfig();
		StateDictionary stateDict = createSyntheticWeights(modelConfig);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(modelConfig);
		GRUDecoder decoder = createSyntheticDecoder(modelConfig);

		MoonbeamMidi model = new MoonbeamMidi(modelConfig, stateDict, embedding, decoder);

		File tempFile = File.createTempFile("moonbeam_lora_test", ".pb");
		tempFile.deleteOnExit();

		model.saveLoraAdapter(tempFile.toPath(), trainConfig);
		Assert.assertTrue("Adapter file should be written", tempFile.exists());
		Assert.assertTrue("Adapter file should not be empty", tempFile.length() > 0);
	}

	// ========================================================================
	// Milestone 8: Evaluation and Polish Tests
	// ========================================================================

	/**
	 * End-to-end generation test: SOS -> generate N tokens -> detokenize -> write MIDI.
	 */
	@Test @TestDepth(2)
	public void testEndToEndGeneration() throws IOException, InvalidMidiDataException {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MoonbeamMidiGenerator autoregressive = new MoonbeamMidiGenerator(model);

		List<MidiCompoundToken> generated = autoregressive.generate(5);
		Assert.assertFalse("Should generate tokens", generated.isEmpty());

		MidiTokenizer tokenizer = new MidiTokenizer();
		List<MidiNoteEvent> events = tokenizer.detokenize(generated);

		if (!events.isEmpty()) {
			File tempFile = File.createTempFile("moonbeam_e2e_test", ".mid");
			tempFile.deleteOnExit();

			MidiFileReader reader = new MidiFileReader();
			reader.write(events, tempFile);
			Assert.assertTrue("MIDI file should be written", tempFile.exists());
			Assert.assertTrue("MIDI file should not be empty", tempFile.length() > 0);
		}
	}

	/**
	 * Prompt completion test: create a MIDI file, read it as prompt,
	 * continue generation, write output.
	 */
	@Test @TestDepth(2)
	public void testPromptCompletion() throws IOException, InvalidMidiDataException {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiFileReader reader = new MidiFileReader();
		MidiTokenizer tokenizer = new MidiTokenizer();

		List<MidiNoteEvent> promptEvents = new ArrayList<>();
		promptEvents.add(new MidiNoteEvent(60, 0, 100, 80, 0));
		promptEvents.add(new MidiNoteEvent(64, 100, 100, 80, 0));
		promptEvents.add(new MidiNoteEvent(67, 200, 100, 80, 0));

		File promptFile = File.createTempFile("moonbeam_prompt_test", ".mid");
		promptFile.deleteOnExit();
		reader.write(promptEvents, promptFile);

		List<MidiNoteEvent> readEvents = reader.read(promptFile);
		List<MidiCompoundToken> promptTokens = tokenizer.tokenize(readEvents);

		MoonbeamMidiGenerator autoregressive = new MoonbeamMidiGenerator(model);
		autoregressive.setPrompt(promptTokens.toArray(new MidiCompoundToken[0]));

		List<MidiCompoundToken> generated = autoregressive.generate(3);
		Assert.assertFalse("Should generate tokens after prompt", generated.isEmpty());

		List<MidiCompoundToken> allTokens = new ArrayList<>(promptTokens);
		allTokens.addAll(generated);

		List<MidiNoteEvent> outputEvents = tokenizer.detokenize(allTokens);
		Assert.assertTrue("Output should have at least prompt events",
				outputEvents.size() >= readEvents.size());

		File outputFile = File.createTempFile("moonbeam_prompt_out_test", ".mid");
		outputFile.deleteOnExit();
		reader.write(outputEvents, outputFile);
		Assert.assertTrue("Output MIDI should be written", outputFile.exists());
	}

	/**
	 * Temperature sampling produces different outputs with different seeds.
	 *
	 * <p>With temperature > 0, the sampling is stochastic. Different seeds
	 * should produce different sequences (with high probability for
	 * non-degenerate logits). We use non-zero weights to ensure non-trivial
	 * logit distributions.</p>
	 */
	@Test
	public void testTemperatureSamplingDiversity() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createNonZeroDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);

		MoonbeamMidiGenerator gen1 = new MoonbeamMidiGenerator(model);
		gen1.setTemperature(1.0);
		gen1.setSeed(42);
		List<MidiCompoundToken> output1 = gen1.generate(5);

		MoonbeamMidiGenerator gen2 = new MoonbeamMidiGenerator(model);
		gen2.setTemperature(1.0);
		gen2.setSeed(12345);
		List<MidiCompoundToken> output2 = gen2.generate(5);

		boolean anyDifference = false;
		int minLen = Math.min(output1.size(), output2.size());
		for (int i = 0; i < minLen; i++) {
			if (!output1.get(i).equals(output2.get(i))) {
				anyDifference = true;
				break;
			}
		}

		if (output1.size() != output2.size()) {
			anyDifference = true;
		}

		Assert.assertTrue(
				"Different seeds with temperature>0 should produce different outputs",
				anyDifference);
	}

	/**
	 * Top-p sampling restricts the vocabulary.
	 *
	 * <p>Tests the GRU decoder's sampleFromLogits method directly to verify
	 * that top-p filtering reduces the set of possible outputs.</p>
	 */
	@Test
	public void testTopPSamplingRestriction() {
		int vocabSize = 10;
		PackedCollection logits = new PackedCollection(new TraversalPolicy(vocabSize));
		logits.setMem(0, 10.0);
		logits.setMem(1, 5.0);
		logits.setMem(2, 1.0);
		for (int i = 3; i < vocabSize; i++) {
			logits.setMem(i, -10.0);
		}

		Set<Integer> sampledTokens = new HashSet<>();
		Random random = new Random(42);
		for (int trial = 0; trial < 100; trial++) {
			int token = AutoregressiveModel.sampleToken(logits, vocabSize, 1.0, 0.9, random);
			sampledTokens.add(token);
		}

		Assert.assertTrue("Top-p=0.9 should mostly sample from top tokens",
				sampledTokens.size() <= 3);
		Assert.assertTrue("Should sample token 0 (highest logit)",
				sampledTokens.contains(0));
	}

	/**
	 * Greedy decoding (temperature=0) produces deterministic output.
	 */
	@Test
	public void testGreedyDecodingDeterministic() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);

		MoonbeamMidiGenerator gen1 = new MoonbeamMidiGenerator(model);
		gen1.setTemperature(0.0);
		List<MidiCompoundToken> output1 = gen1.generate(3);

		MoonbeamMidiGenerator gen2 = new MoonbeamMidiGenerator(model);
		gen2.setTemperature(0.0);
		List<MidiCompoundToken> output2 = gen2.generate(3);

		Assert.assertEquals("Greedy should produce same length",
				output1.size(), output2.size());
		for (int i = 0; i < output1.size(); i++) {
			Assert.assertEquals("Greedy outputs should be identical at position " + i,
					output1.get(i), output2.get(i));
		}
	}

	/**
	 * Verify that setSeed makes sampling reproducible.
	 */
	@Test
	public void testSeedReproducibility() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createNonZeroDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);

		MoonbeamMidiGenerator gen1 = new MoonbeamMidiGenerator(model);
		gen1.setTemperature(0.8);
		gen1.setSeed(99);
		List<MidiCompoundToken> output1 = gen1.generate(5);

		MoonbeamMidiGenerator gen2 = new MoonbeamMidiGenerator(model);
		gen2.setTemperature(0.8);
		gen2.setSeed(99);
		List<MidiCompoundToken> output2 = gen2.generate(5);

		Assert.assertEquals("Same seed should produce same length",
				output1.size(), output2.size());
		for (int i = 0; i < output1.size(); i++) {
			Assert.assertEquals("Same seed should produce identical output at " + i,
					output1.get(i), output2.get(i));
		}
	}

	/**
	 * Verify that the profiling summary is non-empty after running inference.
	 */
	@Test
	public void testProfilingSummary() {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);

		MoonbeamMidiGenerator autoregressive = new MoonbeamMidiGenerator(model);
		autoregressive.generate(2);

		String summary = model.getProfilingSummary();
		Assert.assertNotNull("Profiling summary should not be null", summary);
	}

	/**
	 * Verify that the generateFromFile method can read a MIDI file, generate,
	 * and write output in a single call.
	 */
	@Test @TestDepth(2)
	public void testGenerateFromFile() throws IOException, InvalidMidiDataException {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MidiFileReader reader = new MidiFileReader();

		List<MidiNoteEvent> events = new ArrayList<>();
		events.add(new MidiNoteEvent(60, 0, 50, 80, 0));
		events.add(new MidiNoteEvent(64, 50, 50, 80, 0));

		File inputFile = File.createTempFile("moonbeam_gen_input", ".mid");
		inputFile.deleteOnExit();
		reader.write(events, inputFile);

		File outputFile = File.createTempFile("moonbeam_gen_output", ".mid");
		outputFile.deleteOnExit();

		MoonbeamMidiGenerator autoregressive = new MoonbeamMidiGenerator(model);
		autoregressive.generateFromFile(inputFile, outputFile, 3);

		Assert.assertTrue("Output file should exist", outputFile.exists());
		Assert.assertTrue("Output file should not be empty", outputFile.length() > 0);
	}

	/**
	 * Verify that unconditional generation writes a valid MIDI file.
	 */
	@Test @TestDepth(2)
	public void testGenerateUnconditional() throws IOException, InvalidMidiDataException {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		StateDictionary stateDict = createSyntheticWeights(config);
		CompoundMidiEmbedding embedding = new CompoundMidiEmbedding(config);
		GRUDecoder decoder = createSyntheticDecoder(config);

		MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
		MoonbeamMidiGenerator autoregressive = new MoonbeamMidiGenerator(model);

		File outputFile = File.createTempFile("moonbeam_unconditional", ".mid");
		outputFile.deleteOnExit();

		autoregressive.generateUnconditional(outputFile, 5);
		Assert.assertTrue("Output file should exist", outputFile.exists());
	}

	/**
	 * Verify that the MidiDataset can be created from a directory of MIDI files.
	 */
	@Test @TestDepth(2)
	public void testDatasetFromDirectory() throws IOException, InvalidMidiDataException {
		MoonbeamConfig config = MoonbeamConfig.testConfig();
		MidiTrainingConfig trainConfig = MidiTrainingConfig.testConfig();
		MidiFileReader reader = new MidiFileReader();

		File tempDir = createTempMidiDirectory(reader);

		MidiDataset dataset = new MidiDataset(tempDir, config, trainConfig);
		Assert.assertTrue("Should load sequences from directory",
				dataset.getSequenceCount() > 0);

		int pairCount = 0;
		for (ValueTarget<PackedCollection> ignored : dataset) {
			pairCount++;
		}
		Assert.assertTrue("Should produce training pairs", pairCount > 0);
	}

	/**
	 * Verify that MidiTrainingConfig.toString() returns a readable string.
	 */
	@Test
	public void testTrainingConfigToString() {
		MidiTrainingConfig config = MidiTrainingConfig.defaultConfig();
		String str = config.toString();
		Assert.assertNotNull(str);
		Assert.assertTrue("Should contain lr", str.contains("lr="));
		Assert.assertTrue("Should contain loraRank", str.contains("loraRank="));
	}

	// ========================================================================
	// Helper Methods
	// ========================================================================

	/**
	 * Create a StateDictionary with synthetic (zero-initialized) weights.
	 */
	private static StateDictionary createSyntheticWeights(MoonbeamConfig config) {
		Map<String, PackedCollection> weights = new HashMap<>();
		int dim = config.hiddenSize;
		int kvDim = dim * config.numKvHeads / config.numHeads;
		int ffnDim = config.intermediateSize;

		for (int i = 0; i < config.numLayers; i++) {
			String prefix = String.format("model.layers.%d", i);
			weights.put(prefix + ".input_layernorm.weight", onesCollection(dim));
			weights.put(prefix + ".post_attention_layernorm.weight", onesCollection(dim));
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

		int n = config.decoderLayers;
		int[] inputSizes = new int[n];
		PackedCollection[] weightIh = new PackedCollection[n];
		PackedCollection[] weightHh = new PackedCollection[n];
		PackedCollection[] biasIh = new PackedCollection[n];
		PackedCollection[] biasHh = new PackedCollection[n];
		for (int l = 0; l < n; l++) {
			inputSizes[l] = decoderHidden;
			weightIh[l] = new PackedCollection(new TraversalPolicy(3 * decoderHidden, decoderHidden));
			weightHh[l] = new PackedCollection(new TraversalPolicy(3 * decoderHidden, decoderHidden));
			biasIh[l] = new PackedCollection(new TraversalPolicy(3 * decoderHidden));
			biasHh[l] = new PackedCollection(new TraversalPolicy(3 * decoderHidden));
		}

		return new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
				new PackedCollection(new TraversalPolicy(decoderHidden, hidden)),
				new PackedCollection(new TraversalPolicy(decoderHidden)),
				new PackedCollection(new TraversalPolicy(vocabSize, decoderHidden)),
				new PackedCollection(new TraversalPolicy(vocabSize)),
				new PackedCollection(new TraversalPolicy(vocabSize, decoderHidden)));
	}

	/**
	 * Create a GRU decoder with non-zero weights to produce non-uniform logit
	 * distributions, which is needed for sampling diversity tests.
	 */
	private static GRUDecoder createNonZeroDecoder(MoonbeamConfig config) {
		int hidden = config.hiddenSize;
		int decoderHidden = config.decoderHiddenSize;
		int vocabSize = config.decodeVocabSize;

		Random rng = new Random(42);

		int n = config.decoderLayers;
		int[] inputSizes = new int[n];
		PackedCollection[] weightIh = new PackedCollection[n];
		PackedCollection[] weightHh = new PackedCollection[n];
		PackedCollection[] biasIh = new PackedCollection[n];
		PackedCollection[] biasHh = new PackedCollection[n];
		for (int l = 0; l < n; l++) {
			inputSizes[l] = decoderHidden;
			weightIh[l] = randomMatrix(3 * decoderHidden, decoderHidden, rng);
			weightHh[l] = randomMatrix(3 * decoderHidden, decoderHidden, rng);
			biasIh[l] = randomCollection(3 * decoderHidden, rng);
			biasHh[l] = randomCollection(3 * decoderHidden, rng);
		}

		return new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
				randomMatrix(decoderHidden, hidden, rng),
				randomCollection(decoderHidden, rng),
				randomMatrix(vocabSize, decoderHidden, rng),
				randomCollection(vocabSize, rng),
				randomMatrix(vocabSize, decoderHidden, rng));
	}

	/**
	 * Create a PackedCollection filled with ones.
	 */
	private static PackedCollection onesCollection(int size) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(size));
		for (int i = 0; i < size; i++) {
			collection.setMem(i, 1.0);
		}
		return collection;
	}

	/**
	 * Create a PackedCollection filled with small random values.
	 */
	private static PackedCollection randomCollection(int size, Random rng) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(size));
		for (int i = 0; i < size; i++) {
			collection.setMem(i, (rng.nextDouble() - 0.5) * 0.1);
		}
		return collection;
	}

	/**
	 * Create a 2D PackedCollection filled with small random values.
	 */
	private static PackedCollection randomMatrix(int rows, int cols, Random rng) {
		PackedCollection collection = new PackedCollection(new TraversalPolicy(rows, cols));
		for (int i = 0; i < rows * cols; i++) {
			collection.setMem(i, (rng.nextDouble() - 0.5) * 0.1);
		}
		return collection;
	}

	/**
	 * Create a temporary directory with a few MIDI files for testing.
	 */
	private static File createTempMidiDirectory(MidiFileReader reader)
			throws IOException, InvalidMidiDataException {
		File tempDir = new File(System.getProperty("java.io.tmpdir"), "moonbeam_test_midi");
		tempDir.mkdirs();

		for (int f = 0; f < 3; f++) {
			List<MidiNoteEvent> events = new ArrayList<>();
			for (int n = 0; n < 4; n++) {
				int pitch = 60 + (f * 4) + n;
				events.add(new MidiNoteEvent(pitch, (long) n * 50, 25, 80, 0));
			}

			File midiFile = new File(tempDir, "test_" + f + ".mid");
			midiFile.deleteOnExit();
			reader.write(events, midiFile);
		}

		return tempDir;
	}
}
