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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * ar-manager tool names (without the {@code mcp__ar-manager__} prefix)
     * that are always included for agent jobs.
     *
     * <p>Tracker tools are read-only for agents. Task endpoints
     * ({@code tracker_get_task}, {@code tracker_list_tasks}, and
     * {@code tracker_search_tasks}) are workspace-scoped on the server
     * and only expose tasks attached to a workstream in the agent's
     * workspace. Other included tracker read endpoints
     * ({@code tracker_project_summary}, {@code tracker_list_projects},
     * and {@code tracker_list_releases}) remain available for shared
     * project and release visibility and are not documented here as
     * strictly workspace-filtered.</p>
     *
     * <p>{@code workstream_submit_task} is included so agents can
     * delegate work to other workstreams in the same workspace. The
     * server rejects self-submission (a job targeting the agent's own
     * workstream) with an explanatory error, since that would cause
     * two agents to commit to the same git branch concurrently.</p>
     *
     * <p>The {@code workspace_secret_list_names} and
     * {@code workspace_secret_render_file} tools also exist on the
     * ar-manager server but are deliberately excluded from this list —
     * see {@link #EXCLUDED_AR_MANAGER_TOOLS}. Agents stage workspace
     * credentials through the in-container {@code ar-secrets} MCP server
     * instead, which writes the rendered file in the agent's own
     * filesystem namespace.</p>
     *
     * <p>The set of ar-manager tools that exist on the server but are
     * deliberately NOT in this list is documented in
     * {@link #EXCLUDED_AR_MANAGER_TOOLS}. The
     * {@code allowlistCoversEveryArManagerTool} test in
     * {@code McpConfigBuilderTest} fails when a newly registered
     * server tool is in neither set, forcing a deliberate decision
     * before merging.</p>
     */
    static final Set<String> AR_MANAGER_TOOL_NAMES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            "controller_health",
            "send_message",
            "memory_recall",
            "memory_store",
            "workstream_context",
            "workstream_list",
            "workstream_get_status",
            "workstream_get_job",
            "workstream_submit_task",
            "github_pr_find",
            "github_pr_review_comments",
            "github_pr_conversation",
            "github_pr_reply",
            "github_list_open_prs",
            "github_create_pr",
            "github_request_copilot_review",
            "github_read_file",
            "github_pr_check_status",
            "project_read_plan",
            "tracker_get_task",
            "tracker_list_tasks",
            "tracker_search_tasks",
            "tracker_project_summary",
            "tracker_list_projects",
            "tracker_list_releases"
        ))
    );

    /**
     * ar-manager tool names that are deliberately NOT exposed to coding
     * agents. Each tool listed here is either an admin/orchestration
     * operation (creating workstreams, dispatching workflows, mutating
     * controller config) or a shared-state mutation (creating, updating,
     * or deleting tracker projects, releases, or tasks).
     *
     * <p>Tools in this set still exist on the ar-manager server but
     * cannot be invoked by agent jobs through the
     * {@code --allowedTools} list. Operators retain access via the
     * Slack MCP integration and the ar-manager HTTP API.</p>
     *
     * <p>Together with {@link #AR_MANAGER_TOOL_NAMES}, this set must
     * cover every {@code @mcp.tool()} function in
     * {@code tools/mcp/manager/server.py}. The
     * {@code allowlistCoversEveryArManagerTool} test fails if a tool
     * is missing from both sets.</p>
     */
    static final Set<String> EXCLUDED_AR_MANAGER_TOOLS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            // Controller admin
            "controller_update_config",
            // Workstream admin
            "workstream_register",
            "workstream_update_config",
            // Project orchestration / mutations
            "project_create_branch",
            "project_verify_branch",
            "project_commit_plan",
            // Tracker mutations (agents must not alter the shared tracker)
            "tracker_create_project",
            "tracker_update_project",
            "tracker_delete_project",
            "tracker_create_release",
            "tracker_update_release",
            "tracker_delete_release",
            "tracker_create_task",
            "tracker_update_task",
            "tracker_delete_task",
            // Workspace secrets: still callable by admin/Slack flows on the
            // controller, but agents use the in-container ar-secrets MCP
            // server (tools/mcp/secrets/server.py) so the rendered file
            // lands in the agent's filesystem rather than ar-manager's.
            "workspace_secret_list_names",
            "workspace_secret_render_file"
        ))
    );

    /**
     * Comma-separated {@code mcp__ar-manager__*} entries appended to the
     * {@code --allowedTools} flag when ar-manager is configured.
     */
    private static final String AR_MANAGER_TOOLS = buildArManagerToolsCsv();

    /**
     * Builds the comma-separated {@code mcp__ar-manager__*} entry list
     * from {@link #AR_MANAGER_TOOL_NAMES}.
     *
     * @return comma-separated allowed-tool entries for the agent allowlist
     */
    private static String buildArManagerToolsCsv() {
        StringBuilder sb = new StringBuilder();
        for (String name : AR_MANAGER_TOOL_NAMES) {
            if (sb.length() > 0) sb.append(',');
            sb.append("mcp__ar-manager__").append(name);
        }
        return sb.toString();
    }

    /**
     * Maximum characters of a {@code pushedToolsConfig} JSON to include in
     * diagnostic log lines. Strings longer than this are truncated with an
     * ellipsis so logs remain readable when the full config is large.
     */
    private static final int PUSHED_TOOLS_CONFIG_PREVIEW_LIMIT = 200;

    /** Shared Jackson mapper for serializing the MCP config JSON. */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Renders a {@code pushedToolsConfig} JSON string in a form safe and useful
     * for log output. {@code null} becomes {@code "<null>"}, the empty string
     * becomes {@code "<empty>"}, and longer values are truncated to
     * {@link #PUSHED_TOOLS_CONFIG_PREVIEW_LIMIT} characters with an ellipsis.
     *
     * <p>Used across the pushed-tools pipeline ({@link ClaudeCodeJobFactory},
     * {@link ClaudeCodeJob}, {@link ManagedToolsDownloader}, and the
     * submission sites in the controller) so that every stage emits a
     * consistent, comparable preview of the value it observed.</p>
     *
     * @param config the pushed-tools configuration JSON; may be {@code null}
     * @return a log-safe preview string, never {@code null}
     */
    public static String pushedToolsConfigPreview(String config) {
        if (config == null) return "<null>";
        if (config.isEmpty()) return "<empty>";
        if (config.length() <= PUSHED_TOOLS_CONFIG_PREVIEW_LIMIT) return config;
        return config.substring(0, PUSHED_TOOLS_CONFIG_PREVIEW_LIMIT) + "...";
    }

    /** ar-manager service URL (e.g., {@code "http://ar-manager:8010"}). */
    private String arManagerUrl;

    /** Temporary HMAC bearer token for ar-manager authentication. */
    private String arManagerToken;

    /** Pushed-tools configuration JSON (see {@link #setPushedToolsConfig}). */
    private String pushedToolsConfig;

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
     * Sets the pushed-tools configuration JSON. The expected schema is a
     * map keyed by server name with each value carrying {@code url},
     * {@code tools} (array of tool names), and an optional {@code env}
     * map. Servers in this config become stdio entries pointing at
     * {@code ~/.flowtree/tools/mcp/{name}/server.py} in the agent
     * container; the source file is provisioned separately by
     * {@link ManagedToolsDownloader#ensurePushedTools(String)}.
     *
     * @param config the JSON configuration; may be {@code null}
     */
    public void setPushedToolsConfig(String config) {
        this.pushedToolsConfig = config;
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

        // Pushed tools: provisioned to ~/.flowtree/tools/mcp/{name}/server.py
        // by ManagedToolsDownloader and registered here as stdio entries.
        Map<String, JsonNode> pushed = parsePushedConfig();
        String homeDir = System.getProperty("user.home");
        for (Map.Entry<String, JsonNode> entry : pushed.entrySet()) {
            String name = entry.getKey();
            ObjectNode serverNode = mcpServers.putObject(name);
            serverNode.put("command", pythonCommand);
            ArrayNode argsArray = serverNode.putArray("args");
            argsArray.add(homeDir + "/.flowtree/tools/mcp/" + name + "/server.py");
            JsonNode envNode = entry.getValue().get("env");
            if (envNode != null && envNode.isObject()) serverNode.set("env", envNode);
        }

        // Include project MCP servers discovered from .mcp.json (skip
        // anything already covered by a pushed entry above)
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            if (pushed.containsKey(entry.getKey())) continue;
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
     * Pattern that a pushed MCP server name must match.
     * Allows only lowercase ASCII letters, digits, and hyphens, with a
     * letter or digit as the first character. This prevents names that
     * contain path separators (e.g. {@code ../evil}) or characters that
     * alter the MCP config JSON or allowed-tools string.
     */
    static final Pattern SAFE_SERVER_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    /**
     * Pattern that a pushed MCP tool name must match.
     * Allows only lowercase ASCII letters, digits, and underscores, with a
     * letter or underscore as the first character. This prevents names that
     * contain commas or other characters that could inject entries into the
     * comma-separated {@code --allowedTools} string.
     */
    static final Pattern SAFE_TOOL_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /**
     * Returns {@code true} when {@code name} is a safe pushed MCP server name.
     *
     * @param name candidate server name from pushed-tools JSON
     * @return true if the name matches {@link #SAFE_SERVER_NAME}
     */
    static boolean isValidServerName(String name) {
        return name != null && SAFE_SERVER_NAME.matcher(name).matches();
    }

    /**
     * Returns {@code true} when {@code name} is a safe MCP tool name.
     *
     * @param name candidate tool name from pushed-tools JSON
     * @return true if the name matches {@link #SAFE_TOOL_NAME}
     */
    static boolean isValidToolName(String name) {
        return name != null && SAFE_TOOL_NAME.matcher(name).matches();
    }

    /**
     * Parses {@link #pushedToolsConfig} into a server-name → entry map.
     * Returns an empty map when the config is {@code null}, empty, or
     * malformed. Each entry value is the raw {@link JsonNode} for that
     * server (carrying {@code tools}, optional {@code env}, etc.).
     *
     * <p>Server names that do not match {@link #SAFE_SERVER_NAME} are
     * silently skipped to prevent path-traversal attacks when the name is
     * used as a filesystem path segment (see {@link #buildMcpConfig()}).</p>
     *
     * @return parsed pushed-tools map, never {@code null}
     */
    Map<String, JsonNode> parsePushedConfig() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (pushedToolsConfig == null || pushedToolsConfig.isEmpty()) return result;
        try {
            JsonNode root = mapper.readTree(pushedToolsConfig);
            if (!root.isObject()) return result;
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!isValidServerName(name)) {
                    warn("Skipping pushed server with invalid name: " + name);
                    continue;
                }
                JsonNode node = root.get(name);
                if (node != null && node.isObject()) result.put(name, node);
            }
        } catch (IOException e) {
            warn("Failed to parse pushedToolsConfig: " + e.getMessage());
        }
        return result;
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

        // Pushed-tool tools: tool names are listed verbatim in the JSON.
        // Both the server name and the tool name are validated before being
        // appended so a malformed pushed config cannot inject entries into
        // the --allowedTools string (server names are already validated in
        // parsePushedConfig; tool names are validated here).
        Map<String, JsonNode> pushed = parsePushedConfig();
        for (Map.Entry<String, JsonNode> entry : pushed.entrySet()) {
            JsonNode toolsNode = entry.getValue().get("tools");
            if (toolsNode == null || !toolsNode.isArray()) continue;
            int count = 0;
            for (JsonNode tn : toolsNode) {
                if (tn.isTextual()) {
                    String toolName = tn.asText();
                    if (!isValidToolName(toolName)) {
                        warn("Skipping pushed tool with invalid name: " + toolName);
                        continue;
                    }
                    sb.append(",mcp__").append(entry.getKey()).append("__").append(toolName);
                    count++;
                }
            }
            log("Added " + count + " tool(s) from pushed server " + entry.getKey());
        }

        // Discover and include tools from project MCP servers
        Path workDir = resolveWorkingDirectory();
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            String serverName = entry.getKey();
            if (pushed.containsKey(serverName)) continue;
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
     * Injects environment variables consumed by in-container MCP helpers
     * (notably {@code ar-secrets}) onto the agent {@link ProcessBuilder}'s
     * environment map. Sets {@code AR_WORKSTREAM_URL},
     * {@code AR_CONTROLLER_URL}, {@code AR_WORKSTREAM_ID}, and
     * {@code AR_MANAGER_TOKEN} when their source values are non-empty.
     *
     * @param env    the mutable environment map (typically
     *               {@code pb.environment()})
     * @param wsUrl  the resolved workstream URL with placeholders replaced;
     *               may be {@code null} or empty
     */
    public void applyAgentEnvironment(Map<String, String> env, String wsUrl) {
        if (wsUrl != null && !wsUrl.isEmpty()) {
            env.put("AR_WORKSTREAM_URL", wsUrl);
            String base = ClaudeCodeJob.extractControllerBaseUrl(wsUrl);
            if (base != null) env.put("AR_CONTROLLER_URL", base);
            String wid = ClaudeCodeJob.extractWorkstreamId(wsUrl);
            if (wid != null) env.put("AR_WORKSTREAM_ID", wid);
        }
        if (arManagerToken != null && !arManagerToken.isEmpty()) {
            env.put("AR_MANAGER_TOKEN", arManagerToken);
        }
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
