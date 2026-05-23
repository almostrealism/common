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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks in the wording of the deduplication audit prompt so the principles
 * established in the {@code feature/agent-prompts} workstream cannot silently
 * regress. The dedup audit's job is to IMPROVE code reuse around the work
 * being done, not just to police newly-added duplication. The escape
 * hatches forbidden by these tests came from a real audit on
 * {@code feature/review-phase} that cleared a 4-copy {@code truncate} helper
 * by appealing to "pre-existing," "net change is zero," and "below the
 * detector threshold." None of those are valid rationales.
 *
 * @author Michael Murray
 */
public class DeduplicationRulePromptTest extends TestSuiteBase {

    private static String prompt() {
        return DeduplicationRule.buildDeduplicationPrompt(
                Collections.singletonList("FooHandler.truncate(String, int)"),
                false, 1, "master");
    }

    // ── New wording: the audit must improve code reuse ───────────────────────

    @Test(timeout = 30000)
    public void promptStatesGoalIsImprovingCodeReuse() {
        String text = prompt();
        assertTrue("Prompt should describe the goal as improving code reuse",
                text.contains("IMPROVE CODE REUSE"));
        assertTrue("Prompt should state the codebase must be left with LESS duplication",
                text.contains("LESS DUPLICATION"));
    }

    @Test(timeout = 30000)
    public void promptRejectsPreExistingRationalization() {
        String text = prompt();
        assertTrue("Prompt should explicitly say pre-existing copies are still in scope",
                text.contains("Pre-existing copies are still duplicates"));
    }

    @Test(timeout = 30000)
    public void promptRejectsDetectorThresholdRationalization() {
        String text = prompt();
        assertTrue("Prompt should explain that detector thresholds are a floor, not a ceiling",
                text.contains("FLOOR, not a CEILING"));
        assertTrue("Prompt should call out the duplicate_code linter by name",
                text.contains("duplicate_code"));
    }

    @Test(timeout = 30000)
    public void promptRejectsNetChangeRationalization() {
        String text = prompt();
        assertTrue("Prompt should reject the \"net change is zero\" framing",
                text.contains("Net change in duplication is zero") ||
                text.contains("net cross-file duplicate count did not increase"));
    }

    // ── Scope boundary ───────────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptDefinesRelatedScope() {
        String text = prompt();
        assertTrue("Prompt should define what \"related to this work\" means",
                text.contains("WHAT \"RELATED TO THIS WORK\" MEANS"));
        assertTrue("Prompt should mention same/adjacent package as a related scope",
                text.contains("same package") && text.contains("adjacent package"));
    }

    @Test(timeout = 30000)
    public void promptIncludesOutOfScopeCounterExample() {
        String text = prompt();
        assertTrue("Prompt should include the OUT OF SCOPE counter-example",
                text.contains("OUT OF SCOPE"));
        assertTrue("Prompt should explicitly say not every discovered duplication is in scope",
                text.contains("not every duplication you happen to discover"));
    }

    @Test(timeout = 30000)
    public void promptIncludesTruncateWorkedExample() {
        String text = prompt();
        assertTrue("Prompt should include the truncate worked example by name",
                text.contains("truncate(String, int)"));
        assertTrue("Prompt should name BazListener and QuxNotifier as pre-existing copies",
                text.contains("BazListener") && text.contains("QuxNotifier"));
    }

    // ── Old escape-hatch wording must be gone ────────────────────────────────

    @Test(timeout = 30000)
    public void promptDoesNotDemandPreBranchExistence() {
        String text = prompt();
        assertFalse("Prompt must not require the duplicate to pre-date the branch",
                text.contains("ALREADY EXISTED in the codebase BEFORE this branch"));
        assertFalse("Prompt must not state that branch-modified copies cannot be duplicates",
                text.contains("That is just the file where the method was introduced"));
    }

    @Test(timeout = 30000)
    public void promptDoesNotInstructAgentToSkipCandidatesOnBranchModifiedFiles() {
        String text = prompt();
        assertFalse("Prompt must not tell the agent to skip candidates whose file is "
                        + "in the branch diff",
                text.contains("they are not a duplicate of each other; skip"));
    }

    // ── Edit rules carried forward ───────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptRetainsGitRevertProhibition() {
        String text = prompt();
        assertTrue("Prompt should still forbid git restore / checkout -- / reset",
                text.contains("NEVER use git restore"));
    }

    @Test(timeout = 30000)
    public void promptForbidsCreatingNewMavenModule() {
        String text = prompt();
        assertTrue("Prompt should forbid spawning a new Maven module to hold the shared helper",
                text.contains("Do NOT create a new Maven module"));
    }

    // ── Truncation indicator preserved ───────────────────────────────────────

    @Test(timeout = 30000)
    public void promptIndicatesTruncationWhenCapped() {
        List<String> capped = Arrays.asList("A.a()", "B.b()", "C.c()");
        String text = DeduplicationRule.buildDeduplicationPrompt(capped, true, 42, "master");
        assertTrue("Prompt should disclose how many of the total methods are shown",
                text.contains("showing 3 of 42 total"));
    }

    // ── Base branch threading ────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void promptUsesConfiguredBaseBranchInDiffCommand() {
        String text = DeduplicationRule.buildDeduplicationPrompt(
                Collections.singletonList("X.y()"), false, 1, "main");
        assertTrue("Prompt should reference origin/main when base branch is main",
                text.contains("git diff origin/main...HEAD --name-only"));
        assertTrue("Prompt should reference origin/main in the log command",
                text.contains("git log origin/main..HEAD --oneline"));
        assertTrue("Prompt should reference origin/main in git show example",
                text.contains("git show origin/main:<path>"));
        assertFalse("Prompt must not hard-code origin/master when base branch differs",
                text.contains("origin/master"));
    }

    @Test(timeout = 30000)
    public void promptFallsBackToMasterWhenBaseBranchNull() {
        String text = DeduplicationRule.buildDeduplicationPrompt(
                Collections.singletonList("X.y()"), false, 1, null);
        assertTrue("Null base branch should resolve to origin/master",
                text.contains("git diff origin/master...HEAD --name-only"));
    }

    @Test(timeout = 30000)
    public void promptFallsBackToMasterWhenBaseBranchBlank() {
        String text = DeduplicationRule.buildDeduplicationPrompt(
                Collections.singletonList("X.y()"), false, 1, "  ");
        assertTrue("Blank base branch should resolve to origin/master",
                text.contains("git diff origin/master...HEAD --name-only"));
    }
}
