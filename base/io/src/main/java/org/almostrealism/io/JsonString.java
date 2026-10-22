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

/**
 * A JSON string literal: a piece of text as it is written into a JSON document.
 *
 * <p>Every hand-built JSON document in the platform — controller API
 * responses, CLI output read by tool servers — writes its string values
 * through this type, so that one rule decides what a JSON string may contain.
 * {@link #toString()} is the complete literal, quotes included, so a value
 * can be appended to a document directly; {@link #escaped()} is the literal
 * without its quotes, for a caller that embeds the text inside a literal it
 * is assembling itself.</p>
 *
 * <pre>{@code
 * "{\"name\":" + new JsonString(name) + "}"
 * }</pre>
 */
public final class JsonString {
	/**
	 * Lowercase hex digits, for the {@code \\u00xx} escapes {@link #escaped()} emits.
	 * Held as a table because escaping runs over whole CI logs, where formatting
	 * each escape separately is measurable.
	 */
	private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

	/** The unescaped text this literal represents; never {@code null}. */
	private final String text;

	/**
	 * Creates the JSON string literal for {@code text}.
	 *
	 * @param text the text the literal represents; {@code null} is treated as
	 *             the empty string
	 */
	public JsonString(String text) {
		this.text = text == null ? "" : text;
	}

	/**
	 * Returns the contents of this literal without its surrounding quotes:
	 * the text with backslash, double-quote, and every C0 control character
	 * escaped.
	 *
	 * <p>The control characters without a short escape are written as
	 * {@code \\u00xx}. JSON forbids them literally, and a strict parser —
	 * Python's {@code json.loads} among them — rejects a document containing
	 * one, so text that carries them has to be escaped rather than passed
	 * through. Terminal output is the case that matters in practice: build and
	 * CI logs are full of {@code ESC} (0x1b) from ANSI colour sequences.</p>
	 *
	 * @return the escaped text, with no surrounding quotes
	 */
	public String escaped() {
		StringBuilder out = new StringBuilder(text.length() + 16);

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			switch (c) {
				case '\\': out.append("\\\\"); break;
				case '"':  out.append("\\\""); break;
				case '\n': out.append("\\n"); break;
				case '\r': out.append("\\r"); break;
				case '\t': out.append("\\t"); break;
				case '\b': out.append("\\b"); break;
				case '\f': out.append("\\f"); break;
				default:
					if (c < 0x20) {
						// Every remaining control character is below 0x20, so the
						// leading two hex digits are always zero.
						out.append("\\u00")
							.append(HEX_DIGITS[(c >> 4) & 0xf])
							.append(HEX_DIGITS[c & 0xf]);
					} else {
						out.append(c);
					}
			}
		}

		return out.toString();
	}

	/**
	 * Returns the complete literal as it appears in a JSON document: the
	 * {@link #escaped()} contents between double quotes.
	 *
	 * @return the quoted, escaped literal
	 */
	@Override
	public String toString() {
		return "\"" + escaped() + "\"";
	}
}
