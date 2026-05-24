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

    /**
     * Real opencode 1.15.4 output: NDJSON event stream with top-level
     * {@code type} / {@code sessionID} / {@code part}. Captured from
     * {@code opencode run --format json} against a local llama.cpp endpoint.
     */
    private static final String OPENCODE_1_15_EVENT_STREAM = ""
            + "{\"type\":\"step_start\",\"timestamp\":1779130904807,"
            + "\"sessionID\":\"ses_1c388ed5affeeJOw2nrnpRZdFc\","
            + "\"part\":{\"id\":\"prt_e3c7730e4001AiAKjiD26HhJXl\","
            + "\"messageID\":\"msg_e3c77138a001GeGe2Ad9jnMWbI\","
            + "\"sessionID\":\"ses_1c388ed5affeeJOw2nrnpRZdFc\","
            + "\"type\":\"step-start\"}}\n"
            + "{\"type\":\"text\",\"timestamp\":1779130904864,"
            + "\"sessionID\":\"ses_1c388ed5affeeJOw2nrnpRZdFc\","
            + "\"part\":{\"id\":\"prt_e3c7730e7001b6Uwgu84XAjEDo\","
            + "\"messageID\":\"msg_e3c77138a001GeGe2Ad9jnMWbI\","
            + "\"sessionID\":\"ses_1c388ed5affeeJOw2nrnpRZdFc\","
            + "\"type\":\"text\",\"text\":\"OK\","
            + "\"time\":{\"start\":1779130904807,\"end\":1779130904862}}}\n";

    /** The 1.15.4 NDJSON event stream is recognised and mapped onto the result. */
    @Test(timeout = 5000)
    public void parsesOpencode1_15EventStream() {
        AgentRunResult result = OpencodeOutputParser.parse(
                OPENCODE_1_15_EVENT_STREAM, 0, false, 850L, Collections.emptyMap(), SILENT);

        assertEquals(0, result.exitCode());
        assertEquals("ses_1c388ed5affeeJOw2nrnpRZdFc", result.sessionId());
        assertEquals("step_start should count as one turn", 1, result.numTurns());
        assertEquals(OpencodeOutputParser.STOP_SUCCESS, result.stopReason());
        assertFalse(result.sessionIsError());
        assertEquals("OK", result.runnerMetadata().get("response_text"));
    }

    /** A multi-chunk text stream concatenates assistant text in order. */
    @Test(timeout = 5000)
    public void concatenatesStreamedTextChunks() {
        String stream = ""
                + "{\"type\":\"step_start\",\"sessionID\":\"ses_x\","
                + "\"part\":{\"messageID\":\"msg_1\",\"type\":\"step-start\"}}\n"
                + "{\"type\":\"text\",\"sessionID\":\"ses_x\","
                + "\"part\":{\"messageID\":\"msg_1\",\"type\":\"text\",\"text\":\"Hello \"}}\n"
                + "{\"type\":\"text\",\"sessionID\":\"ses_x\","
                + "\"part\":{\"messageID\":\"msg_1\",\"type\":\"text\",\"text\":\"world\"}}\n";
        AgentRunResult result = OpencodeOutputParser.parse(
                stream, 0, false, 100L, Collections.emptyMap(), SILENT);
        assertEquals("ses_x", result.sessionId());
        assertEquals(1, result.numTurns());
        assertEquals("Hello world", result.runnerMetadata().get("response_text"));
    }

    /** An event-stream {@code error} event flips sessionIsError even on exit 0. */
    @Test(timeout = 5000)
    public void eventStreamErrorEventTrumpsZeroExit() {
        String stream = ""
                + "{\"type\":\"step_start\",\"sessionID\":\"ses_y\","
                + "\"part\":{\"messageID\":\"msg_2\",\"type\":\"step-start\"}}\n"
                + "{\"type\":\"error\",\"sessionID\":\"ses_y\","
                + "\"part\":{\"type\":\"error\",\"message\":\"provider 502\"}}\n";
        AgentRunResult result = OpencodeOutputParser.parse(
                stream, 0, false, 50L, Collections.emptyMap(), SILENT);
        assertTrue(result.sessionIsError());
        assertEquals(OpencodeOutputParser.STOP_ERROR_UNKNOWN, result.stopReason());
    }

    // --- Provider-aware cost extraction (OpenRouter) --------------------------

    /**
     * When {@code reportsCost=true} and a {@code usage.cost} field is present,
     * the parsed result carries that cost. This is the OpenRouter case — the
     * provider returns accumulated cost in each session's usage block.
     */
    @Test(timeout = 5000)
    public void extractsCostFromUsageWhenReportsCostTrue() {
        String output = "{\"session_id\":\"s1\",\"steps\":3,\"stopReason\":\"success\","
                + "\"usage\":{\"cost\":0.0042,\"input_tokens\":500,\"output_tokens\":80}}";
        AgentRunResult result = OpencodeOutputParser.parse(
                output, 0, false, 100L, Collections.emptyMap(), SILENT, true);
        assertEquals(0.0042, result.costUsd(), 1e-9);
    }

    /**
     * When {@code reportsCost=false}, the cost field in usage is ignored and
     * costUsd stays 0.0. This is the local/llama.cpp case.
     */
    @Test(timeout = 5000)
    public void ignoresCostFromUsageWhenReportsCostFalse() {
        String output = "{\"session_id\":\"s1\",\"steps\":3,\"stopReason\":\"success\","
                + "\"usage\":{\"cost\":99.99,\"input_tokens\":500,\"output_tokens\":80}}";
        AgentRunResult result = OpencodeOutputParser.parse(
                output, 0, false, 100L, Collections.emptyMap(), SILENT, false);
        assertEquals(0.0, result.costUsd(), 1e-9);
    }

    /**
     * When the usage block is absent and {@code reportsCost=true}, cost defaults to 0.0
     * without throwing.
     */
    @Test(timeout = 5000)
    public void costDefaultsToZeroWhenUsageAbsent() {
        String output = "{\"session_id\":\"s1\",\"steps\":2,\"stopReason\":\"success\"}";
        AgentRunResult result = OpencodeOutputParser.parse(
                output, 0, false, 50L, Collections.emptyMap(), SILENT, true);
        assertEquals(0.0, result.costUsd(), 1e-9);
    }

    /** When no step_start events appear but text events carry messageIDs, count those. */
    @Test(timeout = 5000)
    public void countsDistinctMessageIdsWhenStepStartAbsent() {
        String stream = ""
                + "{\"type\":\"text\",\"sessionID\":\"ses_z\","
                + "\"part\":{\"messageID\":\"msg_a\",\"type\":\"text\",\"text\":\"first\"}}\n"
                + "{\"type\":\"text\",\"sessionID\":\"ses_z\","
                + "\"part\":{\"messageID\":\"msg_b\",\"type\":\"text\",\"text\":\"second\"}}\n";
        AgentRunResult result = OpencodeOutputParser.parse(
                stream, 0, false, 10L, Collections.emptyMap(), SILENT);
        assertEquals(2, result.numTurns());
        assertEquals("firstsecond", result.runnerMetadata().get("response_text"));
    }
}
