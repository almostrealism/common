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
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * An {@link AutoregressiveModel} that generates token ids from a {@link CausalLanguageModel} by
 * decoding one position at a time with a per-layer key/value cache.
 *
 * <p>Each step feeds one token's embedding at the current sequence position to the model's
 * {@link CausalLanguageModel#decodeModel single-position decode variant}, which scores that position
 * against the keys and values of every earlier position held in the cache and returns the
 * {@code (vocabSize)} log-probabilities of the next token. The work per token is one position rather
 * than the whole window, which is the point of the cache: the sliding-window generator re-runs a full
 * {@code (seqLen, vocabSize)} forward pass per byte, whereas this generator reuses the cached past.
 * The decoded output is the same — greedy decoding here agrees byte for byte with the full forward,
 * because the cached attention scores the same keys and values and applies the same rotary rotation
 * at each position as the trained full-window path.</p>
 *
 * <p>The per-layer caches are fixed {@code (seqLen, dim)} buffers, so this generator decodes the
 * first {@code seqLen} positions of a sequence; {@link #load(Integer)} refuses a step at or beyond
 * {@code seqLen} rather than index past the cache. Generating more than {@code seqLen} tokens is the
 * {@link SlidingWindowAutoregressiveModel sliding-window generator}'s role.</p>
 *
 * <p>The sequence position is kept on the device, as in the base class: {@link #reset()} returns it
 * to zero and {@link #advance()} adds one, both as compiled operations, so no position value is
 * staged from the host. {@link #reset()} additionally clears every layer's key and value cache, so a
 * new sequence starts clean and cannot read a previous sequence's cached keys and values. The caches
 * are the per-layer buffers {@link CausalLanguageModel#decodeModel} supplies to the attention layers
 * and hands back, which this generator holds and the compiled decode model reads and writes, which is
 * why this dedicated subclass, rather than the {@link AutoregressiveModel#of factory generator}, is
 * needed to decode: the factory owns only its input buffer, position, temperature and sampling, and
 * has no handle with which to clear the caches on reset.</p>
 *
 * <p>Sampling uses {@link AutoregressiveModel#sampleToken}: a temperature of zero (the initial value)
 * selects the most probable token, and a positive temperature samples from the tempered distribution,
 * restricted to the nucleus (top-p) of most probable tokens when {@link #setTopP(double)} has lowered
 * the threshold below its default of {@code 1.0}.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>The generator compiles the decode variant of the model it is given and owns that compilation,
 * its input buffer, the per-layer caches, the position and the temperature; {@link #destroy()}
 * releases all of them. The decode variant is built over the model's own {@link StateDictionary}, so
 * no weights are copied and the weights remain the caller's, usable through another
 * {@link CausalLanguageModel} over the same dictionary — which is also how text is generated from
 * weights that are being trained.</p>
 *
 * @see CausalLanguageModel
 * @see SlidingWindowAutoregressiveModel
 */
public class KeyValueCacheAutoregressiveModel extends AutoregressiveModel<Integer> {
	/** The inference compilation of the single-position decode model, owned by this generator. */
	private final CompiledModel inference;

	/** Number of tokens in the vocabulary. */
	private final int vocabSize;

	/** Model dimension; the length of one token's embedding. */
	private final int dim;

	/**
	 * The number of positions the decode caches hold; decoding a position at or beyond this would
	 * index past each layer's fixed {@code (seqLen, dim)} key and value cache.
	 */
	private final int seqLen;

	/** The source of randomness for positive temperatures; null when only greedy decoding is used. */
	private final Random random;

	/**
	 * The token embedding table {@code (vocabSize, dim)}, not owned by this generator: a row of it is
	 * copied into {@link #input} before each forward pass.
	 */
	private final PackedCollection embedding;

	/** The model input: the current token's {@code (1, dim)} embedding. */
	private final PackedCollection input;

	/** The sampling temperature, created by this generator and released by {@link #destroy()}. */
	private final PackedCollection temperature;

	/** The per-layer key caches of the decode model, cleared by {@link #reset()}. */
	private final List<PackedCollection> keyCaches;

	/** The per-layer value caches of the decode model, cleared by {@link #reset()}. */
	private final List<PackedCollection> valueCaches;

	/**
	 * Creates a generator over a language model, compiling its single-position decode variant.
	 *
	 * @param model  the language model whose weights are decoded; its own layers are not compiled, so
	 *               it (and other models over the same weights) remain usable
	 * @param random the source of randomness for positive temperatures; may be null when only greedy
	 *               decoding is used
	 */
	public KeyValueCacheAutoregressiveModel(CausalLanguageModel model, Random random) {
		this(model, new PackedCollection(1), new PackedCollection(1), random);
	}

	/**
	 * Creates a generator over a language model with the position and temperature it owns.
	 *
	 * @param model       the language model whose weights are decoded
	 * @param position    the position, owned by this generator
	 * @param temperature the temperature, owned by this generator
	 * @param random      the source of randomness for positive temperatures
	 */
	private KeyValueCacheAutoregressiveModel(CausalLanguageModel model, PackedCollection position,
											 PackedCollection temperature, Random random) {
		super(position, temperature);
		this.vocabSize = model.getConfig().vocabSize;
		this.dim = model.getConfig().dim;
		this.seqLen = model.getConfig().seqLen;
		this.random = random;
		this.temperature = temperature;
		this.embedding = model.getWeights().get(CausalLanguageModel.EMBEDDING_KEY);
		this.input = new PackedCollection(new TraversalPolicy(1, dim));
		this.keyCaches = new ArrayList<>();
		this.valueCaches = new ArrayList<>();

		CompiledModel compiled;
		try {
			Model decode = model.decodeModel(position, keyCaches, valueCaches);
			compiled = decode.compile(false);
		} catch (RuntimeException e) {
			destroy();
			throw e;
		}

		this.inference = compiled;
	}

	/**
	 * Releases the compiled position operations, the inference compilation, the input buffer, the
	 * per-layer caches, the position and the temperature; every release is attempted even when an
	 * earlier one fails. The compiled operations and the compiled model are released before the memory
	 * they read. The generator cannot be used afterwards; a repeated call is harmless.
	 */
	@Override
	public void destroy() {
		List<Runnable> releases = new ArrayList<>();
		releases.add(super::destroy);
		releases.add(() -> Destroyable.destroy(inference));
		releases.add(input::destroy);
		keyCaches.forEach(cache -> releases.add(cache::destroy));
		valueCaches.forEach(cache -> releases.add(cache::destroy));
		releases.add(getPosition()::destroy);
		releases.add(temperature::destroy);
		Destroyable.releaseAll(releases);
	}

	/**
	 * Returns to the start of a sequence, clearing every layer's key and value cache so that the next
	 * sequence cannot read this one's cached keys and values.
	 */
	@Override
	public void reset() {
		super.reset();
		keyCaches.forEach(PackedCollection::clear);
		valueCaches.forEach(PackedCollection::clear);
	}

	/**
	 * Copies the given token's embedding into the model input for the current step.
	 *
	 * <p>The decode caches hold {@code seqLen} positions, so this generator can decode positions
	 * {@code 0} through {@code seqLen - 1}; decoding at {@code seqLen} or beyond would index past each
	 * layer's fixed {@code (seqLen, dim)} key and value cache. Such a step is refused here rather than
	 * left to corrupt memory or read a stale cache entry: a caller that needs to generate more than
	 * {@code seqLen} tokens uses the sliding-window generator instead.</p>
	 *
	 * @param id the token id
	 * @throws IllegalStateException if the current step is at or beyond {@code seqLen}
	 */
	@Override
	protected void load(Integer id) {
		if (getCurrentStep() >= seqLen) {
			throw new IllegalStateException("cannot decode position " + getCurrentStep()
					+ "; the key/value cache holds " + seqLen
					+ " positions (use the sliding-window generator for longer sequences)");
		}

		input.setFrom(0, embedding.range(new TraversalPolicy(dim), id * dim), 0, dim);
	}

	/**
	 * Runs the decode model over the current token at the current position and returns the
	 * log-probabilities of the token that follows it.
	 *
	 * @return a {@code (vocabSize)} view of the model output
	 */
	@Override
	protected PackedCollection forward() {
		return inference.forward(input);
	}

	/**
	 * Selects the next token from the log-probabilities of the previous step at the current
	 * temperature and nucleus (top-p) threshold.
	 *
	 * @param logProbabilities the {@code (vocabSize)} log-probabilities
	 * @return the selected token id
	 */
	@Override
	protected Integer sample(PackedCollection logProbabilities) {
		return sampleToken(logProbabilities, vocabSize, getTemperature(), getTopP(), random);
	}
}
