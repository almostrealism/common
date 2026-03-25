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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sound.midi.InvalidMidiDataException;

/**
 * Autoregressive generation model for compound MIDI tokens.
 *
 * <p>This class manages the compound token generation loop for the Moonbeam
 * model. Unlike standard text autoregressive models where input and output
 * are single token IDs, Moonbeam takes a 6-attribute compound token as input
 * and produces 7 decode tokens per position via the GRU decoder, which are
 * then mapped back to a compound token.</p>
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
 * MidiAutoregressiveModel autoregressive = model.createAutoregressiveModel();
 * autoregressive.setPrompt(new MidiCompoundToken[]{ MidiCompoundToken.sos(), token1, token2 });
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
 * @see CompoundMidiEmbedding
 * @see GRUDecoder
 */
public class MidiAutoregressiveModel {

	private final MoonbeamMidi model;
	private final MoonbeamConfig config;
	private final CompoundMidiEmbedding embedding;
	private final GRUDecoder decoder;
	private final int[] vocabOffsets;

	private int currentStep;
	private MidiCompoundToken currentToken;
	private MidiCompoundToken[] prompt;
	private int promptLength;

	/**
	 * Cached hidden state from the most recent transformer forward pass.
	 * Used to carry the hidden state from the last prompt token forward
	 * to the first generation step without re-forwarding.
	 */
	private PackedCollection lastHidden;

	/**
	 * Temperature for sampling (0 = greedy).
	 *
	 * <p>When set to 0, the decoder uses greedy argmax. Higher values
	 * produce more random output. Typical values are 0.7-1.0.</p>
	 */
	private double temperature;

	/**
	 * Top-p (nucleus) sampling threshold.
	 *
	 * <p>When set to 1.0, no filtering is applied. Lower values restrict
	 * sampling to the most probable tokens whose cumulative probability
	 * exceeds topP. Typical values are 0.9-0.95.</p>
	 */
	private double topP;

	/** Random number generator for sampling. */
	private Random random;

	/**
	 * Create a MidiAutoregressiveModel from a MoonbeamMidi model.
	 *
	 * @param model the Moonbeam model containing transformer, embedding, and decoder
	 */
	public MidiAutoregressiveModel(MoonbeamMidi model) {
		this.model = model;
		this.config = model.getConfig();
		this.embedding = model.getEmbedding();
		this.decoder = model.getDecoder();
		this.vocabOffsets = GRUDecoder.computeVocabOffsets(config);
		this.currentStep = 0;
		this.currentToken = MidiCompoundToken.sos();
		this.temperature = 0.0;
		this.topP = 1.0;
		this.random = new Random();
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
		this.prompt = promptTokens;
		this.promptLength = promptTokens != null ? promptTokens.length : 0;
		this.currentStep = 0;
		this.currentToken = MidiCompoundToken.sos();
		this.lastHidden = null;
	}

	/**
	 * Generate and return the next compound token in the sequence.
	 *
	 * <p>During the prompt phase, each prompt token is forwarded through
	 * the transformer to populate the KV cache. The hidden state from
	 * the last prompt token is saved and used to decode the first
	 * generated token on the next call.</p>
	 *
	 * <p>During generation, the previously saved hidden state is decoded
	 * via the GRU decoder, the resulting token is forwarded through the
	 * transformer to extend the KV cache, and its hidden state is saved
	 * for the next call.</p>
	 *
	 * @return the next compound token (may be EOS to signal end)
	 */
	public MidiCompoundToken next() {
		if (currentStep < promptLength) {
			MidiCompoundToken inputToken = prompt[currentStep];
			processToken(inputToken);
			lastHidden = model.forward(embedToken(inputToken));
			currentToken = inputToken;
			currentStep++;
			return currentToken;
		}

		if (lastHidden == null) {
			// No prompt was set; forward the initial SOS token
			processToken(currentToken);
			lastHidden = model.forward(embedToken(currentToken));
			currentStep++;
		}

		PackedCollection hiddenVec = extractHiddenVector(lastHidden);
		int[] decodeTokens = decoder.decode(hiddenVec, temperature, topP, random);
		MidiCompoundToken generated = decodeToCompoundToken(decodeTokens);

		processToken(generated);
		lastHidden = model.forward(embedToken(generated));

		currentToken = generated;
		currentStep++;
		return generated;
	}

