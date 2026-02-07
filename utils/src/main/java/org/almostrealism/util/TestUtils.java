/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.util;

import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.io.Console;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.util.Objects;

/**
 * Utility class for parsing test configuration from environment variables and system properties.
 *
 * <p>This class provides static methods that read test configuration from the following
 * environment variables:</p>
 * <ul>
 *   <li>{@code AR_LONG_TESTS} - Enable long-running tests (boolean)</li>
 *   <li>{@code AR_TRAIN_TESTS} - Enable ML training tests (boolean)</li>
 *   <li>{@code AR_TEST_DEPTH} - Test depth/thoroughness level (integer)</li>
 *   <li>{@code AR_TEST_PROFILE} - Test profile name (string, e.g., "pipeline")</li>
 *   <li>{@code AR_KNOWN_ISSUES} - Include tests for known issues (boolean)</li>
 * </ul>
 *
 * <h2>Static Initialization</h2>
 * <p>When this class loads, it:</p>
 * <ol>
 *   <li>Configures RAM warning settings based on test configuration</li>
 *   <li>Creates the {@code results/} directory if it doesn't exist</li>
 *   <li>Sets up file logging to {@code results/logs/test.out}</li>
 * </ol>
 *
 * <h2>Pipeline Profile</h2>
 * <p>When {@code AR_TEST_PROFILE=pipeline}, the test configuration optimizes for CI/CD:</p>
 * <ul>
 *   <li>Long tests are NOT skipped</li>
 *   <li>Known issues ARE skipped</li>
 *   <li>Training tests are disabled</li>
 *   <li>Verbose logs are disabled</li>
 *   <li>Test depth is set to maximum</li>
 * </ul>
 *
 * @author Michael Murray
 * @see TestSettings for the configuration interface
 */
public class TestUtils implements TestSettings {

	/**
	 * The "pipeline" profile name, used for CI/CD configurations.
	 */
	public static final String PIPELINE = "pipeline";

	static {
		if (RAM.enableWarnings)
			RAM.enableWarnings = !skipLongTests;

		File results = new File("results");
		if (!results.exists()) {
			results.mkdir();
		}

		Console.root().addListener(OutputFeatures.fileOutput("results/logs/test.out"));
	}

