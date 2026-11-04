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

import org.almostrealism.ml.qwen3.Qwen3Tokenizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Drift guard for the text-tokenization internals page ({@code docs/internals/text-tokenization.md}).
 * Every complete Java excerpt on that page is copied from a method of this class, and each method
 * asserts the behavioral claim the page makes about its example: the {@link ByteTokenizer}
 * byte-level bijection and its id range, the {@link SentencePieceBPETokenizer} merge/boundary
 * encoding, its per-byte fallback for characters outside the vocabulary, the fact that
 * {@code encodeAsLong} adds no special tokens, and the {@link Qwen3Tokenizer} encode/decode API
 * with its public BOS/EOS constants.
 *
 * <p>Every artifact used here is synthetic: {@link ByteTokenizer} needs no vocabulary,
 * {@link SentencePieceTokenizerFixture} writes a tiny ARTK binary, and
 * {@link Qwen3Tokenizer#createTestTokenizer()} builds an in-memory vocabulary. Nothing is gated on
 * an environment probe and no downloaded model file is required, so the page's examples are proven
 * on any backend.</p>
 */
public class TextTokenizationExamplesTest extends TestSuiteBase {

	/**
	 * Page section "ByteTokenizer": text is encoded as its UTF-8 bytes, one token per byte, every
	 * id in {@code 0..255}, and the string round-trips.
	 */
	@Test(timeout = 10000)
	public void byteTokenizerRoundTrip() {
		ByteTokenizer tokenizer = new ByteTokenizer();

		String text = "café";
		long[] ids = tokenizer.encodeAsLong(text);

		// "café" is five UTF-8 bytes: 'c', 'a', 'f', and two for 'é'.
		Assert.assertEquals(5, ids.length);
		for (long id : ids) {
			Assert.assertTrue(id >= 0 && id < ByteTokenizer.VOCAB_SIZE);
		}
		Assert.assertEquals(text, tokenizer.decodeAsLong(ids));
	}

	/**
	 * Page section "The BPE pipeline": merges apply in priority order and a word boundary
	 * participates in them. In the fixture vocabulary the two single characters merge, then the
	 * result merges with the boundary that precedes it, and decoding restores the spaces.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 30000)
	public void sentencePieceMergesAndBoundary() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

		long[] ids = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.PROMPT);

		Assert.assertArrayEquals(SentencePieceTokenizerFixture.EXPECTED, ids);
		Assert.assertEquals(SentencePieceTokenizerFixture.PROMPT, tokenizer.decodeAsLong(ids));
	}

	/**
	 * Page section "The BPE pipeline": a character the vocabulary does not contain becomes one
	 * {@code <0xNN>} token per UTF-8 byte, and decoding folds those tokens back into the character.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 30000)
	public void sentencePieceByteFallback() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

		long[] ids = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.UNKNOWN);

		// '€' (U+20AC) is three UTF-8 bytes, so it falls back to three byte tokens.
		int byteCount = SentencePieceTokenizerFixture.UNKNOWN.getBytes(StandardCharsets.UTF_8).length;
		Assert.assertEquals(byteCount, ids.length);
		Assert.assertEquals(SentencePieceTokenizerFixture.UNKNOWN, tokenizer.decodeAsLong(ids));
	}

	/**
	 * Page section "The exporter and the ARTK format": {@code encodeAsLong} adds no special tokens,
	 * while {@code encode(text, true)} brackets the same ids with BOS and EOS. A caller that needs
	 * them adds them itself.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 30000)
	public void sentencePieceAddsNoSpecialTokens() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

		long[] plain = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.PROMPT);
		int[] bracketed = tokenizer.encode(SentencePieceTokenizerFixture.PROMPT, true);

		Assert.assertArrayEquals(SentencePieceTokenizerFixture.EXPECTED, plain);
		Assert.assertEquals(plain.length + 2, bracketed.length);
		Assert.assertEquals(SentencePieceTokenizerFixture.BOS, bracketed[0]);
		Assert.assertEquals(SentencePieceTokenizerFixture.EOS, bracketed[bracketed.length - 1]);
	}

	/**
	 * Page section "The family tree": {@link Qwen3Tokenizer} tokenizes through its own
	 * {@code encode(String, boolean, boolean)} / {@code decode(int[])} API rather than the
	 * {@link org.almostrealism.ml.Tokenizer} interface. {@code addBos}/{@code addEos} bracket the
	 * same content with the public BOS/EOS constants, and the control tokens are skipped on decode.
	 */
	@Test(timeout = 10000)
	public void qwen3SpecialTokenControl() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		int[] plain = tokenizer.encode("Hello World!", false, false);
		int[] bracketed = tokenizer.encode("Hello World!", true, true);

		Assert.assertEquals(plain.length + 2, bracketed.length);
		Assert.assertEquals(Qwen3Tokenizer.BOS_TOKEN, bracketed[0]);
		Assert.assertEquals(Qwen3Tokenizer.EOS_TOKEN, bracketed[bracketed.length - 1]);
		Assert.assertEquals(tokenizer.decode(plain), tokenizer.decode(bracketed));
	}
}
