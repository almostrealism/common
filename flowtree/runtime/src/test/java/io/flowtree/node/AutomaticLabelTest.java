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

package io.flowtree.node;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the capability-label detection and application logic of
 * {@link AutomaticLabel}: how {@code os.name} maps to a platform value, how a host
 * name is normalized to its short form, and the rule that detection never overwrites
 * a configured label.
 *
 * <p>The {@code os.name} system property is manipulated and restored around the
 * platform tests so that the {@code mac}/{@code linux} branch is exercised
 * deterministically regardless of the host the suite runs on.</p>
 *
 * @author Michael Murray
 */
public class AutomaticLabelTest extends TestSuiteBase {

	/** Each constant advertises the fixed label key it assigns. */
	@Test(timeout = 10000)
	public void keysAreStable() {
		Assert.assertEquals("platform", AutomaticLabel.PLATFORM.key());
		Assert.assertEquals("hostname", AutomaticLabel.HOSTNAME.key());
	}

	/** A macOS {@code os.name} is detected as the {@code macos} platform value. */
	@Test(timeout = 10000)
	public void detectsMacPlatform() {
		String original = System.getProperty("os.name");
		try {
			System.setProperty("os.name", "Mac OS X");
			Assert.assertEquals("macos", AutomaticLabel.PLATFORM.detect());
		} finally {
			restoreOsName(original);
		}
	}

	/** Any non-mac {@code os.name} is detected as the {@code linux} platform value. */
	@Test(timeout = 10000)
	public void detectsLinuxPlatform() {
		String original = System.getProperty("os.name");
		try {
			System.setProperty("os.name", "Linux");
			Assert.assertEquals("linux", AutomaticLabel.PLATFORM.detect());
		} finally {
			restoreOsName(original);
		}
	}

	/** The platform label applies its default normalization: the value is unchanged. */
	@Test(timeout = 10000)
	public void platformNormalizeIsIdentity() {
		Assert.assertEquals("anything", AutomaticLabel.PLATFORM.normalize("anything"));
	}

	/** A fully-qualified host name is reduced to its short, lower-case form. */
	@Test(timeout = 10000)
	public void hostnameNormalizeStripsDomainAndLowerCases() {
		Assert.assertEquals("mac-studio", AutomaticLabel.HOSTNAME.normalize("Mac-Studio.local"));
		Assert.assertEquals("mac-studio", AutomaticLabel.HOSTNAME.normalize("mac-studio.example.ts.net"));
		Assert.assertEquals("halo", AutomaticLabel.HOSTNAME.normalize("  HALO  "));
	}

	/** Names that identify no particular machine normalize to {@code null}. */
	@Test(timeout = 10000)
	public void hostnameNormalizeRejectsNonSpecificNames() {
		Assert.assertNull(AutomaticLabel.HOSTNAME.normalize(null));
		Assert.assertNull(AutomaticLabel.HOSTNAME.normalize(""));
		Assert.assertNull(AutomaticLabel.HOSTNAME.normalize("localhost"));
		Assert.assertNull("a bare domain dot leaves an empty label",
				AutomaticLabel.HOSTNAME.normalize(".example.com"));
	}

	/** {@link AutomaticLabel#describeMachine()} always reports the detectable platform. */
	@Test(timeout = 10000)
	public void describeMachineReportsPlatform() {
		String original = System.getProperty("os.name");
		try {
			System.setProperty("os.name", "Linux");
			String description = AutomaticLabel.describeMachine();
			Assert.assertTrue("platform must be reported as key=value: " + description,
					description.contains("platform=linux"));
		} finally {
			restoreOsName(original);
		}
	}

	/** Applying a label to a node assigns the detected value when none is configured. */
	@Test(timeout = 10000)
	public void applyToAssignsDetectedValue() {
		String original = System.getProperty("os.name");
		Node node = new Node(null, 0, 4, 4);
		try {
			System.setProperty("os.name", "Linux");
			Assert.assertNull("the node starts with no platform label", node.getLabels().get("platform"));

			AutomaticLabel.PLATFORM.applyTo(node);

			Assert.assertEquals("linux", node.getLabels().get("platform"));
		} finally {
			restoreOsName(original);
		}
	}

	/** Detection never overwrites a value that was configured explicitly. */
	@Test(timeout = 10000)
	public void applyToPreservesConfiguredValue() {
		String original = System.getProperty("os.name");
		Node node = new Node(null, 0, 4, 4);
		try {
			System.setProperty("os.name", "Linux");
			node.setLabel("platform", "custom");

			AutomaticLabel.PLATFORM.applyTo(node);

			Assert.assertEquals("the configured value survives detection",
					"custom", node.getLabels().get("platform"));
		} finally {
			restoreOsName(original);
		}
	}

	/**
	 * Restores (or clears) the {@code os.name} system property after a test that
	 * mutated it.
	 *
	 * @param original the value captured before the test, or {@code null} if unset
	 */
	private static void restoreOsName(String original) {
		if (original == null) {
			System.clearProperty("os.name");
		} else {
			System.setProperty("os.name", original);
		}
	}
}
