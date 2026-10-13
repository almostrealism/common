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

package io.flowtree.workstream;

import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.ShellCommandJob;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link Workstream#newJobFactory(String, String)} and
 * {@link Workstream#newShellJobFactory(String, String)}, the single places every
 * submission path takes its workstream job defaults from, and for the
 * per-job values derived from workstream state.
 */
public class WorkstreamNewJobFactoryTest extends TestSuiteBase {

	/** Builds a workstream with every job default populated. */
	private static Workstream configuredWorkstream() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setAllowedTools("Read,Edit");
		workstream.setMaxTurns(42);
		workstream.setMaxBudgetUsd(7.5);
		workstream.setDefaultBranch("feature/default");
		workstream.setBaseBranch("develop");
		workstream.setPushToOrigin(false);
		workstream.setWorkingDirectory("/work");
		workstream.setRepoUrl("https://github.com/owner/repo.git");
		workstream.setGitUserName("Agent");
		workstream.setGitUserEmail("agent@example.com");
		workstream.setPlanningDocument("design/PLAN.md");
		workstream.setDependentRepos(List.of("https://github.com/owner/dep.git"));
		workstream.setUseTmux(true);
		workstream.setMaxWallClockHours(0);
		return workstream;
	}

	/** Every workstream default is carried onto the factory. */
	@Test(timeout = 10000)
	public void carriesWorkstreamDefaults() {
		CodingAgentJob.Factory factory = configuredWorkstream().newJobFactory("prompt");

		assertEquals("Read,Edit", factory.getAllowedTools());
		assertEquals(42, factory.getMaxTurns());
		assertEquals(7.5, factory.getMaxBudgetUsd(), 0.0);
		assertEquals("feature/default", factory.getTargetBranch());
		assertEquals("develop", factory.getBaseBranch());
		assertFalse(factory.isPushToOrigin());
		assertEquals("/work", factory.getWorkingDirectory());
		assertEquals("https://github.com/owner/repo.git", factory.getRepoUrl());
		assertEquals("Agent", factory.getGitUserName());
		assertEquals("agent@example.com", factory.getGitUserEmail());
		assertEquals("design/PLAN.md", factory.getPlanningDocument());
		assertEquals(List.of("https://github.com/owner/dep.git"), factory.getDependentRepos());
		assertTrue(factory.isUseTmux());
		assertEquals("zero wall-clock hours is carried, not treated as absent",
				Integer.valueOf(0), factory.getMaxWallClockHours());
	}

	/** An explicit target branch replaces the workstream default. */
	@Test(timeout = 10000)
	public void explicitTargetBranchOverridesDefault() {
		CodingAgentJob.Factory factory = configuredWorkstream().newJobFactory("prompt", "ci/explicit");
		assertEquals("ci/explicit", factory.getTargetBranch());
		assertFalse(factory.isPushToOrigin());
	}

	/** A workstream with no branch and no wall-clock setting leaves those unset. */
	@Test(timeout = 10000)
	public void unsetDefaultsStayUnset() {
		CodingAgentJob.Factory factory = new Workstream("ws", "C", "#c").newJobFactory("prompt");
		assertNull(factory.getTargetBranch());
		assertNull(factory.getRepoUrl());
		assertNull(factory.getMaxWallClockHours());
		assertFalse(factory.isUseTmux());
	}

	/** A shell-job factory carries the repository, branch, and checkout location. */
	@Test(timeout = 10000)
	public void shellJobFactoryCarriesCheckoutDefaults() {
		ShellCommandJob.Factory factory = configuredWorkstream().newShellJobFactory("echo hi", null);
		assertEquals("echo hi", factory.getCommand());
		assertEquals("feature/default", factory.getTargetBranch());
		assertEquals("/work", factory.getWorkingDirectory());
		assertEquals("https://github.com/owner/repo.git", factory.getRepoUrl());
	}

	/** An explicit shell-job target branch replaces the workstream default. */
	@Test(timeout = 10000)
	public void shellJobExplicitTargetBranchOverridesDefault() {
		ShellCommandJob.Factory factory = configuredWorkstream().newShellJobFactory("echo hi", "ci/explicit");
		assertEquals("ci/explicit", factory.getTargetBranch());
	}

	/** Submission labels replace the workstream defaults; absent ones fall back to them. */
	@Test(timeout = 10000)
	public void requiredLabelsFallBackToWorkstreamDefaults() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		assertTrue(workstream.requiredLabelsWith(null).isEmpty());

		workstream.setRequiredLabels(Map.of("platform", "macos"));
		assertEquals(Map.of("platform", "macos"), workstream.requiredLabelsWith(Map.of()));
		assertEquals(Map.of("gpu", "true"), workstream.requiredLabelsWith(Map.of("gpu", "true")));
	}

	/** The job URL names this workstream and the job, and is absent without a bound port. */
	@Test(timeout = 10000)
	public void jobUrlRequiresBoundPort() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		assertEquals("http://0.0.0.0:7780/api/workstreams/ws/jobs/job-1", workstream.jobUrl(7780, "job-1"));
		assertNull(workstream.jobUrl(0, "job-1"));
	}
}
