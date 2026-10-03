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

package org.almostrealism.ml.tokenization;

import org.almostrealism.ml.Tokenizer;

import java.nio.charset.StandardCharsets;

/**
 * A {@link Tokenizer} over a fixed vocabulary of the 256 byte values. Text is encoded as its
 * UTF-8 bytes and every byte becomes one token whose id is the unsigned byte value
 * ({@code 0..255}). No vocabulary file, merges or training are needed, so a model built on this
 * tokenizer owes nothing to an external artifact.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Byte level.</b> The mapping between a byte and its id is a bijection over all 256
 *       values; byte {@code b} maps to id {@code b & 0xFF} and back, with no sign extension.</li>
 *   <li><b>Encoding.</b> {@link #encodeAsLong(String)} uses standard UTF-8 encoding, including its
 *       replacement behaviour: an unpaired surrogate in the input becomes {@code '?'} ({@code 0x3F}).
 *       The {@code String} round-trip {@code decode(encode(s)).equals(s)} therefore holds for every
 *       well-formed UTF-16 string, and only for those.</li>
 *   <li><b>Decoding.</b> {@link #decodeAsLong(long[])} decodes the bytes as UTF-8. A sequence that is
 *       not valid UTF-8 decodes with standard replacement: one {@code U+FFFD} per malformed input
 *       sequence reported by the decoder, which may span several bytes. Decoding through a
 *       {@code String} is therefore not byte-lossless for malformed sequences; byte-level
 *       losslessness is a property of the byte/id mapping, not of the {@code String} API.</li>
 *   <li><b>Bounds.</b> Every id passed to a decode method must be in {@code 0..VOCAB_SIZE-1};
 *       any other id is rejected with an {@link IllegalArgumentException} rather than narrowed onto
 *       a valid byte.</li>
 * </ul>
 */
public class ByteTokenizer implements Tokenizer {
	/** Number of distinct tokens: one per byte value. */
	public static final int VOCAB_SIZE = 256;

	/**
	 * Returns the size of this tokenizer's vocabulary.
	 *
	 * @return {@link #VOCAB_SIZE}
	 */
	public int getVocabSize() {
		return VOCAB_SIZE;
	}

	/**
	 * Encodes text as the unsigned values of its UTF-8 bytes.
	 *
	 * @param text the text to encode
	 * @return one token id in {@code 0..255} per UTF-8 byte
	 */
	@Override
	public long[] encodeAsLong(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		long[] tokens = new long[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			tokens[i] = toId(bytes[i]);
		}

		return tokens;
	}

	/**
	 * Decodes token ids as UTF-8 bytes.
	 *
	 * @param tokens token ids, each in {@code 0..255}
	 * @return the decoded text
	 * @throws IllegalArgumentException if any id is outside {@code 0..255}
	 */
	@Override
	public String decodeAsLong(long[] tokens) {
		byte[] bytes = new byte[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			bytes[i] = toByte(tokens[i]);
		}

		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Maps a byte to its token id, the unsigned byte value.
	 *
	 * @param b the byte
	 * @return the id in {@code 0..255}
	 */
	int toId(byte b) {
		return b & 0xFF;
	}

	/**
	 * Maps a token id back to its byte, rejecting ids outside the vocabulary.
	 *
	 * @param id the token id
	 * @return the byte whose unsigned value is {@code id}
	 * @throws IllegalArgumentException if {@code id} is outside {@code 0..255}
	 */
	byte toByte(long id) {
		if (id < 0 || id >= VOCAB_SIZE) {
			throw new IllegalArgumentException("Token id " + id + " is outside the byte vocabulary 0.." +
					(VOCAB_SIZE - 1));
		}

		return (byte) id;
	}
}
