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
import io.flowtree.jobs.agent.PhaseConfigBundle;
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
import java.util.stream.Stream;

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

    /** Verifies that {@code resolveRunner} returns the runner registered under the configured name. */
    @Test(timeout = 10000)
    public void resolveRunnerHonoursRunnerName() {
        CodingAgentJob job = new CodingAgentJob("t-1", "p");
        job.setRunnerName(RECORDING_RUNNER);
        AgentRunner resolved = job.resolveRunner(Phase.PRIMARY);
        assertEquals(RECORDING_RUNNER, resolved.getName());
    }

    /** Verifies that {@code resolveRunner} falls back to the Claude runner when no runner name is set. */
    @Test(timeout = 10000)
    public void resolveRunnerDefaultsToClaude() {
        CodingAgentJob job = new CodingAgentJob("t-1", "p");
        AgentRunner resolved = job.resolveRunner(Phase.PRIMARY);
        assertEquals(AgentRunnerRegistry.CLAUDE, resolved.getName());
    }

    /** Verifies that a per-phase runner override is used for that phase while other phases use the default. */
    @Test(timeout = 10000)
    public void resolveRunnerHonoursPerPhaseOverride() {
        CodingAgentJob job = new CodingAgentJob("t-1", "p");
        job.setRunnerForPhase(Phase.DEDUPLICATION, RECORDING_RUNNER);
        assertEquals(RECORDING_RUNNER,
                job.resolveRunner(Phase.DEDUPLICATION).getName());
        // Other phases still fall back to default.
        assertEquals(AgentRunnerRegistry.CLAUDE,
                job.resolveRunner(Phase.PRIMARY).getName());
    }

    /** Verifies that all job-level configuration is propagated into the built {@link AgentRunRequest}. */
    @Test(timeout = 10000)
    public void buildRunRequestThreadsConfigurationThroughRequest() {
        CodingAgentJob job = new CodingAgentJob("t-2", "do the thing");
        job.setAllowedTools("Read,Edit");
        job.setMaxTurns(11);
        job.setMaxBudgetUsd(3.0);
        job.setPhaseConfigBundle(
                PhaseConfigBundle.EMPTY.withDefaultModel("opus").withDefaultEffort("high"));

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

    /** Verifies that {@code executeSingleRun} dispatches through the configured runner and captures the result. */
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

    /** Verifies that the runner name survives an encode/set round-trip. */
    @Test(timeout = 10000)
    public void runnerNameRoundTripsThroughEncodeAndSet() {
        CodingAgentJob original = new CodingAgentJob("t-4", "p");
        original.setRunnerName(RECORDING_RUNNER);
        String encoded = original.encode();
        assertTrue("defaultRunner key missing in encoded form: " + encoded,
                encoded.contains("::defaultRunner:=" + RECORDING_RUNNER));

        CodingAgentJob roundTrip = new CodingAgentJob();
        roundTrip.set("defaultRunner", RECORDING_RUNNER);
        assertEquals(RECORDING_RUNNER, roundTrip.getRunnerName());
    }

    /** Verifies that the legacy {@code runner} key is accepted during deserialization and sets the default runner. */
    @Test(timeout = 10000)
    public void legacyRunnerKeySetsDefaultRunner() {
        // Phase-2 deserialization must continue to accept the legacy
        // ``::runner:=<name>`` key emitted by pre-Phase-2 jobs.
        CodingAgentJob restored = new CodingAgentJob();
        restored.set("runner", RECORDING_RUNNER);
        assertEquals(RECORDING_RUNNER, restored.getDefaultRunner());
        assertEquals(RECORDING_RUNNER, restored.getRunnerName());
    }

    /** Verifies that when the runner name is the default it is not included in the encoded form. */
    @Test(timeout = 10000)
    public void runnerNameDefaultIsOmittedFromEncode() {
        CodingAgentJob job = new CodingAgentJob("t-5", "p");
        String encoded = job.encode();
        assertFalse("default runner should not be serialised: " + encoded,
                encoded.contains("::defaultRunner:="));
        assertFalse("legacy runner key should not be re-emitted: " + encoded,
                encoded.contains("::runner:="));
        assertFalse("runners map should not be serialised when empty: " + encoded,
                encoded.contains("::runners:="));
    }

    /** Verifies that the per-phase runner map survives a full encode/decode round-trip. */
    @Test(timeout = 10000)
    public void perPhaseRunnerMapRoundTrips() {
        CodingAgentJob original = new CodingAgentJob("t-rp", "p");
        original.setRunnerForPhase(Phase.PRIMARY, RECORDING_RUNNER);
        original.setRunnerForPhase(Phase.DEDUPLICATION, RECORDING_RUNNER);
        String encoded = original.encode();
        assertTrue("runners key missing in encoded form: " + encoded,
                encoded.contains("::runners:="));

        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        assertEquals(RECORDING_RUNNER, restored.getRunnerForPhase(Phase.PRIMARY));
        assertEquals(RECORDING_RUNNER, restored.getRunnerForPhase(Phase.DEDUPLICATION));
        // Phases not in the map fall back to default ("claude").
        assertEquals(AgentRunnerRegistry.CLAUDE,
                restored.getRunnerForPhase(Phase.COMMIT_MESSAGE));
    }

    /** Verifies that a per-phase runners map and a defaultRunner key can coexist in the serialized form. */
    @Test(timeout = 10000)
    public void runnersKeyAndDefaultRunnerCoexistOnTheWire() {
        CodingAgentJob original = new CodingAgentJob("t-coexist", "p");
        original.setDefaultRunner(RECORDING_RUNNER);
        original.setRunnerForPhase(Phase.DEDUPLICATION, AgentRunnerRegistry.CLAUDE);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        assertEquals(RECORDING_RUNNER, restored.getDefaultRunner());
        assertEquals(AgentRunnerRegistry.CLAUDE,
                restored.getRunnerForPhase(Phase.DEDUPLICATION));
        // Unset phase still falls through to defaultRunner.
        assertEquals(RECORDING_RUNNER,
                restored.getRunnerForPhase(Phase.ORGANIZATIONAL_PLACEMENT));
    }

    /** Verifies that an explicit {@code defaultRunner} key wins over the legacy {@code runner} key when both are present. */
    @Test(timeout = 10000)
    public void runnersAndLegacyRunnerKeyTogetherPrefersExplicitDefault() {
        // When both legacy ``runner`` and new ``defaultRunner`` are present
        // (mixed-producer wire format), the explicit ``defaultRunner`` wins.
        CodingAgentJob restored = new CodingAgentJob();
        restored.set("defaultRunner", RECORDING_RUNNER);
        restored.set("runner", AgentRunnerRegistry.CLAUDE);
        assertEquals(RECORDING_RUNNER, restored.getDefaultRunner());
    }

    /** Verifies that an unrecognised phase name in the runners map is silently skipped without throwing. */
    @Test(timeout = 10000)
    public void unknownPhaseInRunnersIsSkippedRatherThanFailing() {
        // A future producer must not be able to brick a current consumer.
        CodingAgentJob restored = new CodingAgentJob();
        restored.set("runners", "primary=" + RECORDING_RUNNER
                + ",future-phase=opencode");
        assertEquals(RECORDING_RUNNER, restored.getRunnerForPhase(Phase.PRIMARY));
    }

    /** Verifies that every known rule name resolves to the expected {@link Phase} value. */
    @Test(timeout = 10000)
    public void resolveCurrentPhaseRoundTripsForEveryRuleName() {
        // Every rule name must resolve to a Phase so dispatch can route the
        // correction session to the operator-configured runner.
        assertEquals(Phase.ENFORCE_CHANGES, Phase.fromRuleName("enforce-changes"));
        assertEquals(Phase.REVIEW, Phase.fromRuleName("review"));
        assertEquals(Phase.DEDUPLICATION, Phase.fromRuleName("deduplication"));
        assertEquals(Phase.ORGANIZATIONAL_PLACEMENT,
                Phase.fromRuleName("organizational-placement"));
        assertEquals(Phase.MAVEN_DEPENDENCY_PROTECTION,
                Phase.fromRuleName("no-maven-dependency-changes"));
        assertEquals(Phase.POST_COMPLETION,
                Phase.fromRuleName("post-completion-command"));
        assertEquals(Phase.COMMIT_MESSAGE, Phase.fromRuleName("commit-message"));
    }

    /** Verifies that the REVIEW phase routes to a configured per-phase runner while other phases use the default. */
    @Test(timeout = 10000)
    public void reviewPhaseRoutesToConfiguredRunner() {
        CodingAgentJob job = new CodingAgentJob("t-rv", "p");
        job.setRunnerForPhase(Phase.REVIEW, RECORDING_RUNNER);
        assertEquals(RECORDING_RUNNER, job.resolveRunner(Phase.REVIEW).getName());
        // Other phases still fall back to default.
        assertEquals(AgentRunnerRegistry.CLAUDE,
                job.resolveRunner(Phase.PRIMARY).getName());
    }

    /** Verifies that the REVIEW phase inherits the default runner when no per-phase override is set. */
    @Test(timeout = 10000)
    public void reviewPhaseInheritsDefaultRunner() {
        CodingAgentJob job = new CodingAgentJob("t-rv", "p");
        // No per-phase override — must inherit the (default) Claude runner.
        assertEquals(AgentRunnerRegistry.CLAUDE,
                job.resolveRunner(Phase.REVIEW).getName());
    }

    /** Verifies that REVIEW is declared between ENFORCE_CHANGES and DEDUPLICATION in the {@link Phase} enum. */
    @Test(timeout = 10000)
    public void reviewPhaseOrderedBetweenEnforceChangesAndDeduplication() {
        // The declaration order of Phase governs encode/decode iteration, so
        // verify the REVIEW phase appears between ENFORCE_CHANGES and
        // DEDUPLICATION — matching the runtime execution order documented in
        // PHASES.md.
        Phase[] values = Phase.values();
        int ec = -1, rv = -1, dd = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == Phase.ENFORCE_CHANGES) ec = i;
            if (values[i] == Phase.REVIEW) rv = i;
            if (values[i] == Phase.DEDUPLICATION) dd = i;
        }
        assertTrue("ENFORCE_CHANGES must be declared before REVIEW", ec >= 0 && ec < rv);
        assertTrue("REVIEW must be declared before DEDUPLICATION", rv < dd);
    }

    /** Verifies that {@code hasUncommittedChanges} returns {@code true} when a dependent repo has uncommitted files. */
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

    /** Verifies that {@code hasUncommittedChanges} returns {@code false} when both the primary and dependent repos are clean. */
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

        /** Returns the fixed name used to register this runner in the registry. */
        @Override
        public String getName() { return RECORDING_RUNNER; }

        /** Returns a capabilities descriptor with tool-list and MCP support enabled. */
        @Override
        public AgentCapabilities capabilities() {
            return new AgentCapabilities(false, false, false, false, true, true, false,
                    Collections.emptySet());
        }

        /** Records the request and returns the preconfigured {@link #nextResult}. */
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
            try (Stream<Path> stream = Files.walk(root)) {
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
