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

import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the environment-independent contract of {@link TestUtils}: the test
 * group partition function, the current-group membership rule, the comparison
 * gate, and the {@link TestSettings#testProfileIs(String)} default method.
 *
 * <p>These assertions are written to hold regardless of the ambient
 * {@code AR_TEST_*} configuration by expressing each property relative to the
 * accessor that reads the same configuration.</p>
 */
public class TestUtilsBehaviorTest extends TestSuiteBase {

	/** The pipeline profile name is a fixed constant callers compare against. */
	@Test(timeout = 10000)
	public void pipelineProfileConstantIsStable() {
		Assert.assertEquals("pipeline", TestUtils.PIPELINE);
	}

	/** The group count is always a positive shard count. */
	@Test(timeout = 10000)
	public void groupCountIsPositive() {
		Assert.assertTrue(TestUtils.getTestGroupCount() > 0);
	}

	/**
	 * A class is partitioned by {@code |hashCode| mod groupCount}, and the
	 * result is stable and within range.
	 */
	@Test(timeout = 10000)
	public void groupForClassFollowsHashPartition() {
		String className = "org.almostrealism.example.WidgetTest";
		int count = TestUtils.getTestGroupCount();
		int group = TestUtils.getGroupForClass(className);

		Assert.assertEquals(Math.abs(className.hashCode()) % count, group);
		Assert.assertTrue(group >= 0 && group < count);
		// Deterministic across calls.
		Assert.assertEquals(group, TestUtils.getGroupForClass(className));
	}

	/**
	 * Current-group membership is "no group configured, or this class maps to
	 * the configured group" — consistent with the group partition.
	 */
	@Test(timeout = 10000)
	public void shouldRunInCurrentGroupMatchesPartition() {
		String className = "org.almostrealism.example.AnotherTest";
		Integer target = TestUtils.getTestGroup();
		boolean run = TestUtils.shouldRunInCurrentGroup(className);

		if (target == null) {
			Assert.assertTrue("With no target group every class runs", run);
		} else {
			Assert.assertEquals(TestUtils.getGroupForClass(className) == target, run);
		}
	}

	/** Comparison testing is enabled exactly when the profile is not the pipeline profile. */
	@Test(timeout = 10000)
	public void comparisonEnabledOutsidePipeline() {
		Assert.assertEquals(
				!TestUtils.PIPELINE.equals(TestUtils.getTestProfile()),
				TestUtils.isComparisonTestEnabled());
	}

	/** {@link TestSettings#testProfileIs(String)} compares against the active profile. */
	@Test(timeout = 10000)
	public void testProfileIsMatchesActiveProfile() {
		TestSettings settings = new TestUtils();
		String active = TestUtils.getTestProfile();

		Assert.assertTrue("The active profile should match itself", settings.testProfileIs(active));
		Assert.assertFalse("A distinct profile name should not match",
				settings.testProfileIs(active + "-not-a-profile"));
	}
}
