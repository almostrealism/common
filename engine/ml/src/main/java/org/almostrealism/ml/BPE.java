/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml;

/**
 * Byte Pair Encoding (BPE) tokenization utilities.
 *
 * <p>Provides a minimal BPE encoder used by Llama2-style models.
 * The algorithm greedily merges the highest-scoring consecutive token pair
 * until no merge can improve the encoding score.</p>
 *
 * @see org.almostrealism.ml.llama2.Llama2
 */
public class BPE {
	/**
	 * Find the first match for text in vocab,
	 * return that index (or -1 if not found).
	 */
	public static int lookup(String text, String[] vocab, int vocabSize) {
		for (int i = 0; i < vocabSize; i++) {
			if (text.equals(vocab[i])) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Encodes the given text into BPE token IDs.
	 * <p>
	 * First maps each character to its vocabulary ID, then iteratively merges
	 * the highest-scoring consecutive pair until no improvement is possible.
	 * </p>
	 *
	 * @param text       the input text to encode
	 * @param vocab      the vocabulary array (index = token ID)
	 * @param vocabScores merge scores for each vocabulary token
	 * @param vocabSize  the number of entries in the vocabulary
	 * @param tokens     output array to fill with encoded token IDs; must be large enough
	 * @return the number of tokens produced
	 */
	public static int encode(String text, String[] vocab, float[] vocabScores, int vocabSize, int[] tokens) {
		int tokenCount = 0;

		for (int i = 0; i < text.length(); ++i) {
			char c = text.charAt(i);
			String singleChar = String.valueOf(c);

			int id = lookup(singleChar, vocab, vocabSize);
			if (id == -1) throw new RuntimeException();

			tokens[tokenCount] = id;
			tokenCount++;
		}

		// merge the best consecutive pair each iteration
		w: while (true) {
			float bestScore = -1e10f;
			int bestId = -1;
			int bestIndex = -1;

			for (int i = 0; i < tokenCount - 1; i++) {
				String buf = vocab[tokens[i]] + vocab[tokens[i + 1]];

				int id = lookup(buf, vocab, vocabSize);
				if (id != -1 && vocabScores[id] > bestScore) {
					bestScore = vocabScores[id];
					bestId = id;
					bestIndex = i;
				}
			}

			if (bestIndex == -1) {
				break w;
			}

			tokens[bestIndex] = bestId;

			for (int i = bestIndex + 1; i < tokenCount - 1; i++) {
				tokens[i] = tokens[i + 1];
			}

			tokenCount--;
		}

		return tokenCount;
	}
}
