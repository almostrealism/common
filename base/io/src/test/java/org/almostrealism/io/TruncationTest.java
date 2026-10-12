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
 * Locks the {@link Truncation#apply(String, int)} contract directly in base/io,
 * independently of the flowtree call sites that consume it: the result never
 * exceeds the limit with the marker included, a limit too small to hold the
 * marker yields a bare prefix, a non-positive limit or {@code null} input
 * yields an empty string, and the marker is appended only when something is
 * actually removed.
 */
public class TruncationTest {

	/** Input no longer than the limit is returned unchanged, marker and all. */
	@Test(timeout = 10000)
	public void inputThatFitsIsUnchanged() {
		Assert.assertEquals("short", Truncation.ELLIPSIS.apply("short", 10));
		Assert.assertEquals("abcde", Truncation.ELLIPSIS.apply("abcde", 5));
		Assert.assertEquals("abcde", Truncation.CUT.apply("abcde", 5));
		Assert.assertEquals("", Truncation.ELLIPSIS.apply("", 5));
	}

	/** A hard cut drops the tail with no marker, exactly at the limit. */
	@Test(timeout = 10000)
	public void cutDropsTailWithNoMarker() {
		Assert.assertEquals("abcde", Truncation.CUT.apply("abcdefgh", 5));
		Assert.assertEquals("a", Truncation.CUT.apply("abcdefgh", 1));
	}

	/** An ellipsis ends shortened text and the result length equals the limit. */
	@Test(timeout = 10000)
	public void ellipsisEndsShortenedTextWithinLimit() {
		Assert.assertEquals("ab...", Truncation.ELLIPSIS.apply("abcdefgh", 5));
		Assert.assertEquals("a...", Truncation.ELLIPSIS.apply("abcdefgh", 4));
		Assert.assertEquals(5, Truncation.ELLIPSIS.apply("abcdefgh", 5).length());
	}

	/**
	 * A limit no larger than the marker is too small to hold it, so the result
	 * is a bare prefix of the input rather than a content-free marker.
	 */
	@Test(timeout = 10000)
	public void limitNoLargerThanMarkerYieldsBarePrefix() {
		Assert.assertEquals("abc", Truncation.ELLIPSIS.apply("abcdefgh", 3));
		Assert.assertEquals("ab", Truncation.ELLIPSIS.apply("abcdefgh", 2));
		Assert.assertEquals("a", Truncation.ELLIPSIS.apply("abcdefgh", 1));
	}

	/** A non-positive limit yields an empty string without throwing. */
	@Test(timeout = 10000)
	public void nonPositiveLimitYieldsEmpty() {
		Assert.assertEquals("", Truncation.CUT.apply("abcdef", 0));
		Assert.assertEquals("", Truncation.ELLIPSIS.apply("abcdef", 0));
		Assert.assertEquals("", Truncation.CUT.apply("abcdef", -1));
		Assert.assertEquals("", Truncation.ELLIPSIS.apply("abcdef", -5));
	}

	/** Null input yields an empty string regardless of the limit or style. */
	@Test(timeout = 10000)
	public void nullInputYieldsEmpty() {
		Assert.assertEquals("", Truncation.CUT.apply(null, 10));
		Assert.assertEquals("", Truncation.ELLIPSIS.apply(null, 10));
		Assert.assertEquals("", Truncation.CUT.apply(null, 0));
		Assert.assertEquals("", new Truncation(" [truncated]").apply(null, 10));
	}

	/** A custom multi-character marker ends shortened text exactly at the limit. */
	@Test(timeout = 10000)
	public void customMarkerEndsShortenedTextExactlyAtLimit() {
		String result = new Truncation(" [truncated]").apply("x".repeat(30), 20);
		Assert.assertEquals("xxxxxxxx [truncated]", result);
		Assert.assertEquals(20, result.length());
	}

	/** A null marker behaves as a hard cut, matching an empty marker. */
	@Test(timeout = 10000)
	public void nullMarkerBehavesAsHardCut() {
		Assert.assertEquals("abcde", new Truncation(null).apply("abcdefgh", 5));
		Assert.assertEquals("", new Truncation(null).apply("abcdef", 0));
		Assert.assertEquals("abc", new Truncation(null).apply("abc", 5));
	}
}
