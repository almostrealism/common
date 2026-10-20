/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link CodingAgentJobFactory#getDisplaySummary()}, the label
 * submission notifications use for a factory's jobs.
 */
public class CodingAgentJobFactoryDisplaySummaryTest extends TestSuiteBase {

	/** A description, when set, is the summary regardless of the prompt. */
	@Test(timeout = 10000)
	public void descriptionWins() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("x".repeat(200));
		factory.setDescription("Resolve test failures");
		assertEquals("Resolve test failures", factory.getDisplaySummary());
	}

	/** A short prompt is its own summary. */
	@Test(timeout = 10000)
	public void shortPromptIsReturnedAsIs() {
		assertEquals("Fix the build", new CodingAgentJobFactory("Fix the build").getDisplaySummary());
	}

	/** A long prompt is summarized by its character count. */
	@Test(timeout = 10000)
	public void longPromptIsSummarizedByLength() {
		assertEquals("1,200 character prompt",
				new CodingAgentJobFactory("y".repeat(1200)).getDisplaySummary());
	}

	/** An empty description does not hide the prompt, and a factory with no prompt says so. */
	@Test(timeout = 10000)
	public void emptyDescriptionAndMissingPrompt() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("Fix the build");
		factory.setDescription("");
		assertEquals("Fix the build", factory.getDisplaySummary());
		assertEquals("(no prompt)", new CodingAgentJobFactory().getDisplaySummary());
	}

	/** The summary survives the wire, since it is derived from serialized properties. */
	@Test(timeout = 10000)
	public void summarySurvivesRoundTrip() {
		CodingAgentJobFactory factory = new CodingAgentJob.Factory("Fix the build");
		factory.setDescription("Build fix");
		assertEquals("Build fix", GitManagedJobSerializationTest
				.roundTripFactory((CodingAgentJob.Factory) factory).getDisplaySummary());
	}
}
