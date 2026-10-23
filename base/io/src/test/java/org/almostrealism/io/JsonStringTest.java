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

package org.almostrealism.io;

import org.junit.Assert;
import org.junit.Test;

/**
 * Locks the {@link JsonString} contract directly in base/io,
 * independently of the flowtree and tools JSON writers that consume it:
 * backslash and quote are escaped, the five control characters with a short
 * JSON escape use it, every other C0 control character is written as a
 * lowercase {@code \\u00xx} escape, everything else passes through,
 * {@code null} becomes an empty string, and the literal form adds the quotes.
 */
public class JsonStringTest {

	/** Backslash and double quote are escaped; ordinary text is unchanged. */
	@Test(timeout = 10000)
	public void escapesBackslashAndQuote() {
		Assert.assertEquals("plain text", escape("plain text"));
		Assert.assertEquals("a\\\\b", escape("a\\b"));
		Assert.assertEquals("say \\\"hi\\\"", escape("say \"hi\""));
	}

	/** Newline, carriage return, tab, backspace and form feed use their short escapes. */
	@Test(timeout = 10000)
	public void controlCharactersWithShortEscapesUseThem() {
		Assert.assertEquals("\\n\\r\\t\\b\\f", escape("\n\r\t\b\f"));
	}

	/** Every other C0 control character is written as a lowercase unicode escape. */
	@Test(timeout = 10000)
	public void remainingControlCharactersUseLowercaseUnicodeEscapes() {
		Assert.assertEquals("\\u0000\\u001b[31m\\u001f", escape("\u0000\u001b[31m\u001f"));
	}

	/** Characters at or above 0x20, including DEL and non-ASCII text, pass through unchanged. */
	@Test(timeout = 10000)
	public void nonControlCharactersPassThrough() {
		Assert.assertEquals(" /\u007fé中", escape(" /\u007fé中"));
	}

	/** A {@code null} input yields an empty string. */
	@Test(timeout = 10000)
	public void nullYieldsEmptyString() {
		Assert.assertEquals("", escape(null));
	}

	/** The literal form is the escaped contents between double quotes. */
	@Test(timeout = 10000)
	public void literalIsQuotedEscapedContents() {
		Assert.assertEquals("\"say \\\"hi\\\"\\n\"", new JsonString("say \"hi\"\n").toString());
		Assert.assertEquals("\"\"", new JsonString(null).toString());
	}

	/**
	 * Returns the escaped contents of the literal for {@code text}.
	 *
	 * @param text the text to escape, or {@code null}
	 * @return the literal's contents without quotes
	 */
	private static String escape(String text) {
		return new JsonString(text).escaped();
	}
}
