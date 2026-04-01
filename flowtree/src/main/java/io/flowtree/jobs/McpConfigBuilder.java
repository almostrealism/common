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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds MCP configuration JSON and allowed-tools strings for the
 * Claude Code command line.
 *
 * <p>ar-manager is the single centralized MCP tool for all agent jobs.
 * It provides messaging, memory, GitHub, and workstream tools.  When
 * an ar-manager URL and auth token are configured, this builder emits
 * it as an HTTP entry in the MCP config.</p>
 *
 * <p>Project-local servers discovered from {@code .mcp.json} (e.g.,
 * ar-test-runner, ar-docs) are included alongside ar-manager.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob
 * @see McpToolDiscovery
 */
public class McpConfigBuilder implements ConsoleFeatures {

    /** ar-manager tools that are always included for agent jobs. */
    private static final String AR_MANAGER_TOOLS =
        "mcp__ar-manager__send_message," +
        "mcp__ar-manager__memory_recall," +
        "mcp__ar-manager__memory_store," +
        "mcp__ar-manager__memory_branch_context," +
        "mcp__ar-manager__github_pr_find," +
        "mcp__ar-manager__github_pr_review_comments," +
        "mcp__ar-manager__github_pr_conversation," +
        "mcp__ar-manager__github_pr_reply," +
        "mcp__ar-manager__github_list_open_prs," +
        "mcp__ar-manager__github_create_pr," +
        "mcp__ar-manager__workstream_get_status," +
        "mcp__ar-manager__controller_health";

    /** Shared Jackson mapper for serializing the MCP config JSON. */
    private static final ObjectMapper mapper = new ObjectMapper();

    /** ar-manager service URL (e.g., {@code "http://ar-manager:8010"}). */
    private String arManagerUrl;

    /** Temporary HMAC bearer token for ar-manager authentication. */
    private String arManagerToken;

    /** Project working directory used to locate {@code .mcp.json}. */
    private Path workingDirectory;

    /** Python executable used to launch stdio MCP servers (default: {@code "python3"}). */
    private String pythonCommand = "python3";

    /**
     * Sets the ar-manager HTTP URL for centralized tool access.
     *
     * @param url the ar-manager service URL (e.g., "http://ar-manager:8010")
     */
    public void setArManagerUrl(String url) {
        this.arManagerUrl = url;
    }

    /**
     * Sets the HMAC temporary auth token for ar-manager.
     *
     * @param token the bearer token (e.g., "armt_tmp_...")
     */
    public void setArManagerToken(String token) {
        this.arManagerToken = token;
    }

    /**
     * Sets the working directory used for discovering project MCP servers.
     *
     * @param workingDirectory the project working directory
     */
    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Sets the Python command used to launch project MCP servers.
     *
     * <p>Defaults to {@code "python3"}. When a managed venv is active,
     * this should be set to the absolute path of the venv's python
     * binary (e.g., {@code ~/.flowtree/venv/bin/python3}).</p>
     *
     * @param pythonCommand the python executable path or name
     */
    public void setPythonCommand(String pythonCommand) {
        this.pythonCommand = pythonCommand;
    }

