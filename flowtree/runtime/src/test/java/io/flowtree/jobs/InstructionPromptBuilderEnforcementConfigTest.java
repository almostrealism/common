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

import static org.junit.Assert.assertTrue;

/**
 * Tests for the unconditional "Enforcement Configuration" section in
 * {@link InstructionPromptBuilder}.  The abandon-before-tamper rule is
 * the proactive prompt-template complement to the structural
 * {@code .claude/hooks/block-checkstyle-edit.sh} block and its
 * opencode counterpart.  The rule is in scope on every primary and
 * correction session, regardless of workstream URL or other
 * configuration, so these tests are unconditional and use the bare
 * {@code build()} call.
 *
 * <p>This test class is intentionally separate from
 * {@link InstructionPromptBuilderTest} so the enforcement-config rule
 * is easy to find and so a future refactor of the existing class
 * does not accidentally drop the rule's coverage.</p>
 */
public class InstructionPromptBuilderEnforcementConfigTest extends TestSuiteBase {

	/**
	 * Phrase anchors taken from the abandon-before-tamper principle
	 * text.  Each anchor is a short, distinctive substring that
	 * uniquely identifies a clause in the rendered section; together
	 * they assert that the entire principle is present.  The
	 * checkstyle-specific backstop sentence ("Attempts to edit
	 * checkstyle configuration are blocked; do not try to circumvent
	 * the block.") was removed in the prompt trim because the
	 * block-checkstyle-edit hook enforces it structurally.
	 */
	private static final String[] ANCHORS = new String[] {
		"Enforcement configuration",
		"checkstyle rules and suppressions",
		"NEVER be weakened, exempted, or disabled",
		"ABANDON the task",
		"declaring failure is always preferable",
	};

	/** The bare prompt must contain the section header. */
	@Test(timeout = 30000)
	public void enforcementConfigSectionAppearsInBarePrompt() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertTrue("Enforcement Configuration section must appear in every prompt",
			result.contains("## Enforcement Configuration"));
	}

	/** The principle must include all its core clauses. */
	@Test(timeout = 30000)
	public void enforcementConfigPrincipleIncludesAllAnchors() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		for (String anchor : ANCHORS) {
			assertTrue("Enforcement Configuration section must contain: " + anchor,
				result.contains(anchor));
		}
	}

	/** The principle appears in correction sessions too. */
	@Test(timeout = 30000)
	public void enforcementConfigSectionAppearsInCorrectionSession() {
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setCorrectionSession(true)
			.build();
		assertTrue("Enforcement Configuration section must also appear in correction sessions",
			result.contains("## Enforcement Configuration"));
	}

	/** The principle appears regardless of workstream URL configuration. */
	@Test(timeout = 30000)
	public void enforcementConfigSectionAppearsWithoutWorkstreamUrl() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.build();
		assertTrue("Enforcement Configuration section must appear even without a workstream URL",
			result.contains("## Enforcement Configuration"));
	}

	/** The principle appears before the user request marker. */
	@Test(timeout = 30000)
	public void enforcementConfigSectionAppearsBeforeUserRequestMarker() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.build();
		int sectionIdx = result.indexOf("## Enforcement Configuration");
		int requestIdx = result.indexOf("--- BEGIN USER REQUEST ---");
		assertTrue("Enforcement Configuration section should appear in the prompt", sectionIdx >= 0);
		assertTrue("User request marker should appear in the prompt", requestIdx >= 0);
		assertTrue("Enforcement Configuration section should precede the user request marker",
			sectionIdx < requestIdx);
	}

	/**
	 * The principle is positioned near the top of the prompt, so the
	 * agent sees it before reaching for the edit-checkstyle hook.
	 * Specifically it should appear after the git workflow reminder
	 * (which is the unconditional operational block at the top) and
	 * before the Working Efficiently section (which is the next
	 * unconditional operational block).
	 */
	@Test(timeout = 30000)
	public void enforcementConfigSectionFollowsGitWorkflowReminder() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.build();
		int gitIdx = result.indexOf("Note on git workflow");
		int enforcementIdx = result.indexOf("## Enforcement Configuration");
		assertTrue("Git workflow reminder should appear in the prompt", gitIdx >= 0);
		assertTrue("Enforcement Configuration section should appear in the prompt", enforcementIdx >= 0);
		assertTrue("Enforcement Configuration should follow the git workflow reminder",
			enforcementIdx > gitIdx);
	}

	/**
	 * The principle does not duplicate the existing Test Integrity
	 * Policy section: that one is conditional on
	 * {@code protectTestFiles} and is a project-policy marker, while
	 * this new section is unconditional and covers all enforcement
	 * configuration (checkstyle rules and suppressions, policy
	 * validators, test integrity checks).  We assert the section text
	 * mentions all three so the principle's scope is clear.
	 */
	@Test(timeout = 30000)
	public void enforcementConfigPrincipleListsScope() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		// The principle should enumerate the three enforcement-config
		// categories so the agent understands the rule's scope.
		assertTrue("Principle must mention checkstyle rules and suppressions",
			result.contains("checkstyle rules and suppressions"));
		assertTrue("Principle must mention policy validators",
			result.contains("policy validators"));
		assertTrue("Principle must mention test integrity checks",
			result.contains("test integrity checks"));
	}

	/**
	 * The principle is concise: a single short paragraph (one
	 * continuation of the same paragraph, no internal blank lines).
	 * If a future refactor accidentally breaks it into multiple
	 * paragraphs or expands it beyond a short statement, this test
	 * flags the change so the rule stays operational rather than
	 * motivational.  We check that the section header is followed
	 * within a short window by the body's closing text and then
	 * another section.
	 */
	@Test(timeout = 30000)
	public void enforcementConfigSectionIsConcise() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		int start = result.indexOf("## Enforcement Configuration");
		assertTrue("Section must be present", start >= 0);
		// Within 1500 characters of the section header, the rule's
		// close phrase must appear.  This is a soft cap: the current
		// rendering is well under 1000 chars, so 1500 gives room for
		// natural wording tweaks without becoming an essay.
		int close = result.indexOf("declaring failure is always preferable", start);
		assertTrue("Principle close phrase must appear soon after the header",
			close >= 0 && close - start < 1500);
		// And it must not be the very last thing in the prompt.
		int requestIdx = result.indexOf("--- BEGIN USER REQUEST ---");
		assertTrue("User request marker must appear after the principle", requestIdx >= 0);
		assertTrue("Principle must end before the user request marker",
			close < requestIdx);
	}

	/**
	 * The principle does not contradict the existing Test Integrity
	 * Policy section: that one is project-policy ("you MUST NOT
	 * modify test files that exist on the base branch"), and the new
	 * one is meta-policy ("never weaken enforcement config").  Both
	 * are present, and the new one does not re-state the test
	 * integrity policy verbatim.
	 */
	@Test(timeout = 30000)
	public void enforcementConfigPrincipleDoesNotContradictTestIntegrityPolicy() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setProtectTestFiles(true)
			.build();
		assertTrue("Enforcement Configuration section must be present",
			result.contains("## Enforcement Configuration"));
		assertTrue("Test Integrity Policy section must still be present",
			result.contains("## Test Integrity Policy"));
		// The new principle's wording about "test integrity checks"
		// is the meta-rule (abandon-before-tamper), not a duplicate
		// of the project rule (don't modify test files).  We assert
		// the new principle is intact and the project rule is intact.
		assertTrue("Project rule text must still be present",
			result.contains("You MUST NOT modify test files that exist on the base branch"));
	}
}
