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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link EnforcementRunner} honours a rule's
 * {@link EnforcementRule#classifyViolation violation classification}: an
 * environment-determined violation is retired without a single correction
 * session, and the reason it was retired reaches the completion event, while an
 * agent-fixable violation is still retried as before.
 */
public class EnforcementRunnerViolationClassificationTest extends TestSuiteBase {

    /** Working-directory sandbox, recreated for each test. */
    private Path tempDir;

    /** Creates a fresh temporary working directory before each test. */
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("enforce-classify-test");
    }

    /** Recursively deletes the temporary directory after each test. */
    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /**
     * A rule whose violation is classified environment-determined must run zero
     * correction sessions, and the retirement reason it reported must appear on
     * the completion event so it is visible in {@code workstream_get_job} and
     * {@code workstream_context}.
     */
    @Test(timeout = 30000)
    public void environmentDeterminedViolationIsNotRetriedAndReasonIsOnEvent() {
        AtomicInteger corrections = new AtomicInteger();

        EnforcementRule envRule = new EnforcementRule() {
            @Override public String getName() { return "needs-missing-library"; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "install the library"; }
            @Override public int getMaxRetries() { return 5; }

            @Override
            public ViolationClassification classifyViolation(CodingAgentJob job) {
                return ViolationClassification.environmentDetermined(
                        "required native library libfoo is not installed on this host");
            }
        };

        CodingAgentJob job = new CodingAgentJob("env-1", "Build the thing") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                corrections.incrementAndGet();
            }

            @Override
            void executeSingleRun() {
                corrections.incrementAndGet();
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setEnforceOrganizationalPlacement(false);
        job.addEnforcementRule(envRule);

        job.runEnforcementRules();

        assertEquals("An environment-determined violation must not spend any correction session",
                0, corrections.get());

        CodingAgentJobEvent event = job.createEvent(null);
        String message = event.getErrorMessage();
        assertNotNull("The retirement reason must be recorded on the completion event", message);
        assertTrue("The event must name the retired rule: " + message,
                message.contains("needs-missing-library"));
        assertTrue("The event must say the violation was environment-determined: " + message,
                message.contains("environment-determined"));
        assertTrue("The event must carry the rule's reason: " + message,
                message.contains("libfoo"));
    }

    /**
     * A violation that starts agent-fixable but becomes environment-determined
     * after a correction must stop being retried the moment it does: the runner
     * re-evaluates the classification before every attempt, so it spends exactly
     * the one session that changed the situation and then retires the rule with
     * the environment-determined reason — it does not burn the rest of the cap
     * re-asking a question whose answer can no longer change.
     */
    @Test(timeout = 30000)
    public void classificationIsReevaluatedSoARuleThatTurnsEnvironmentDeterminedStops() {
        AtomicInteger corrections = new AtomicInteger();

        EnforcementRule rule = new EnforcementRule() {
            private boolean corrected = false;

            @Override public String getName() { return "turns-environment-determined"; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "try once"; }
            @Override public int getMaxRetries() { return 3; }
            @Override public void onCorrectionAttempted(CodingAgentJob job) { corrected = true; }

            @Override
            public ViolationClassification classifyViolation(CodingAgentJob job) {
                return corrected
                        ? ViolationClassification.environmentDetermined(
                                "the correction moved the change into a binary file")
                        : ViolationClassification.agentFixable();
            }
        };

        CodingAgentJob job = new CodingAgentJob("reclassify-1", "Do the work") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                corrections.incrementAndGet();
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setEnforceOrganizationalPlacement(false);
        job.addEnforcementRule(rule);

        job.runEnforcementRules();

        assertEquals("Exactly one correction should run before the violation turns"
                + " environment-determined", 1, corrections.get());

        CodingAgentJobEvent event = job.createEvent(null);
        String message = event.getErrorMessage();
        assertNotNull("The retirement reason must be recorded on the completion event", message);
        assertTrue("The event must name the retired rule: " + message,
                message.contains("turns-environment-determined"));
        assertTrue("The event must say the violation became environment-determined: " + message,
                message.contains("environment-determined"));
        assertTrue("The event must carry the rule's reason: " + message,
                message.contains("binary file"));
    }

    /**
     * When the job-wide total-attempt ceiling stops a rule before the rule has
     * reached its own per-pass cap, the rule is left violated — and the reason
     * it stopped must still reach the completion event, not merely the log. A
     * rule with a per-rule cap equal to {@link CodingAgentJob#DEFAULT_MAX_RULE_ENTRIES}
     * that re-enters pass after pass eventually hits the global ceiling of
     * {@link CodingAgentJob#DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS} mid-pass, so
     * the final pass exits with fewer than its cap of attempts: exactly the path
     * that previously only logged a warning.
     */
    @Test(timeout = 30000)
    public void globalCeilingRetirementReasonReachesTheEvent() {
        AtomicInteger corrections = new AtomicInteger();

        EnforcementRule rule = new EnforcementRule() {
            @Override public String getName() { return "never-resolves"; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "try again"; }
            @Override public int getMaxRetries() { return CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES; }
        };

        CodingAgentJob job = new CodingAgentJob("ceiling-1", "Do the work") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                corrections.incrementAndGet();
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setEnforceOrganizationalPlacement(false);
        job.addEnforcementRule(rule);

        job.runEnforcementRules();

        assertEquals("The job-wide ceiling must bound the total correction sessions",
                CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS, corrections.get());

        CodingAgentJobEvent event = job.createEvent(null);
        String message = event.getErrorMessage();
        assertNotNull("The retirement reason must be recorded on the completion event", message);
        assertTrue("The event must name the retired rule: " + message,
                message.contains("never-resolves"));
        assertTrue("The event must say the rule stopped at the job-wide ceiling: " + message,
                message.contains("job-wide ceiling"));
    }

    /**
     * The control: a rule that does not override the classification keeps the
     * agent-fixable default, so it is still retried — the opposite of the
     * environment-determined rule above, which runs no correction session at
     * all. Here the working tree is not a git repository, so no-progress
     * detection cannot fire (the fingerprint is unavailable) and the
     * always-violated rule re-enters pass after pass until the job-wide attempt
     * cap stops it; all that matters for the contrast is that it ran at least
     * its per-pass cap of correction sessions.
     */
    @Test(timeout = 30000)
    public void agentFixableViolationIsStillRetried() {
        AtomicInteger corrections = new AtomicInteger();

        EnforcementRule fixableRule = new EnforcementRule() {
            @Override public String getName() { return "fixable"; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
            @Override public int getMaxRetries() { return 2; }
        };

        CodingAgentJob job = new CodingAgentJob("fix-1", "Do the work") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                corrections.incrementAndGet();
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setEnforceOrganizationalPlacement(false);
        job.addEnforcementRule(fixableRule);

        job.runEnforcementRules();

        assertTrue("An agent-fixable violation must still be retried (ran "
                        + corrections.get() + " correction sessions)",
                corrections.get() >= 2);

        CodingAgentJobEvent event = job.createEvent(null);
        assertNotNull(event.getErrorMessage());
        assertTrue("The event must record that the rule was retired after exhausting retries: "
                        + event.getErrorMessage(),
                event.getErrorMessage().contains("fixable"));
    }
}
