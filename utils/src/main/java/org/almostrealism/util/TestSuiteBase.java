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

import org.junit.Rule;

/**
 * Base class for Almost Realism unit tests providing automatic test depth filtering
 * via the {@link TestDepth} annotation.
 *
 * <p>Extend this class instead of implementing {@link TestFeatures} directly to get
 * automatic {@link TestDepth} annotation support without any additional boilerplate.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public class MyTest extends AlmostRealismTest {
 *     @Test(timeout = 30000)
 *     public void basicTest() {
 *         // Runs at any depth (no annotation)
 *     }
 *
 *     @Test(timeout = 30000)
 *     @TestDepth(1)
 *     public void mediumTest() {
 *         // Only runs if AR_TEST_DEPTH >= 1
 *     }
 *
 *     @Test(timeout = 60000)
 *     @TestDepth(3)
 *     public void expensiveTest() {
 *         // Only runs if AR_TEST_DEPTH >= 3
 *     }
 * }
 * }</pre>
 *
 * <h2>Test Depth Tiers</h2>
 * <ul>
 *   <li><b>No annotation</b>: Basic smoke tests (always run)</li>
 *   <li><b>@TestDepth(1)</b>: Medium complexity tests</li>
 *   <li><b>@TestDepth(2)</b>: Comprehensive tests</li>
 *   <li><b>@TestDepth(3)</b>: Heavy/expensive tests</li>
 *   <li><b>@TestDepth(10)</b>: Very expensive tests</li>
 * </ul>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AR_TEST_DEPTH}: Minimum depth level (default: 9)</li>
 *   <li>{@code AR_TEST_PROFILE=pipeline}: Runs all tests regardless of depth</li>
 * </ul>
 *
 * @author Michael Murray
 * @see TestDepth
 * @see TestDepthRule
 * @see TestFeatures
 */
public abstract class TestSuiteBase implements TestFeatures {

	/**
	 * Rule that automatically skips tests based on {@link TestDepth} annotations.
	 */
	@Rule
	public TestDepthRule depthRule = testDepthRule();
}
