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
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link OpencodeOutputParser} extracts metrics from a recorded
 * fixture, tolerates unknown fields, defaults absent fields, and handles
 * opencode error output.
 */
public class OpencodeOutputParserTest extends TestSuiteBase {

    /** Logger that uses default ConsoleFeatures behavior; we ignore its output. */
    private static final ConsoleFeatures SILENT = new ConsoleFeatures() {};

    /** Representative successful-session output recorded from a forgiving opencode build. */
    private static final String SUCCESS_FIXTURE = "{"
            + "\"session_id\":\"opc-12345\","
            + "\"steps\":7,"
            + "\"stopReason\":\"success\","
            + "\"is_error\":false,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},"
            + "{\"role\":\"assistant\",\"content\":\"hello\"}],"
            + "\"denials\":[{\"tool\":\"Bash\"}]"
            + "}";

    /** Representative error output (non-zero exit, no useful fields). */
    private static final String ERROR_FIXTURE =
            "Error: failed to contact provider http://localhost:11434/v1\n"
                    + "Connection refused\n";

    /** Fixture that mixes known and unknown top-level fields. */
    private static final String UNKNOWN_FIELDS_FIXTURE = "{"
            + "\"session_id\":\"opc-77\","
            + "\"steps\":2,"
            + "\"stopReason\":\"success\","
            + "\"future_field_we_have_not_seen\":42,"
            + "\"another_unknown\":[1,2,3]"
            + "}";

    /** Fixture with no metric fields beyond an assistant transcript. */
    private static final String TRANSCRIPT_ONLY = ""
            + "{\"role\":\"user\",\"content\":\"do it\"}\n"
            + "{\"role\":\"assistant\",\"content\":\"step 1\"}\n"
            + "{\"role\":\"assistant\",\"content\":\"step 2\"}\n";

    /** The success fixture surfaces every known metric. */
    @Test(timeout = 5000)
    public void parsesSuccessFixture() {
        AgentRunResult result = OpencodeOutputParser.parse(
                SUCCESS_FIXTURE, 0, false, 1234L, Map.of("provider_url", "x"), SILENT);

        assertEquals(0, result.exitCode());
        assertEquals("opc-12345", result.sessionId());
        assertEquals(7, result.numTurns());
        assertEquals(1234L, result.durationMs());
        assertEquals(0L, result.durationApiMs());
        assertEquals(0.0, result.costUsd(), 0.0);
        assertEquals("success", result.stopReason());
        assertFalse(result.sessionIsError());
        assertEquals("hello", result.runnerMetadata().get("response_text"));
        assertEquals(List.of("Bash"), result.deniedToolNames());
        assertEquals("x", result.runnerMetadata().get("provider_url"));
    }

    /** Unknown top-level fields are ignored without throwing. */
    @Test(timeout = 5000)
    public void tolerantOfUnknownFields() {
        AgentRunResult result = OpencodeOutputParser.parse(
                UNKNOWN_FIELDS_FIXTURE, 0, false, 50L, Collections.emptyMap(), SILENT);

        assertEquals("opc-77", result.sessionId());
        assertEquals(2, result.numTurns());
        assertEquals("success", result.stopReason());
    }

    /** Missing fields default sensibly: zeros, nulls, empty lists. */
    @Test(timeout = 5000)
    public void defaultsWhenFieldsAbsent() {
        AgentRunResult result = OpencodeOutputParser.parse(
                "{\"unrelated\":true}", 0, false, 99L, null, SILENT);

        assertEquals(0, result.numTurns());
        assertEquals(0.0, result.costUsd(), 0.0);
        assertNull(result.sessionId());
        assertEquals(OpencodeOutputParser.STOP_SUCCESS, result.stopReason());
        assertTrue(result.deniedToolNames().isEmpty());
        assertNull(result.runnerMetadata().get("response_text"));
        assertNotNull(result.runnerMetadata());
    }

    /** An empty body and non-zero exit produces the error-unknown stop reason. */
    @Test(timeout = 5000)
    public void handlesErrorOutput() {
        AgentRunResult result = OpencodeOutputParser.parse(
                ERROR_FIXTURE, 1, false, 10L, Collections.emptyMap(), SILENT);

        assertEquals(1, result.exitCode());
        assertEquals(OpencodeOutputParser.STOP_ERROR_UNKNOWN, result.stopReason());
        assertTrue(result.sessionIsError());
        assertNull(result.sessionId());
        assertEquals(0, result.numTurns());
    }

    /** A non-zero exit with no JSON body still reflects the killed-for-inactivity flag. */
    @Test(timeout = 5000)
    public void handlesInactivityKill() {
        AgentRunResult result = OpencodeOutputParser.parse(
                "", 137, true, 5L, Collections.emptyMap(), SILENT);
        assertEquals(137, result.exitCode());
        assertTrue(result.killedForInactivity());
        assertEquals(OpencodeOutputParser.STOP_ERROR_UNKNOWN, result.stopReason());
    }

    /** When the metric fields are absent, turns are counted from assistant transcript lines. */
    @Test(timeout = 5000)
    public void countsAssistantTurnsWhenStepsAbsent() {
        AgentRunResult result = OpencodeOutputParser.parse(
                TRANSCRIPT_ONLY, 0, false, 200L, Collections.emptyMap(), SILENT);
        assertEquals(2, result.numTurns());
    }

    /** Cost is always reported as zero — local-model cost reporting is meaningless. */
    @Test(timeout = 5000)
    public void costIsAlwaysZero() {
        // Even when the fixture includes a fictional cost field, we report 0.
        String withFakeCost = "{\"session_id\":\"x\",\"steps\":1,\"total_cost_usd\":99.0}";
        AgentRunResult result = OpencodeOutputParser.parse(
                withFakeCost, 0, false, 0L, Collections.emptyMap(), SILENT);
        assertEquals(0.0, result.costUsd(), 0.0);
    }
}
