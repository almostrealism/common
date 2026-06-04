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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link InstructionPromptBuilder} verifying that conditional
 * sections are included or excluded based on builder configuration.
 */
public class InstructionPromptBuilderTest extends TestSuiteBase {

	/** InstructionPromptBuilder includes user prompt text always. */
	@Test(timeout = 30000)
	public void includesUserPromptAlways() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Fix the authentication bug")
			.build();
		assertTrue("Expected prompt text in output",
			result.contains("Fix the authentication bug"));
	}

	/** InstructionPromptBuilder includes Communication section when workstream URL is set. */
	@Test(timeout = 30000)
	public void includesCommunicationSectionWhenUrlSet() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.build();
		assertTrue("Expected Communication section when workstream URL is set",
			result.contains("## Communication"));
		assertTrue("Expected send_message reference",
			result.contains("send_message"));
	}

	/** InstructionPromptBuilder excludes Communication section when no workstream URL. */
	@Test(timeout = 30000)
	public void excludesMessageSectionWhenNoUrl() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertFalse("Expected no send_message reference without workstream URL",
			result.contains("send_message"));
		assertFalse("Expected no Communication section without workstream URL",
			result.contains("## Communication"));
	}

	/** InstructionPromptBuilder includes Test Integrity Policy when protectTestFiles is true. */
	@Test(timeout = 30000)
	public void includesTestIntegrityWhenProtected() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setProtectTestFiles(true)
			.build();
		assertTrue("Expected Test Integrity Policy section",
			result.contains("Test Integrity Policy"));
	}

	/** InstructionPromptBuilder includes Merge Conflicts section when hasMergeConflicts is true. */
	@Test(timeout = 30000)
	public void includesMergeConflictSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setHasMergeConflicts(true)
			.setBaseBranch("master")
			.setTargetBranch("feature/work")
			.setMergeConflictFiles(Arrays.asList("src/Main.java", "README.md"))
			.build();
		assertTrue("Expected Merge Conflicts section",
			result.contains("## Merge Conflicts"));
		assertTrue("Expected conflict file src/Main.java in output",
			result.contains("src/Main.java"));
		assertTrue("Expected conflict file README.md in output",
			result.contains("README.md"));
	}

	/** InstructionPromptBuilder includes Branch Awareness section when targetBranch is set. */
	@Test(timeout = 30000)
	public void includesBranchCatchupSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setTargetBranch("feature/my-branch")
			.build();
		assertTrue("Expected Branch Awareness section when target branch is set",
			result.contains("Branch Awareness"));
		assertTrue("Expected workstream_context reference",
			result.contains("workstream_context"));
	}

	/** InstructionPromptBuilder includes budget and turn limit when set. */
	@Test(timeout = 30000)
	public void includesBudgetSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setMaxTurns(100)
			.setMaxBudgetUsd(5.0)
			.build();
		assertTrue("Expected budget amount in output",
			result.contains("$5.00"));
		assertTrue("Expected turn limit in output",
			result.contains("100 turns"));
	}

	/** InstructionPromptBuilder includes Planning Document section when set. */
	@Test(timeout = 30000)
	public void includesPlanningDocSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setPlanningDocument("docs/planning/feature-plan.md")
			.build();
		assertTrue("Expected Planning Document section",
			result.contains("## Planning Document"));
		assertTrue("Expected planning document path in output",
			result.contains("docs/planning/feature-plan.md"));
	}

	/** InstructionPromptBuilder includes Dependent Repositories section when paths are set. */
	@Test(timeout = 30000)
	public void includesDependentReposSection() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setTargetBranch("feature/work")
			.setDependentRepoPaths(Arrays.asList(
				"/workspace/project/repo-a",
				"/workspace/project/repo-b"))
			.build();
		assertTrue("Expected Dependent Repositories section",
			result.contains("## Dependent Repositories"));
		assertTrue("Expected first dep repo path in output",
			result.contains("/workspace/project/repo-a"));
		assertTrue("Expected second dep repo path in output",
			result.contains("/workspace/project/repo-b"));
	}

	/** InstructionPromptBuilder excludes Dependent Repositories when paths not set. */
	@Test(timeout = 30000)
	public void excludesDependentReposSectionWhenUnset() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertFalse("Expected no Dependent Repositories section when paths not set",
			result.contains("## Dependent Repositories"));
	}

	/** InstructionPromptBuilder includes inactivity restart preamble when attempt > 0. */
	@Test(timeout = 30000)
	public void includesInactivityRestartPreambleWhenAttemptPositive() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setInactivityRestartAttempt(1)
			.build();
		assertTrue("Expected inactivity restart warning header when attempt > 0",
			result.contains("SESSION RESTARTED -- INACTIVITY TIMEOUT"));
		assertTrue("Expected pgrep -f cause guidance in inactivity preamble",
			result.contains("pgrep -f"));
	}

	/** InstructionPromptBuilder excludes inactivity restart preamble when attempt == 0. */
	@Test(timeout = 30000)
	public void excludesInactivityRestartPreambleWhenAttemptZero() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setInactivityRestartAttempt(0)
			.build();
		assertFalse("Expected no inactivity restart warning when attempt == 0",
			result.contains("SESSION RESTARTED -- INACTIVITY TIMEOUT"));
	}

	// ── Correction-session preamble suppression ─────────────────────────────

	/** Primary session with enforceChanges includes Code Changes Are Required block. */
	@Test(timeout = 30000)
	public void primarySessionWithEnforceChangesIncludesStrictBlock() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setEnforceChanges(true)
			.build();
		assertTrue("Primary session with enforceChanges should include the strict block",
			result.contains("Code Changes Are Required"));
	}

	/** Correction session suppresses enforceChanges strict block. */
	@Test(timeout = 30000)
	public void correctionSessionSuppressesEnforceChangesStrictBlock() {
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setEnforceChanges(true)
			.setCorrectionSession(true)
			.build();
		assertFalse("Correction session must not include the outer enforceChanges strict block",
			result.contains("Code Changes Are Required"));
		assertFalse("Correction session must not include the 'Exiting without code changes' threat",
			result.contains("Exiting without code changes"));
	}

	/** Correction session includes permissive Non-Code Requests block when workstream URL set. */
	@Test(timeout = 30000)
	public void correctionSessionStillIncludesPermissivePromptsWhenWorkstreamUrlSet() {
		// When enforce_changes is true on the outer job but we are inside a
		// correction session, the permissive "no changes is OK" block must
		// appear in place of the strict block so the rule can legitimately
		// exit without changes.
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setEnforceChanges(true)
			.setCorrectionSession(true)
			.build();
		assertTrue("Correction session must include the permissive Non-Code Requests block",
			result.contains("Non-Code Requests"));
		assertTrue("Correction session must include the Justifying No Code Changes block",
			result.contains("Justifying No Code Changes"));
	}

	/** Primary session with enforcementAttempt > 0 includes SESSION RESTARTED -- RETRY. */
	@Test(timeout = 30000)
	public void primarySessionIncludesEnforcementRetryPreamble() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setEnforcementAttempt(2)
			.build();
		assertTrue("Primary session with attempt > 0 must include retry preamble",
			result.contains("SESSION RESTARTED -- RETRY"));
	}

	/** Correction session suppresses enforcement retry preamble. */
	@Test(timeout = 30000)
	public void correctionSessionSuppressesEnforcementRetryPreamble() {
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setEnforcementAttempt(2)
			.setCorrectionSession(true)
			.build();
		assertFalse("Correction session must not include the retry preamble",
			result.contains("SESSION RESTARTED -- RETRY"));
	}

	// ── Harness-feedback invitation ─────────────────────────────────────────

	/** InstructionPromptBuilder includes Feedback to Harness when workstream URL is set. */
	@Test(timeout = 30000)
	public void harnessFeedbackInvitationAppearsWhenWorkstreamUrlSet() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.build();
		assertTrue("Harness feedback section must appear when workstream URL is set",
			result.contains("Feedback to the Harness"));
		assertTrue("Harness feedback must reference activity=\"harness_feedback\"",
			result.contains("harness_feedback"));
	}

	/** InstructionPromptBuilder excludes harness feedback when no workstream URL. */
	@Test(timeout = 30000)
	public void harnessFeedbackInvitationAbsentWithoutWorkstreamUrl() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertFalse("Harness feedback should not appear without a workstream URL",
			result.contains("Feedback to the Harness"));
	}

	/** InstructionPromptBuilder includes harness feedback in correction sessions too. */
	@Test(timeout = 30000)
	public void harnessFeedbackInvitationAppearsInCorrectionSessions() {
		// The feedback invitation should be visible in both primary and
		// correction prompts so agents can flag harness issues from any phase.
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setCorrectionSession(true)
			.build();
		assertTrue("Harness feedback section must also appear in correction prompts",
			result.contains("Feedback to the Harness"));
	}

	/** Harness feedback invitation appears before the user request marker in prompt. */
	@Test(timeout = 30000)
	public void harnessFeedbackInvitationAppearsBeforeUserRequestMarker() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.build();
		int feedbackIdx = result.indexOf("Feedback to the Harness");
		int requestIdx = result.indexOf("--- BEGIN USER REQUEST ---");
		assertTrue("Feedback section should appear in the prompt", feedbackIdx >= 0);
		assertTrue("User request marker should appear in the prompt", requestIdx >= 0);
		assertTrue("Feedback section should precede the user request marker",
			feedbackIdx < requestIdx);
	}

	// ── Git workflow reminder ────────────────────────────────────────────────

	/** InstructionPromptBuilder includes Note on git workflow in every prompt. */
	@Test(timeout = 30000)
	public void gitWorkflowReminderAppearsInEveryPrompt() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.build();
		assertTrue("Git workflow reminder must appear in every prompt",
			result.contains("Note on git workflow"));
		assertTrue("Reminder must state commits land in a single commit",
			result.contains("single commit"));
	}

	/** InstructionPromptBuilder includes git workflow reminder when workstream URL is set. */
	@Test(timeout = 30000)
	public void gitWorkflowReminderAppearsWithWorkstreamUrl() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.build();
		assertTrue("Git workflow reminder must appear when workstream URL is set",
			result.contains("Note on git workflow"));
	}

	/** InstructionPromptBuilder includes git workflow reminder in correction sessions. */
	@Test(timeout = 30000)
	public void gitWorkflowReminderAppearsInCorrectionSession() {
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setCorrectionSession(true)
			.build();
		assertTrue("Git workflow reminder must appear in correction sessions too",
			result.contains("Note on git workflow"));
	}

	/** Git workflow reminder appears before the user request marker in prompt. */
	@Test(timeout = 30000)
	public void gitWorkflowReminderAppearsBeforeUserRequestMarker() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.build();
		int reminderIdx = result.indexOf("Note on git workflow");
		int requestIdx = result.indexOf("--- BEGIN USER REQUEST ---");
		assertTrue("Git workflow reminder should appear in the prompt", reminderIdx >= 0);
		assertTrue("User request marker should appear in the prompt", requestIdx >= 0);
		assertTrue("Git workflow reminder should precede the user request marker",
			reminderIdx < requestIdx);
	}

	/** Git workflow reminder explains that multi-phase prompts still work. */
	@Test(timeout = 30000)
	public void gitWorkflowReminderExplainsMultiPhaseTreatment() {
		String result = new InstructionPromptBuilder()
			.setPrompt("test")
			.build();
		assertTrue("Reminder must explain that multi-phase prompts still work",
			result.contains("logically separate phases"));
	}

	// ── Language requirement ─────────────────────────────────────────────────

	/** InstructionPromptBuilder includes language requirement in primary prompts. */
	@Test(timeout = 30000)
	public void languageRequirementAppearsInPrimaryPrompt() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.build();
		assertTrue("Language requirement must appear in every primary prompt",
			result.contains("All output must be in English"));
		assertTrue("Language requirement must explicitly prohibit non-English output",
			result.contains("Do not write in any other language"));
	}

	/** Language requirement appears near top of prompt before user request marker. */
	@Test(timeout = 30000)
	public void languageRequirementAppearsNearTopOfPrompt() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.build();
		int langIdx = result.indexOf("All output must be in English");
		int requestIdx = result.indexOf("--- BEGIN USER REQUEST ---");
		assertTrue("Language requirement should appear in the prompt", langIdx >= 0);
		assertTrue("User request marker should appear in the prompt", requestIdx >= 0);
		assertTrue("Language requirement should precede the user request marker",
			langIdx < requestIdx);
	}

	/** InstructionPromptBuilder includes language requirement in correction sessions too. */
	@Test(timeout = 30000)
	public void languageRequirementAppearsInCorrectionSession() {
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setCorrectionSession(true)
			.build();
		assertTrue("Language requirement must also appear in correction sessions",
			result.contains("All output must be in English"));
	}

	// TODO(review): If CI agent-commit validation forbids modifying base-branch test files,
	// consider moving these Working Efficiently tests into a new test class instead of editing this one.

	/** InstructionPromptBuilder includes the Working Efficiently section in primary prompts. */
	@Test(timeout = 30000)
	public void workingEfficientlySectionAppearsInPrimaryPrompt() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.build();
		assertTrue("Working Efficiently section must appear in primary prompts",
			result.contains("## Working Efficiently"));
	}

	/** Working Efficiently section mentions all eight principles. */
	@Test(timeout = 30000)
	public void workingEfficientlySectionIncludesAllEightPrinciples() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.build();
		// Phrase anchors that uniquely identify each principle in the rendered
		// prompt. Each one is a short, distinctive substring from the principle's
		// lead clause; together they assert all eight points are present.
		String[] anchors = new String[] {
			"Read small files whole; don't grep-thrash",
			"One comprehensive git query beats many",
			"Treat confirmed facts as established",
			"Keep an outline of large files",
			"Parallelize independent work",
			"Recall before re-deriving",
			"Hypothesis tree for diagnosis",
			"Set up toolchains in the right order"
		};
		for (String anchor : anchors) {
			assertTrue("Working Efficiently section must include principle: " + anchor,
				result.contains(anchor));
		}
	}

	/** Working Efficiently section appears even when no target branch is set. */
	@Test(timeout = 30000)
	public void workingEfficientlySectionAppearsWithoutTargetBranch() {
		String result = new InstructionPromptBuilder()
			.setPrompt("Do some work")
			.setTargetBranch("")
			.build();
		assertTrue("Working Efficiently section must appear without a target branch",
			result.contains("## Working Efficiently"));
	}

	/** Working Efficiently section appears in correction-session prompts. */
	@Test(timeout = 30000)
	public void workingEfficientlySectionAppearsInCorrectionSession() {
		String result = new InstructionPromptBuilder()
			.setPrompt("rule correction prompt")
			.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
			.setCorrectionSession(true)
			.build();
		assertTrue("Working Efficiently section must also appear in correction sessions",
			result.contains("## Working Efficiently"));
	}

	/** Working Efficiently section appears before the user request marker. */
	@Test(timeout = 30000)
	public void workingEfficientlySectionAppearsBeforeUserRequestMarker() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.build();
		int sectionIdx = result.indexOf("## Working Efficiently");
		int requestIdx = result.indexOf("--- BEGIN USER REQUEST ---");
		assertTrue("Working Efficiently section should appear in the prompt", sectionIdx >= 0);
		assertTrue("User request marker should appear in the prompt", requestIdx >= 0);
		assertTrue("Working Efficiently section should precede the user request marker",
			sectionIdx < requestIdx);
	}

	/** Working Efficiently section follows Branch Awareness when a target branch is set. */
	@Test(timeout = 30000)
	public void workingEfficientlySectionFollowsBranchAwareness() {
		String result = new InstructionPromptBuilder()
			.setPrompt("task body")
			.setTargetBranch("feature/my-branch")
			.build();
		int branchIdx = result.indexOf("Branch Awareness");
		int workingIdx = result.indexOf("## Working Efficiently");
		assertTrue("Branch Awareness section should appear when target branch is set", branchIdx >= 0);
		assertTrue("Working Efficiently section should appear in the prompt", workingIdx >= 0);
		assertTrue("Working Efficiently section should follow Branch Awareness",
			workingIdx > branchIdx);
	}
}
