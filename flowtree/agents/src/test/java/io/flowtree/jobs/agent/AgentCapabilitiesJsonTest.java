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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.JsonFieldExtractor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the JSON self-descriptions of {@link AgentCapabilities} and
 * {@link Phase} that {@code GET /api/agents} assembles, and that
 * {@link AgentCapabilities} keeps the model order its runner declared.
 */
public class AgentCapabilitiesJsonTest extends TestSuiteBase {

    /** Every capability flag and both identifier sets appear under their component names. */
    @Test(timeout = 5000)
    public void capabilitiesJsonCarriesEveryComponent() {
        AgentCapabilities cap = new AgentCapabilities(true, false, true, false, true, false, true,
                new LinkedHashSet<>(List.of("opus", "claude-opus-5")),
                new LinkedHashSet<>(List.of("anthropic")));
        ObjectNode json = cap.toJson();
        assertTrue(json.get("reportsCost").asBoolean());
        assertFalse(json.get("reportsTurns").asBoolean());
        assertTrue(json.get("supportsEffortLevel").asBoolean());
        assertFalse(json.get("supportsMaxBudget").asBoolean());
        assertTrue(json.get("supportsMcpHttpTransport").asBoolean());
        assertFalse(json.get("supportsMcpStdioTransport").asBoolean());
        assertTrue(json.get("supportsPermissionDenialReporting").asBoolean());
        assertEquals(JsonFieldExtractor.MAPPER.valueToTree(List.of("opus", "claude-opus-5")),
                json.get("supportedModels"));
        assertEquals(JsonFieldExtractor.MAPPER.valueToTree(List.of("anthropic")),
                json.get("supportedProviders"));
    }

    /** The Claude runner advertises its models in declaration order, aliases first. */
    @Test(timeout = 5000)
    public void claudeCapabilitiesKeepDeclaredModelOrder() {
        AgentCapabilities cap = new ClaudeCodeRunner().capabilities();
        assertEquals(ClaudeCodeRunner.VALID_MODELS, new ArrayList<>(cap.supportedModels()));
    }

    /** A non-empty model set advertises only its members; an empty set advertises every model. */
    @Test(timeout = 5000)
    public void advertisesModelHonorsUnconstrainedEmptySet() {
        AgentCapabilities constrained = new AgentCapabilities(false, false, false, false, false, false, false,
                new LinkedHashSet<>(List.of("opus")));
        assertTrue(constrained.advertisesModel("opus"));
        assertFalse(constrained.advertisesModel("sonnet"));
        AgentCapabilities unconstrained = new AgentCapabilities(false, false, false, false, false, false, false,
                null);
        assertTrue(unconstrained.advertisesModel("sonnet"));
    }

    /** Each phase describes itself by wire name and description. */
    @Test(timeout = 5000)
    public void phaseJsonCarriesWireNameAndDescription() {
        for (Phase phase : Phase.values()) {
            ObjectNode json = phase.toJson();
            assertEquals(phase.wireName(), json.get("name").asText());
            assertEquals(phase.description(), json.get("description").asText());
        }
    }
}
