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
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.ValueTarget;

import javax.sound.midi.InvalidMidiDataException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Training dataset for the Moonbeam MIDI model.
 *
 * <p>Reads MIDI files from a directory, tokenizes them via {@link MidiTokenizer}
 * into compound token sequences, and provides batched training examples as
 * (input, target) pairs for use with {@code ModelOptimizer}.</p>
 *
 * <h2>Packing Strategy</h2>
 * <p>Following the Moonbeam reference, multiple MIDI files can be concatenated
 * ("packed") into single training sequences with EOS separator tokens between
 * them. This improves GPU utilization when individual MIDI files are shorter
 * than the maximum sequence length.</p>
 *
 * <h2>Training Pair Format</h2>
 * <p>Each training pair consists of:</p>
 * <ul>
 *   <li><b>Input:</b> one-hot encoded compound token indices for the GRU
 *       decode vocabulary, shape (decodeVocabSize,)</li>
 *   <li><b>Target:</b> one-hot encoded next-token GRU decode vocabulary
 *       indices, shape (decodeVocabSize,)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MidiDataset dataset = new MidiDataset(
 *     new File("/path/to/midi/files"),
 *     MoonbeamConfig.defaultConfig(),
 *     MidiTrainingConfig.defaultConfig());
 *
 * ModelOptimizer optimizer = new ModelOptimizer(model);
 * optimizer.setDataset(() -> dataset);
 * optimizer.setLossFunction(new NegativeLogLikelihood());
 * optimizer.optimize(config.getEpochs());
 * }</pre>
 *
 * @see MidiTokenizer
 * @see MidiTrainingConfig
 * @see MoonbeamConfig
 */
public class MidiDataset implements Dataset<PackedCollection> {

	private final MoonbeamConfig modelConfig;
	private final int maxSeqLen;
	private final List<List<MidiCompoundToken>> tokenizedSequences;
	private final int[] vocabOffsets;

	/**
	 * Create a dataset from a directory of MIDI files.
	 *
	 * @param midiDirectory directory containing .mid/.midi files
	 * @param modelConfig   model configuration for vocabulary sizes
	 * @param trainingConfig training configuration for sequence length
	 * @throws IOException if files cannot be read
	 */
	public MidiDataset(File midiDirectory, MoonbeamConfig modelConfig,
					   MidiTrainingConfig trainingConfig) throws IOException {
		this.modelConfig = modelConfig;
		this.maxSeqLen = trainingConfig.getMaxSeqLen();
		this.vocabOffsets = GRUDecoder.computeVocabOffsets(modelConfig);
		this.tokenizedSequences = loadAndTokenize(midiDirectory);
	}

	/**
	 * Create a dataset from pre-tokenized sequences (for testing).
	 *
	 * @param sequences   list of tokenized compound token sequences
	 * @param modelConfig model configuration
	 * @param maxSeqLen   maximum sequence length
	 */
	public MidiDataset(List<List<MidiCompoundToken>> sequences,
					   MoonbeamConfig modelConfig, int maxSeqLen) {
		this.modelConfig = modelConfig;
		this.maxSeqLen = maxSeqLen;
		this.vocabOffsets = GRUDecoder.computeVocabOffsets(modelConfig);
		this.tokenizedSequences = new ArrayList<>(sequences);
	}

	@Override
	public Iterator<ValueTarget<PackedCollection>> iterator() {
		List<ValueTarget<PackedCollection>> pairs = buildTrainingPairs();
		return pairs.iterator();
	}

	/**
	 * Returns the number of tokenized sequences in this dataset.
	 *
	 * @return sequence count
	 */
	public int getSequenceCount() {
		return tokenizedSequences.size();
	}

	/**
	 * Returns the total number of tokens across all sequences.
	 *
	 * @return total token count
	 */
	public int getTotalTokenCount() {
		int total = 0;
		for (List<MidiCompoundToken> seq : tokenizedSequences) {
			total += seq.size();
		}
		return total;
	}

	/**
	 * Pack multiple sequences into fixed-length training sequences,
	 * concatenated with EOS separator tokens.
	 *
	 * @return list of packed sequences, each at most maxSeqLen tokens
	 */
	public List<List<MidiCompoundToken>> packSequences() {
		List<List<MidiCompoundToken>> packed = new ArrayList<>();
		List<MidiCompoundToken> current = new ArrayList<>();

		for (List<MidiCompoundToken> sequence : tokenizedSequences) {
			if (current.size() + sequence.size() > maxSeqLen && !current.isEmpty()) {
				packed.add(new ArrayList<>(current));
				current.clear();
			}

			if (sequence.size() > maxSeqLen) {
				packed.add(new ArrayList<>(sequence.subList(0, maxSeqLen)));
			} else {
				current.addAll(sequence);
			}
		}

		if (!current.isEmpty()) {
			packed.add(current);
		}

		return packed;
	}

