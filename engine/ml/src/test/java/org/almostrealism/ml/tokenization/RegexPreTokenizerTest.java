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
import org.junit.Test;

import java.util.List;

/**
 * Tests that the default {@link RegexPreTokenizer} splits text the same way as the
 * GPT-2/Qwen reference pre-tokenizer
 * ({@code (?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+}).
 *
 * <p>The reference regex engine treats {@code \s} as Unicode whitespace and matches the
 * contraction alternatives case-insensitively. Any divergence changes the segment
 * boundaries BPE operates within, so the token ids no longer match the ones the model
 * was trained on.</p>
 */
public class RegexPreTokenizerTest extends TestSuiteBase {

	/**
	 * Verifies ordinary ASCII text, contractions and whitespace runs split as in the reference.
	 */
	@Test(timeout = 5000)
	public void asciiTextSplitsLikeReference() {
		List<String> segments = new RegexPreTokenizer().preTokenize("Hello, world!\n\nI'm here  ok");
		assertEquals(List.of("Hello", ",", " world", "!\n\n", "I", "'m", " here", " ", " ok"), segments);
	}

	/**
	 * A no-break space (U+00A0) is whitespace, so it must not be absorbed into the
	 * following punctuation run. French typography places one before {@code !}, {@code ?}
	 * and {@code :}, so this occurs routinely in real text.
	 */
	@Test(timeout = 5000)
	public void noBreakSpaceIsNotPunctuation() {
		List<String> segments = new RegexPreTokenizer().preTokenize("Bonjour !");
		assertEquals(List.of("Bonjour", " ", "!"), segments);
	}

	/**
	 * Unicode whitespace after punctuation must start the next word segment rather
	 * than extend the punctuation segment.
	 */
	@Test(timeout = 5000)
	public void unicodeWhitespaceAfterPunctuationPrefixesNextWord() {
		List<String> segments = new RegexPreTokenizer().preTokenize("x. y");
		assertEquals(List.of("x", ".", " y"), segments);
	}

	/**
	 * A run of ideographic spaces (U+3000) is split so that the last space prefixes the
	 * following word, exactly as a run of ASCII spaces is.
	 */
	@Test(timeout = 5000)
	public void ideographicSpaceRunSplitsLikeAsciiSpaceRun() {
		List<String> segments = new RegexPreTokenizer().preTokenize("a　　b");
		assertEquals(List.of("a", "　", "　b"), segments);
	}

	/**
	 * Contractions are matched case-insensitively, so an upper-case contraction is split
	 * from the letters that follow it just as a lower-case one is.
	 */
	@Test(timeout = 5000)
	public void upperCaseContractionSplitsLikeLowerCase() {
		RegexPreTokenizer preTokenizer = new RegexPreTokenizer();
		assertEquals(List.of("'t", "was"), preTokenizer.preTokenize("'twas"));
		assertEquals(List.of("'T", "WAS"), preTokenizer.preTokenize("'TWAS"));
	}
}
