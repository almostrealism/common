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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GitManagedJob} and {@link CodingAgentJob} serialization
 * via the {@code encode()} and {@code set()} methods. Uses
 * {@link CodingAgentJob} as the concrete implementation since
 * {@link GitManagedJob} is abstract.
 *
 * <p>There are two categories of test here:
 * <ol>
 *   <li><em>Field-level tests</em> — verify that individual fields survive the
 *       encode/decode round-trip.</li>
 *   <li><em>Comprehensive round-trip tests</em> — populate every settable
 *       property on a job (or factory), encode it, decode it into a fresh
 *       instance, and assert that every property is equal.  These tests are
 *       the primary regression guard: adding a new field to {@code encode()}
 *       without a corresponding {@code set()} case (or vice-versa) will
 *       cause the comprehensive test to fail immediately.</li>
 * </ol>
 */
public class GitManagedJobSerializationTest extends TestSuiteBase {

	// ── Helpers ─────────────────────────────────────────────────────────────

	/**
	 * Encodes {@code original}, then parses the wire format and calls
	 * {@code set()} on a fresh {@link CodingAgentJob} for every key-value pair.
	 *
	 * <p>This mirrors the logic in {@link io.flowtree.JobClassLoader}: the
	 * encoded string is {@code className::k1:=v1::k2:=v2...} and
	 * {@code "::"} is the entry separator while {@code ":="} is the
	 * key-value separator.  The split uses a limit of {@code -1} so trailing
	 * empty segments are retained.</p>
	 */
	static CodingAgentJob roundTrip(CodingAgentJob original) {
		String encoded = original.encode();
		CodingAgentJob restored = new CodingAgentJob();
		String[] parts = encoded.split("::");
		for (int i = 1; i < parts.length; i++) {
			int sep = parts[i].indexOf(":=");
			if (sep > 0) {
				String key = parts[i].substring(0, sep);
				String value = parts[i].substring(sep + 2);
				restored.set(key, value);
			}
		}
		return restored;
	}

	static CodingAgentJob.Factory roundTripFactory(CodingAgentJob.Factory original) {
		String encoded = original.encode();
		CodingAgentJob.Factory restored = new CodingAgentJob.Factory();
		String[] parts = encoded.split("::");
		for (int i = 1; i < parts.length; i++) {
			int sep = parts[i].indexOf(":=");
			if (sep > 0) {
				restored.set(parts[i].substring(0, sep), parts[i].substring(sep + 2));
			}
		}
		return restored;
	}

	// ── Field-level tests ────────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void encodeIncludesAllFields() {
		CodingAgentJob job = new CodingAgentJob("task-1", "Fix the bug");
		job.setAllowedTools("Read,Edit,Bash");
		job.setMaxTurns(75);
		job.setTargetBranch("feature/fix");
		job.setBaseBranch("develop");

		String encoded = job.encode();

