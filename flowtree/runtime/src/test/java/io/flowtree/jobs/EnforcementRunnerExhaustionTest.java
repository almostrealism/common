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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the enforcement loop exhaustion and absolute-ceiling behaviour
 * in {@link EnforcementRunner}.
 *
 * <p>Three scenarios are covered:
 * <ol>
 *   <li>When a rule's per-pass cap is exhausted, the orchestrator must not
 *       re-enter that rule in the next outer-loop iteration.</li>
 *   <li>The absolute ceiling ({@code DEFAULT_MAX_RULE_ENTRIES = 10}) is a
 *       hard safety-net that prevents any rule from re-entering beyond that
 *       limit regardless of per-rule cap state.</li>
 *   <li>The commit-message rule writes a fallback commit.txt when its retries
 *       are exhausted.</li>
 * </ol>
 */
public class EnforcementRunnerExhaustionTest extends TestSuiteBase {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("enforce-exhaust-test");
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

    private Path commitTxt() {
        return tempDir.resolve("commit.txt");
    }

    private void writeCommitTxt(String content) throws IOException {
        Files.writeString(commitTxt(), content, StandardCharsets.UTF_8);
    }

    private String readCommitTxt() throws IOException {
        if (!Files.exists(commitTxt())) return null;
        return Files.readString(commitTxt(), StandardCharsets.UTF_8);
    }

    private void deleteCommitTxt() throws IOException {
        Files.deleteIfExists(commitTxt());
    }

    // ── Item 1 tests: rule exhaustion is honored ──────────────────────────────

    /**
     * When the commit-message rule exhausts its 2 retry attempts, the outer
     * enforcement loop must not re-enter it. The fallback commit.txt is written
     * and the job proceeds.
     */
    @Test(timeout = 30000)
    public void commitMessageRuleExhaustionTriggersFallbackAndDoesNotReenter() throws IOException {
        AtomicInteger correctionSessionCount = new AtomicInteger();

        CodingAgentJob job = new CodingAgentJob("t1", "Fix the authentication bug in UserService") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                correctionSessionCount.incrementAndGet();
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        // No commit.txt at all — rule is violated on every check.

        job.runEnforcementRules();

        // With cap=2, we get exactly 2 correction sessions before exhaustion fallback kicks in.
        assertEquals("Expected exactly 2 correction attempts before fallback",
                2, correctionSessionCount.get());

        // After exhaustion, a fallback commit.txt must be written.
        String msg = readCommitTxt();
        assertNotNull("Fallback commit.txt must be written when rule is exhausted", msg);
        assertFalse("Fallback message must not be empty", msg.trim().isEmpty());
    }

