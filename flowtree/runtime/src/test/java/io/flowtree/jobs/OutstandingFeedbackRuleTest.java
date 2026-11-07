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
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves {@link OutstandingFeedbackRule} holds a pass that recorded an
 * unanswered reviewer finding: the rule reports a violation, and a job whose
 * working tree carries the scratch ledger reports {@code DEGRADED} rather than
 * {@code SUCCESS} — the "a pass ending with an unanswered thread is not SUCCESS"
 * case of the PR #866 remediation.
 */
public class OutstandingFeedbackRuleTest extends TestSuiteBase {

    /** Temporary working directory holding the scratch ledger, deleted after each test. */
    private Path workingDir;

    /** Recursively deletes the temporary working directory. */
    @After
    public void tearDown() throws IOException {
        if (workingDir != null && Files.exists(workingDir)) {
            try (Stream<Path> walk = Files.walk(workingDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /** Writes the scratch ledger with the given content under a fresh working directory. */
    private String workingDirWithLedger(String content) throws IOException {
        workingDir = Files.createTempDirectory("outstanding-feedback-test");
        Path ledger = workingDir.resolve(OutstandingFeedbackRule.LEDGER);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, content, StandardCharsets.UTF_8);
        return workingDir.toString();
    }

    /** A ledger naming a finding reports an unaccounted-for finding. */
    @Test(timeout = 30000)
    public void anUnansweredFindingInTheLedgerIsAViolation() throws Exception {
        String dir = workingDirWithLedger("LaneSum output store is written by every SIMD lane\n");
        assertTrue(OutstandingFeedbackRule.hasUnaccountedFeedback(dir));
    }

    /** A ledger of only blank lines accounts for everything. */
    @Test(timeout = 30000)
    public void anEmptyLedgerIsNotAViolation() throws Exception {
        String dir = workingDirWithLedger("\n   \n");
        assertFalse(OutstandingFeedbackRule.hasUnaccountedFeedback(dir));
    }

    /** No ledger, and no working directory, both read as nothing outstanding. */
    @Test(timeout = 30000)
    public void anAbsentLedgerIsNotAViolation() throws Exception {
        workingDir = Files.createTempDirectory("outstanding-feedback-test");
        assertFalse(OutstandingFeedbackRule.hasUnaccountedFeedback(workingDir.toString()));
        assertFalse(OutstandingFeedbackRule.hasUnaccountedFeedback(null));
    }

    /** The correction prompt names the findings that remain to be answered. */
    @Test(timeout = 30000)
    public void theCorrectionPromptListsTheOutstandingFindings() throws Exception {
        String dir = workingDirWithLedger("CompiledKernelStructureContext accepts laneCount < 1\n");
        CodingAgentJob job = new CodingAgentJob("task", "task");
        job.setWorkingDirectory(dir);
        OutstandingFeedbackRule rule = new OutstandingFeedbackRule();
        assertTrue(rule.isViolated(job));
        String prompt = rule.buildCorrectionPrompt(job);
        assertTrue("prompt must name the finding: " + prompt,
                prompt.contains("CompiledKernelStructureContext accepts laneCount < 1"));
    }

    /** A pass whose ledger still names a finding reports DEGRADED, not SUCCESS. */
    @Test(timeout = 30000)
    public void aPassEndingWithAnUnansweredThreadIsNotSuccess() throws Exception {
        String dir = workingDirWithLedger("Unresolved Copilot thread: data race on LaneSum\n");
        CodingAgentJob job = new CodingAgentJob("task", "task");
        job.setWorkingDirectory(dir);

        CodingAgentJobEvent event = job.createEvent(null);
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertTrue("message must name the feedback: " + event.getErrorMessage(),
                event.getErrorMessage().toLowerCase().contains("feedback"));
    }

    /** A pass that answered every finding (empty ledger) stays SUCCESS. */
    @Test(timeout = 30000)
    public void aPassWithEveryFindingAnsweredStaysSuccess() throws Exception {
        String dir = workingDirWithLedger("");
        CodingAgentJob job = new CodingAgentJob("task", "task");
        job.setWorkingDirectory(dir);

        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }
}
