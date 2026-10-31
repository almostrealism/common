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

package org.almostrealism.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link Gsm7}, the GSM-7 normalization and length helper that keeps
 * an SMS from silently switching to the costlier UCS-2 encoding.
 */
public class Gsm7Test extends TestSuiteBase {

	/** An em-dash and an en-dash both fold to an ASCII hyphen. */
	@Test(timeout = 10000)
	public void testDashesFoldToHyphen() {
		Assert.assertEquals("a-b", Gsm7.normalize("a—b"));
		Assert.assertEquals("a-b", Gsm7.normalize("a–b"));
		Assert.assertEquals("a-b", Gsm7.normalize("a−b"));
	}

	/** Curly single and double quotes fold to their straight ASCII forms. */
	@Test(timeout = 10000)
	public void testCurlyQuotesFoldToStraight() {
		Assert.assertEquals("'x'", Gsm7.normalize("‘x’"));
		Assert.assertEquals("\"x\"", Gsm7.normalize("“x”"));
	}

	/** A Unicode ellipsis becomes three ASCII dots. */
	@Test(timeout = 10000)
	public void testEllipsisFoldsToDots() {
		Assert.assertEquals("wait...", Gsm7.normalize("wait…"));
	}

	/** A non-breaking space becomes an ordinary space. */
	@Test(timeout = 10000)
	public void testNonBreakingSpaceFoldsToSpace() {
		Assert.assertEquals("a b", Gsm7.normalize("a b"));
	}

	/** The backtick, which GSM-7 lacks, folds to an apostrophe. */
	@Test(timeout = 10000)
	public void testBacktickFoldsToApostrophe() {
		Assert.assertEquals("it's", Gsm7.normalize("it`s"));
	}

	/** A character outside GSM-7 with no folding is dropped. */
	@Test(timeout = 10000)
	public void testUnrepresentableCharacterIsDropped() {
		Assert.assertEquals("hi", Gsm7.normalize("h😀i"));
	}

	/** Plain ASCII passes through unchanged. */
	@Test(timeout = 10000)
	public void testPlainAsciiIsUnchanged() {
		String text = "Build failed on master. See logs.";
		Assert.assertEquals(text, Gsm7.normalize(text));
	}

	/** Null normalizes to the empty string rather than throwing. */
	@Test(timeout = 10000)
	public void testNullNormalizesToEmpty() {
		Assert.assertEquals("", Gsm7.normalize(null));
	}

	/** Basic characters each cost one septet; extension characters cost two. */
	@Test(timeout = 10000)
	public void testSeptetLengthCountsExtensionCharsAsTwo() {
		Assert.assertEquals(5, Gsm7.septetLength("hello"));
		Assert.assertEquals(2, Gsm7.septetLength("["));
		// 'a'(1) + '{'(2) + 'b'(1) + '}'(2) = 6
		Assert.assertEquals(6, Gsm7.septetLength("a{b}"));
	}

	/** A dropped character contributes nothing to the septet count. */
	@Test(timeout = 10000)
	public void testSeptetLengthIgnoresUnrepresentable() {
		Assert.assertEquals(2, Gsm7.septetLength("h😀i"));
	}

	/** Representability matches the GSM-7 basic set with the backtick excluded. */
	@Test(timeout = 10000)
	public void testRepresentability() {
		Assert.assertTrue(Gsm7.isRepresentable('A'));
		Assert.assertTrue(Gsm7.isRepresentable(' '));
		Assert.assertTrue(Gsm7.isRepresentable('\n'));
		Assert.assertFalse(Gsm7.isRepresentable('`'));
		Assert.assertFalse(Gsm7.isRepresentable('—'));
	}
}
