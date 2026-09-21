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

import io.flowtree.jobs.HarnessStatusReporter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The session's {@code init} event reports which MCP servers connected. A
 * required server that did not is a failed session, not a quiet one: these
 * tests cover reading that event, carrying the finding on the result, and
 * how the phase-exit status message and the request builder expose it.
 */
public class ClaudeCodeRunnerMcpAvailabilityTest extends TestSuiteBase {

    /** An init event in which ar-manager failed and ar-jmx wants auth; two servers connected. */
    private static final String INIT_WITH_FAILED_MANAGER =
            "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\","
            + "\"tools\":[\"Bash\",\"SendMessage\"],"
            + "\"mcp_servers\":["
            + "{\"name\":\"ar-manager\",\"status\":\"failed\",\"source\":\"dynamic\"},"
            + "{\"name\":\"onyx\",\"status\":\"connected\",\"source\":\"user\"},"
            + "{\"name\":\"ar-docs\",\"status\":\"connected\",\"source\":\"dynamic\"},"
            + "{\"name\":\"ar-jmx\",\"status\":\"needs-auth\",\"source\":\"dynamic\"}]}\n";

    /** A result event for a session that ran to completion. */
    private static final String RESULT_SUCCESS =
            "{\"type\":\"result\",\"session_id\":\"s-1\",\"subtype\":\"success\","
            + "\"is_error\":false,\"duration_ms\":248000,\"num_turns\":9,\"total_cost_usd\":0.99}\n";

    /** Every server whose status is not {@code connected} is listed, with its status, in event order. */
    @Test(timeout = 5000)
    public void initEventListsEveryServerThatDidNotConnect() {
        Map<String, String> failed = new ClaudeCodeRunner().failedMcpServersAtInit(INIT_WITH_FAILED_MANAGER);

        assertEquals(List.of("ar-manager", "ar-jmx"), List.copyOf(failed.keySet()));
        assertEquals("failed", failed.get("ar-manager"));
        assertEquals("needs-auth", failed.get("ar-jmx"));
    }

    /** No init event, empty output, or an init event with everything connected yields nothing. */
    @Test(timeout = 5000)
    public void noInitEventOrAllConnectedMeansNothingFailed() {
        ClaudeCodeRunner runner = new ClaudeCodeRunner();
        assertTrue(runner.failedMcpServersAtInit(RESULT_SUCCESS).isEmpty());
        assertTrue(runner.failedMcpServersAtInit("").isEmpty());
        assertTrue(runner.failedMcpServersAtInit(null).isEmpty());
        assertTrue(runner.failedMcpServersAtInit("not json\n{\"type\":\"system\",\"subtype\":\"init\","
                + "\"mcp_servers\":[{\"name\":\"ar-manager\",\"status\":\"connected\"}]}").isEmpty());
    }

    /** A failed required server is reported on the result; the session's other metrics are still read. */
    @Test(timeout = 5000)
    public void onlyRequiredServersReachTheResult() {
        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson(
                INIT_WITH_FAILED_MANAGER + RESULT_SUCCESS, 0, false, SILENT, Set.of("ar-manager"));

        assertTrue(result.hasUnavailableRequiredMcpServer());
        assertEquals(List.of("ar-manager"), result.unavailableRequiredMcpServers());
        // The rest of the result is still read: this is a session that ran to
        // completion, and its cost and duration are real even though its work is not kept.
        assertEquals(248000L, result.durationMs());
        assertEquals(0.99, result.costUsd(), 1e-9);
        assertEquals("success", result.stopReason());
        assertTrue(result.describeUnavailableRequiredMcpServers().contains("ar-manager"));
    }

    /** A server that failed but was not required does not make the result a failure. */
    @Test(timeout = 5000)
    public void aFailedServerThatIsNotRequiredIsNotAFailure() {
        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson(
                INIT_WITH_FAILED_MANAGER + RESULT_SUCCESS, 0, false, SILENT, Set.of("ar-docs"));

        assertFalse(result.hasUnavailableRequiredMcpServer());
        assertEquals("", result.describeUnavailableRequiredMcpServers());
    }

    /** The original four-argument parse declares nothing required, so it never reports a failure. */
    @Test(timeout = 5000)
    public void theFourArgumentParseRequiresNothing() {
        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson(
                INIT_WITH_FAILED_MANAGER + RESULT_SUCCESS, 0, false, SILENT);

        assertFalse(result.hasUnavailableRequiredMcpServer());
    }

    /** The twelve-argument result constructor, kept for runners that do not report connection status, reports nothing unavailable. */
    @Test(timeout = 5000)
    public void twelveArgumentResultConstructorReportsNothingUnavailable() {
        AgentRunResult result = new AgentRunResult(0, false, "", "s", 1L, 1L, 1, 0.0, "success", false,
                Collections.emptyList(), Collections.emptyMap());

        assertFalse(result.hasUnavailableRequiredMcpServer());
        assertTrue(result.unavailableRequiredMcpServers().isEmpty());
    }

    /** The request builder carries the required set, defaulting to none and treating {@code null} as none. */
    @Test(timeout = 5000)
    public void requestCarriesRequiredServersAndDefaultsToNone() {
        AgentRunRequest bare = AgentRunRequest.builder().prompt("p").maxTurns(1).build();
        assertTrue(bare.getRequiredMcpServers().isEmpty());

        AgentRunRequest required = AgentRunRequest.builder().prompt("p").maxTurns(1)
                .requiredMcpServers(Set.of("ar-manager")).build();
        assertEquals(Set.of("ar-manager"), required.getRequiredMcpServers());

        AgentRunRequest nulled = AgentRunRequest.builder().prompt("p").maxTurns(1)
                .requiredMcpServers(null).build();
        assertTrue(nulled.getRequiredMcpServers().isEmpty());
    }

    /** The phase-exit status message names the unavailable server and does not call the phase a success. */
    @Test(timeout = 5000)
    public void phaseExitMessageNamesTheUnavailableServerAndNotSuccess() {
        AgentRunResult result = new ClaudeCodeRunner().parseClaudeNdjson(
                INIT_WITH_FAILED_MANAGER + RESULT_SUCCESS, 0, false, SILENT, Set.of("ar-manager"));

        String message = HarnessStatusReporter.formatPhaseExit(Phase.PRIMARY, result);

        assertTrue(message, message.contains("ar-manager"));
        assertTrue(message, message.contains("FAILED"));
        assertFalse(message, message.contains("success in"));
    }
}
