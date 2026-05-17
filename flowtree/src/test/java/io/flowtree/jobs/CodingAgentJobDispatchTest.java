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
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link CodingAgentJob} dispatches each session through a
 * configurable {@link AgentRunner}. Uses a recording runner so the test
 * does not launch any subprocess.
 */
public class CodingAgentJobDispatchTest extends TestSuiteBase {

    /** Name under which the recording runner is registered during each test. */
    private static final String RECORDING_RUNNER = "recording-test-runner";

    /** Captures requests handed to it and yields configurable canned results. */
    private RecordingRunner runner;

    /** Registers a fresh recording runner before each test. */
    @Before
    public void registerRecordingRunner() {
        runner = new RecordingRunner();
        AgentRunnerRegistry.register(RECORDING_RUNNER, () -> runner);
    }

    /** Clears the recording state between tests for predictable fixtures. */
    @After
    public void clearRunnerInstance() {
        runner = null;
    }

    @Test(timeout = 10000)
    public void resolveRunnerHonoursRunnerName() {
        CodingAgentJob job = new CodingAgentJob("t-1", "p");
        job.setRunnerName(RECORDING_RUNNER);
        AgentRunner resolved = job.resolveRunner();
        assertEquals(RECORDING_RUNNER, resolved.getName());
    }

    @Test(timeout = 10000)
    public void resolveRunnerDefaultsToClaude() {
        CodingAgentJob job = new CodingAgentJob("t-1", "p");
        AgentRunner resolved = job.resolveRunner();
        assertEquals(AgentRunnerRegistry.CLAUDE, resolved.getName());
    }

    @Test(timeout = 10000)
    public void buildRunRequestThreadsConfigurationThroughRequest() {
        CodingAgentJob job = new CodingAgentJob("t-2", "do the thing");
        job.setAllowedTools("Read,Edit");
        job.setMaxTurns(11);
        job.setMaxBudgetUsd(3.0);
        job.setModel("opus");
        job.setEffort("high");

        AgentRunRequest req = job.buildRunRequest(
                "Read,Edit,mcp__ar-manager__send_message",
                "{\"mcpServers\":{}}",
                Path.of("/tmp/x.json"),
                0);

        assertNotNull(req.getPrompt());
        assertTrue(req.getPrompt().contains("do the thing"));
        assertEquals("Read,Edit,mcp__ar-manager__send_message", req.getAllowedTools());
        assertEquals("{\"mcpServers\":{}}", req.getMcpConfigJson());
        assertEquals(11, req.getMaxTurns());
        assertEquals(3.0, req.getMaxBudgetUsd(), 0.0001);
        assertEquals("opus", req.getModel());
        assertEquals("high", req.getEffort());
        assertEquals("t-2", req.getTaskId());
        assertEquals(Path.of("/tmp/x.json"), req.getOutputCapturePath());
    }

