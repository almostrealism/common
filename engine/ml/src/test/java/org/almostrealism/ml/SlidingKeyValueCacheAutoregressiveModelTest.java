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
import org.almostrealism.ml.tokenization.ByteTokenizer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Pins the {@link SlidingKeyValueCacheAutoregressiveModel rolling key/value-cache generator}, which
 * decodes past the {@code seqLen} positions its caches hold by writing them as rings.
 *
 * <p>The oracle is the {@link SlidingWindowAutoregressiveModel sliding-window generator}, which
 * {@code CausalLanguageModelTest.slidingWindowGenerationMatchesFullForward} verifies against the full
 * forward pass. For a single-block model the two must decode identical tokens however long the
 * sequence runs, because rotary scores depend only on relative positions and that block's keys and
 * values depend only on each token and its position. For a deeper model the rolling cache keeps the
 * deeper keys and values each token was decoded with, so each block attends to its own window of
 * {@code seqLen} positions; that is pinned by an eviction test instead, which requires the output to
 * stop depending on a token exactly when every block's window has moved past it.</p>
 */
public class SlidingKeyValueCacheAutoregressiveModelTest extends TestSuiteBase {
	/** Byte vocabulary of the models under test. */
	private static final int VOCAB = ByteTokenizer.VOCAB_SIZE;

	/** Rotary base frequency of the models under test. */
	private static final double ROPE_BASE = 10000.0;

	/** Seed of the random source both generators sample from at a positive temperature. */
	private static final long SAMPLING_SEED = 11L;

	/** Positive temperature at which the two generators' sampled continuations are compared. */
	private static final double SAMPLING_TEMPERATURE = 0.9;

