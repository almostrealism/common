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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the resolution rules and JSON translation layers in
 * {@link OpencodeConfigBuilder}.
 */
public class OpencodeConfigBuilderTest extends TestSuiteBase {

    /** JSON mapper for assertions. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A representative orchestrator MCP config with both HTTP and stdio servers. */
    private static final String MCP_INPUT = "{"
            + "\"mcpServers\":{"
            + "\"ar-manager\":{\"type\":\"http\",\"url\":\"http://localhost:7780\","
            + "\"headers\":{\"Authorization\":\"Bearer abc\"}},"
            + "\"ar-build-validator\":{\"command\":\"python3\","
            + "\"args\":[\"tools/mcp/build_validator/server.py\"]}"
            + "}}";

    /** When {@code OPENCODE_PROVIDER_URL} is set, the builder returns it verbatim. */
    @Test(timeout = 5000)
    public void resolveProviderUrlReadsEnvFirst() {
        Map<String, String> env = new HashMap<>();
        env.put(OpencodeConfigBuilder.ENV_PROVIDER_URL, "http://10.0.0.5:11434/v1");
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(env::get);
        assertEquals("http://10.0.0.5:11434/v1", b.resolveProviderUrl());
    }

    /** Without {@code OPENCODE_PROVIDER_URL}, the builder uses the ollama default. */
    @Test(timeout = 5000)
    public void resolveProviderUrlDefaultsToOllama() {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        assertEquals(OpencodeConfigBuilder.DEFAULT_PROVIDER_URL, b.resolveProviderUrl());
    }

    /** API key resolution honors {@code OPENCODE_API_KEY}; empty by default. */
    @Test(timeout = 5000)
    public void resolveApiKeyReadsEnvOrEmpty() {
        Map<String, String> env = new HashMap<>();
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(env::get);
        assertEquals("", b.resolveApiKey());

        env.put(OpencodeConfigBuilder.ENV_API_KEY, "sk-test-xyz");
        OpencodeConfigBuilder withKey = new OpencodeConfigBuilder(env::get);
        assertEquals("sk-test-xyz", withKey.resolveApiKey());
    }

    /** Model precedence: request > env > hardcoded fallback. */
    @Test(timeout = 5000)
    public void resolveModelHonorsPrecedence() {
        Map<String, String> env = new HashMap<>();
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(env::get);
        assertEquals(OpencodeConfigBuilder.FALLBACK_MODEL, b.resolveModel(null));
        assertEquals(OpencodeConfigBuilder.FALLBACK_MODEL, b.resolveModel(""));

        env.put(OpencodeConfigBuilder.ENV_DEFAULT_MODEL, "env-override");
        OpencodeConfigBuilder withEnv = new OpencodeConfigBuilder(env::get);
        assertEquals("env-override", withEnv.resolveModel(null));
        assertEquals("requested-model", withEnv.resolveModel("requested-model"));
    }

    /** Qualified-model identifier wraps the resolved name in the {@code local/} provider prefix. */
    @Test(timeout = 5000)
    public void resolveQualifiedModelPrefixesProvider() {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        assertEquals("local/" + OpencodeConfigBuilder.FALLBACK_MODEL, b.resolveQualifiedModel(null));
        assertEquals("local/requested-model", b.resolveQualifiedModel("requested-model"));
    }

    /** HTTP MCP servers become opencode {@code type=remote} entries with headers preserved. */
    @Test(timeout = 5000)
    public void translateMcpServersHandlesHttpEntries() throws Exception {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        ObjectNode mcp = b.translateMcpServers(MCP_INPUT);
        JsonNode manager = mcp.get("ar-manager");
        assertNotNull(manager);
        assertEquals("remote", manager.path("type").asText());
        assertEquals("http://localhost:7780", manager.path("url").asText());
        assertEquals("Bearer abc", manager.path("headers").path("Authorization").asText());
        assertTrue(manager.path("enabled").asBoolean());
    }

    /** Stdio MCP servers become opencode {@code type=local} entries with a flattened command list. */
    @Test(timeout = 5000)
    public void translateMcpServersHandlesStdioEntries() {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        ObjectNode mcp = b.translateMcpServers(MCP_INPUT);
        JsonNode bv = mcp.get("ar-build-validator");
        assertNotNull(bv);
        assertEquals("local", bv.path("type").asText());
        assertTrue(bv.path("enabled").asBoolean());
        JsonNode cmd = bv.path("command");
        assertTrue(cmd.isArray());
        assertEquals("python3", cmd.get(0).asText());
        assertEquals("tools/mcp/build_validator/server.py", cmd.get(1).asText());
    }