    /**
     * Builds the JSON MCP configuration string for the {@code --mcp-config} flag.
     *
     * <p>The output has the structure {@code {"mcpServers":{...}}} containing:</p>
     * <ul>
     *   <li><b>ar-manager:</b> HTTP entry with bearer token auth (when URL and token are set)</li>
     *   <li><b>Project servers:</b> discovered from {@code .mcp.json} (skipping
     *       ar-manager to avoid duplication)</li>
     * </ul>
     *
     * @return the MCP configuration JSON string
     */
    public String buildMcpConfig() {
        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");

        ObjectNode root = mapper.createObjectNode();
        ObjectNode mcpServers = root.putObject("mcpServers");

        // ar-manager: centralized HTTP entry with auth
        if (arManagerUrl != null && !arManagerUrl.isEmpty()
                && arManagerToken != null && !arManagerToken.isEmpty()) {
            String url = arManagerUrl;
            if (rootHost != null && !rootHost.isEmpty()) {
                url = url.replace("0.0.0.0", rootHost);
            }

            ObjectNode managerNode = mcpServers.putObject("ar-manager");
            managerNode.put("type", "http");
            managerNode.put("url", url);

            ObjectNode headersNode = managerNode.putObject("headers");
            headersNode.put("Authorization", "Bearer " + arManagerToken);

            log("ar-manager HTTP entry: " + url);
        }

        // Include project MCP servers discovered from .mcp.json
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            ObjectNode serverNode = mcpServers.putObject(entry.getKey());
            serverNode.put("command", pythonCommand);
            ArrayNode argsArray = serverNode.putArray("args");
            argsArray.add(entry.getValue());
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            warn("Failed to serialize MCP config: " + e.getMessage());
            return "{\"mcpServers\":{}}";
        }
    }

    /**
     * Builds the comma-separated allowed tools list for the
     * {@code --allowedTools} flag.
     *
     * <p>Starting with the provided base tools string, this method appends:</p>
     * <ul>
     *   <li>ar-manager tools (when URL and token are configured)</li>
     *   <li>Tools from discovered project servers</li>
     * </ul>
     *
     * @param baseTools the base comma-separated tools string (e.g., "Read,Edit,Bash")
     * @return the complete comma-separated allowed tools string
     */
    public String buildAllowedTools(String baseTools) {
        StringBuilder sb = new StringBuilder(baseTools);

        // Add ar-manager tools when configured
        if (arManagerUrl != null && !arManagerUrl.isEmpty()
                && arManagerToken != null && !arManagerToken.isEmpty()) {
            sb.append(",").append(AR_MANAGER_TOOLS);
            log("Added ar-manager tools");
        }

        // Discover and include tools from project MCP servers
        Path workDir = resolveWorkingDirectory();
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            String serverName = entry.getKey();
            Path serverFile = workDir.resolve(entry.getValue());
            List<String> tools = discoverToolNames(serverFile);
            for (String tool : tools) {
                sb.append(",mcp__").append(serverName).append("__").append(tool);
            }
            if (!tools.isEmpty()) {
                log("Discovered " + tools.size() + " tools from " + serverName);
            }
        }

        return sb.toString();
    }

    /**
     * Discovers MCP servers defined in the project's {@code .mcp.json} file
     * and cross-references with {@code .claude/settings.json} to determine
     * which servers are enabled.
     *
     * <p>The {@code ar-manager} server is always excluded from discovery
     * since it is handled separately as a centralized HTTP service.</p>
     *
     * @return map of server name to Python source file path (relative to working dir)
     */
    Map<String, String> discoverProjectMcpServers() {
        Map<String, String> servers = new LinkedHashMap<>();
        Path workDir = resolveWorkingDirectory();

        Path mcpJson = workDir.resolve(".mcp.json");
        if (!Files.exists(mcpJson)) return servers;

        Map<String, String> allServers = parseMcpJson(mcpJson);
        if (allServers.isEmpty()) return servers;

        List<String> enabled = parseEnabledServers(workDir.resolve(".claude/settings.json"));

        for (Map.Entry<String, String> entry : allServers.entrySet()) {
            String name = entry.getKey();
            // ar-manager is centralized — skip it in project discovery
            if ("ar-manager".equals(name)) continue;
            if (enabled.isEmpty() || enabled.contains(name)) {
                servers.put(name, entry.getValue());
            }
        }

        return servers;
    }

    /**
     * Delegates tool name discovery to {@link McpToolDiscovery#discoverToolNames(Path)}.
     *
     * @param serverFile path to the Python server source file
     * @return list of tool function names
     */
    List<String> discoverToolNames(Path serverFile) {
        return McpToolDiscovery.discoverToolNames(serverFile);
    }

    /**
     * Parses {@code .mcp.json} to extract server names and their Python
     * source file paths using Jackson {@link ObjectMapper}.
     *
     * @param mcpJsonPath path to the {@code .mcp.json} file
     * @return map of server name to source file path (first arg)
     */
    private Map<String, String> parseMcpJson(Path mcpJsonPath) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(Files.readString(mcpJsonPath, StandardCharsets.UTF_8));
            JsonNode mcpServers = root.get("mcpServers");
            if (mcpServers == null || !mcpServers.isObject()) return result;

            Iterator<String> fieldNames = mcpServers.fieldNames();
            while (fieldNames.hasNext()) {
                String serverName = fieldNames.next();
                JsonNode serverNode = mcpServers.get(serverName);
                if (serverNode == null || !serverNode.isObject()) continue;

                JsonNode argsNode = serverNode.get("args");
                if (argsNode == null || !argsNode.isArray() || argsNode.isEmpty()) continue;

                JsonNode firstArg = argsNode.get(0);
                if (firstArg != null && firstArg.isTextual()) {
                    result.put(serverName, firstArg.asText());
                }
            }
        } catch (IOException e) {
            warn("Failed to read .mcp.json: " + e.getMessage());
        }
        return result;
    }

    /**
     * Parses {@code .claude/settings.json} to extract the list of
     * enabled MCP servers from the {@code enabledMcpjsonServers} field.
     *
     * @param settingsPath path to the {@code .claude/settings.json} file
     * @return list of enabled server names, or empty list if file doesn't exist
     */
    private List<String> parseEnabledServers(Path settingsPath) {
        List<String> enabled = new ArrayList<>();
        if (!Files.exists(settingsPath)) return enabled;

        try {
            JsonNode root = mapper.readTree(Files.readString(settingsPath, StandardCharsets.UTF_8));
            JsonNode enabledNode = root.get("enabledMcpjsonServers");
            if (enabledNode == null || !enabledNode.isArray()) return enabled;

            for (JsonNode entry : enabledNode) {
                if (entry.isTextual()) {
                    enabled.add(entry.asText());
                }
            }
        } catch (IOException e) {
            warn("Failed to read settings.json: " + e.getMessage());
        }
        return enabled;
    }

    /**
     * Resolves the working directory, falling back to the current directory
     * if none was explicitly set.
     *
     * @return the resolved working directory path
     */
    private Path resolveWorkingDirectory() {
        if (workingDirectory != null) {
            return workingDirectory;
        }
        return Path.of(System.getProperty("user.dir"));
    }
}
