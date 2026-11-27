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

package io.flowtree.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import io.flowtree.submission.PhaseConfigResolver;
import io.flowtree.workstream.WorkspaceEntry;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PhaseConfigQueryHandler}, the
 * {@code POST /api/workstreams/{id}/phase-config} diagnostic and dry-run
 * validator: workstream lookup, workspace-layer resolution, hypothetical per-job
 * overrides, strict request validation, HTTP status codes, JSON serialization
 * and capability warnings.
 */
public class PhaseConfigQueryHandlerTest extends TestSuiteBase {

    /** Parses response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The id under which {@link #workstream} is resolvable. */
    private static final String WORKSTREAM_ID = "ws-phase-config";

    /** The workspace {@link #workstream} belongs to. */
    private static final String WORKSPACE_ID = "space-phase-config";

    /** The workstream the handler explains; reconfigured per test. */
    private Workstream workstream;

    /** The workspace the workstream belongs to; reconfigured per test. */
    private WorkspaceEntry workspace;

    /** The handler under test, bound to {@link #workstream} and {@link #workspace}. */
    private PhaseConfigQueryHandler handler;

    /** Builds a workstream in a workspace, with a workstream-default model and a workspace-default effort. */
    @Before
    public void setUp() {
        workstream = new Workstream(WORKSTREAM_ID, "C-phase", "#phase");
        workstream.setWorkspaceId(WORKSPACE_ID);
        workstream.setPhaseConfigBundle(new PhaseConfigBundle(
                new PhaseConfig(null, "claude-opus-5-5", null), Map.of()));
        workspace = new WorkspaceEntry();
        workspace.setId(WORKSPACE_ID);
        workspace.setDefaultPhaseConfig(new PhaseConfig(null, null, "low"));
        handler = new PhaseConfigQueryHandler(
                id -> WORKSTREAM_ID.equals(id) ? workstream : null,
                id -> WORKSPACE_ID.equals(id) ? workspace : null);
    }

