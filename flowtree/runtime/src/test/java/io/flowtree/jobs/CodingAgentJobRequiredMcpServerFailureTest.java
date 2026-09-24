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

import io.flowtree.jobs.agent.AgentCapabilities;
import io.flowtree.jobs.agent.AgentRunRequest;
import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.jobs.agent.AgentRunner;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration coverage for the {@link CodingAgentJob#executeSingleRun()}
 * terminal-failure guard: proves that the {@link IllegalStateException}
 * thrown when {@link AgentRunResult#hasUnavailableRequiredMcpServer()} is
 * {@code true} actually propagates through {@link CodingAgentJob#doWork()}
 * and {@link GitManagedJob#run()}, so falsification, enforcement, the
 * retrospective phase, the output consumer, and the git-commit path are all
 * skipped rather than the exception being swallowed somewhere in between.
 *
 * <p>Enforcement is off by default (see
 * {@link McpConfigBuilder#requiredServerNames()}), so these tests turn it on
 * for their own duration: what they cover is the guard's behaviour when a
 * required server is lost, which has to keep working for whenever the
 * default goes back over.</p>
 *
 * <p>Drives the real (non-overridden) {@code executeSingleRun()}/{@code
 * doWork()}/{@code run()} path via a stub {@link AgentRunner} registered
 * under a unique name, so no subprocess is launched and no real MCP server
 * is contacted.</p>
 */
public class CodingAgentJobRequiredMcpServerFailureTest extends TestSuiteBase {

    /** Name under which the stub runner is registered for these tests. */
    private static final String STUB_RUNNER = "required-mcp-failure-stub-runner";

    /** Temporary working directory used as each job's sandbox. */
    private Path tempDir;

    /** Value of the enforcement flag before the test set it, restored afterwards. */
    private String previousEnforcement;

    /**
     * Creates a fresh temporary directory, registers the stub runner, and
     * turns required-server enforcement on for the duration of the test —
     * it is off by default, and without it these jobs would declare no
     * required servers and the guard under test would never be reached.
     */
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("required-mcp-failure-test");
        AgentRunnerRegistry.register(STUB_RUNNER, UnavailableRequiredServerRunner::new);
        previousEnforcement = System.getProperty("AR_REQUIRE_MCP_SERVERS");
        System.setProperty("AR_REQUIRE_MCP_SERVERS", "enabled");
    }

    /** Restores the enforcement flag and recursively deletes the temporary directory. */
    @After
    public void tearDown() throws IOException {
        if (previousEnforcement == null) {
            System.clearProperty("AR_REQUIRE_MCP_SERVERS");
        } else {
            System.setProperty("AR_REQUIRE_MCP_SERVERS", previousEnforcement);
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /**
     * A stub {@link AgentRunner} that reports every MCP server the request
     * declared required as unavailable, mirroring what a real runner reports
     * when the session's init event names it as not connected.
     */
    private static final class UnavailableRequiredServerRunner implements AgentRunner {
        @Override
        public String getName() { return STUB_RUNNER; }

        @Override
        public AgentCapabilities capabilities() {
            return new AgentCapabilities(false, false, false, false, true, true, false,
                    Collections.emptySet());
        }

        @Override
        public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
            return new AgentRunResult(0, false, "", "session-id", 10L, 5L, 1, 0.0,
                    "success", false, Collections.emptyList(), Collections.emptyMap(),
                    List.copyOf(request.getRequiredMcpServers()));
        }
    }

    /**
     * Drives the real dispatch path while recording whether the phases that
     * are supposed to be unreachable after the throw ever ran.
     */
    private static final class SpyJob extends CodingAgentJob {
        /** Set when {@link #runFalsificationPhase()} runs. */
        boolean falsificationRan;
        /** Set when {@link #runEnforcementRules()} runs. */
        boolean enforcementRan;
        /** Set when {@link #runReflectionPhase()} runs. */
        boolean reflectionRan;

        /**
         * @param taskId task identifier passed through to {@link CodingAgentJob}
         * @param prompt prompt passed through to {@link CodingAgentJob}
         */
        SpyJob(String taskId, String prompt) {
            super(taskId, prompt);
        }

        @Override
        void runFalsificationPhase() {
            falsificationRan = true;
        }

        @Override
        void runEnforcementRules() {
            enforcementRan = true;
        }

        @Override
        void runReflectionPhase() {
            reflectionRan = true;
        }
    }

    /**
     * Builds a job configured so that {@code ar-manager} is the required
     * server (real {@code setArManagerUrl}/{@code setArManagerToken}, so
     * {@link McpConfigBuilder#requiredServerNames()} returns it) and
     * dispatched through the stub runner that always reports it unavailable.
     */
    private SpyJob unavailableRequiredServerJob() {
        SpyJob job = new SpyJob("task", "do the work");
        job.setWorkingDirectory(tempDir.toString());
        job.setArManagerUrl("http://ar-manager:8010");
        job.setArManagerToken("armt_tmp_testtoken");
        job.setRunnerName(STUB_RUNNER);
        job.setOutputConsumer(output -> fail("output consumer must not run when the session is discarded"));
        return job;
    }

    /**
     * {@code doWork()} throws before falsification, enforcement, or the
     * retrospective phase ever runs.
     */
    @Test(timeout = 30000)
    public void doWorkThrowsAndSkipsLaterPhases() {
        SpyJob job = unavailableRequiredServerJob();

        try {
            job.doWork();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ar-manager"));
        }

        assertFalse("falsification must not run after the throw", job.falsificationRan);
        assertFalse("enforcement must not run after the throw", job.enforcementRan);
        assertFalse("retrospective must not run after the throw", job.reflectionRan);
    }

    /**
     * The primary session runs with its tools intact and leaves a change in
     * the working tree; the retrospective that follows loses the required
     * server. The primary change must survive, because a throw there escapes
     * {@code doWork()} and skips the whole git-commit block in
     * {@link GitManagedJob#run()}, discarding work that was produced correctly.
     * Only the retrospective is treated this way — it is the one phase that
     * cannot leave untrusted content in the tree
     * ({@link Phase#modifiesWorkingTree()}).
     */
    @Test(timeout = 30000)
    public void retrospectiveLossKeepsThePrimarySessionsWork() throws IOException {
        Path produced = tempDir.resolve("produced-by-primary.txt");
        AgentRunnerRegistry.register(PHASE_AWARE_RUNNER,
                () -> new PhaseAwareRunner(produced));

        SpyJob job = new SpyJob("task", "do the work");
        job.setWorkingDirectory(tempDir.toString());
        job.setArManagerUrl("http://ar-manager:8010");
        job.setArManagerToken("armt_tmp_testtoken");
        job.setRunnerName(PHASE_AWARE_RUNNER);

        job.executeSingleRun();
        assertTrue("the primary session must have produced its change",
                Files.exists(produced));

        job.setCurrentActivity(Phase.RETROSPECTIVE.wireName());
        job.executeSingleRun();

        assertTrue("the primary session's work must survive the retrospective loss",
                Files.exists(produced));
        assertFalse("no further session may launch after the loss",
                job.restartGovernor().canLaunchSession());
        assertTrue("the block reason must name the server: " + job.restartGovernor().blockReason(),
                job.restartGovernor().blockReason().contains("ar-manager"));
    }

    /**
     * A phase that can edit the tree is not given the retrospective's
     * treatment. Its untrusted edits are already in the working tree and
     * cannot be told apart from the work around them, so the job fails rather
     * than commit them alongside it.
     */
    @Test(timeout = 30000)
    public void lossInATreeModifyingPhaseStillFailsTheJob() {
        SpyJob job = unavailableRequiredServerJob();
        job.setOutputConsumer(null);
        job.setCurrentActivity(Phase.GIT_TAMPERING_RESTART.wireName());

        try {
            job.executeSingleRun();
            fail("a tree-modifying phase must not continue to the commit");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ar-manager"));
        }
    }

    /** Name under which the phase-aware stub runner is registered. */
    private static final String PHASE_AWARE_RUNNER = "required-mcp-phase-aware-stub-runner";

    /**
     * A stub runner that behaves like a healthy agent during primary work —
     * writing a file to stand in for the session's edits — and reports the
     * required servers unavailable once the retrospective phase runs.
     */
    private static final class PhaseAwareRunner implements AgentRunner {
        /** File written during the primary phase, standing in for the session's edits. */
        private final Path produced;

        /**
         * @param produced the file the primary phase writes
         */
        PhaseAwareRunner(Path produced) {
            this.produced = produced;
        }

        @Override
        public String getName() { return PHASE_AWARE_RUNNER; }

        @Override
        public AgentCapabilities capabilities() {
            return new AgentCapabilities(false, false, false, false, true, true, false,
                    Collections.emptySet());
        }

        @Override
        public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
            boolean retrospective = Phase.RETROSPECTIVE.wireName().equals(request.getActivityTag());
            if (!retrospective) {
                try {
                    Files.writeString(produced, "primary work\n", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("could not write the primary change", e);
                }
            }
            return new AgentRunResult(0, false, "", "session-id", 10L, 5L, 1, 0.0,
                    "success", false, Collections.emptyList(), Collections.emptyMap(),
                    retrospective ? List.copyOf(request.getRequiredMcpServers())
                            : Collections.emptyList());
        }
    }

    /**
     * The full {@link GitManagedJob#run()} completes without letting the
     * exception escape (it is caught and recorded as the job's error), and
     * because it fired before the git-commit block in {@code run()}, no
     * commit is made.
     */
    @Test(timeout = 30000)
    public void runCompletesWithoutEscapingAndWithoutCommitting() {
        SpyJob job = unavailableRequiredServerJob();

        job.run();

        assertFalse(job.falsificationRan);
        assertFalse(job.enforcementRan);
        assertFalse(job.reflectionRan);
        assertTrue(job.getStagedFiles().isEmpty());
        assertNull(job.getCommitHash());
    }
}
