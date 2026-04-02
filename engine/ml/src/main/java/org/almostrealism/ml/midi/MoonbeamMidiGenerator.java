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
import org.almostrealism.ml.AutoregressiveModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.midi.InvalidMidiDataException;

/**
 * Autoregressive generation model for compound MIDI tokens.
 *
 * <p>This class wraps {@link AutoregressiveModel}{@code <MidiCompoundToken>} to provide
 * MIDI-specific autoregressive generation. Unlike standard text models where input and
 * output are single token IDs, Moonbeam takes a 6-attribute compound token as input and
 * produces 7 decode tokens per position via the GRU decoder.</p>
 *
 * <h2>Generation Flow</h2>
 * <ol>
 *   <li>Embed compound token via {@link CompoundMidiEmbedding}</li>
 *   <li>Forward through compiled transformer to get hidden state</li>
 *   <li>Decode hidden state via {@link GRUDecoder} to get 7 output tokens</li>
 *   <li>Map output tokens back to a {@link MidiCompoundToken}</li>
 *   <li>Use generated token as input for the next step</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MoonbeamMidi model = new MoonbeamMidi(config, stateDict, embedding, decoder);
 * MoonbeamMidiGenerator generator = model.createAutoregressiveModel();
 * generator.setPrompt(new MidiCompoundToken[]{ MidiCompoundToken.sos(), token1, token2 });
 *
 * List<MidiCompoundToken> generated = new ArrayList<>();
 * for (int i = 0; i < maxLen; i++) {
 *     MidiCompoundToken next = autoregressive.next();
 *     if (next.isEOS()) break;
 *     generated.add(next);
 * }
 * }</pre>
 *
 * @see MoonbeamMidi
 * @see AutoregressiveModel
 * @see CompoundMidiEmbedding
 * @see GRUDecoder
 */
public class MoonbeamMidiGenerator {

	private final AutoregressiveModel<MidiCompoundToken> inner;
	private final MoonbeamMidi model;
	private final MoonbeamConfig config;
	private final CompoundMidiEmbedding embedding;
	private final GRUDecoder decoder;

	/** Current length of the prompt set via {@link #setPrompt(MidiCompoundToken[])}. */
	private int promptLength;

	/**
	 * Top-p (nucleus) sampling threshold.
	 *
	 * <p>When set to 1.0, no filtering is applied. Lower values restrict
	 * sampling to the most probable tokens whose cumulative probability
	 * exceeds topP. Typical values are 0.9-0.95.</p>
	 */
	private double topP;

	/** Random number generator for sampling. */
	private final Random random;

	/**
	 * Create a MoonbeamMidiGenerator from a MoonbeamMidi model.
	 *
	 * @param model the Moonbeam model containing transformer, embedding, and decoder
	 */
	public MoonbeamMidiGenerator(MoonbeamMidi model) {
		this(model, new Random());
	}

	/**
	 * Create a MoonbeamMidiGenerator from a MoonbeamMidi model with the given random source.
	 *
	 * @param model  the Moonbeam model containing transformer, embedding, and decoder
	 * @param random random number generator for sampling
	 */
	public MoonbeamMidiGenerator(MoonbeamMidi model, Random random) {
		this.model = model;
		this.config = model.getConfig();
		this.embedding = model.getEmbedding();
		this.decoder = model.getDecoder();
		this.topP = 1.0;
		this.random = random;

		int hiddenSize = config.hiddenSize;
		PackedCollection input = new PackedCollection(new TraversalPolicy(1, hiddenSize));
		PackedCollection temperature = new PackedCollection(1);

		this.inner = new AutoregressiveModel<>(
				step -> model.setPosition(step),
				token -> {
					model.setAttributePositions(token);
					PackedCollection emb = embedding.embed(token).evaluate();
					input.setMem(0, emb.toArray(0, hiddenSize), 0, hiddenSize);
				},
				() -> model.forward(input),
				hidden -> {
					PackedCollection vec = new PackedCollection(hiddenSize);
					vec.setMem(0, hidden.toArray(0, hiddenSize), 0, hiddenSize);
					int[] decodeTokens = decoder.decode(vec, temperature.toDouble(0), topP, random);
					return decodeToCompoundToken(decodeTokens);
				},
				temperature);

		this.inner.setCurrentToken(MidiCompoundToken.sos());
	}

