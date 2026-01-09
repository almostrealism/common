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

import org.junit.Assume;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * A JUnit rule that skips tests based on the {@link TestDepth} annotation
 * and test group assignment.
 *
 * <p>Tests are filtered by two criteria:</p>
 * <ol>
 *   <li><b>Test Depth</b>: Tests annotated with {@code @TestDepth(n)} will be
 *       skipped if the current test depth is less than {@code n}.</li>
 *   <li><b>Test Group</b>: When {@code AR_TEST_GROUP} is set, only tests whose
 *       class hashes to that group will run. This enables parallel test execution
 *       across multiple VMs.</li>
 * </ol>
 *
 * <p>The current test depth is read from {@link TestSettings#testDepth}, which
 * comes from the {@code AR_TEST_DEPTH} environment variable (default: 9).</p>
 *
 * <h2>Usage with TestSuiteBase (recommended)</h2>
 * <pre>{@code
 * public class MyTest extends TestSuiteBase {
 *     @Test(timeout = 30000)
 *     @TestDepth(2)
 *     public void expensiveTest() {
 *         // Automatically skipped if depth < 2
 *     }
 * }
 * }</pre>
 *
 * <h2>Usage with TestFeatures interface</h2>
 * <pre>{@code
 * public class MyTest implements TestFeatures {
 *     @Rule public TestDepthRule depthRule = testDepthRule();
 *
 *     @Test(timeout = 30000)
 *     @TestDepth(2)
 *     public void expensiveTest() {
 *         // Automatically skipped if depth < 2
 *     }
 * }
 * }</pre>
 *
 * <h2>Parallel Test Execution</h2>
 * <p>To run tests in parallel across 4 VMs:</p>
 * <pre>
 * # VM 1: mvn test -DAR_TEST_GROUP=0 -DAR_TEST_GROUPS=4
 * # VM 2: mvn test -DAR_TEST_GROUP=1 -DAR_TEST_GROUPS=4
 * # VM 3: mvn test -DAR_TEST_GROUP=2 -DAR_TEST_GROUPS=4
 * # VM 4: mvn test -DAR_TEST_GROUP=3 -DAR_TEST_GROUPS=4
 * </pre>
 *
 * @author Michael Murray
 * @see TestDepth
 * @see TestSuiteBase
 * @see TestFeatures#testDepthRule()
 * @see TestUtils#shouldRunInCurrentGroup(String)
 */
public class TestDepthRule implements MethodRule {
	private final int currentDepth;

	/**
	 * Creates a rule with the specified current depth.
	 *
	 * @param currentDepth the current test depth level
	 */
	public TestDepthRule(int currentDepth) {
		this.currentDepth = currentDepth;
	}

	/**
	 * Creates a rule using the depth from {@link TestUtils#getTestDepth()}.
	 */
	public TestDepthRule() {
		this(TestUtils.getTestDepth());
	}

	@Override
	public Statement apply(Statement base, FrameworkMethod method, Object target) {
		// Check test group first (skip entire class if not in current group)
		String className = target.getClass().getName();
		if (!TestUtils.shouldRunInCurrentGroup(className)) {
			Integer targetGroup = TestUtils.getTestGroup();
			int classGroup = TestUtils.getGroupForClass(className);
			return new Statement() {
				@Override
				public void evaluate() {
					Assume.assumeTrue(
						"Test class assigned to group " + classGroup +
						", running group " + targetGroup,
						false
					);
				}
			};
		}

		// Check test depth
		TestDepth annotation = method.getAnnotation(TestDepth.class);
		if (annotation != null && currentDepth < annotation.value()) {
			return new Statement() {
				@Override
				public void evaluate() {
					Assume.assumeTrue(
						"Test requires depth " + annotation.value() +
						", current depth is " + currentDepth,
						false
					);
				}
			};
		}

		return base;
	}
}