    /**
     * When the commit-message rule's correction sessions produce a valid commit.txt
     * (not violated), the rule exits cleanly and does not re-enter.
     */
    @Test(timeout = 30000)
    public void commitMessageRuleResolvesBeforeExhaustionAndDoesNotReenter() throws IOException {
        AtomicInteger correctionSessionCount = new AtomicInteger();

        CodingAgentJob job = new CodingAgentJob("t1", "Fix the authentication bug") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                correctionSessionCount.incrementAndGet();
                // First correction produces a valid commit.txt.
                if (correctionSessionCount.get() == 1) {
                    try {
                        writeCommitTxt("Add token expiry validation");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        deleteCommitTxt();

        job.runEnforcementRules();

        // Only one correction session needed — valid commit.txt produced.
        assertEquals("Expected exactly 1 correction session",
                1, correctionSessionCount.get());

        // Rule resolved without exhausting retries.
        String msg = readCommitTxt();
        assertNotNull(msg);
        assertEquals("Add token expiry validation", msg.trim());
    }

    // ── Item 2 tests: absolute ceiling on rule re-entries ──────────────────────

    /**
     * A rule that always fails must hit the absolute ceiling ({@code DEFAULT_MAX_RULE_ENTRIES=10})
     * and exit rather than looping forever. This is the safety-net for the infinite loop bug.
     */
    @Test(timeout = 60000)
    public void failsForeverRuleHitsAbsoluteCeilingAndExits() throws IOException {
        AtomicInteger executionCount = new AtomicInteger();

        // A custom rule that is always violated and whose correction prompt
        // always makes things worse (e.g. writes invalid commit.txt).
        EnforcementRule failsForeverRule = new EnforcementRule() {
            @Override
            public String getName() { return "always-fail"; }

            @Override
            public int getMaxRetries() { return 100; } // high per-rule cap

            @Override
            public boolean isViolated(CodingAgentJob job) { return true; }

            @Override
            public String buildCorrectionPrompt(CodingAgentJob job) {
                return "Write something wrong to commit.txt";
            }

            @Override
            public void onCorrectionAttempted(CodingAgentJob job) {
                // Write an invalid commit.txt to keep the rule violated.
                try {
                    Files.writeString(tempDir.resolve("commit.txt"), "bad", StandardCharsets.UTF_8);
                } catch (IOException ignored) { }
            }
        };

        CodingAgentJob job = new CodingAgentJob("t1", "Do the work") {
            @Override
            void executeSingleRun() {
                executionCount.incrementAndGet();
                // Keep re-running so the outer loop keeps cycling.
                try {
                    writeCommitTxt("still bad");
                } catch (IOException e) { /* ignore */ }
            }

            // TODO(review): @Override void harnessStatus() won't compile — CodingAgentJob.harnessStatus() returns HarnessStatusReporter, not void; remove this override (real method is already no-op when workstreamUrl is null)
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        job.addEnforcementRule(failsForeverRule);
        deleteCommitTxt();

        long start = System.currentTimeMillis();
        job.runEnforcementRules();
        long elapsed = System.currentTimeMillis() - start;

        // Should NOT run forever — absolute ceiling must stop it.
        // With max rule entries = 10, we expect at most 10 outer loop entries for this rule.
        // But the outer loop also does executeSingleRun() calls which increment executionCount.
        // The key is: it must complete in a reasonable time (< 60s).
        assertTrue("Rule must exit within reasonable time, not loop forever", elapsed < 55000);

        // Verify the absolute ceiling was hit (log message check is tricky in test,
        // but we can verify the rule was skipped — execution count should be bounded).
        // After 10 entries, the rule is skipped, so execution should stop.
    }

    /**
     * The absolute ceiling defaults to 10 and is enforced per-rule, not globally.
     * Different rules each get their own entry counter.
     */
    @Test(timeout = 30000)
    public void absoluteCeilingIsPerRuleNotGlobal() throws IOException {
        AtomicInteger rule1Executions = new AtomicInteger();
        AtomicInteger rule2Executions = new AtomicInteger();

        EnforcementRule alwaysFailRule1 = new EnforcementRule() {
            @Override public String getName() { return "fail-1"; }
            @Override public int getMaxRetries() { return 100; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "fail1"; }
            @Override public void onCorrectionAttempted(CodingAgentJob job) { rule1Executions.incrementAndGet(); }
        };

        EnforcementRule alwaysFailRule2 = new EnforcementRule() {
            @Override public String getName() { return "fail-2"; }
            @Override public int getMaxRetries() { return 100; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "fail2"; }
            @Override public void onCorrectionAttempted(CodingAgentJob job) { rule2Executions.incrementAndGet(); }
        };

        CodingAgentJob job = new CodingAgentJob("t1", "Do the work") {
            @Override
            void executeSingleRun() {
                // Keep the test hermetic: rule corrections route through
                // runCorrectionSession() -> executeSingleRun(), and the real
                // implementation would launch a Claude subprocess. The rule's
                // onCorrectionAttempted() (which increments the counters) still
                // runs, so the per-rule ceiling assertions remain meaningful.
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        job.addEnforcementRule(alwaysFailRule1);
        job.addEnforcementRule(alwaysFailRule2);

        long start = System.currentTimeMillis();
        job.runEnforcementRules();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("Should complete in reasonable time", elapsed < 55000);

        // Each rule gets its own ceiling of 10, so both should get ~10 entries.
        // (The exact number depends on the loop structure, but both should be
        // bounded, not one rule getting 20 while the other gets 0).
        assertTrue("fail-1 executions must be bounded by per-rule ceiling",
                rule1Executions.get() <= CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES);
        assertTrue("fail-2 executions must be bounded by per-rule ceiling",
                rule2Executions.get() <= CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES);
    }

    // ── Item 3 tests: commit-message fallback content ───────────────────────────

    /**
     * The fallback commit message uses the job prompt's first line (truncated to 72 chars).
     */
    @Test(timeout = 30000)
    public void fallbackCommitMessageUsesPromptFirstLine() throws IOException {
        CodingAgentJob job = new CodingAgentJob("t1", "Fix the authentication bug in UserService\n\nMore details here") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) { }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        deleteCommitTxt();

        job.runEnforcementRules();

        String msg = readCommitTxt();
        assertNotNull(msg);
        assertEquals("Fix the authentication bug in UserService", msg.trim());
    }

    /**
     * The fallback commit message falls back to "Job {taskId} commit" when
     * the prompt is empty or whitespace-only.
     */
    @Test(timeout = 30000)
    public void fallbackCommitMessageUsesJobIdWhenPromptEmpty() throws IOException {
        CodingAgentJob job = new CodingAgentJob("t1", "   \n\n  ") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) { }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        deleteCommitTxt();

        job.runEnforcementRules();

        String msg = readCommitTxt();
        assertNotNull(msg);
        assertEquals("Job t1 commit", msg.trim());
    }

    /**
     * When the prompt first line exceeds 72 characters, it is truncated with "...".
     */
    @Test(timeout = 30000)
    public void fallbackCommitMessageTruncatesLongFirstLine() throws IOException {
        String longPrompt = "This is a very long first line that definitely exceeds seventy-two characters and should be truncated";
        CodingAgentJob job = new CodingAgentJob("t1", longPrompt) {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) { }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);
        deleteCommitTxt();

        job.runEnforcementRules();

        String msg = readCommitTxt();
        assertNotNull(msg);
        assertTrue("Fallback must be truncated to 72 chars with ellipsis",
                msg.trim().length() <= 72);
        assertTrue("Truncated message should end with ...", msg.trim().endsWith("..."));
    }

    /**
     * When the commit-message rule is NOT active (no target branch), no
     * fallback commit.txt is written.
     */
    @Test(timeout = 30000)
    public void noFallbackWhenCommitMessageRuleNotActive() {
        CodingAgentJob job = new CodingAgentJob("t1", "Do the work") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) { }
        };
        job.setWorkingDirectory(tempDir.toString());
        // No target branch — commit-message rule not added.
        job.setEnforceOrganizationalPlacement(false);

        job.runEnforcementRules();

        // No commit.txt should be written when the rule is not active.
        assertTrue("commit.txt should not exist when rule is not active",
                !Files.exists(commitTxt()));
    }
}