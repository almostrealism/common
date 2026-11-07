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
import org.junit.internal.AssumptionViolatedException;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * Tests that {@link TestDepthRule} keeps tests marked
 * {@link TestProperties#audioDeviceRequired()} from running under the
 * {@link TestUtils#PIPELINE} profile, so a CI machine with a working audio
 * device never plays them aloud.
 */
public class TestDepthRuleAudioDeviceTest extends TestSuiteBase {

	/** Test methods the rule is applied to; they are never run as tests themselves. */
	public static class Candidates {
		/** A method that would play through a real audio device. */
		@TestProperties(audioDeviceRequired = true)
		public void playsAudio() { }

		/** A method with no audio device requirement. */
		public void silent() { }
	}

	/** The statement the rule wraps, recording whether the rule let it run. */
	private static class Base extends Statement {
		/** Whether the statement was evaluated. */
		private boolean evaluated;

		@Override
		public void evaluate() { evaluated = true; }
	}

	/**
	 * Applies the rule to the named {@link Candidates} method under the pipeline
	 * profile, restoring the previous profile before returning.
	 *
	 * @param method the name of the {@link Candidates} method
	 * @param base   the statement to wrap
	 * @return the statement the rule produced
	 */
	private Statement applyUnderPipeline(String method, Statement base) throws NoSuchMethodException {
		FrameworkMethod target = new FrameworkMethod(Candidates.class.getMethod(method));
		String previous = System.getProperty("AR_TEST_PROFILE");
		System.setProperty("AR_TEST_PROFILE", TestUtils.PIPELINE);

		try {
			// The test itself is the target so the rule's group check sees a class in the current group
			return new TestDepthRule().apply(base, target, this);
		} finally {
			if (previous == null) {
				System.clearProperty("AR_TEST_PROFILE");
			} else {
				System.setProperty("AR_TEST_PROFILE", previous);
			}
		}
	}

	/**
	 * A test that needs an audio device is skipped because of the profile, whether
	 * or not this host has a device; the reason distinguishes it from the no-device skip.
	 */
	@Test(timeout = 30000)
	public void audioDeviceTestIsSkippedUnderPipeline() throws Throwable {
		Base base = new Base();
		String reason = null;

		try {
			applyUnderPipeline("playsAudio", base).evaluate();
		} catch (AssumptionViolatedException skip) {
			reason = skip.getMessage();
		}

		Assert.assertNotNull("The test was not skipped", reason);
		Assert.assertTrue(reason, reason.contains(TestUtils.PIPELINE));
		Assert.assertFalse(base.evaluated);
	}

	/**
	 * The profile does not skip a test with no audio device requirement.
	 */
	@Test(timeout = 30000)
	public void otherTestsStillRunUnderPipeline() throws Throwable {
		Base base = new Base();
		applyUnderPipeline("silent", base).evaluate();
		Assert.assertTrue(base.evaluated);
	}
}
