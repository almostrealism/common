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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link ByteTokenizer}: the byte-level bijection over all 256 values, the
 * {@code String} round-trip on well-formed text, the documented UTF-8 replacement behaviour for
 * lone surrogates and malformed byte sequences, and the rejection of out-of-range ids.
 */
public class ByteTokenizerTest extends TestSuiteBase {

	/** Every byte value maps to its unsigned id and back, with no sign extension. */
	@Test(timeout = 10000)
	public void allBytesRoundTrip() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		for (int value = 0; value < ByteTokenizer.VOCAB_SIZE; value++) {
			byte b = (byte) value;
			int id = tokenizer.toId(b);
			Assert.assertEquals("byte " + value, value, id);
			Assert.assertEquals("id " + id, b, tokenizer.toByte(id));
		}
	}

	/** ASCII text round-trips and encodes to its byte values. */
	@Test(timeout = 10000)
	public void asciiRoundTrip() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		String text = "Java is orchestration, not execution.";
		int[] ids = tokenizer.encodeAsInt(text);
		Assert.assertEquals(text.length(), ids.length);
		Assert.assertEquals('J', ids[0]);
		Assert.assertEquals(text, tokenizer.decodeAsInt(ids));
	}

	/** Multi-byte UTF-8 text, including a supplementary character, round-trips. */
	@Test(timeout = 10000)
	public void multiByteRoundTrip() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		String text = "café € 😀 中";
		long[] ids = tokenizer.encodeAsLong(text);

		byte[] expected = text.getBytes(StandardCharsets.UTF_8);
		Assert.assertEquals(expected.length, ids.length);
		for (int i = 0; i < ids.length; i++) {
			Assert.assertEquals(expected[i] & 0xFF, ids[i]);
			Assert.assertTrue(ids[i] >= 0 && ids[i] < 256);
		}

		Assert.assertEquals(text, tokenizer.decodeAsLong(ids));
	}

	/** The empty string encodes to no tokens and decodes back to the empty string. */
	@Test(timeout = 10000)
	public void emptyRoundTrip() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		Assert.assertEquals(0, tokenizer.encodeAsLong("").length);
		Assert.assertEquals("", tokenizer.decodeAsLong(new long[0]));
	}

	/** A lone surrogate is replaced by {@code '?'} during UTF-8 encoding, as documented. */
	@Test(timeout = 10000)
	public void loneSurrogateReplaced() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		long[] ids = tokenizer.encodeAsLong("a\uD800b");
		Assert.assertArrayEquals(new long[] { 'a', 0x3F, 'b' }, ids);
		Assert.assertEquals("a?b", tokenizer.decodeAsLong(ids));
	}

	/** Malformed UTF-8 decodes with one replacement character per malformed sequence. */
	@Test(timeout = 10000)
	public void malformedDecode() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		Assert.assertEquals("�A", tokenizer.decodeAsLong(new long[] { 0xE2, 0x82, 'A' }));
		Assert.assertEquals("x�y", tokenizer.decodeAsLong(new long[] { 'x', 0xFF, 'y' }));
	}

	/** Ids outside {@code 0..255} are rejected rather than narrowed onto a valid byte. */
	@Test(timeout = 10000)
	public void outOfRangeIdsRejected() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		assertRejected(() -> tokenizer.toByte(256));
		assertRejected(() -> tokenizer.toByte(-1));
		assertRejected(() -> tokenizer.decodeAsLong(new long[] { 'a', 256 }));
		assertRejected(() -> tokenizer.decodeAsInt(new int[] { -1 }));
	}

	/**
	 * Asserts that the given action throws {@link IllegalArgumentException}.
	 *
	 * @param action the action expected to fail
	 */
	private void assertRejected(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			log("rejected: " + expected.getMessage());
		}
	}
}
