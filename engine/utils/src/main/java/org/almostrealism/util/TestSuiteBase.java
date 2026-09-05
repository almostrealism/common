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

import io.almostrealism.expression.Expression;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.junit.After;
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
 * <p>Each tier contains a representative mix of test types and durations.
 * Depth is not a proxy for test duration — it controls thoroughness.</p>
 * <ul>
 *   <li><b>No annotation</b>: Core coverage (~20 min total), mix of fast and slow</li>
 *   <li><b>@TestDepth(1)</b>: Broader coverage (~60 min total)</li>
 *   <li><b>@TestDepth(2)</b>: Comprehensive coverage (~2 hr total)</li>
 *   <li><b>@TestDepth(3+)</b>: Full suite (~4+ hr total)</li>
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
	static {
		if (SystemUtils.isEnabled("AR_SIGNALWIRE_ALERTS").orElse(false)) {
			SignalWireDeliveryProvider.attachDefault();
		}
	}

	/**
	 * No-op {@link ConsoleFeatures} logger for tests that do not assert on log output.
	 * Shared by all test subclasses to avoid repeating the anonymous-class declaration.
	 */
	protected static final ConsoleFeatures SILENT = new ConsoleFeatures() {};

	/**
	 * Rule that automatically skips tests based on {@link TestDepth} annotations.
	 */
	@Rule
	public TestDepthRule depthRule = testDepthRule();

	/**
	 * Removes any profile the test that just ran left assigned.
	 *
	 * <p>{@link TestFeatures#initKernelMetrics} installs a profile through
	 * {@link Hardware#assignProfile}, which keeps its listeners in static fields —
	 * among them {@link Expression#timing}, which makes every expression comparison
	 * and every expression cache probe record a timing entry. Nothing in
	 * {@code initKernelMetrics} removes them again, so the profiling one test asked
	 * for otherwise stays switched on for every test that shares the JVM after it,
	 * and its cost is charged to whichever test happens to run next.</p>
	 *
	 * <p>The listeners are installed and removed as a set, so any one of them answers
	 * whether a profile is assigned. {@link Expression#timing} is the one to ask
	 * because reading it does not load {@link Hardware}, and so does not initialize a
	 * backend on behalf of a test that never used one.</p>
	 *
	 * <p>JUnit runs a subclass's {@code @After} methods before the superclass's, so a
	 * test that saves its profile on the way out still finds it assigned.</p>
	 */
	@After
	public void clearProfile() {
		if (Expression.timing != null) {
			Hardware.getLocalHardware().clearProfile();
		}
	}
}
