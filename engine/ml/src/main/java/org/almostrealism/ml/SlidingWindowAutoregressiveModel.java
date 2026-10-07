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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;

import java.util.Random;

/**
 * An {@link AutoregressiveModel} that generates token ids from a {@link CausalLanguageModel}
 * by decoding with a sliding window over the model's full {@code (seqLen)} forward pass.
 *
 * <p>Each step runs one forward pass over the most recent tokens of the sequence and samples
 * the next token from the log-probabilities at the last filled position. Positions after the
 * filled prefix hold padding, which the causal mask keeps from affecting the filled rows; once
 * the sequence is longer than {@code seqLen} the window slides, so every forward pass sees its
 * tokens at positions {@code 0..seqLen-1}, exactly as every training window did. The forward
 * pass is therefore the trained one, at the cost of a full window per token; a single-position
 * decode with a key/value cache would avoid that cost.</p>
 *
 * <p>The window is kept in device memory: each token reaches the device as a single value, and
 * the window slides by device-to-device copies, so no window contents are staged on the host.
 * The position this class maintains is not read by the model.</p>
 *
 * <p>Sampling uses {@link AutoregressiveModel#sampleToken}: a temperature of zero (the initial
 * value) selects the most probable token, and a positive temperature samples from the tempered
 * distribution.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>The generator compiles the model it is given for inference and owns that compilation,
 * its window buffers, position and temperature; {@link #destroy()} releases all of them. The
 * model is consumed: a {@link org.almostrealism.model.Model} is compiled once, so it must not
 * have been compiled elsewhere, and once the compilation is released the model must not be
 * compiled or run again. Its weights remain the caller's, and stay usable through another
 * {@link CausalLanguageModel} over the same {@link StateDictionary}, which is also how text is
 * generated from weights that are being trained.</p>
 *
 * @see CausalLanguageModel
 */
public class SlidingWindowAutoregressiveModel extends AutoregressiveModel<Integer> {
	/** The inference compilation of the model, owned by this generator. */
	private final CompiledModel inference;

	/** Number of tokens in the vocabulary. */
	private final int vocabSize;

	/** Number of positions of the window. */
	private final int seqLen;

	/** The source of randomness for positive temperatures; null when only greedy decoding is used. */
	private final Random random;

	/**
	 * The model input: the most recent tokens of the sequence, oldest first. Positions after the
	 * filled prefix hold padding, which is zeroed when a sequence starts so that every position is
	 * a valid token id.
	 */
	private final PackedCollection input;

	/** Scratch space for sliding {@link #input} by one position without an overlapping copy. */
	private final PackedCollection scratch;

	/** The token being appended. */
	private final PackedCollection token;

	/** Number of filled positions of {@link #input}. */
	private int filled;

	/**
	 * Creates a generator over a language model, compiling the model for inference.
	 *
	 * @param model  the language model, consumed by this generator; it must not have been
	 *               compiled elsewhere
	 * @param random the source of randomness for positive temperatures; may be null when only
	 *               greedy decoding is used
	 */
	public SlidingWindowAutoregressiveModel(CausalLanguageModel model, Random random) {
		this(model, new PackedCollection(1), new PackedCollection(1), random);
	}

	/**
	 * Creates a generator over a language model with the position and temperature it owns.
	 *
	 * @param model       the language model
	 * @param position    the position, owned by this generator
	 * @param temperature the temperature, owned by this generator
	 * @param random      the source of randomness for positive temperatures
	 */
	private SlidingWindowAutoregressiveModel(CausalLanguageModel model, PackedCollection position,
											 PackedCollection temperature, Random random) {
		super(position, temperature);
		this.vocabSize = model.getConfig().vocabSize;
		this.seqLen = model.getConfig().seqLen;
		this.random = random;
		this.input = new PackedCollection(new TraversalPolicy(seqLen));
		this.scratch = new PackedCollection(new TraversalPolicy(seqLen));
		this.token = new PackedCollection(1);
		own(input, scratch, token, position, temperature);

		CompiledModel compiled;
		try {
			compiled = model.compile(false);
		} catch (RuntimeException e) {
			destroy();
			throw e;
		}

		this.inference = compiled;
		own(inference);
		input.clear();
	}

	/**
	 * Returns to the start of a sequence and clears the window.
	 */
	@Override
	public void reset() {
		super.reset();
		input.clear();
		filled = 0;
	}

	/**
	 * Appends a token to the window, sliding it by one position when it is full.
	 *
	 * @param id the token id
	 */
	@Override
	protected void load(Integer id) {
		if (filled == seqLen) {
			scratch.setFrom(0, input, 1, seqLen - 1);
			input.setFrom(0, scratch, 0, seqLen - 1);
			filled--;
		}

		int value = id;
		token.fill((double) value);
		input.setFrom(filled++, token, 0, 1);
	}

	/**
	 * Runs the model over the window and returns the log-probabilities of the token following
	 * the last filled position.
	 *
	 * @return a {@code (vocabSize)} view of the model output
	 */
	@Override
	protected PackedCollection forward() {
		return inference.forward(input).range(new TraversalPolicy(vocabSize), (filled - 1) * vocabSize);
	}

	/**
	 * Selects the next token from the log-probabilities of the previous step at the current
	 * temperature.
	 *
	 * @param logProbabilities the {@code (vocabSize)} log-probabilities
	 * @return the selected token id
	 */
	@Override
	protected Integer sample(PackedCollection logProbabilities) {
		return sampleToken(logProbabilities, vocabSize, getTemperature(), 1.0, random);
	}
}
