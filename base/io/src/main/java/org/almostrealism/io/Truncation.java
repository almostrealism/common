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
 * A style of shortening text to a length limit: the marker, if any, that
 * replaces the removed tail so a reader can tell the text was cut.
 *
 * <p>The result of {@link #apply(String, int)} never exceeds the limit, the
 * marker included. A limit too small to hold the marker yields a bare prefix
 * of the input, and a non-positive limit yields an empty string, so a caller
 * never has to guard the limit it passes.</p>
 *
 * <pre>{@code
 * Truncation.ELLIPSIS.apply("abcdefgh", 5);   // "ab..."
 * Truncation.CUT.apply("abcdefgh", 5);        // "abcde"
 * new Truncation(" [truncated]").apply(output, 4000);
 * }</pre>
 */
// TODO(review): add a base/io unit test for Truncation; it is currently tested only via flowtree call sites
public class Truncation {
	/** Cuts the text at the limit with no marker. */
	public static final Truncation CUT = new Truncation("");

	/** Ends shortened text with {@code "..."}. */
	public static final Truncation ELLIPSIS = new Truncation("...");

	/** The text that replaces the removed tail of a shortened string. */
	private final String marker;

	/**
	 * Creates a truncation style that ends shortened text with {@code marker}.
	 *
	 * @param marker the text appended in place of the removed tail; empty for a hard cut
	 */
	public Truncation(String marker) {
		this.marker = marker;
	}

	/**
	 * Shortens {@code text} to at most {@code maxLength} characters, ending it
	 * with this style's marker when anything is removed.
	 *
	 * @param text      the text to shorten, or {@code null}
	 * @param maxLength the maximum length of the result, marker included
	 * @return {@code text} unchanged when it fits, the shortened text otherwise,
	 *         or an empty string when {@code text} is {@code null} or the limit
	 *         is not positive
	 */
	public String apply(String text, int maxLength) {
		if (text == null || maxLength <= 0) return "";
		if (text.length() <= maxLength) return text;
		if (maxLength <= marker.length()) return text.substring(0, maxLength);
		return text.substring(0, maxLength - marker.length()) + marker;
	}
}
