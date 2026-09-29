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

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Locks the contract of {@link SystemUtils#getNonEmptyProperty(String, String)}:
 * an empty value falls back to the default, where
 * {@link SystemUtils#getProperty(String, String)} returns it unchanged.
 *
 * <p>Service URLs are read this way because a deployment that declares
 * {@code AR_MEMORY_URL=} means "use the default", and a blank URL is never a
 * usable configuration.</p>
 */
public class SystemUtilsNonEmptyPropertyTest {
	/** A disposable property key used only by these tests. */
	private static final String KEY = "AR_TEST_NON_EMPTY_PROPERTY";

	/** Original value of {@link #KEY}, captured before each test for restore. */
	private String original;

	/** Captures the property these tests mutate so it can be restored. */
	@Before
	public void captureProperty() {
		original = System.getProperty(KEY);
	}

	/** Restores the property to its value before the test. */
	@After
	public void restoreProperty() {
		if (original == null) {
			System.clearProperty(KEY);
		} else {
			System.setProperty(KEY, original);
		}
	}

	/** A set value is returned as-is. */
	@Test(timeout = 30000)
	public void setValueIsReturned() {
		System.setProperty(KEY, "http://example:1");
		Assert.assertEquals("http://example:1", SystemUtils.getNonEmptyProperty(KEY, "fallback"));
	}

	/** A missing value falls back to the default. */
	@Test(timeout = 30000)
	public void missingValueUsesDefault() {
		System.clearProperty(KEY);
		Assert.assertEquals("fallback", SystemUtils.getNonEmptyProperty(KEY, "fallback"));
	}

	/**
	 * An empty value falls back to the default, which is exactly where this
	 * method differs from {@link SystemUtils#getProperty(String, String)}.
	 */
	@Test(timeout = 30000)
	public void emptyValueUsesDefault() {
		System.setProperty(KEY, "");
		Assert.assertEquals("fallback", SystemUtils.getNonEmptyProperty(KEY, "fallback"));
		Assert.assertEquals("", SystemUtils.getProperty(KEY, "fallback"));
	}

	/** Whitespace is a value, not an empty one, and is not trimmed away. */
	@Test(timeout = 30000)
	public void whitespaceIsNotEmpty() {
		System.setProperty(KEY, " ");
		Assert.assertEquals(" ", SystemUtils.getNonEmptyProperty(KEY, "fallback"));
	}
}