    /** Reads the full body of {@code response} as JSON. */
    private static JsonNode json(Response response) throws IOException {
        return MAPPER.readTree(new String(response.getData().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Returns the {@code source} reported for {@code field} of {@code phase}. */
    private static String source(JsonNode body, Phase phase, String field) {
        return body.get("phaseConfigResolution").get(phase.wireName()).get(field).get("source").asText();
    }

    /** Asserts {@code response} is a {@code 400} whose error contains {@code expected}. */
    private void assertBadRequest(Response response, String expected) throws IOException {
        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        JsonNode body = json(response);
        assertFalse(body.get("ok").asBoolean());
        assertEquals(WORKSTREAM_ID, body.get("workstreamId").asText());
        assertTrue(body.toString(), body.get("error").asText().contains(expected));
    }

    /** An unknown workstream is a {@code 404} with {@code ok=false}, naming the id. */
    @Test(timeout = 10000)
    public void unknownWorkstreamIsNotFound() throws IOException {
        Response response = handler.handle("no-such-ws", null);
        assertEquals(Response.Status.NOT_FOUND, response.getStatus());
        JsonNode body = json(response);
        assertFalse(body.get("ok").asBoolean());
        assertEquals("no-such-ws", body.get("workstreamId").asText());
        assertTrue(body.toString(), body.get("error").asText().contains("no-such-ws"));
    }

    /**
     * With no body, every field is attributed to the workstream, workspace or
     * controller level that supplied it, and the resolved bundle is reported.
     */
    @Test(timeout = 10000)
    public void emptyBodyExplainsWorkstreamWorkspaceAndControllerLevels() throws IOException {
        for (String body : new String[] {null, "", "{}"}) {
            Response response = handler.handle(WORKSTREAM_ID, body);
            assertEquals(Response.Status.OK, response.getStatus());
            JsonNode json = json(response);
            assertTrue(json.toString(), json.get("ok").asBoolean());
            assertEquals(WORKSTREAM_ID, json.get("workstreamId").asText());
            for (Phase phase : Phase.values()) {
                assertEquals(PhaseConfigResolver.SOURCE_WORKSTREAM_DEFAULT, source(json, phase, "model"));
                assertEquals(PhaseConfigResolver.SOURCE_WORKSPACE_DEFAULT, source(json, phase, "effort"));
                assertEquals(PhaseConfigResolver.SOURCE_CONTROLLER_DEFAULT, source(json, phase, "runner"));
                assertEquals(PhaseConfigResolver.SOURCE_NONE, source(json, phase, "provider"));
            }
            JsonNode review = json.get("phaseConfigResolution").get(Phase.REVIEW.wireName());
            assertEquals("claude-opus-5-5", review.get("model").get("value").asText());
            assertEquals("low", review.get("effort").get("value").asText());
            assertEquals(AgentRunnerRegistry.CLAUDE, review.get("runner").get("value").asText());
            assertTrue(review.get("provider").get("value").isNull());
            assertNull("claude honours effort, so no warnings", json.get("warnings"));
        }
    }

    /** A hypothetical per-phase override is resolved as a job-level entry for that phase only. */
    @Test(timeout = 10000)
    public void hypotheticalOverrideIsAttributedToTheJob() throws IOException {
        JsonNode json = json(handler.handle(WORKSTREAM_ID,
                "{\"phaseConfigs\":{\"review\":{\"effort\":\"high\"}}}"));
        assertTrue(json.toString(), json.get("ok").asBoolean());
        assertEquals(PhaseConfigResolver.SOURCE_JOB_PHASE, source(json, Phase.REVIEW, "effort"));
        assertEquals("high", json.get("phaseConfigResolution").get(Phase.REVIEW.wireName())
                .get("effort").get("value").asText());
        assertEquals(PhaseConfigResolver.SOURCE_WORKSPACE_DEFAULT, source(json, Phase.PRIMARY, "effort"));
    }

    /** A workstream whose workspace does not resolve still explains its own layers. */
    @Test(timeout = 10000)
    public void missingWorkspaceSkipsTheWorkspaceLayer() throws IOException {
        workstream.setWorkspaceId("unresolvable-space");
        JsonNode json = json(handler.handle(WORKSTREAM_ID, null));
        assertTrue(json.toString(), json.get("ok").asBoolean());
        assertEquals(PhaseConfigResolver.SOURCE_WORKSTREAM_DEFAULT, source(json, Phase.PRIMARY, "model"));
        assertEquals(PhaseConfigResolver.SOURCE_NONE, source(json, Phase.PRIMARY, "effort"));
    }

    /** A model the resolved runner would refuse is a {@code 400} naming the model. */
    @Test(timeout = 10000)
    public void unsupportedModelIsBadRequest() throws IOException {
        assertBadRequest(handler.handle(WORKSTREAM_ID,
                "{\"phaseConfigs\":{\"review\":{\"runner\":\"claude\",\"model\":\"gpt-4\"}}}"), "gpt-4");
    }

    /** An opencode provider outside the runner's advertised set is a {@code 400}, not a later run failure. */
    @Test(timeout = 10000)
    public void unknownOpencodeProviderIsBadRequest() throws IOException {
        assertBadRequest(handler.handle(WORKSTREAM_ID,
                "{\"defaultPhaseConfig\":{\"runner\":\"opencode\",\"model\":null,"
                        + "\"provider\":\"no-such-provider\"}}"), "no-such-provider");
    }

    /** Malformed JSON is a {@code 400} rather than a silent explanation of the current configuration. */
    @Test(timeout = 10000)
    public void malformedBodyIsBadRequest() throws IOException {
        assertBadRequest(handler.handle(WORKSTREAM_ID, "{\"phaseConfigs\":"), "not valid JSON");
    }

    /** A non-string field value, which the lenient submit parser would drop, is a {@code 400}. */
    @Test(timeout = 10000)
    public void nonStringValueIsBadRequest() throws IOException {
        assertBadRequest(handler.handle(WORKSTREAM_ID,
                "{\"phaseConfigs\":{\"review\":{\"model\":42}}}"), "phaseConfigs.review.model");
    }

    /** An unknown phase name is a {@code 400} naming it. */
    @Test(timeout = 10000)
    public void unknownPhaseIsBadRequest() throws IOException {
        assertBadRequest(handler.handle(WORKSTREAM_ID,
                "{\"fallbackPhaseConfigs\":{\"not-a-phase\":{\"runner\":\"claude\"}}}"), "not-a-phase");
    }

    /** A removed legacy field is rejected exactly as the submit path rejects it. */
    @Test(timeout = 10000)
    public void legacyFieldIsBadRequest() throws IOException {
        assertBadRequest(handler.handle(WORKSTREAM_ID, "{\"model\":\"claude-opus-5-5\"}"), "model");
    }

    /** An effort set for a runner that cannot honour it is reported as a warning, not an error. */
    @Test(timeout = 10000)
    public void effortForOpencodeIsWarned() throws IOException {
        JsonNode json = json(handler.handle(WORKSTREAM_ID,
                "{\"phaseConfigs\":{\"review\":{\"runner\":\"opencode\",\"model\":\"some-model\"}}}"));
        assertTrue(json.toString(), json.get("ok").asBoolean());
        JsonNode warnings = json.get("warnings");
        assertEquals(json.toString(), 1, warnings.size());
        String warning = warnings.get(0).asText();
        assertTrue(warning, warning.contains(Phase.REVIEW.wireName()));
        assertTrue(warning, warning.contains(AgentRunnerRegistry.OPENCODE));
        assertTrue(warning, warning.contains("low"));
    }
}