		assertTrue("Expected class name prefix",
			encoded.startsWith(CodingAgentJob.class.getName()));
		assertTrue("Expected prompt key",
			encoded.contains("prompt:="));
		assertTrue("Expected tools key",
			encoded.contains("tools:="));
		assertTrue("Expected maxTurns key",
			encoded.contains("maxTurns:="));
		assertTrue("Expected branch key",
			encoded.contains("branch:="));
		assertTrue("Expected baseBranch key",
			encoded.contains("baseBranch:="));
	}

	@Test(timeout = 30000)
	public void setRestoresAllFields() {
		CodingAgentJob job = new CodingAgentJob();
		job.set("taskId", "task-2");
		job.set("prompt", Base64.getEncoder()
			.encodeToString("Do the work".getBytes(StandardCharsets.UTF_8)));
		job.set("tools", Base64.getEncoder()
			.encodeToString("Read,Glob".getBytes(StandardCharsets.UTF_8)));
		job.set("maxTurns", "30");
		job.set("branch", Base64.getEncoder()
			.encodeToString("feature/test".getBytes(StandardCharsets.UTF_8)));

		assertEquals("task-2", job.getTaskId());
		assertEquals("Do the work", job.getPrompt());
		assertEquals("Read,Glob", job.getAllowedTools());
		assertEquals(30, job.getMaxTurns());
		assertEquals("feature/test", job.getTargetBranch());
	}

	@Test(timeout = 30000)
	public void encodeDecodeRoundTrip() {
		CodingAgentJob original = new CodingAgentJob("task-rt", "Round trip prompt");
		original.setAllowedTools("Read,Edit");
		original.setMaxTurns(42);
		original.setMaxBudgetUsd(7.5);
		original.setTargetBranch("feature/roundtrip");
		original.setBaseBranch("main");

		CodingAgentJob restored = roundTrip(original);

		assertEquals(original.getPrompt(), restored.getPrompt());
		assertEquals(original.getAllowedTools(), restored.getAllowedTools());
		assertEquals(original.getMaxTurns(), restored.getMaxTurns());
		assertEquals(original.getMaxBudgetUsd(), restored.getMaxBudgetUsd(), 0.001);
		assertEquals(original.getTargetBranch(), restored.getTargetBranch());
		assertEquals(original.getBaseBranch(), restored.getBaseBranch());
	}

	// ── dependentRepos — job level ───────────────────────────────────────────

	/**
	 * Regression test for the bug where {@code dependentRepos} was not
	 * included in {@link GitManagedJob#encode()} and had no corresponding
	 * case in {@link GitManagedJob#set(String, String)}.  When the job was
	 * transmitted over the FlowTree network the dependent-repo list was
	 * silently dropped and the repos were left on whatever branch a previous
	 * job had left them on.
	 */
	@Test(timeout = 30000)
	public void jobDependentReposEncodeDecodeRoundTrip() {
		CodingAgentJob original = new CodingAgentJob("task-dep", "Test dependent repos");
		original.setTargetBranch("feature/my-work");
		original.setDependentRepos(Arrays.asList(
			"https://github.com/org/repo-a",
			"https://github.com/org/repo-b"));

		String encoded = original.encode();
		assertTrue("encode() must include depRepos key",
			encoded.contains("depRepos:="));

		CodingAgentJob restored = roundTrip(original);

		List<String> repos = restored.getDependentRepos();
		assertNotNull("dependentRepos must not be null after decode", repos);
		assertEquals(2, repos.size());
		assertEquals("https://github.com/org/repo-a", repos.get(0));
		assertEquals("https://github.com/org/repo-b", repos.get(1));
	}

	@Test(timeout = 30000)
	public void jobDependentReposAbsentWhenEmpty() {
		CodingAgentJob job = new CodingAgentJob("task-nodep", "No deps");
		job.setDependentRepos(null);
		assertFalse("encode() must not include depRepos key when null",
			job.encode().contains("depRepos:="));

		CodingAgentJob job2 = new CodingAgentJob("task-nodep2", "No deps");
		job2.setDependentRepos(Collections.emptyList());
		assertFalse("encode() must not include depRepos key when empty",
			job2.encode().contains("depRepos:="));
	}

	// ── Comprehensive round-trips ────────────────────────────────────────────

	/**
	 * Comprehensive round-trip for every {@link GitManagedJob} property.
	 *
	 * <p>Populate every field that has a setter on {@link GitManagedJob},
	 * encode the job, decode it, and assert all fields are equal.  Any
	 * property added in the future that is missing from either
	 * {@code encode()} or {@code set()} will cause this test to fail.</p>
	 */
	@Test(timeout = 30000)
	public void allGitManagedJobPropertiesRoundTrip() {
		CodingAgentJob original = new CodingAgentJob("task-full-git", "Full git props");
		original.setTargetBranch("feature/full");
		original.setBaseBranch("master");
		original.setRepoUrl("https://github.com/org/main-repo");
		original.setDefaultWorkspacePath("/workspace/project");
		original.setWorkingDirectory("/workspace/project/main-repo");
		original.setPushToOrigin(true);
		original.setCreateBranchIfMissing(true);
		original.setGitUserName("Test User");
		original.setGitUserEmail("test@example.com");
		original.setWorkstreamUrl("http://localhost:7780/api/workstreams/abc/jobs/xyz");
		original.setProtectTestFiles(true);
		original.setMaxFileSizeBytes(2048 * 1024L);
		original.setDependentRepos(Arrays.asList(
			"https://github.com/org/dep-one",
			"https://github.com/org/dep-two"));

		CodingAgentJob restored = roundTrip(original);

		assertEquals(original.getTargetBranch(), restored.getTargetBranch());
		assertEquals(original.getBaseBranch(), restored.getBaseBranch());
		assertEquals(original.getRepoUrl(), restored.getRepoUrl());
		assertEquals(original.getDefaultWorkspacePath(), restored.getDefaultWorkspacePath());
		assertEquals(original.getWorkingDirectory(), restored.getWorkingDirectory());
		assertTrue("pushToOrigin", restored.isPushToOrigin());
		assertTrue("createBranchIfMissing", restored.isCreateBranchIfMissing());
		assertEquals(original.getGitUserName(), restored.getGitUserName());
		assertEquals(original.getGitUserEmail(), restored.getGitUserEmail());
		assertEquals(original.getWorkstreamUrl(), restored.getWorkstreamUrl());
		assertTrue("protectTestFiles", restored.isProtectTestFiles());
		assertEquals(original.getMaxFileSizeBytes(), restored.getMaxFileSizeBytes());

		List<String> restoredRepos = restored.getDependentRepos();
		assertNotNull("dependentRepos must not be null after decode", restoredRepos);
		assertEquals(2, restoredRepos.size());
		assertEquals(original.getDependentRepos().get(0), restoredRepos.get(0));
		assertEquals(original.getDependentRepos().get(1), restoredRepos.get(1));
	}

	/**
	 * Comprehensive round-trip for every {@link CodingAgentJob}-specific property.
	 *
	 * <p>Any property added to {@code CodingAgentJob.encode()} without a
	 * corresponding {@code set()} case (or vice-versa) will cause this
	 * test to fail.</p>
	 */
	@Test(timeout = 30000)
	public void allCodingAgentJobPropertiesRoundTrip() {
		CodingAgentJob original = new CodingAgentJob("task-full-cc", "Full ClaudeCode props");
		original.setAllowedTools("Read,Edit,Bash,Glob,Grep");
		original.setMaxTurns(100);
		original.setMaxBudgetUsd(25.0);
		original.setArManagerUrl("https://manager.example.com");
		original.setArManagerToken("token-abc-123");
		original.setPlanningDocument("## Plan\nDo the thing.");
		original.setProtectTestFiles(true);
		original.setEnforceChanges(true);
		original.setDeduplicationMode(CodingAgentJob.DEDUP_NONE);
		original.setEnforceMavenDependencies(true);

		CodingAgentJob restored = roundTrip(original);

		assertEquals(original.getPrompt(), restored.getPrompt());
		assertEquals(original.getAllowedTools(), restored.getAllowedTools());
		assertEquals(original.getMaxTurns(), restored.getMaxTurns());
		assertEquals(original.getMaxBudgetUsd(), restored.getMaxBudgetUsd(), 0.001);
		assertEquals(original.getArManagerUrl(), restored.getArManagerUrl());
		assertEquals(original.getArManagerToken(), restored.getArManagerToken());
		assertEquals(original.getPlanningDocument(), restored.getPlanningDocument());
		assertTrue("protectTestFiles", restored.isProtectTestFiles());
		assertTrue("enforceChanges", restored.isEnforceChanges());
		assertEquals(original.getDeduplicationMode(), restored.getDeduplicationMode());
		assertTrue("enforceMavenDependencies", restored.isEnforceMavenDependencies());
	}

	// ── Factory tests ────────────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void factoryEncodeDecodeRoundTrip() {
		CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt A", "Prompt B");
		factory.setAllowedTools("Read,Edit,Bash");
		factory.setTargetBranch("feature/factory-test");
		factory.setMaxTurns(60);
		factory.setMaxBudgetUsd(15.0);

		String encoded = factory.encode();

		assertTrue("Expected Factory class name in encoded output",
			encoded.contains(CodingAgentJob.Factory.class.getName()));
		assertTrue("Expected prompts key in encoded output",
			encoded.contains("prompts:="));
		assertTrue("Expected branch key in encoded output",
			encoded.contains("branch:="));
		assertTrue("Expected tools key in encoded output",
			encoded.contains("tools:="));
	}

	@Test(timeout = 30000)
	public void factoryDependentReposRoundTrip() {
		CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt");
		factory.setDependentRepos(Arrays.asList(
			"https://github.com/org/repo-a",
			"https://github.com/org/repo-b"));

		String encoded = factory.encode();
		assertTrue("Expected dependentRepos key in encoded output",
			encoded.contains("dependentRepos:="));

		CodingAgentJob.Factory restored = roundTripFactory(factory);

		List<String> repos = restored.getDependentRepos();
		assertNotNull("Expected non-null dependentRepos after decode", repos);
		assertEquals(2, repos.size());
		assertEquals("https://github.com/org/repo-a", repos.get(0));
		assertEquals("https://github.com/org/repo-b", repos.get(1));
	}

	@Test(timeout = 30000)
	public void factoryDependentReposAbsentWhenEmpty() {
		CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt");
		factory.setDependentRepos(null);

		String encoded = factory.encode();
		assertFalse("Expected no dependentRepos key when null",
			encoded.contains("dependentRepos:="));

		CodingAgentJob.Factory factory2 = new CodingAgentJob.Factory("Prompt");
		factory2.setDependentRepos(Collections.emptyList());

		String encoded2 = factory2.encode();
		assertFalse("Expected no dependentRepos key when empty list",
			encoded2.contains("dependentRepos:="));
	}
}
