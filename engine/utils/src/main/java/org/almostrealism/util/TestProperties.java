/*
 * Copyright 2026 Michael Murray
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares test properties that control whether a test method is skipped
 * based on the current test configuration.
 *
 * <p>This annotation replaces the inline {@code if (skipLongTests) return;}
 * pattern with a declarative mechanism enforced by {@link TestDepthRule}.
 * When a method is annotated with {@code @TestProperties}, the rule checks
 * the corresponding {@link TestSettings} flags and skips the test via
 * {@code Assume.assumeTrue()} if the flag indicates the test should not run.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Test
 * @TestProperties(knownIssue = true)
 * public void testForBug123() {
 *     // Skipped when AR_KNOWN_ISSUES is not enabled
 * }
 *
 * @Test
 * @TestProperties(longRunning = true, highMemory = true)
 * public void heavyIntegrationTest() {
 *     // Skipped when long tests or high memory tests are disabled
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see TestDepthRule
 * @see TestSettings
 * @see TestSuiteBase
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TestProperties {
	/**
	 * Marks a test as long-running (30+ minutes). When {@link TestSettings#skipLongTests}
	 * is true, the test will be skipped.
	 *
	 * @return true if this test is long-running
	 */
	boolean longRunning() default false;

	/**
	 * Marks a test as requiring high memory. When {@link TestSettings#skipHighMemTests}
	 * is true, the test will be skipped.
	 *
	 * @return true if this test requires high memory
	 */
	boolean highMemory() default false;

	/**
	 * Marks a test as covering a known issue. When {@link TestSettings#skipKnownIssues}
	 * is true, the test will be skipped.
	 *
	 * @return true if this test covers a known issue
	 */
	boolean knownIssue() default false;
}
