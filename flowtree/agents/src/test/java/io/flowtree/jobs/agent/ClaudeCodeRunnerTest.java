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

package io.flowtree.jobs.agent;

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies that {@link ClaudeCodeRunner} composes the Claude Code command
 * line and parses its NDJSON output exactly as the orchestrator used to
 * before the pluggable-agents refactor.
 */
public class ClaudeCodeRunnerTest extends TestSuiteBase {

    /** Logger that uses default ConsoleFeatures behavior; sufficient for tests that don't assert log output. */
    private static final ConsoleFeatures SILENT = new ConsoleFeatures() {};

    /**
     * Builds a minimal {@link AgentRunRequest} populated with everything the
     * runner relies on for command construction.
     */
    private static AgentRunRequest minimalRequest() {
        return AgentRunRequest.builder()
                .prompt("do the thing")
                .workingDirectory(Path.of("/tmp"))
                .allowedTools("Read,Edit")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .maxTurns(7)
                .maxBudgetUsd(2.5)
                .taskId("task-1")
                .inactivityTimeoutMillis(1_000L)
                .build();
    }

    /** Sanity-checks the registry pre-registration and the runner's identifier. */
    @Test(timeout = 5000)
    public void registryReturnsClaudeRunnerByName() {
        AgentRunner runner = AgentRunnerRegistry.get(AgentRunnerRegistry.CLAUDE);
        assertEquals(ClaudeCodeRunner.NAME, runner.getName());
        assertTrue(AgentRunnerRegistry.available().contains(AgentRunnerRegistry.CLAUDE));
    }

    /** The Claude runner's capability flags reflect what the CLI actually reports. */
    @Test(timeout = 5000)
    public void capabilitiesAdvertiseClaudeFeatures() {
        AgentCapabilities cap = new ClaudeCodeRunner().capabilities();
        assertTrue(cap.reportsCost());
        assertTrue(cap.reportsTurns());
        assertTrue(cap.supportsEffortLevel());
        assertTrue(cap.supportsMaxBudget());
        assertTrue(cap.supportsMcpHttpTransport());
        assertTrue(cap.supportsMcpStdioTransport());
        assertTrue(cap.supportsPermissionDenialReporting());
        assertTrue(cap.supportedModels().contains("claude-opus-4-7"));
    }

    /**
     * Verifies the command line includes the expected core flags and ordering.
     * Avoids asserting positional indices to keep the test resilient to
     * future flag additions; instead checks that each required flag and its
     * value are adjacent.
     */
    @Test(timeout = 5000)
    public void buildCommandLineIncludesCoreClaudeFlags() {
        ClaudeCodeRunner runner = new ClaudeCodeRunner();
        List<String> cmd = runner.buildCommandLine(minimalRequest());

        // Binary first.
        assertEquals("claude", cmd.get(0));
        assertFlagFollows(cmd, "-p", "do the thing");
        assertFlagFollows(cmd, "--output-format", "json");
        assertFlagFollows(cmd, "--allowedTools", "Read,Edit");
        assertFlagFollows(cmd, "--max-turns", "7");
        assertFlagFollows(cmd, "--max-budget-usd", "2.50");
        assertFlagFollows(cmd, "--mcp-config", "{\"mcpServers\":{}}");
    }

    /**
     * The {@code --model} and {@code --effort} flags must only appear when
     * the request specifies them; omitting them matches the pre-refactor
     * behaviour where empty values were dropped before being added to the
     * command list.
     */
    @Test(timeout = 5000)
    public void buildCommandLineOmitsOptionalFlagsWhenAbsent() {
        ClaudeCodeRunner runner = new ClaudeCodeRunner();
        List<String> cmd = runner.buildCommandLine(minimalRequest());

        assertFalse("unexpected --model flag", cmd.contains("--model"));
        assertFalse("unexpected --effort flag", cmd.contains("--effort"));
    }

    /** When provided, the model and effort flags are emitted with their values. */
    @Test(timeout = 5000)
    public void buildCommandLineEmitsModelAndEffortWhenSet() {
        ClaudeCodeRunner runner = new ClaudeCodeRunner();
        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("p")
                .workingDirectory(Path.of("/tmp"))
                .allowedTools("Read")
                .mcpConfigJson("{}")
                .maxTurns(1)
                .maxBudgetUsd(0)
                .model("opus")
                .effort("high")
                .build();

        List<String> cmd = runner.buildCommandLine(req);
        assertFlagFollows(cmd, "--model", "opus");
        assertFlagFollows(cmd, "--effort", "high");
        // Budget cap off → flag must be omitted.
        assertFalse(cmd.contains("--max-budget-usd"));
    }

