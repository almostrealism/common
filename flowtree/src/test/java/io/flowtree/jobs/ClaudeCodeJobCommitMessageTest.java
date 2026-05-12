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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the commit-message production and preservation mechanics in
 * {@link ClaudeCodeJob} and {@link CommitMessageRule}.
 *
 * <p>The tests use a spy subclass of {@link ClaudeCodeJob} that overrides
 * {@link ClaudeCodeJob#executeSingleRun()} to avoid launching a real Claude
 * Code subprocess.</p>
 */
public class ClaudeCodeJobCommitMessageTest extends TestSuiteBase {

    /** Temporary working directory recreated for each test. */
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("commit-msg-test");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void writeCommitTxt(String content) throws IOException {
        Files.write(tempDir.resolve("commit.txt"), content.getBytes(StandardCharsets.UTF_8));
    }

    private String readCommitTxt() throws IOException {
        Path p = tempDir.resolve("commit.txt");
        if (!Files.exists(p)) return null;
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Creates a spy {@link ClaudeCodeJob} whose {@code executeSingleRun()} performs
     * {@code sessionAction} instead of launching a real subprocess.
     */
    private ClaudeCodeJob spyJob(String prompt, Runnable sessionAction) {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", prompt) {
            @Override
            void executeSingleRun() {
                // Delete commit.txt as the real executeSingleRun() does.
                try {
                    Files.deleteIfExists(tempDir.resolve("commit.txt"));
                } catch (IOException e) {
                    // ignore in test
                }
                sessionAction.run();
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        return job;
    }

    // ── CommitMessageRule.isViolated() ────────────────────────────────────────

    @Test(timeout = 30000)
    public void commitMessageRuleViolatedWhenCommitTxtMissing() {
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setWorkingDirectory(tempDir.toString());
        assertTrue("Rule must be violated when commit.txt is absent",
                rule.isViolated(job));
    }

    @Test(timeout = 30000)
    public void commitMessageRuleViolatedWhenCommitTxtEmpty() throws IOException {
        writeCommitTxt("   ");
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setWorkingDirectory(tempDir.toString());
        assertTrue("Rule must be violated when commit.txt is blank",
                rule.isViolated(job));
    }

    @Test(timeout = 30000)
    public void commitMessageRuleViolatedWhenCommitTxtEqualsTaskPrompt() throws IOException {
        String prompt = "Fix the authentication bug in UserService";
        writeCommitTxt(prompt);
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", prompt);
        job.setWorkingDirectory(tempDir.toString());
        assertTrue("Rule must be violated when commit.txt is a copy of the task prompt",
                rule.isViolated(job));
    }

    @Test(timeout = 30000)
    public void commitMessageRuleNotViolatedOnNormalMessage() throws IOException {
        writeCommitTxt("Fix auth bug: validate token expiry before session creation\n\nAdded expiry check in UserService.authenticate().");
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "Fix the authentication bug in UserService");
        job.setWorkingDirectory(tempDir.toString());
        assertFalse("Rule must not be violated on a normal agent-authored message",
                rule.isViolated(job));
    }

    @Test(timeout = 30000)
    public void commitMessageRuleViolatedWhenAgentEchoesOwnPrompt() throws IOException {
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "some task");
        job.setWorkingDirectory(tempDir.toString());

        // First call builds the correction prompt and stores it internally.
        String correctionPrompt = rule.buildCorrectionPrompt(job);
        assertNotNull(correctionPrompt);

        // Agent just copies the correction prompt into commit.txt.
        writeCommitTxt(correctionPrompt);

        assertTrue("Rule must be violated when agent echoes the correction prompt",
                rule.isViolated(job));
    }

    @Test(timeout = 30000)
    public void commitMessageRuleMaxRetriesIsTwo() {
        assertEquals(2, CommitMessageRule.MAX_RETRIES);
        CommitMessageRule rule = new CommitMessageRule();
        assertEquals(2, rule.getMaxRetries());
    }

    @Test(timeout = 30000)
    public void commitMessageRuleNameIsCommitMessage() {
        assertEquals("commit-message", new CommitMessageRule().getName());
    }

    // ── CommitMessageRule.onCorrectionAttempted ────────────────────────────────

    @Test(timeout = 30000)
    public void onCorrectionAttemptedSetsRecoveredSourceWhenResolved() throws IOException {
        writeCommitTxt("Add validation for pushed-tools server names");
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "some task");
        job.setWorkingDirectory(tempDir.toString());
        assertFalse("Pre-condition: rule is not violated", rule.isViolated(job));
        rule.onCorrectionAttempted(job);
        assertEquals(CommitMessageRule.SOURCE_RECOVERED, job.getCommitMessageSource());
    }

    @Test(timeout = 30000)
    public void onCorrectionAttemptedDoesNotSetSourceWhenStillViolated() {
        CommitMessageRule rule = new CommitMessageRule();
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "some task");
        job.setWorkingDirectory(tempDir.toString());
        assertTrue("Pre-condition: rule is violated (no commit.txt)", rule.isViolated(job));
        rule.onCorrectionAttempted(job);
        assertNull("Source should not be set when violation is not resolved",
                job.getCommitMessageSource());
    }

    // ── getCommitMessage() source tagging ─────────────────────────────────────

    @Test(timeout = 30000)
    public void getCommitMessageSourceIsAgentWhenCommitTxtPresent() throws IOException {
        writeCommitTxt("Add validation for server name format");
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setWorkingDirectory(tempDir.toString());
        String msg = job.getCommitMessage();
        assertEquals("Add validation for server name format", msg);
        assertEquals("agent", job.getCommitMessageSource());
    }

    @Test(timeout = 30000)
    public void getCommitMessageSourceIsPromptFallbackWhenCommitTxtMissing() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
        job.setWorkingDirectory(tempDir.toString());
        String msg = job.getCommitMessage();
        assertNotNull(msg);
        assertTrue("Fallback message should start with 'Claude Code:'",
                msg.startsWith("Claude Code:"));
        assertEquals("prompt_fallback", job.getCommitMessageSource());
    }

    // ── commit.txt preservation across correction sessions ───────────────────

    /**
     * Verifies that a {@code commit.txt} written by the primary session is preserved
     * after a no-op correction session (the correction agent makes no file changes).
     */
    @Test(timeout = 30000)
    public void commitTxtPreservedAcrossNoOpCorrectionSession() throws IOException {
        writeCommitTxt("Primary session commit message");

        // Spy: the correction-session agent does nothing (no-op).
        ClaudeCodeJob job = spyJob("task prompt", () -> { /* no-op */ });

        // Simulate a correction session (the real method is protected/accessible here).
        job.runCorrectionSession("correction prompt", "deduplication");

        assertEquals("Primary session commit message", readCommitTxt());
    }

    /**
     * Verifies that a {@code commit.txt} written by the primary session is preserved
     * even when the correction session agent writes its own commit.txt.
     */
    @Test(timeout = 30000)
    public void commitTxtPreservedWhenCorrectionSessionWritesNewOne() throws IOException {
        writeCommitTxt("Primary session commit message");

        // Spy: the correction-session agent writes a different commit.txt.
        ClaudeCodeJob job = spyJob("task prompt", () -> {
            try {
                Files.write(tempDir.resolve("commit.txt"),
                        "Correction session commit".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        job.runCorrectionSession("correction prompt", "deduplication");

        assertEquals("Primary session commit message", readCommitTxt());
    }

    /**
     * Verifies that when the primary session did NOT write a {@code commit.txt},
     * a correction session that writes one keeps its value (the fix for the
     * previous bug where the correction session's commit.txt was deleted).
     */
    @Test(timeout = 30000)
    public void correctionSessionCommitTxtKeptWhenPrimarySessionHadNone() throws IOException {
        // No commit.txt from the primary session.

        // Spy: correction session writes a commit.txt.
        ClaudeCodeJob job = spyJob("task prompt", () -> {
            try {
                Files.write(tempDir.resolve("commit.txt"),
                        "Commit message from correction session".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        job.runCorrectionSession("correction prompt", "commit-message");

        assertEquals("Commit message from correction session", readCommitTxt());
    }

    // ── CommitMessageRule correction prompt is not wrapped in enforce_changes ─

    /**
     * The {@link CommitMessageRule} correction prompt must be treated as a
     * correction session — the harness must suppress the outer
     * {@code enforce_changes} strict block so the rule's self-contained prompt
     * is not contradicted.
     *
     * <p>This is enforced by the existing {@code setCorrectionSession(true)} path
     * in {@link InstructionPromptBuilder}, which the enforcement loop triggers via
     * {@link ClaudeCodeJob#setCurrentActivity(String)}.  The test verifies the
     * wiring: when {@code currentActivity} is set to {@code "commit-message"},
     * the built prompt must NOT include the "Code Changes Are Required" section.</p>
     */
    @Test(timeout = 30000)
    public void commitMessageRuleCorrectionPromptDoesNotIncludeEnforceChangesBlock() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do the work");
        job.setEnforceChanges(true);
        job.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1");

        // Simulate what runCorrectionSession() sets before invoking the agent.
        job.setCurrentActivity("commit-message");

        String correctionPrompt = job.buildInstructionPrompt();
        assertFalse("CommitMessageRule correction session must not include 'Code Changes Are Required'",
                correctionPrompt.contains("Code Changes Are Required"));
        assertFalse("CommitMessageRule correction session must not include the 'Exiting without code changes' threat",
                correctionPrompt.contains("Exiting without code changes"));
        assertTrue("CommitMessageRule correction session must include permissive 'Non-Code Requests' block",
                correctionPrompt.contains("Non-Code Requests"));
    }

    // ── InstructionPromptBuilder — commit.txt instruction ────────────────────

    /**
     * The commit.txt write instruction must appear in primary-session prompts
     * when a target branch is set.
     */
    @Test(timeout = 30000)
    public void primarySessionPromptIncludesCommitTxtInstruction() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do the work");
        job.setTargetBranch("feature/my-branch");

        String prompt = job.buildInstructionPrompt();
        assertTrue("Primary prompt must include commit.txt instruction",
                prompt.contains("commit.txt"));
        assertTrue("Primary prompt must explain commit message format",
                prompt.contains("Before You Finish"));
    }

    /**
     * The commit.txt write instruction must NOT appear in correction-session prompts
     * (where currentActivity is set), as those sessions have their own focused task.
     */
    @Test(timeout = 30000)
    public void correctionSessionPromptOmitsCommitTxtInstruction() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do the work");
        job.setTargetBranch("feature/my-branch");
        job.setWorkstreamUrl("http://controller:8080/api/workstreams/ws1");
        job.setCurrentActivity("deduplication");

        String prompt = job.buildInstructionPrompt();
        assertFalse("Correction-session prompt must not include 'Before You Finish' commit block",
                prompt.contains("Before You Finish"));
    }

    /**
     * The commit.txt write instruction must NOT appear when no target branch is
     * configured (no git management active — no commit will be made).
     */
    @Test(timeout = 30000)
    public void promptWithoutTargetBranchOmitsCommitTxtInstruction() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do the work");
        // No target branch set.
        String prompt = job.buildInstructionPrompt();
        assertFalse("Prompt without target branch must not include 'Before You Finish' commit block",
                prompt.contains("Before You Finish"));
    }

    // ── ClaudeCodeJobEvent — commitMessageSource ─────────────────────────────

    @Test(timeout = 30000)
    public void commitMessageSourcePropagatesIntoEventDefault() {
        ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("j1", "desc");
        assertNull("Default commitMessageSource must be null", event.getCommitMessageSource());
    }

    @Test(timeout = 30000)
    public void commitMessageSourcePropagatesIntoEventViaBuilder() {
        ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("j1", "desc");
        event.withCommitMessageSource("agent");
        assertEquals("agent", event.getCommitMessageSource());
    }

    @Test(timeout = 30000)
    public void commitMessageSourceAppearsInEventJson() {
        ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("j1", "desc");
        event.withCommitMessageSource("prompt_fallback");
        String json = event.toJson();
        assertTrue("JSON must include commitMessageSource field",
                json.contains("commitMessageSource"));
        assertTrue("JSON must include the source value",
                json.contains("prompt_fallback"));
    }
}