	/**
	 * Set the prompt tokens for the model.
	 *
	 * <p>During generation, the model will process prompt tokens first,
	 * building up the KV cache, before generating new tokens.</p>
	 *
	 * @param promptTokens array of compound tokens to use as prompt
	 */
	public void setPrompt(MidiCompoundToken[] promptTokens) {
		this.promptLength = promptTokens != null ? promptTokens.length : 0;
		inner.setPrompt(promptTokens, promptLength);
		inner.setCurrentStep(0);
		inner.setCurrentToken(MidiCompoundToken.sos());
	}

	/**
	 * Generate and return the next compound token in the sequence.
	 *
	 * @return the next compound token (may be EOS to signal end)
	 */
	public MidiCompoundToken next() {
		return inner.next();
	}

	/**
	 * Generate a sequence of compound tokens.
	 *
	 * @param maxTokens maximum number of tokens to generate
	 * @return list of generated tokens (excluding prompt, including EOS if generated)
	 */
	public List<MidiCompoundToken> generate(int maxTokens) {
		// Exhaust the prompt phase
		while (inner.getCurrentStep() < promptLength) {
			inner.next();
		}

		List<MidiCompoundToken> generated = new ArrayList<>();
		for (int i = 0; i < maxTokens; i++) {
			MidiCompoundToken token = inner.next();
			generated.add(token);
			if (token.isEOS()) break;
		}

		return generated;
	}

	/**
	 * Set the sampling temperature.
	 *
	 * <p>When temperature is 0, the decoder uses greedy argmax. Higher values
	 * produce more diverse output. Typical values are 0.7-1.0 for creative
	 * generation.</p>
	 *
	 * @param temperature 0.0 for greedy, higher for more randomness
	 */
	public void setTemperature(double temperature) {
		inner.setTemperature(temperature);
	}

	/**
	 * Set the top-p (nucleus) sampling threshold.
	 *
	 * <p>When topP is 1.0, all tokens are eligible. Lower values restrict
	 * sampling to the most probable tokens whose cumulative probability
	 * exceeds topP. Common values are 0.9 for focused output or 0.95
	 * for more variety.</p>
	 *
	 * @param topP cumulative probability threshold (0.0 to 1.0)
	 */
	public void setTopP(double topP) {
		this.topP = topP;
	}

	/**
	 * Set the random seed for reproducible sampling.
	 *
	 * @param seed the random seed
	 */
	public void setSeed(long seed) {
		random.setSeed(seed);
	}

	/** Returns the current step in the sequence. */
	public int getCurrentStep() { return inner.getCurrentStep(); }

	/** Returns the current (most recently produced) token. */
	public MidiCompoundToken getCurrentToken() { return inner.getCurrentToken(); }

	/** Returns the temperature setting. */
	public double getTemperature() { return inner.getTemperature(); }

	/** Returns the top-p setting. */
	public double getTopP() { return topP; }

