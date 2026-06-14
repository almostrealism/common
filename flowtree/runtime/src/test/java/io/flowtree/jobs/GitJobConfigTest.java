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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GitJobConfig} covering builder defaults, immutability,
 * git-enabled detection, and excluded pattern merging.
 */
public class GitJobConfigTest extends TestSuiteBase {

	/**
	 * Verifies that the builder correctly sets all config fields and produces an immutable config.
	 */
	@Test(timeout = 30000)
	public void builderCreatesImmutableConfig() {
		GitJobConfig config = GitJobConfig.builder()
			.targetBranch("feature/test")
			.baseBranch("main")
			.repoUrl("https://github.com/owner/repo.git")
			.pushToOrigin(false)
			.createBranchIfMissing(false)
			.protectTestFiles(true)
			.maxFileSizeBytes(2048)
			.gitUserName("agent")
			.gitUserEmail("agent@test.com")
			.build();

		assertEquals("feature/test", config.getTargetBranch());
		assertEquals("main", config.getBaseBranch());
		assertEquals("https://github.com/owner/repo.git", config.getRepoUrl());
		assertFalse(config.isPushToOrigin());
		assertFalse(config.isCreateBranchIfMissing());
		assertTrue(config.isProtectTestFiles());
		assertEquals(2048, config.getMaxFileSizeBytes());
		assertEquals("agent", config.getGitUserName());
		assertEquals("agent@test.com", config.getGitUserEmail());
	}

	/**
	 * Verifies that the builder produces correct default values when no options are specified.
	 */
	@Test(timeout = 30000)
	public void defaultValues() {
		GitJobConfig config = GitJobConfig.builder().build();

		assertEquals("master", config.getBaseBranch());
		assertTrue(config.isPushToOrigin());
		assertTrue(config.isCreateBranchIfMissing());
		assertFalse(config.isProtectTestFiles());
		assertEquals(GitJobConfig.DEFAULT_MAX_FILE_SIZE, config.getMaxFileSizeBytes());
	}

	/**
	 * Verifies that git operations are disabled when no target branch is set, and enabled when one is provided.
	 */
	@Test(timeout = 30000)
	public void nullBranchMeansNoGitOps() {
		GitJobConfig config = GitJobConfig.builder().build();
		assertFalse(config.isGitEnabled());

		GitJobConfig enabled = GitJobConfig.builder()
			.targetBranch("feature/x")
			.build();
		assertTrue(enabled.isGitEnabled());
	}

	/**
	 * Verifies that getAllExcludedPatterns returns the union of default and additional excluded patterns.
	 */
	@Test(timeout = 30000)
	public void allExcludedPatternsCombinesDefaultAndAdditional() {
		Set<String> additional = new HashSet<>(Arrays.asList("*.tmp", "scratch/**"));
		GitJobConfig config = GitJobConfig.builder()
			.additionalExcludedPatterns(additional)
			.build();

		Set<String> all = config.getAllExcludedPatterns();

		// Should contain the additional patterns
		assertTrue(all.contains("*.tmp"));
		assertTrue(all.contains("scratch/**"));

		// Should also contain default patterns
		for (String defaultPattern : GitJobConfig.DEFAULT_EXCLUDED_PATTERNS) {
			assertTrue("Expected default pattern: " + defaultPattern,
				all.contains(defaultPattern));
		}

		// Total size should be defaults + additional (no overlap expected)
		assertEquals(
			GitJobConfig.DEFAULT_EXCLUDED_PATTERNS.size() + additional.size(),
			all.size()
		);
	}

	/**
	 * Verifies that the default exclusion set does NOT contain the
	 * blanket {@code .claude/**} pattern, so that project-shared
	 * {@code .claude/hooks/**}, {@code .claude/agents/**}, and
	 * {@code .claude/commands/**} content remains committable.
	 *
	 * <p>This is a regression guard for a bug where the blanket
	 * exclusion silently dropped {@code .claude/hooks/lib/*.py} and
	 * other project-shared content from agent commits. The blanket
	 * pattern must be replaced with narrow patterns targeting only
	 * machine-local paths (per-project state, local settings, lock
	 * files).</p>
	 */
	@Test(timeout = 30000)
	public void defaultExclusionsDoNotContainBlanketClaudePattern() {
		assertFalse(".claude/** must not be a blanket exclusion — it dropped "
				+ "project-shared .claude/hooks/** content from agent commits",
			GitJobConfig.DEFAULT_EXCLUDED_PATTERNS.contains(".claude/**"));
	}

	/**
	 * Verifies that the default exclusion set contains narrow patterns
	 * covering the genuinely machine-local paths under {@code .claude/}
	 * that {@code .gitignore} already treats as local: per-project
	 * session state, machine-local settings, and the scheduled-tasks
	 * lock file.
	 */
	@Test(timeout = 30000)
	public void defaultExclusionsIncludeNarrowClaudeLocalPatterns() {
		assertTrue("Expected .claude/projects/** to exclude per-project session state",
			GitJobConfig.DEFAULT_EXCLUDED_PATTERNS.contains(".claude/projects/**"));
		assertTrue("Expected .claude/*.local.json to exclude machine-local settings",
			GitJobConfig.DEFAULT_EXCLUDED_PATTERNS.contains(".claude/*.local.json"));
		assertTrue("Expected .claude/scheduled_tasks.lock to exclude the per-run lock",
			GitJobConfig.DEFAULT_EXCLUDED_PATTERNS.contains(".claude/scheduled_tasks.lock"));
	}
}