    /** Validation rejects unknown models. */
    @Test(timeout = 5000)
    public void validateRequestRejectsUnknownModel() {
        ClaudeCodeRunner runner = new ClaudeCodeRunner();
        AgentRunRequest bad = AgentRunRequest.builder()
                .prompt("x").model("nope").build();
        try {
            runner.validateRequest(bad);
            fail("expected IllegalArgumentException for unknown model");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    /** Validation rejects unknown effort levels. */
    @Test(timeout = 5000)
    public void validateRequestRejectsUnknownEffort() {
        ClaudeCodeRunner runner = new ClaudeCodeRunner();
        AgentRunRequest bad = AgentRunRequest.builder()
                .prompt("x").effort("ludicrous").build();
        try {
            runner.validateRequest(bad);
            fail("expected IllegalArgumentException for unknown effort");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    /**
     * The NDJSON parser must surface the fields the orchestrator used to
     * extract by hand: session id, subtype, duration, turns, cost, denials.
     */
    @Test(timeout = 5000)
    public void parseClaudeNdjsonExtractsTopLevelMetrics() {
        String sample = "{\"type\":\"system\",\"session_id\":\"s-1\"}\n"
                + "{\"type\":\"result\",\"session_id\":\"s-1\",\"subtype\":\"success\","
                + "\"is_error\":false,\"duration_ms\":1234,\"duration_api_ms\":900,"
                + "\"num_turns\":3,\"total_cost_usd\":0.25,"
                + "\"permission_denials\":[{\"tool\":\"Bash\"},{\"tool\":\"Edit\"}]}\n";

        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson(sample, 0, false, SILENT);

        assertEquals(0, result.exitCode());
        assertFalse(result.killedForInactivity());
        assertEquals("s-1", result.sessionId());
        assertEquals("success", result.stopReason());
        assertFalse(result.sessionIsError());
        assertEquals(1234L, result.durationMs());
        assertEquals(900L, result.durationApiMs());
        assertEquals(3, result.numTurns());
        assertEquals(0.25, result.costUsd(), 0.0001);
        assertEquals(List.of("Bash", "Edit"), result.deniedToolNames());
    }

    /**
     * An empty output (e.g. process never produced anything) parses to a
     * result with default-zero metrics and a null session id; the caller can
     * still observe the exit code.
     */
    @Test(timeout = 5000)
    public void parseClaudeNdjsonHandlesEmptyOutput() {
        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson("", 42, true, SILENT);
        assertEquals(42, result.exitCode());
        assertTrue(result.killedForInactivity());
        assertNull(result.sessionId());
        assertEquals(0L, result.durationMs());
        assertEquals(0, result.numTurns());
        assertEquals(0.0, result.costUsd(), 0.0001);
        assertTrue(result.deniedToolNames().isEmpty());
    }

    /** {@code cost_usd} (older Claude builds) populates {@code costUsd} when the new key is absent. */
    @Test(timeout = 5000)
    public void parseClaudeNdjsonFallsBackToCostUsd() {
        String sample = "{\"type\":\"result\",\"cost_usd\":0.10,\"num_turns\":1,"
                + "\"duration_ms\":5}";
        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson(sample, 0, false, SILENT);
        assertEquals(0.10, result.costUsd(), 0.0001);
        assertEquals(1, result.numTurns());
    }

    /** A pre-built runner returned by the registry must be configured and usable. */
    @Test(timeout = 5000)
    public void registryFreshInstancePerCall() {
        AgentRunner a = AgentRunnerRegistry.get(AgentRunnerRegistry.CLAUDE);
        AgentRunner b = AgentRunnerRegistry.get(AgentRunnerRegistry.CLAUDE);
        assertNotNull(a);
        assertNotNull(b);
        // Each call yields a distinct instance — the supplier is invoked each time.
        assertTrue("registry should hand out fresh instances per call", a != b);
    }

    /**
     * Asserts that {@code cmd} contains {@code flag} immediately followed by
     * {@code value}; raises an {@link AssertionError} on the first mismatch.
     */
    private static void assertFlagFollows(List<String> cmd, String flag, String value) {
        int idx = cmd.indexOf(flag);
        Assert.assertTrue("missing flag " + flag + " in " + cmd, idx >= 0);
        Assert.assertTrue("no value after " + flag, idx + 1 < cmd.size());
        Assert.assertEquals("expected " + flag + " " + value + " but got " + cmd.get(idx + 1),
                value, cmd.get(idx + 1));
    }
}