    @Test(timeout = 10000)
    public void executeSingleRunDispatchesThroughRunner() throws Exception {
        Path workDir = Files.createTempDirectory("coding-agent-dispatch-");
        try {
            CodingAgentJob job = new CodingAgentJob("t-3", "do the thing");
            job.setWorkingDirectory(workDir.toString());
            job.setRunnerName(RECORDING_RUNNER);

            runner.nextResult = new AgentRunResult(
                    0, false,
                    "{\"type\":\"result\"}", "sess-A",
                    100L, 50L, 2, 0.10,
                    "success", false,
                    List.of("Bash"),
                    Collections.emptyMap());

            job.executeSingleRun();

            assertEquals(1, runner.requests.size());
            AgentRunRequest req = runner.requests.get(0);
            assertNotNull(req.getPrompt());
            assertNotNull(req.getMcpConfigJson());
            assertEquals("t-3", req.getTaskId());

            // The orchestrator absorbed the runner's session-id and exit code.
            assertEquals("sess-A", job.getSessionId());
            assertEquals(0, job.getExitCode());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test(timeout = 10000)
    public void runnerNameRoundTripsThroughEncodeAndSet() {
        CodingAgentJob original = new CodingAgentJob("t-4", "p");
        original.setRunnerName(RECORDING_RUNNER);
        String encoded = original.encode();
        assertTrue("runner key missing in encoded form: " + encoded,
                encoded.contains("::runner:=" + RECORDING_RUNNER));

        CodingAgentJob roundTrip = new CodingAgentJob();
        roundTrip.set("runner", RECORDING_RUNNER);
        assertEquals(RECORDING_RUNNER, roundTrip.getRunnerName());
    }

    @Test(timeout = 10000)
    public void runnerNameDefaultIsOmittedFromEncode() {
        CodingAgentJob job = new CodingAgentJob("t-5", "p");
        String encoded = job.encode();
        assertFalse("default runner should not be serialised: " + encoded,
                encoded.contains("::runner:="));
    }

    @Test(timeout = 10000)
    public void hasUncommittedChangesReturnsTrueForDirtyDependentRepo() throws Exception {
        Path primary = initGitRepo();
        Path dep = initGitRepo();
        try {
            // Primary: clean committed state.
            Path pFile = primary.resolve("Primary.java");
            Files.writeString(pFile, "class Primary {}");
            gitRun(primary, "add", "Primary.java");
            gitRun(primary, "commit", "-m", "init");

            // Dependent: seed commit, then a new untracked file (dirty).
            Path dSeed = dep.resolve("seed.txt");
            Files.writeString(dSeed, "seed");
            gitRun(dep, "add", "seed.txt");
            gitRun(dep, "commit", "-m", "seed");
            Files.writeString(dep.resolve("Dep.java"), "class Dep {}");

            String depPath = dep.toString();
            CodingAgentJob job = new CodingAgentJob("t-dep-dirty", "p") {
                @Override
                public List<String> getDependentRepoPaths() {
                    return List.of(depPath);
                }
            };
            job.setWorkingDirectory(primary.toString());

            assertTrue("hasUncommittedChanges must detect dirty dependent repo",
                    job.hasUncommittedChanges());
        } finally {
            deleteRecursively(primary);
            deleteRecursively(dep);
        }
    }

    @Test(timeout = 10000)
    public void hasUncommittedChangesReturnsFalseWhenBothReposClean() throws Exception {
        Path primary = initGitRepo();
        Path dep = initGitRepo();
        try {
            // Both repos: clean committed state.
            for (Path repo : new Path[] {primary, dep}) {
                Path f = repo.resolve("seed.txt");
                Files.writeString(f, "seed");
                gitRun(repo, "add", "seed.txt");
                gitRun(repo, "commit", "-m", "seed");
            }

            String depPath = dep.toString();
            CodingAgentJob job = new CodingAgentJob("t-both-clean", "p") {
                @Override
                public List<String> getDependentRepoPaths() {
                    return List.of(depPath);
                }
            };
            job.setWorkingDirectory(primary.toString());

            assertFalse("hasUncommittedChanges must return false when both repos are clean",
                    job.hasUncommittedChanges());
        } finally {
            deleteRecursively(primary);
            deleteRecursively(dep);
        }
    }

    /** Initialises a new git repo in a temp directory and returns its path. */
    private static Path initGitRepo() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("dispatch-test-repo-");
        gitRun(dir, "init");
        gitRun(dir, "config", "user.email", "test@test.com");
        gitRun(dir, "config", "user.name", "Test");
        return dir;
    }

    /** Runs a git sub-command in {@code workDir} and waits for completion. */
    private static void gitRun(Path workDir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = GitOperations.resolveGitCommand();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);
        pb.start().waitFor();
    }

    /** Stub runner that records requests and returns a canned result. */
    private static final class RecordingRunner implements AgentRunner {
        /** Requests received in order of arrival. */
        final List<AgentRunRequest> requests = new ArrayList<>();
        /** Result returned for the next {@link #run} call. */
        AgentRunResult nextResult = new AgentRunResult(
                0, false, "", "session-id",
                10L, 5L, 1, 0.0,
                "success", false,
                Collections.emptyList(),
                Collections.emptyMap());

        @Override
        public String getName() { return RECORDING_RUNNER; }

        @Override
        public AgentCapabilities capabilities() {
            return new AgentCapabilities(false, false, false, false, true, true, false,
                    Collections.emptySet());
        }

        @Override
        public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
            requests.add(request);
            return nextResult;
        }
    }

    /** Recursively deletes {@code root}; used to clean up temp directories. */
    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try {
            if (!Files.exists(root)) return;
            try (var stream = Files.walk(root)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignore) { /* best-effort */ }
                        });
            }
        } catch (Exception ignore) {
            // best-effort cleanup
        }
    }
}
