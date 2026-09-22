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

    /** Creates a fresh temporary directory and registers the stub runner before each test. */
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("required-mcp-failure-test");
        AgentRunnerRegistry.register(STUB_RUNNER, UnavailableRequiredServerRunner::new);
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
     * A phase after primary loses the required server. The primary session
     * already ran with its tools intact, so its work must survive: the loss
     * must not throw — a throw here escapes {@code doWork()} and skips the
     * whole git-commit block in {@link GitManagedJob#run()}, discarding
     * everything the primary session produced. Instead no further session may
     * launch, since each one would configure the same server and find it
     * missing again.
     */
    @Test(timeout = 30000)
    public void lossAfterPrimaryStopsLaunchingInsteadOfDiscardingTheWork() {
        SpyJob job = unavailableRequiredServerJob();
        job.setOutputConsumer(null);
        job.setCurrentActivity(Phase.RETROSPECTIVE.wireName());

        job.executeSingleRun();

        assertFalse("no further session may launch after the loss",
                job.restartGovernor().canLaunchSession());
        assertTrue("the block reason must name the server: " + job.restartGovernor().blockReason(),
                job.restartGovernor().blockReason().contains("ar-manager"));
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