    /** Empty or missing input produces an empty MCP block (not null). */
    @Test(timeout = 5000)
    public void translateMcpServersTreatsEmptyInputAsEmpty() {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        assertEquals(0, b.translateMcpServers(null).size());
        assertEquals(0, b.translateMcpServers("").size());
        assertEquals(0, b.translateMcpServers("{}").size());
    }

    /**
     * Built-in tool names become per-tool allow entries; each MCP server with
     * any allowed tool becomes a single {@code "allow"} action string —
     * opencode 1.x's PermissionActionConfig does not support per-tool
     * granularity on MCP servers, only a single action per server.
     */
    @Test(timeout = 5000)
    public void translateAllowlistHandlesBuiltinsAndMcp() {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        ObjectNode permission = b.translateAllowlist(
                "Read,Edit,Bash,mcp__ar-manager__memory_store,mcp__ar-manager__memory_recall,"
                        + "mcp__ar-build-validator__start_validation");

        JsonNode tools = permission.path("tools");
        assertEquals("allow", tools.path("Read").asText());
        assertEquals("allow", tools.path("Edit").asText());
        assertEquals("allow", tools.path("Bash").asText());
        assertFalse(tools.has("Write"));

        JsonNode mcp = permission.path("mcp");
        assertEquals("allow", mcp.path("ar-manager").asText());
        assertEquals("allow", mcp.path("ar-build-validator").asText());
        // Two distinct ar-manager tool entries collapse to a single per-server allow.
        assertEquals(2, mcp.size());
    }

    /**
     * Empty allowlist input yields an empty permission block. The always-on
     * {@code external_directory} grant is added by {@link OpencodeConfigBuilder#buildConfigJson},
     * not by {@link OpencodeConfigBuilder#translateAllowlist}, so it must not
     * leak into the bare translator output.
     */
    @Test(timeout = 5000)
    public void translateAllowlistHandlesEmptyInput() {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        assertEquals(0, b.translateAllowlist(null).size());
        assertEquals(0, b.translateAllowlist("").size());
    }

    /** A full config build threads provider URL, model, MCP, and permissions into one document. */
    @Test(timeout = 5000)
    public void buildConfigJsonIncludesAllSections() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put(OpencodeConfigBuilder.ENV_PROVIDER_URL, "http://localhost:8080/v1");
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(env::get);

        AgentRunRequest req = AgentRunRequest.builder()
                .model("custom-model")
                .mcpConfigJson(MCP_INPUT)
                .allowedTools("Read,mcp__ar-manager__memory_store")
                .build();

        String json = b.buildConfigJson(req);
        JsonNode root = MAPPER.readTree(json);
        assertEquals("local/custom-model", root.path("model").asText());
        assertEquals("http://localhost:8080/v1",
                root.path("provider").path("local").path("options").path("baseURL").asText());
        // No API key set, so it should be absent rather than empty.
        assertNull(root.path("provider").path("local").path("options").get("apiKey"));
        assertTrue(root.path("provider").path("local").path("models").has("custom-model"));
        assertTrue(root.path("mcp").has("ar-manager"));
        assertEquals("allow", root.path("permission").path("tools").path("Read").asText());
        assertEquals("allow", root.path("permission").path("mcp").path("ar-manager").asText());
        // external_directory is always granted in the full build so headless
        // runs don't hang on opencode's interactive permission prompt.
        assertEquals("allow", root.path("permission").path("external_directory").asText());
    }

    /** When the canonical {@code {"mcpServers":{}}} shape is empty, no top-level {@code mcp} block is emitted. */
    @Test(timeout = 5000)
    public void buildConfigJsonOmitsEmptyMcpBlock() throws Exception {
        OpencodeConfigBuilder b = new OpencodeConfigBuilder(new HashMap<String, String>()::get);
        AgentRunRequest req = AgentRunRequest.builder()
                .mcpConfigJson("{\"mcpServers\":{}}")
                .allowedTools("Read")
                .build();
        JsonNode root = MAPPER.readTree(b.buildConfigJson(req));
        assertFalse("empty mcp block should be omitted", root.has("mcp"));
    }
}
