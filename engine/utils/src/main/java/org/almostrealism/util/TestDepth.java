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
 * Specifies the minimum test depth required to run a test method.
 * Tests annotated with this will be skipped if the current test depth
 * (from {@code AR_TEST_DEPTH} environment variable) is less than the
 * specified value.
 *
 * <p>Each depth tier should contain a <b>representative mix</b> of test
 * types and durations — fast unit tests alongside longer integration tests —
 * so that even a low-depth run exercises the full range of functionality.
 * Depth is not a proxy for test duration; it controls how many tests run
 * at each level of thoroughness.</p>
 *
 * <p>Target time budgets per tier:</p>
 * <ul>
 *   <li><b>0 (no annotation)</b>: ~20 minutes total — core coverage across
 *       all modules, including some long-running tests</li>
 *   <li><b>1</b>: ~60 minutes total — broader coverage, still a mix of
 *       fast and slow tests</li>
 *   <li><b>2</b>: ~2 hours total — comprehensive coverage</li>
 *   <li><b>3+</b>: Full suite — everything runs (~4+ hours)</li>
 * </ul>
 *
 * <p><b>Important:</b> Do not assign depth based solely on timeout duration.
 * A 2-minute test that validates a critical code path belongs at depth 0.
 * A 5-second test that duplicates coverage already present at depth 0
 * belongs at depth 1 or higher. The goal is that each tier provides
 * meaningful confidence independently.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyTest extends TestSuiteBase {
 *     @Test(timeout = 30000)
 *     @TestDepth(2)
 *     public void additionalCoverageTest() {
 *         // Only runs if AR_TEST_DEPTH >= 2
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see TestDepthRule
 * @see TestSuiteBase
 * @see TestSettings#testDepth
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TestDepth {
	/**
	 * The minimum test depth required to run this test.
	 *
	 * @return the required depth level (default 0)
	 */
	int value() default 0;
}