	/**
	 * Generate a sequence of compound tokens.
	 *
	 * @param maxTokens maximum number of tokens to generate
	 * @return list of generated tokens (excluding prompt, including EOS if generated)
	 */
	public List<MidiCompoundToken> generate(int maxTokens) {
		List<MidiCompoundToken> generated = new ArrayList<>();

		while (currentStep < promptLength) {
			next();
		}

		for (int i = 0; i < maxTokens; i++) {
			MidiCompoundToken token = next();
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
		this.temperature = temperature;
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
		this.random = new Random(seed);
	}

	/** Returns the current step in the sequence. */
	public int getCurrentStep() { return currentStep; }

	/** Returns the current (most recently produced) token. */
	public MidiCompoundToken getCurrentToken() { return currentToken; }

	/** Returns the temperature setting. */
	public double getTemperature() { return temperature; }

	/** Returns the top-p setting. */
	public double getTopP() { return topP; }

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

		List<MidiCompoundToken> allTokens = new ArrayList<>();
		allTokens.addAll(inputTokens);
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
	 * Process a token through the model: update positions and prepare for forward pass.
	 */
	private void processToken(MidiCompoundToken token) {
		model.setPosition(currentStep);
		model.setAttributePositions(token);
	}

	/**
	 * Embed a compound token into a (1, hiddenSize) collection for transformer input.
	 */
	private PackedCollection embedToken(MidiCompoundToken token) {
		PackedCollection emb = embedding.embed(token);
		int hidden = config.hiddenSize;
		PackedCollection input = new PackedCollection(new TraversalPolicy(1, hidden));
		double[] data = emb.toArray(0, hidden);
		input.setMem(0, data, 0, hidden);
		return input;
	}

	/**
	 * Extract the hidden vector from transformer output of shape (1, hiddenSize).
	 */
	private PackedCollection extractHiddenVector(PackedCollection transformerOutput) {
		int hidden = config.hiddenSize;
		PackedCollection vec = new PackedCollection(hidden);
		double[] data = transformerOutput.toArray(0, hidden);
		vec.setMem(0, data, 0, hidden);
		return vec;
	}

	/**
	 * Convert GRU decoder output tokens to a compound token.
	 *
	 * <p>The decode tokens are in the flat decode vocabulary. The first token
	 * (sos_out) is skipped. Tokens 1-6 are mapped to attribute values by
	 * subtracting the vocabulary offset for each position. Values are clamped
	 * to their valid ranges to prevent out-of-bounds embedding lookups when
	 * the decoder selects tokens outside the expected attribute range.</p>
	 *
	 * @param decodeTokens 7 tokens from the GRU decoder
	 * @return the compound token
	 */
	private MidiCompoundToken decodeToCompoundToken(int[] decodeTokens) {
		int[] attributeValues = decoder.toAttributeValues(decodeTokens);

		int onset = clamp(attributeValues[1], 0, MidiTokenizer.MAX_TIME_VALUE);
		int duration = clamp(attributeValues[2], 0, MidiTokenizer.MAX_TIME_VALUE);
		int octave = clamp(attributeValues[3], 0, MidiTokenizer.MAX_OCTAVE);
		int pitchClass = clamp(attributeValues[4], 0, MidiTokenizer.MAX_PITCH_CLASS);
		int instrument = clamp(attributeValues[5], 0, MidiTokenizer.MAX_INSTRUMENT);
		int velocity = clamp(attributeValues[6], 0, MidiTokenizer.MAX_VELOCITY);

		return new MidiCompoundToken(onset, duration, octave,
				pitchClass, instrument, velocity);
	}

	/**
	 * Clamp a value to the given range [min, max].
	 */
	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
