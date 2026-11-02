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

import java.util.Random;

/**
 * A key/value-cache generator whose caches roll, so that it decodes sequences longer than its
 * caches at the cost of one position per token.
 *
 * <p>It decodes through the same {@link CausalLanguageModel#decodeModel single-position decode
 * model} as the {@link KeyValueCacheAutoregressiveModel bounded generator} it extends, and owns the
 * same compilation, input buffer, per-layer {@code (seqLen, dim)} caches, position and temperature,
 * which {@link #reset()} clears and {@link #destroy()} releases exactly as the bounded generator's
 * do. The difference is that it does not stop at {@code seqLen}: each cache is written as a ring, at
 * row {@code position mod seqLen}, so once the sequence outgrows the caches each new token's key and
 * value replace those of the token {@code seqLen} positions before it, and every block attends to the
 * most recent {@code seqLen} positions. Within the first {@code seqLen} positions it is therefore
 * identical to the bounded generator, and so to the full-window forward pass.</p>
 *
 * <p>No cached key is ever re-rotated. Each key keeps the rotation of the absolute position it was
 * written at, and the query is rotated at its own absolute position, computed from the model's
 * inverse frequencies rather than read from a table of {@code seqLen} rows. Rotary attention scores
 * depend only on the distance between the query and key positions, so the scores against a key
 * rotated once at position {@code n} are the scores the
 * {@link SlidingWindowAutoregressiveModel sliding-window generator} computes after re-placing the
 * window at positions {@code 0..seqLen-1}: the distances are the same.</p>
 *
 * <p>For a model of a single block this makes the two generators decode the same tokens: that
 * block's keys and values depend only on each token and its position, so the ring holds exactly the
 * keys and values the sliding window recomputes. In a deeper model they differ by design. The
 * sliding window re-encodes its window from scratch on every step, so the deeper blocks' keys and
 * values of the oldest tokens it keeps are computed without the tokens that have left it; this
 * generator keeps the deeper keys and values each token was given when it was decoded, which saw
 * the {@code seqLen} positions before it. Each block attends to a window of {@code seqLen} positions
 * (the sliding-window attention of Mistral-style models), and a block's output reaches back
 * {@code seqLen - 1} positions further than the block below it.</p>
 *
 * <p>The length a sequence can reach is bounded by the precision of the device, not by the caches.
 * The position is a device-resident number that {@link #advance()} increments, and the ring row and
 * the rotary angle are computed from it in the kernel, so all three are exact only while the
 * position is an integer the device's floating-point type represents exactly: below {@code 2^24}
 * at single precision, the precision of the Metal backend. Well before that the rotary angles lose
 * accuracy too, since {@code position * invFreq} is rounded to the precision of the product, so the
 * rotation of a key very far into a sequence departs from the rotation the sliding window would
 * apply to it at its window-relative position.</p>
 *
 * @see KeyValueCacheAutoregressiveModel
 * @see SlidingWindowAutoregressiveModel
 */
public class SlidingKeyValueCacheAutoregressiveModel extends KeyValueCacheAutoregressiveModel {
	/**
	 * Creates a generator over a language model, compiling its single-position decode variant.
	 *
	 * @param model  the language model whose weights are decoded; its own layers are not compiled, so
	 *               it (and other models over the same weights) remain usable
	 * @param random the source of randomness for positive temperatures; may be null when only greedy
	 *               decoding is used
	 */
	public SlidingKeyValueCacheAutoregressiveModel(CausalLanguageModel model, Random random) {
		super(model, random);
	}

	/**
	 * Decodes every position: past {@code seqLen} the decode writes each token's key and value over
	 * the oldest cached ones.
	 *
	 * @param position the sequence position
	 * @return true
	 */
	@Override
	protected boolean decodes(int position) {
		return true;
	}
}
