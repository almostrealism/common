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
import org.junit.Test;

import java.util.Optional;

/**
 * Locks the value contract of {@link SystemUtils#isEnabled(String)}.
 *
 * <p>These tests exist because a JVM flag was once set to {@code =true} on the
 * assumption that boolean values are accepted. They are not: {@code isEnabled}
 * accepts only {@code "enabled"}/{@code "disabled"} (case-insensitive) and
 * <em>throws</em> on anything else — including {@code "true"}/{@code "false"} —
 * so a boolean value crashes at class-init of whatever static field reads it
 * (e.g. {@code MixdownManager.enablePdslMixdown} reading {@code AR_PDSL_MIXDOWN}).
 * If anyone loosens this parser, these tests should fail and force a deliberate
 * decision rather than a silent behavior change.</p>
 */
public class SystemUtilsIsEnabledTest {
	/** A disposable property key used only by these tests. */
	private static final String KEY = "AR_TEST_ISENABLED_CONTRACT";

	/** Clears the properties touched by each test so cases never bleed together. */
	@After
	public void clearProperty() {
		System.clearProperty(KEY);
		System.clearProperty("AR_PDSL_MIXDOWN");
	}

	/** {@code "enabled"} resolves to {@code Optional.of(true)}. */
	@Test
	public void enabledResolvesToTrue() {
		System.setProperty(KEY, "enabled");
		Assert.assertEquals(Optional.of(true), SystemUtils.isEnabled(KEY));
	}

	/** {@code "disabled"} resolves to {@code Optional.of(false)}. */
	@Test
	public void disabledResolvesToFalse() {
		System.setProperty(KEY, "disabled");
		Assert.assertEquals(Optional.of(false), SystemUtils.isEnabled(KEY));
	}

	/** The accepted tokens are matched case-insensitively. */
	@Test
	public void valueIsCaseInsensitive() {
		System.setProperty(KEY, "ENABLED");
		Assert.assertEquals(Optional.of(true), SystemUtils.isEnabled(KEY));
	}

	/** An unset key resolves to {@code Optional.empty()} (the caller's default applies). */
	@Test
	public void unsetResolvesToEmpty() {
		System.clearProperty(KEY);
		Assert.assertEquals(Optional.empty(), SystemUtils.isEnabled(KEY));
	}

	/** {@code "true"} is rejected — the trap that shipped a broken flag. */
	@Test(expected = IllegalArgumentException.class)
	public void trueIsRejected() {
		System.setProperty(KEY, "true");
		SystemUtils.isEnabled(KEY);
	}

	/** {@code "false"} is rejected for the same reason as {@code "true"}. */
	@Test(expected = IllegalArgumentException.class)
	public void falseIsRejected() {
		System.setProperty(KEY, "false");
		SystemUtils.isEnabled(KEY);
	}

	/** Any value other than enabled/disabled is rejected, not silently ignored. */
	@Test(expected = IllegalArgumentException.class)
	public void arbitraryValueIsRejected() {
		System.setProperty(KEY, "on");
		SystemUtils.isEnabled(KEY);
	}

	/**
	 * The exact production round-trip: the value shipped in
	 * {@code AudioUnit/Rings/Info.plist} ({@code -DAR_PDSL_MIXDOWN=enabled})
	 * must resolve to {@code true} the way {@code MixdownManager} reads it.
	 */
	@Test
	public void pdslMixdownEnabledRoundTrips() {
		System.setProperty("AR_PDSL_MIXDOWN", "enabled");
		Assert.assertEquals(Optional.of(true), SystemUtils.isEnabled("AR_PDSL_MIXDOWN"));
	}
}