	/**
	 * Build training pairs from packed sequences.
	 *
	 * <p>For each consecutive pair of tokens (t_i, t_{i+1}) in a packed
	 * sequence, creates a ValueTarget where the input encodes t_i's
	 * GRU decode vocabulary indices and the target encodes t_{i+1}'s
	 * indices.</p>
	 */
	private List<ValueTarget<PackedCollection>> buildTrainingPairs() {
		List<List<MidiCompoundToken>> packed = packSequences();
		List<ValueTarget<PackedCollection>> pairs = new ArrayList<>();
		int vocabSize = modelConfig.decodeVocabSize;

		for (List<MidiCompoundToken> sequence : packed) {
			for (int i = 0; i < sequence.size() - 1; i++) {
				MidiCompoundToken inputToken = sequence.get(i);
				MidiCompoundToken targetToken = sequence.get(i + 1);

				PackedCollection input = tokenToOneHot(inputToken, vocabSize);
				PackedCollection target = tokenToOneHot(targetToken, vocabSize);

				pairs.add(ValueTarget.of(input, target));
			}
		}

		return pairs;
	}

	/**
	 * Convert a compound token to a one-hot vector over the GRU decode vocabulary.
	 *
	 * <p>For normal tokens, sets 1.0 at the index corresponding to each attribute's
	 * position in the flat decode vocabulary. For special tokens, only the first
	 * position (SOS) is set.</p>
	 *
	 * @param token     the compound token
	 * @param vocabSize the decode vocabulary size
	 * @return one-hot PackedCollection of shape (vocabSize,)
	 */
	private PackedCollection tokenToOneHot(MidiCompoundToken token, int vocabSize) {
		PackedCollection oneHot = new PackedCollection(new TraversalPolicy(vocabSize));

		if (token.isSpecial()) {
			oneHot.setMem(0, 1.0);
			return oneHot;
		}

		int[] values = token.toArray();
		for (int attr = 0; attr < MoonbeamConfig.NUM_ATTRIBUTES; attr++) {
			int index = vocabOffsets[attr + 1] + values[attr];
			if (index >= 0 && index < vocabSize) {
				oneHot.setMem(index, 1.0);
			}
		}

		return oneHot;
	}

	/**
	 * Load and tokenize all MIDI files from a directory.
	 */
	private List<List<MidiCompoundToken>> loadAndTokenize(File directory) throws IOException {
		List<List<MidiCompoundToken>> sequences = new ArrayList<>();
		MidiFileReader reader = new MidiFileReader();
		MidiTokenizer tokenizer = new MidiTokenizer();

		File[] files = directory.listFiles((dir, name) ->
				name.endsWith(".mid") || name.endsWith(".midi"));

		if (files == null || files.length == 0) {
			return sequences;
		}

		for (File file : files) {
			try {
				List<MidiNoteEvent> events = reader.read(file);
				if (!events.isEmpty()) {
					List<MidiCompoundToken> tokens = tokenizer.tokenize(events);
					sequences.add(tokens);
				}
			} catch (InvalidMidiDataException e) {
				// Skip files with invalid MIDI data
			}
		}

		return sequences;
	}

	/**
	 * Create a synthetic dataset for testing purposes.
	 *
	 * <p>Generates simple synthetic MIDI sequences with predictable patterns,
	 * suitable for verifying that the training pipeline runs correctly.</p>
	 *
	 * @param numSequences number of sequences to generate
	 * @param seqLength    number of notes per sequence
	 * @param config       model configuration
	 * @return a new MidiDataset with synthetic data
	 */
	public static MidiDataset synthetic(int numSequences, int seqLength,
										MoonbeamConfig config) {
		MidiTokenizer tokenizer = new MidiTokenizer();
		List<List<MidiCompoundToken>> sequences = new ArrayList<>();

		for (int s = 0; s < numSequences; s++) {
			List<MidiNoteEvent> events = new ArrayList<>();
			for (int n = 0; n < seqLength; n++) {
				int pitch = 60 + (n % 12);
				long onset = (long) n * 50;
				long duration = 25 + (n % 25);
				int velocity = 64 + (n % 32);
				events.add(new MidiNoteEvent(pitch, onset, duration, velocity, 0));
			}
			sequences.add(tokenizer.tokenize(events));
		}

		return new MidiDataset(sequences, config, config.maxSeqLen);
	}
}
