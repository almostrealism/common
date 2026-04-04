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

import org.almostrealism.collect.PackedCollection;

/**
 * Common interface for text tokenizers used in language model inference.
 *
 * <p>Tokenizers convert between human-readable text and sequences of integer token IDs.
 * Implementations handle model-specific vocabulary and encoding rules (e.g., BPE, SentencePiece).
 * </p>
 *
 * @see org.almostrealism.ml.qwen3.Qwen3Tokenizer
 * @see org.almostrealism.ml.tokenization.ByteLevelBPETokenizer
 */
public interface Tokenizer {

	/**
	 * Encodes text into token IDs as a long array.
	 *
	 * @param text the text to encode
	 * @return array of token IDs
	 */
	long[] encodeAsLong(String text);

	/**
	 * Decodes token IDs from a long array back to text.
	 *
	 * @param tokens the token IDs to decode
	 * @return decoded text
	 */
	String decodeAsLong(long[] tokens);

	/**
	 * Encodes text into token IDs as an int array.
	 * <p>
	 * Default implementation converts from {@link #encodeAsLong(String)}.
	 *
	 * @param text the text to encode
	 * @return array of token IDs
	 */
	default int[] encodeAsInt(String text) {
		long[] longTokens = encodeAsLong(text);
		int[] intTokens = new int[longTokens.length];
		for (int i = 0; i < longTokens.length; i++) {
			intTokens[i] = (int) longTokens[i];
		}
		return intTokens;
	}

	/**
	 * Decodes token IDs from an int array back to text.
	 * <p>
	 * Default implementation converts to long array and calls {@link #decodeAsLong(long[])}.
	 *
	 * @param tokens the token IDs to decode
	 * @return decoded text
	 */
	default String decodeAsInt(int[] tokens) {
		long[] longTokens = new long[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			longTokens[i] = tokens[i];
		}
		return decodeAsLong(longTokens);
	}

	/**
	 * Encodes text into a {@link org.almostrealism.collect.PackedCollection} of token IDs.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * Implementations that support GPU-resident token tensors should override this method.
	 * </p>
	 *
	 * @param text the text to encode
	 * @return a packed collection containing the token IDs
	 */
	default PackedCollection encode(String text) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Decodes a {@link org.almostrealism.collect.PackedCollection} of token IDs back to text.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * Implementations that support GPU-resident token tensors should override this method.
	 * </p>
	 *
	 * @param tokens a packed collection containing the token IDs
	 * @return the decoded text
	 */
	default String decode(PackedCollection tokens) {
		throw new UnsupportedOperationException();
	}
}
