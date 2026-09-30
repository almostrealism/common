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
