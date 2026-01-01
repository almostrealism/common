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
 * <p>Test depth tiers:</p>
 * <ul>
 *   <li><b>0</b>: Basic smoke tests (always run)</li>
 *   <li><b>1+</b>: Medium complexity tests</li>
 *   <li><b>2+</b>: Comprehensive tests</li>
 *   <li><b>3+</b>: Heavy/expensive tests</li>
 *   <li><b>10+</b>: Very expensive tests</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyTest extends AlmostRealismTest {
 *     @Test(timeout = 30000)
 *     @TestDepth(2)
 *     public void expensiveTest() {
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
	 * @return the required depth level (default 1)
	 */
	int value() default 1;
}
