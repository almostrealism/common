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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the {@code enforceChanges} re-run cap
 * ({@link CodingAgentJob#getMaxEnforceChangesRetries()}). The cap bounds how
 * many times {@link EnforceChangesRule} re-runs the agent over the whole job.
 * Once it is spent with nothing committed, the production
 * {@link CodingAgentJobEvent} classifier reports
 * {@link JobCompletionEvent.Status#DEGRADED} rather than SUCCESS.
 *
 * <p>Each test drives the real {@link EnforcementRunner} through
 * {@link CodingAgentJob#runEnforcementRules()}, then classifies the job against a
 * real git repository. The repository is attached only after enforcement has
 * run. Without a working directory the runner's no-progress detector (which
 * retires a rule after two attempts that leave the tree unchanged, covered by
 * {@code EnforcementRunnerNoProgressTest}) stays out of the way, so these tests
 * measure the cap alone.</p>
 */
public class EnforceChangesRetryCapTest extends TestSuiteBase {

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /**
     * A job whose agent runs are simulated. Each run is counted, and the run
     * numbered {@code changeOnRun} (1-based) produces a change that the harness
     * then commits. A {@code changeOnRun} of 0 means no run ever produces one.
     */
    private static class SimulatedJob extends CodingAgentJob {
        /** The run that produces a change; 0 for never. */
        private final int changeOnRun;
        /** Number of agent runs launched by the enforcement loop. */
        private int runs;
        /** Whether a run has produced (and the harness committed) a change. */
        private boolean changed;

        /**
         * Creates the job.
         *
         * @param changeOnRun the re-run that produces a change; 0 for never
         */
        SimulatedJob(int changeOnRun) {
            super("enforce-cap-test", "write the report");
            this.changeOnRun = changeOnRun;
            setEnforceChanges(true);
            setReviewEnabled(false);
        }

        @Override
        void executeSingleRun() {
            runs++;
            if (runs == changeOnRun) changed = true;
        }

        @Override
        StagingResult previewStaging() {
            return changed
                    ? new StagingResult(Collections.singletonList("report.md"), Collections.emptyList())
                    : new StagingResult(Collections.emptyList(), Collections.emptyList());
        }

        @Override
        protected boolean hasPublishedCommit() { return changed; }

        /** Returns how many times the enforcement loop re-ran the agent. */
        int getRuns() { return runs; }
    }

    /** Initializes a git repository with one committed file on a feature branch. */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("enforce-changes-cap-test");
        git("init", "--quiet");
        git("config", "user.email", "enforce-cap-test@example.com");
        git("config", "user.name", "Enforce Cap Test");
        git("config", "commit.gpgsign", "false");

        Path seed = repo.resolve("README.md");
        Files.writeString(seed, "seed\n", StandardCharsets.UTF_8);
        git("add", "README.md");
        git("commit", "--quiet", "-m", "seed");
        git("update-ref", "refs/remotes/origin/master", "HEAD");
        git("checkout", "--quiet", "-b", "feature/test");
    }

    /** Recursively deletes the temporary repository. */
    @After
    public void tearDown() throws IOException {
        if (repo != null && Files.exists(repo)) {
            try (Stream<Path> walk = Files.walk(repo)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /**
     * A cap of 0 never re-runs the agent: the empty result is reported DEGRADED
     * at once, and the message names the empty result and the branch.
     */
    @Test(timeout = 60000)
    public void capOfZeroIsImmediatelyDegraded() {
        SimulatedJob job = new SimulatedJob(0);
        job.setMaxEnforceChangesRetries(0);

        job.runEnforcementRules();
        assertEquals("a cap of 0 must not re-run the agent", 0, job.getRuns());

        CodingAgentJobEvent event = classify(job);
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertTrue("message must name the empty result: " + event.getErrorMessage(),
                event.getErrorMessage().contains("empty"));
        assertTrue("message must name the branch: " + event.getErrorMessage(),
                event.getErrorMessage().contains("feature/test"));
    }

    /**
     * A cap of N re-runs the agent exactly N times over the whole job, not N
     * per enforcement pass, and then reports DEGRADED.
     */
    @Test(timeout = 60000)
    public void capOfNGivesNReRunsThenDegraded() {
        SimulatedJob job = new SimulatedJob(0);
        job.setMaxEnforceChangesRetries(3);

        job.runEnforcementRules();
        assertEquals("the agent must be re-run exactly as many times as the cap", 3, job.getRuns());
        assertEquals(3, job.getEnforcementAttempt());

        CodingAgentJobEvent event = classify(job);
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertTrue("message must report the re-runs spent: " + event.getErrorMessage(),
                event.getErrorMessage().contains("after 3 of 3"));
    }

    /**
     * A change produced on a re-run within the cap ends the loop there, and the
     * job, having committed it, reports SUCCESS.
     */
    @Test(timeout = 60000)
    public void changeOnReRunIsSuccess() {
        SimulatedJob job = new SimulatedJob(2);
        job.setMaxEnforceChangesRetries(3);

        job.runEnforcementRules();
        assertEquals("the loop must stop at the re-run that produced a change", 2, job.getRuns());

        assertEquals(JobCompletionEvent.Status.SUCCESS, classify(job).getStatus());
    }

    /**
     * Without {@code enforceChanges}, an exit with nothing committed is the
     * legitimate no-op outcome and stays SUCCESS.
     */
    @Test(timeout = 60000)
    public void emptyResultWithoutEnforceChangesIsSuccess() {
        SimulatedJob job = new SimulatedJob(0);
        job.setEnforceChanges(false);

        job.runEnforcementRules();
        assertEquals(0, job.getRuns());
        assertEquals(JobCompletionEvent.Status.SUCCESS, classify(job).getStatus());
    }

    /**
     * Edits left in the tree with no commit behind them are a stronger signal
     * than an empty result: they stay FAILED with {@code enforceChanges} on.
     */
    @Test(timeout = 60000)
    public void unpublishedWorkStillFails() throws IOException {
        SimulatedJob job = new SimulatedJob(0);
        job.setMaxEnforceChangesRetries(0);
        job.runEnforcementRules();

        Files.writeString(repo.resolve("README.md"), "edited\n", StandardCharsets.UTF_8);
        assertEquals(JobCompletionEvent.Status.FAILED, classify(job).getStatus());
    }

    /**
     * The agent is told what an empty exit leads to: a restart while the cap
     * leaves a re-run, and a DEGRADED result (not a restart) once it does not.
     */
    @Test(timeout = 30000)
    public void promptStatesTheConsequenceOfAnEmptyExit() {
        String withRetries = new InstructionPromptBuilder()
                .setPrompt("write the report")
                .setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
                .setEnforceChanges(true)
                .setMaxEnforceChangesRetries(1)
                .build();
        assertTrue(withRetries.contains("will cause this session to be restarted"));

        String capZero = new InstructionPromptBuilder()
                .setPrompt("write the report")
                .setWorkstreamUrl("http://controller:8080/api/workstreams/ws1")
                .setEnforceChanges(true)
                .setMaxEnforceChangesRetries(0)
                .build();
        assertFalse(capZero.contains("will cause this session to be restarted"));
        assertTrue(capZero.contains("will end DEGRADED"));
        assertFalse("the strict block must not assume a CI-failure task",
                capZero.contains("submitted because CI was failing"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Attaches the temporary repository and its feature branch to {@code job}
     * and returns the completion event the production classifier builds for it.
     *
     * @param job the job whose enforcement has already run
     * @return the completion event
     */
    private CodingAgentJobEvent classify(SimulatedJob job) {
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/test");
        job.setBaseBranch("master");
        return job.createEvent(null);
    }

    /**
     * Runs a git command in the temporary repository, failing the test on a
     * non-zero exit.
     *
     * @param args git arguments
     * @throws Exception if the process cannot be run or exits non-zero
     */
    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(repo.toString()));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