	/**
	 * Over a single-block model with random weights, the rolling generator decodes exactly the tokens
	 * the sliding-window generator decodes, for three times the context length — so the rings wrap
	 * twice and the rotation runs to positions no {@code seqLen}-row table covers — and greedy
	 * decoding is reproducible after {@link AutoregressiveModel#reset()}. Greedy decoding compares only
	 * the most probable token, and over random weights it soon repeats a short loop, so the two
	 * generators are also compared sampling at a positive temperature from the same seed, which agrees
	 * token for token only if their whole distributions agree closely at every position.
	 */
	@Test(timeout = 15 * 60000)
	public void slidingKeyValueCacheMatchesSlidingWindowPastSeqLen() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Producer");
			int generated = 3 * seq;
			int[] window;
			int[] windowSampled;
			try (AutoregressiveModel<Integer> generator = new SlidingWindowAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), new Random(SAMPLING_SEED))) {
				window = CausalLanguageModelTest.generate(generator, prompt, generated);
				generator.setTemperature(SAMPLING_TEMPERATURE);
				windowSampled = CausalLanguageModelTest.generate(generator, prompt, generated);
			}

			int[] first;
			int[] second;
			int[] rollingSampled;
			try (AutoregressiveModel<Integer> generator = new SlidingKeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), new Random(SAMPLING_SEED))) {
				first = CausalLanguageModelTest.generate(generator, prompt, generated);
				Assert.assertEquals(prompt.length + generated, generator.getCurrentStep());
				second = CausalLanguageModelTest.generate(generator, prompt, generated);
				generator.setTemperature(SAMPLING_TEMPERATURE);
				rollingSampled = CausalLanguageModelTest.generate(generator, prompt, generated);
			}

			log("window=" + new ByteTokenizer().decodeAsInt(window)
					+ " rolling=" + new ByteTokenizer().decodeAsInt(first));
			Assert.assertArrayEquals("greedy decoding is not reproducible", first, second);
			Assert.assertArrayEquals("the rolling cache and the sliding window disagree", window, first);
			Assert.assertArrayEquals("the rolling cache and the sliding window sample differently",
					windowSampled, rollingSampled);
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * Over a two-block model, the decoded output at a position depends on a token exactly while some
	 * block's window still reaches it. The first block attends to the last {@code seqLen} positions
	 * and the second to the first block's outputs over the last {@code seqLen} positions, so the
	 * output at position {@code p} reads tokens {@code p - 2 (seqLen - 1)} through {@code p}. Two
	 * sequences that differ only in their first token therefore decode different rows at position
	 * {@code seqLen}, after the rings have wrapped but while that token is still within reach, and
	 * bit-identical rows at position {@code 2 seqLen - 1}, where it is not. The second half fails if
	 * the caches do not evict the oldest position, and the first if the deeper block discards what
	 * it cached.
	 */
	@Test(timeout = 15 * 60000)
	public void slidingKeyValueCacheEvictsTokensBeyondEveryWindow() {
		int seq = 6;
		int dim = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, dim, 2, 2, 8, ROPE_BASE, new Random(7));
		PackedCollection position = new PackedCollection(1);
		PackedCollection input = new PackedCollection(new TraversalPolicy(1, dim));
		List<PackedCollection> keyCaches = new ArrayList<>();
		List<PackedCollection> valueCaches = new ArrayList<>();
		CompiledModel decode = null;

		try {
			decode = lm.decodeModel(position, keyCaches, valueCaches).compile(false);
			int[] tokens = IntStream.range(0, 2 * seq).map(i -> 40 + 7 * i).toArray();
			int[] altered = tokens.clone();
			altered[0] = 200;

			List<double[]> rows = decodeRows(decode, position, input, keyCaches, valueCaches,
					lm.getWeights().get(CausalLanguageModel.EMBEDDING_KEY), tokens, dim);
			List<double[]> alteredRows = decodeRows(decode, position, input, keyCaches, valueCaches,
					lm.getWeights().get(CausalLanguageModel.EMBEDDING_KEY), altered, dim);

			double[] row = rows.get(seq);
			double[] alteredRow = alteredRows.get(seq);
			double reachable = IntStream.range(0, VOCAB)
					.mapToDouble(i -> Math.abs(row[i] - alteredRow[i])).max().orElse(0.0);
			log("maxDifference at position " + seq + " = " + reachable);
			Assert.assertTrue("position " + seq + " must still read the first token through the second block",
					reachable > 1e-4);
			Assert.assertArrayEquals("position " + (2 * seq - 1) + " must no longer read the first token",
					rows.get(2 * seq - 1), alteredRows.get(2 * seq - 1), 0.0);
		} finally {
			Destroyable.destroy(decode);
			position.destroy();
			input.destroy();
			keyCaches.forEach(PackedCollection::destroy);
			valueCaches.forEach(PackedCollection::destroy);
			lm.getWeights().destroy();
		}
	}

	/**
	 * {@link AutoregressiveModel#reset()} clears the rolling caches, so a sequence decoded after one
	 * that wrapped the rings is byte-identical to the same sequence decoded by a fresh generator.
	 */
	@Test(timeout = 15 * 60000)
	public void slidingKeyValueCacheResetClearsCacheAcrossSequences() {
		int seq = 6;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 2, 8, ROPE_BASE, new Random(5));

		try {
			int[] promptA = new ByteTokenizer().encodeAsInt("Longer prompt");
			int[] promptB = new ByteTokenizer().encodeAsInt("Pro");
			int generated = 2 * seq;
			int[] reusedB;
			int[] freshB;
			try (AutoregressiveModel<Integer> generator = new SlidingKeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				CausalLanguageModelTest.generate(generator, promptA, generated);
				reusedB = CausalLanguageModelTest.generate(generator, promptB, generated);
			}
			try (AutoregressiveModel<Integer> fresh = new SlidingKeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				freshB = CausalLanguageModelTest.generate(fresh, promptB, generated);
			}

			Assert.assertArrayEquals("reset must clear the rolling cache so a previous sequence does not leak",
					freshB, reusedB);
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * Destroying a rolling-cache generator releases its position and leaves the weights of its model
	 * usable; a second destroy is harmless, and a generator over the same weights reproduces the tokens
	 * decoded past {@code seqLen}.
	 */
	@Test(timeout = 5 * 60000)
	public void destroyingSlidingKeyValueCacheGeneratorReleasesItsBuffers() {
		int seq = 4;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pro");
			AutoregressiveModel<Integer> generator = new SlidingKeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null);
			int[] before = CausalLanguageModelTest.generate(generator, prompt, 2 * seq);
			PackedCollection position = generator.getPosition();
			Assert.assertFalse(position.isDestroyed());

			generator.destroy();
			Assert.assertTrue("generator position was not released", position.isDestroyed());
			Assert.assertFalse("model weights were released",
					lm.getWeights().get(CausalLanguageModel.EMBEDDING_KEY).isDestroyed());
			generator.destroy();

			try (AutoregressiveModel<Integer> next = new SlidingKeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				Assert.assertArrayEquals(before, CausalLanguageModelTest.generate(next, prompt, 2 * seq));
			}
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * Decodes a sequence of tokens through a compiled decode model from cleared caches, driving the
	 * position by hand, and returns the output row of every position.
	 *
	 * @param decode      the compiled decode model
	 * @param position    the device-resident position the decode model reads
	 * @param input       the model input buffer, {@code (1, dim)}
	 * @param keyCaches   the per-layer key caches, cleared first
	 * @param valueCaches the per-layer value caches, cleared first
	 * @param embedding   the token embedding table
	 * @param tokens      the token ids to feed at positions {@code 0, 1, ...}
	 * @param dim         the model dimension
	 * @return the {@code (vocabSize)} output row of each position, in order
	 */
	private List<double[]> decodeRows(CompiledModel decode, PackedCollection position, PackedCollection input,
									  List<PackedCollection> keyCaches, List<PackedCollection> valueCaches,
									  PackedCollection embedding, int[] tokens, int dim) {
		keyCaches.forEach(PackedCollection::clear);
		valueCaches.forEach(PackedCollection::clear);

		List<double[]> rows = new ArrayList<>();
		for (int s = 0; s < tokens.length; s++) {
			position.fill((double) s);
			input.setFrom(0, embedding.range(new TraversalPolicy(dim), tokens[s] * dim), 0, dim);
			rows.add(decode.forward(input).toArray(0, VOCAB));
		}

		return rows;
	}
}