	/**
	 * Generate tokens for a fill region within a masked token sequence.
	 *
	 * <p>The input sequence contains normal tokens as context, with one or more
	 * regions delimited by {@link MidiCompoundToken#fillStart()} and
	 * {@link MidiCompoundToken#fillEnd()} markers. This method:</p>
	 * <ol>
	 *   <li>Feeds all tokens before the first FILL_START through the transformer
	 *       (building up the KV cache as left context)</li>
	 *   <li>Feeds the FILL_START token itself</li>
	 *   <li>Autoregressively generates tokens until maxFillTokens is reached
	 *       or EOS is produced</li>
	 *   <li>Feeds the FILL_END token and any remaining right-context tokens</li>
	 * </ol>
	 *
	 * <p>The returned list contains only the generated fill tokens (not the
	 * context tokens or fill delimiters).</p>
	 *
	 * @param maskedSequence token sequence containing FILL_START/FILL_END markers
	 * @param maxFillTokens  maximum number of tokens to generate for the fill region
	 * @return list of generated tokens for the fill region
	 */
	public List<MidiCompoundToken> generateInfill(List<MidiCompoundToken> maskedSequence,
												   int maxFillTokens) {
		int fillStartIdx = -1;
		int fillEndIdx = -1;
		for (int i = 0; i < maskedSequence.size(); i++) {
			if (maskedSequence.get(i).isFillStart() && fillStartIdx < 0) {
				fillStartIdx = i;
			} else if (maskedSequence.get(i).isFillEnd() && fillStartIdx >= 0) {
				fillEndIdx = i;
				break;
			}
		}

		if (fillStartIdx < 0 || fillEndIdx < 0) {
			throw new IllegalArgumentException(
					"Masked sequence must contain FILL_START and FILL_END markers");
		}

		// Feed left context + FILL_START as the prompt
		MidiCompoundToken[] leftContext = maskedSequence.subList(0, fillStartIdx + 1)
				.toArray(new MidiCompoundToken[0]);
		setPrompt(leftContext);
		while (inner.getCurrentStep() < leftContext.length) {
			inner.next();
		}

		// Generate fill tokens autoregressively
		List<MidiCompoundToken> fillTokens = new ArrayList<>();
		for (int i = 0; i < maxFillTokens; i++) {
			MidiCompoundToken generated = inner.next();
			if (generated.isEOS()) break;
			fillTokens.add(generated);
		}

		// Feed FILL_END and right context to update the KV cache
		for (int i = fillEndIdx; i < maskedSequence.size(); i++) {
			MidiCompoundToken token = maskedSequence.get(i);
			if (token.isEOS()) break;
			int step = inner.getCurrentStep();
			model.setPosition(step);
			model.setAttributePositions(token);
			inner.setCurrentStep(step + 1);
		}

		return fillTokens;
	}

	/**
	 * Generate tokens from an input MIDI file and write the result.
	 *
	 * <p>Reads a MIDI file as prompt, generates additional tokens, and
	 * writes the combined output to a new MIDI file.</p>
	 *
	 * @param inputFile  the input MIDI file (prompt)
	 * @param outputFile the output MIDI file
	 * @param maxTokens  maximum number of tokens to generate
	 * @throws IOException              if files cannot be read or written
	 * @throws InvalidMidiDataException if MIDI data is invalid
	 */
	public void generateFromFile(File inputFile, File outputFile, int maxTokens)
			throws IOException, InvalidMidiDataException {
		MidiFileReader reader = new MidiFileReader();
		MidiTokenizer tokenizer = new MidiTokenizer();

		List<MidiNoteEvent> inputEvents = reader.read(inputFile);
		List<MidiCompoundToken> inputTokens = tokenizer.tokenize(inputEvents);

		setPrompt(inputTokens.toArray(new MidiCompoundToken[0]));
		List<MidiCompoundToken> generated = generate(maxTokens);

		List<MidiCompoundToken> allTokens = new ArrayList<>(inputTokens);
		allTokens.addAll(generated);

		List<MidiNoteEvent> outputEvents = tokenizer.detokenize(allTokens);
		reader.write(outputEvents, outputFile);
	}

	/**
	 * Generate tokens unconditionally (from SOS token only) and write the result.
	 *
	 * @param outputFile the output MIDI file
	 * @param maxTokens  maximum number of tokens to generate
	 * @throws IOException              if the file cannot be written
	 * @throws InvalidMidiDataException if MIDI data is invalid
	 */
	public void generateUnconditional(File outputFile, int maxTokens)
			throws IOException, InvalidMidiDataException {
		MidiFileReader reader = new MidiFileReader();
		MidiTokenizer tokenizer = new MidiTokenizer();

		List<MidiCompoundToken> generated = generate(maxTokens);

		List<MidiNoteEvent> outputEvents = tokenizer.detokenize(generated);
		reader.write(outputEvents, outputFile);
	}

	/**
	 * Convert GRU decoder output tokens to a compound token.
	 *
	 * <p>The decode tokens are in the flat decode vocabulary. The first token
	 * (sos_out) is skipped. Tokens 1-6 are mapped to attribute values by
	 * subtracting the vocabulary offset for each position.</p>
	 *
	 * @param decodeTokens 7 tokens from the GRU decoder
	 * @return the compound token
	 */
	private MidiCompoundToken decodeToCompoundToken(int[] decodeTokens) {
		int[] attributeValues = decoder.toAttributeValues(decodeTokens);
		return new MidiCompoundToken(
				attributeValues[1], attributeValues[2], attributeValues[3],
				attributeValues[4], attributeValues[5], attributeValues[6]);
	}
}