	/**
	 * Determines whether long-running tests should be skipped.
	 *
	 * <p>Returns false (don't skip) when:</p>
	 * <ul>
	 *   <li>Profile is "pipeline"</li>
	 *   <li>{@code AR_LONG_TESTS=true} environment variable is set</li>
	 *   <li>Test depth is greater than 10</li>
	 * </ul>
	 *
	 * @return true if long tests should be skipped, false otherwise
	 */
	public static boolean getSkipLongTests() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return !SystemUtils.isEnabled("AR_LONG_TESTS").orElse(getTestDepth() > 10);
	}

	/**
	 * Determines whether tests for known issues should be skipped.
	 *
	 * <p>Returns true (skip) when:</p>
	 * <ul>
	 *   <li>Profile is "pipeline"</li>
	 *   <li>Long tests are being skipped</li>
	 *   <li>{@code AR_KNOWN_ISSUES} is not set or false</li>
	 * </ul>
	 *
	 * @return true if known issue tests should be skipped, false otherwise
	 */
	public static boolean getSkipKnownIssues() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return true;
		if (getSkipLongTests()) return true;

		return !SystemUtils.isEnabled("AR_KNOWN_ISSUES").orElse(true);
	}

	/**
	 * Determines whether verbose logging is enabled during tests.
	 *
	 * <p>Returns true when:</p>
	 * <ul>
	 *   <li>Profile is NOT "pipeline"</li>
	 *   <li>Long tests are NOT being skipped</li>
	 *   <li>Known issues are NOT being skipped</li>
	 * </ul>
	 *
	 * @return true if verbose logging should be enabled, false otherwise
	 */
	public static boolean getVerboseLogs() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return false;

		return !getSkipLongTests() && !getSkipKnownIssues();
	}

	/**
	 * Returns the test depth level controlling test thoroughness.
	 *
	 * <p>Higher values enable more comprehensive testing. Read from the
	 * {@code AR_TEST_DEPTH} environment variable.</p>
	 *
	 * <p>Default value is 9 if the environment variable is not set.
	 * Returns {@link Integer#MAX_VALUE} if test profile is "pipeline".</p>
	 *
	 * @return the test depth level
	 */
	public static int getTestDepth() {
		if (Objects.equals(getTestProfile(), PIPELINE)) return Integer.MAX_VALUE;

		String depth = SystemUtils.getProperty("AR_TEST_DEPTH");
		if (depth == null) return 9;
		return Integer.parseInt(depth);
	}

	/**
	 * Returns the current test profile name.
	 *
	 * <p>Common profiles:</p>
	 * <ul>
	 *   <li>{@code "default"} - Standard test configuration</li>
	 *   <li>{@code "pipeline"} - CI/CD optimized configuration</li>
	 * </ul>
	 *
	 * @return the test profile name from {@code AR_TEST_PROFILE}, or "default" if not set
	 */
	public static String getTestProfile() { return SystemUtils.getProperty("AR_TEST_PROFILE", "default"); }

	/**
	 * Determines whether comparison tests requiring external model weights should run.
	 *
	 * <p>Returns false when profile is "pipeline", since CI/CD environments typically
	 * don't have access to large model weight files. Tests can use this with
	 * {@code Assume.assumeTrue(TestUtils.isComparisonTestEnabled())} to skip.</p>
	 *
	 * @return true if comparison tests should run, false if profile is "pipeline"
	 */
	public static boolean isComparisonTestEnabled() {
		return !Objects.equals(getTestProfile(), PIPELINE);
	}

	/**
	 * Returns the test group this runner should execute, or null if all groups should run.
	 *
	 * <p>When running tests in parallel across multiple VMs, each VM is assigned a group
	 * number (0 to {@link #getTestGroupCount()}-1). Tests are deterministically assigned
	 * to groups based on a hash of their class name.</p>
	 *
	 * @return the group number (0-based) from {@code AR_TEST_GROUP}, or null if not set
	 */
	public static Integer getTestGroup() {
		String group = SystemUtils.getProperty("AR_TEST_GROUP");
		if (group == null) return null;
		return Integer.parseInt(group);
	}

	/**
	 * Returns the total number of test groups for parallel execution.
	 *
	 * <p>This is used with {@link #getTestGroup()} to split tests across multiple VMs.
	 * Tests are assigned to groups using: {@code hash(className) % groupCount}</p>
	 *
	 * @return the total number of groups from {@code AR_TEST_GROUPS}, or 4 if not set
	 */
	public static int getTestGroupCount() {
		String count = SystemUtils.getProperty("AR_TEST_GROUPS");
		if (count == null) return 4;
		return Integer.parseInt(count);
	}

	/**
	 * Determines which group a test class belongs to based on its name.
	 *
	 * <p>Uses a hash of the class name to deterministically assign classes to groups.
	 * This ensures the same class always runs in the same group across different runs.</p>
	 *
	 * @param className the fully qualified class name
	 * @return the group number (0 to {@link #getTestGroupCount()}-1)
	 */
	public static int getGroupForClass(String className) {
		return Math.abs(className.hashCode()) % getTestGroupCount();
	}

	/**
	 * Checks if a test class should run in the current test group.
	 *
	 * <p>If no group is specified ({@link #getTestGroup()} returns null), all tests run.
	 * Otherwise, only tests whose class hashes to the current group will run.</p>
	 *
	 * @param className the fully qualified class name
	 * @return true if the test should run, false if it should be skipped
	 */
	public static boolean shouldRunInCurrentGroup(String className) {
		Integer targetGroup = getTestGroup();
		if (targetGroup == null) return true;
		return getGroupForClass(className) == targetGroup;
	}
}
