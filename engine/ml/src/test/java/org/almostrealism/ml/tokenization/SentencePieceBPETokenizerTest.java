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

package org.almostrealism.ml.tokenization;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies {@link SentencePieceBPETokenizer} against the token ids the reference
 * implementation produces, using the released T5Gemma tokenizer exported by
 * {@code engine/ml/scripts/export_tokenizer.py}.
 *
 * <p>The expected ids are not a hand-written guess: they are what
 * {@code AutoTokenizer.from_pretrained(t5gemma-b-b-ul2)} returns for
 * {@link #PROMPT}, the same call {@code dump_t5gemma_reference.py} makes to produce the
 * {@code t5_input_ids} that the encoder parity tests consume. A disagreement here means the
 * Java tokenizer would feed the prompt encoder something the reference never saw.</p>
 *
 * <p>Gated on the exported tokenizer ({@code AR_T5GEMMA_TOKENIZER}; system property first,
 * then environment variable). The test logs a skip and returns when it is absent.</p>
 */
public class SentencePieceBPETokenizerTest extends TestSuiteBase {

	/** Candidate locations for the exported tokenizer (first existing wins). */
	private static final String[] TOKENIZER_PATHS = {
			System.getProperty("AR_T5GEMMA_TOKENIZER", System.getenv("AR_T5GEMMA_TOKENIZER")),
			"/workspace/t5gemma-tokenizer.bin"
	};

	/** The prompt the reference dump tokenizes. */
	private static final String PROMPT = "a warm analog synth pad with slow filter sweeps";

	/** What the reference tokenizer returns for {@link #PROMPT}. */
	private static final long[] EXPECTED = {
			235250, 8056, 16335, 72896, 7174, 675, 6080, 7194, 123832
	};

	/**
	 * Merges are applied in priority order, and a word boundary participates in them: the two
	 * single characters merge first, then the result merges with the boundary before it. Decoding
	 * restores the text, spaces included.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void mergesApplyInPriorityOrder() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

		long[] encoded = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.PROMPT);

		log("fixture encoded " + Arrays.toString(encoded));
		assertEquals(SentencePieceTokenizerFixture.EXPECTED.length, encoded.length);

		for (int i = 0; i < encoded.length; i++) {
			assertEquals("token " + i, SentencePieceTokenizerFixture.EXPECTED[i], encoded[i]);
		}

		assertEquals(SentencePieceTokenizerFixture.PROMPT, tokenizer.decodeAsLong(encoded));
	}

	/**
	 * A character the vocabulary does not contain becomes one token per UTF-8 byte, and decoding
	 * folds those tokens back into the character.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void unknownCharacterBecomesByteTokens() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizer();

		long[] encoded = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.UNKNOWN);
		List<Integer> expected = fixture.unknownBytes();

		assertEquals(expected.size(), encoded.length);
		for (int i = 0; i < encoded.length; i++) {
			assertEquals("byte token " + i, (long) expected.get(i), encoded[i]);
		}

		assertEquals(SentencePieceTokenizerFixture.UNKNOWN, tokenizer.decodeAsLong(encoded));
	}

	/**
	 * A byte is folded only from a whole {@code <0xNN>} token, matching the reference ByteFallback
	 * decoder. An ordinary token that merely contains that substring, and two neighbours that only
	 * form it once concatenated, must survive decoding untouched rather than being misread as a byte.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void byteFoldingRespectsTokenBoundaries() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizerFor(new String[] {
				"<0x4", "1>", "x<0x41>y"
		});

		assertEquals("<0x41>", tokenizer.decodeAsLong(new long[] {0, 1}));
		assertEquals("x<0x41>y", tokenizer.decodeAsLong(new long[] {2}));
	}

	/**
	 * An invalid UTF-8 run of byte tokens becomes one replacement character per byte, matching the
	 * reference ByteFallback decoder, rather than collapsing the whole malformed run into one.
	 *
	 * <p>{@code 0xE5} leads a three-byte sequence but only one continuation byte follows, so the run
	 * is invalid UTF-8 and the reference decoder emits one {@code U+FFFD} per byte token: two here.</p>
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void invalidByteRunBecomesOneReplacementPerByte() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizerFor(new String[] {
				"<0xE5>", "<0x8F>"
		});

		assertEquals("��", tokenizer.decodeAsLong(new long[] {0, 1}));
	}

	/**
	 * A valid multi-byte UTF-8 run of byte tokens folds back into the single character it encodes.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void validByteRunFoldsIntoOneCharacter() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizerFor(new String[] {
				"<0xE2>", "<0x82>", "<0xAC>"
		});

		assertEquals("€", tokenizer.decodeAsLong(new long[] {0, 1, 2}));
	}

	/**
	 * A character that falls back to a byte token the vocabulary does not contain is rejected during
	 * encoding when the tokenizer has no unknown token, rather than being emitted as id {@code -1}.
	 *
	 * <p>Without the fix the inherited {@code encode} substitutes {@code getUNKToken() == -1} for the
	 * missing byte token, returning a negative id that is not a valid embedding index and fails only
	 * later, during generation. Here the vocabulary lacks the {@code <0xNN>} tokens for
	 * {@link SentencePieceTokenizerFixture#UNKNOWN} and there is no unknown token, so encoding throws.</p>
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void unencodableByteWithoutUnknownTokenIsRejected() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizerFor(new String[] {"a", "▁"});

		try {
			tokenizer.encodeAsLong(SentencePieceTokenizerFixture.UNKNOWN);
			Assert.fail("a byte with no vocabulary token and no unknown token was encoded");
		} catch (IllegalArgumentException expected) {
			assertTrue("message names the missing byte token: " + expected.getMessage(),
					expected.getMessage().contains("<0xE2>"));
		}
	}

	/**
	 * When a character falls back to a byte token the vocabulary does not contain but the tokenizer
	 * has an unknown token, encoding substitutes that unknown token -- a valid id -- for each missing
	 * byte, rather than throwing or emitting {@code -1}.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(timeout = 120000)
	public void unencodableByteFallsBackToUnknownToken() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();
		SentencePieceBPETokenizer tokenizer = fixture.tokenizerFor(
				new String[] {"a", "<unk>"}, new int[] {-1, -1, -1, 1});

		long[] encoded = tokenizer.encodeAsLong(SentencePieceTokenizerFixture.UNKNOWN);

		assertEquals(SentencePieceTokenizerFixture.UNKNOWN.getBytes(
				StandardCharsets.UTF_8).length, encoded.length);
		for (int i = 0; i < encoded.length; i++) {
			assertEquals("byte " + i + " substituted by the unknown token", 1L, encoded[i]);
		}
	}

	/**
	 * A file that is not an exported tokenizer is rejected rather than read as one.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeout = 120000)
	public void fileThatIsNotATokenizerIsRejected() throws IOException {
		File wrong = new SentencePieceTokenizerFixture().writeWithHeader(0x00000000, 1);

		try {
			new SentencePieceBPETokenizer(wrong.getPath());
			Assert.fail("a file without the tokenizer magic was read as a tokenizer");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("not an exported tokenizer"));
		}
	}

	/**
	 * A tokenizer written by a later version of the exporter is rejected, naming the version, rather
	 * than being misread field by field.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeout = 120000)
	public void unsupportedVersionIsRejected() throws IOException {
		File future = new SentencePieceTokenizerFixture().writeWithHeader(0x4152544B, 2);

		try {
			new SentencePieceBPETokenizer(future.getPath());
			Assert.fail("a tokenizer of an unsupported version was read");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("version 2"));
		}
	}

	/**
	 * A corrupt serialized count -- a file with the correct magic but a negative or implausibly large
	 * vocabulary size -- is rejected with a controlled {@link IOException} rather than allocating a
	 * negative array or an enormous one. {@code -1} exercises the lower bound and
	 * {@link Integer#MAX_VALUE} the upper bound.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeout = 120000)
	public void corruptVocabularyCountIsRejected() throws IOException {
		SentencePieceTokenizerFixture fixture = new SentencePieceTokenizerFixture();

		for (int count : new int[] {-1, Integer.MAX_VALUE}) {
			File corrupt = fixture.writeWithVocabCount(count);

			try {
				new SentencePieceBPETokenizer(corrupt.getPath());
				Assert.fail("a corrupt vocabulary count " + count + " was read as a tokenizer");
			} catch (IOException expected) {
				assertTrue("message names the field: " + expected.getMessage(),
						expected.getMessage().contains("vocabulary size"));
			}
		}
	}

	/**
	 * The first existing candidate tokenizer, or {@code null} when none is present.
	 *
	 * @return the exported tokenizer, or {@code null}
	 */
	protected File tokenizerFile() {
		for (String path : TOKENIZER_PATHS) {
			if (path == null) continue;

			File file = new File(path);
			if (file.isFile()) return file;
		}

		return null;
	}

	/**
	 * The released prompt is encoded exactly as the reference implementation encodes it,
	 * and decoding those ids returns the prompt.
	 *
	 * @throws IOException if the exported tokenizer cannot be read
	 */
	@Test(timeout = 600000)
	public void releasedPromptMatchesTheReference() throws IOException {
		File exported = tokenizerFile();

		if (exported == null) {
			log("skipping tokenizer parity; exported tokenizer absent (tried "
					+ Arrays.toString(TOKENIZER_PATHS) + ")");
			return;
		}

		SentencePieceBPETokenizer tokenizer = new SentencePieceBPETokenizer(exported.getPath());
		long[] encoded = tokenizer.encodeAsLong(PROMPT);

		log("encoded " + Arrays.toString(encoded));
		assertEquals(EXPECTED.length, encoded.length);

		for (int i = 0; i < EXPECTED.length; i++) {
			assertEquals("token " + i, EXPECTED[i], encoded[i]);
		}

		assertEquals(PROMPT, tokenizer.decodeAsLong(encoded));
	}

	/**
	 * A character outside the vocabulary becomes one byte token per UTF-8 byte, and decoding
	 * folds those tokens back into the original character.
	 *
	 * @throws IOException if the exported tokenizer cannot be read
	 */
	@Test(timeout = 600000)
	public void unknownCharactersSurviveAsByteTokens() throws IOException {
		File exported = tokenizerFile();

		if (exported == null) {
			log("skipping byte-fallback check; exported tokenizer absent");
			return;
		}

		SentencePieceBPETokenizer tokenizer = new SentencePieceBPETokenizer(exported.getPath());

		String text = "warm 🎹 pad";
		long[] encoded = tokenizer.encodeAsLong(text);

		log("byte fallback produced " + encoded.length + " tokens for " + text);
		assertTrue("nothing was encoded", encoded.length > 0);
		assertEquals(text, tokenizer.decodeAsLong(encoded));
	}
}
