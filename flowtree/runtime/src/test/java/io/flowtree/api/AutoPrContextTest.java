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

package io.flowtree.api;

import io.flowtree.jobs.CodingAgentJobFactory;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AutoPrContext#forJob}, which fixes the description a
 * pull request falls back to when its head commit yields no title or body.
 */
public class AutoPrContextTest extends TestSuiteBase {

	/**
	 * A short prompt with no description must not become the fallback; the
	 * display summary would return it verbatim, which is the leak this guards.
	 */
	@Test(timeout = 10000)
	public void shortPromptWithoutDescriptionFallsBackToJobName() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("Delete the staging database");
		assertEquals("Delete the staging database", factory.getDisplaySummary());

		AutoPrContext context = AutoPrContext.forJob(factory, "acme", false);
		assertEquals("Job " + factory.getTaskId(), context.description);
		assertFalse("The fallback must not carry the prompt",
				context.description.contains("staging database"));
	}

	/** An explicit description is the fallback, as submitted. */
	@Test(timeout = 10000)
	public void explicitDescriptionIsTheFallback() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do the work");
		factory.setDescription("Resolve test failures");
		assertEquals("Resolve test failures", AutoPrContext.forJob(factory, "acme", false).description);
	}

	/** A blank description counts as none and falls back to the job name. */
	@Test(timeout = 10000)
	public void blankDescriptionFallsBackToJobName() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do the work");
		factory.setDescription("   ");
		assertEquals("Job " + factory.getTaskId(), AutoPrContext.forJob(factory, "acme", false).description);
	}

	/** Repository, base branch, organisation and draft flag are carried over from the arguments. */
	@Test(timeout = 10000)
	public void carriesRepositoryBranchOrgAndDraft() {
		CodingAgentJobFactory factory = new CodingAgentJobFactory("do the work");
		factory.setRepoUrl("https://github.com/acme/app.git");
		factory.setBaseBranch("develop");

		AutoPrContext context = AutoPrContext.forJob(factory, "acme", true);
		assertEquals("https://github.com/acme/app.git", context.repoUrl);
		assertEquals("develop", context.baseBranch);
		assertEquals("acme", context.githubOrg);
		assertTrue(context.draft);
	}
}
