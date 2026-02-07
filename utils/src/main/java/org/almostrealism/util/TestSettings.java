/*
 * Copyright 2024 Michael Murray
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

import org.almostrealism.io.SystemUtils;

import java.util.Objects;

/**
 * Global test configuration interface that provides access to test settings
 * controlled by environment variables.
 *
 * <p>This interface exposes static configuration fields that are initialized
 * at class loading time from system environment variables and properties.
 * Tests implementing this interface gain access to these configuration values.</p>
 *
 * <h2>Environment Variables</h2>
 * <table>
 * <caption>Test Configuration Environment Variables</caption>
 * <tr><th>Variable</th><th>Description</th><th>Default</th></tr>
 * <tr><td>{@code AR_LONG_TESTS}</td><td>Enable long-running tests</td><td>false</td></tr>
 * <tr><td>{@code AR_TRAIN_TESTS}</td><td>Enable ML training tests</td><td>false</td></tr>
 * <tr><td>{@code AR_TEST_DEPTH}</td><td>Test thoroughness level (higher = more tests)</td><td>Integer.MAX_VALUE</td></tr>
 * <tr><td>{@code AR_TEST_PROFILE}</td><td>Test profile name (e.g., "pipeline")</td><td>"default"</td></tr>
 * <tr><td>{@code AR_KNOWN_ISSUES}</td><td>Include tests for known issues</td><td>true</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyTest implements TestFeatures {
 *     @Test
 *     public void longRunningTest() {
 *         if (skipLongTests) return;  // Skip in quick test runs
 *         // ... long test code ...
 *     }
 *
 *     @Test
 *     public void trainingTest() {
 *         if (!trainingTests) return;  // Only run when explicitly enabled
 *         // ... training code ...
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see TestUtils for environment variable parsing logic
 * @see TestFeatures for the primary testing interface
 */
public interface TestSettings {

	/**
	 * If true, long-running tests should be skipped.
	 * Controlled by the {@code AR_LONG_TESTS} environment variable.
	 */
	boolean skipLongTests = TestUtils.getSkipLongTests();

	/**
	 * If true, tests for known issues should be skipped.
	 * Controlled by the {@code AR_KNOWN_ISSUES} environment variable.
	 */
	boolean skipKnownIssues = TestUtils.getSkipKnownIssues();

	int trainingEpochs = SystemUtils.getInt("AR_TRAINING_EPOCHS").orElse(2);

	/**
	 * If true, verbose logging is enabled during test execution.
	 */
	boolean verboseLogs = TestUtils.getVerboseLogs();

	/**
	 * The test depth level controlling test thoroughness.
	 * Higher values enable more comprehensive testing.
	 * Controlled by the {@code AR_TEST_DEPTH} environment variable.
	 */
	int testDepth = TestUtils.getTestDepth();

	/**
	 * Checks if the current test profile matches the specified profile name.
	 *
	 * @param profile the profile name to check (e.g., "pipeline")
	 * @return true if the current profile matches the specified profile
	 */
	default boolean testProfileIs(String profile) {
		return (Objects.equals(TestUtils.getTestProfile(), profile));
	}
}
