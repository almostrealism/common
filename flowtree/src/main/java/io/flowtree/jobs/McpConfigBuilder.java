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
 * <p>This class extracts MCP configuration building logic from
 * {@link ClaudeCodeJob}, providing a reusable builder that constructs
 * the {@code --mcp-config} JSON and the {@code --allowedTools}
 * comma-separated string.</p>
 *
 * <p>All JSON construction uses Jackson {@link ObjectMapper},
 * {@link ObjectNode}, and {@link ArrayNode} instead of manual
 * {@link StringBuilder} concatenation.</p>
 *
 * <h2>Configuration Sources</h2>
 * <ul>
 *   <li><b>Centralized servers:</b> HTTP entries from the controller,
 *       with {@code 0.0.0.0} replaced by {@code FLOWTREE_ROOT_HOST}</li>
 *   <li><b>Pushed tools:</b> stdio entries pointing to
 *       {@code ~/.flowtree/tools/mcp/{name}/server.py}</li>
 *   <li><b>Project servers:</b> discovered from {@code .mcp.json} and
 *       {@code .claude/settings.json}</li>
 *   <li><b>ar-github:</b> stdio fallback when not centralized or pushed</li>
 *   <li><b>ar-slack:</b> stdio fallback when not centralized, not pushed,
 *       and a workstream URL is configured</li>
 * </ul>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob
 * @see McpToolDiscovery
 */
public class McpConfigBuilder implements ConsoleFeatures {

    /** GitHub MCP tools, always included when ar-github is enabled. */
    private static final String GITHUB_MCP_TOOLS =
        "mcp__ar-github__github_pr_find," +
        "mcp__ar-github__github_pr_review_comments," +
        "mcp__ar-github__github_pr_conversation," +
        "mcp__ar-github__github_pr_reply";

    /** Slack MCP tool, included only when a workstream URL is configured. */
    private static final String SLACK_MCP_TOOL = "mcp__ar-slack__slack_send_message";

    private static final ObjectMapper mapper = new ObjectMapper();

    private String centralizedMcpConfig;
    private String pushedToolsConfig;
    private Map<String, String> workstreamEnv;
    private String workstreamUrl;
    private String githubOrg;
    private Path workingDirectory;

    /**
     * Sets the centralized MCP configuration JSON string from the controller.
     *
     * <p>This JSON maps server names to their HTTP URLs and tool names.
     * Servers in this config are connected via HTTP instead of stdio,
     * and their tools are included in the allowed tools list.</p>
     *
     * @param centralizedMcpConfig JSON mapping server names to URLs and tool names
     */
    public void setCentralizedMcpConfig(String centralizedMcpConfig) {
        this.centralizedMcpConfig = centralizedMcpConfig;
    }

    /**
     * Sets the pushed MCP tools configuration JSON string.
     *
     * <p>This JSON maps tool server names to their download URLs and
     * tool names. Tools are downloaded from the controller and run
     * locally via stdio in the agent's container.</p>
     *
     * @param pushedToolsConfig JSON mapping server names to download URLs and tool names
     */
    public void setPushedToolsConfig(String pushedToolsConfig) {
        this.pushedToolsConfig = pushedToolsConfig;
    }

    /**
     * Sets per-workstream environment variables for pushed tools.
     *
     * <p>These override global env vars defined on the pushed tool entry
     * when constructing the stdio MCP config.</p>
     *
     * @param workstreamEnv map of environment variable names to values
     */
    public void setWorkstreamEnv(Map<String, String> workstreamEnv) {
        this.workstreamEnv = workstreamEnv;
    }

    /**
     * Sets the workstream URL used for conditional ar-slack inclusion.
     *
     * <p>When set, the ar-slack MCP server is included as a stdio
     * fallback if it is not already centralized or pushed.</p>
     *
     * @param workstreamUrl the controller URL for the workstream
     */
    public void setWorkstreamUrl(String workstreamUrl) {
        this.workstreamUrl = workstreamUrl;
    }

    /**
     * Sets the GitHub organization name for this workstream.
     *
     * <p>When set, the {@code AR_GITHUB_ORG} environment variable is
     * injected into the ar-github MCP server entry so that the Python
     * server can pass it to the controller proxy for org-based token
     * selection.</p>
     *
     * @param githubOrg the GitHub organization name (e.g., "my-org")
     */
    public void setGithubOrg(String githubOrg) {
        this.githubOrg = githubOrg;
    }

    /**
     * Sets the working directory used for discovering project MCP servers.
     *
     * <p>This directory is searched for {@code .mcp.json} and
     * {@code .claude/settings.json} files that define project-level
     * MCP server configurations.</p>
     *
     * @param workingDirectory the project working directory
     */
    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Builds the JSON MCP configuration string for the {@code --mcp-config} flag.
     *
     * <p>The output has the structure {@code {"mcpServers":{...}}} containing:</p>
     * <ul>
     *   <li>Centralized servers as HTTP entries with resolved URLs
     *       ({@code 0.0.0.0} replaced by {@code FLOWTREE_ROOT_HOST})</li>
     *   <li>Pushed tools as stdio entries pointing to
     *       {@code ~/.flowtree/tools/mcp/{name}/server.py}</li>
     *   <li>Project MCP servers from {@code .mcp.json} (skipping ar-github,
     *       ar-slack, and any already centralized or pushed servers)</li>
     *   <li>ar-github stdio fallback when not centralized and not pushed</li>
     *   <li>ar-slack stdio fallback when not centralized, not pushed, and
     *       a workstream URL is set</li>
     * </ul>
     *
     * @return the MCP configuration JSON string
     */
    public String buildMcpConfig() {
        Map<String, List<String>> centralizedServers = parseCentralizedConfig();
        Map<String, List<String>> pushedTools = parsePushedConfig();
        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
        String home = System.getProperty("user.home");

        ObjectNode root = mapper.createObjectNode();
        ObjectNode mcpServers = root.putObject("mcpServers");

        // Emit centralized servers as HTTP entries
        if (centralizedMcpConfig != null && !centralizedMcpConfig.isEmpty()) {
            for (String serverName : centralizedServers.keySet()) {
                String url = extractUrlFromCentralizedConfig(serverName);
                if (url == null) continue;

                if (rootHost != null && !rootHost.isEmpty()) {
                    url = url.replace("0.0.0.0", rootHost);
                }

                ObjectNode serverNode = mcpServers.putObject(serverName);
                serverNode.put("type", "http");
                serverNode.put("url", url);
            }
        }

        // Emit pushed tools as stdio entries
        for (String serverName : pushedTools.keySet()) {
            String path = home + "/.flowtree/tools/mcp/" + serverName + "/server.py";

            ObjectNode serverNode = mcpServers.putObject(serverName);
            serverNode.put("command", "python3");
            ArrayNode argsArray = serverNode.putArray("args");
            argsArray.add(path);

            // Merge global pushed-tool env with per-workstream env (workstream wins)
            Map<String, String> mergedEnv = new LinkedHashMap<>();
            Map<String, String> globalEnv = extractEnvFromPushedConfig(serverName);
            if (globalEnv != null) {
                mergedEnv.putAll(globalEnv);
            }
            if (workstreamEnv != null) {
                mergedEnv.putAll(workstreamEnv);
            }
            if ("ar-github".equals(serverName) && githubOrg != null && !githubOrg.isEmpty()) {
                mergedEnv.put("AR_GITHUB_ORG", githubOrg);
            }
            if ("ar-slack".equals(serverName) && workstreamUrl != null && !workstreamUrl.isEmpty()) {
                String resolvedWsUrl = workstreamUrl;
                if (rootHost != null && !rootHost.isEmpty()) {
                    resolvedWsUrl = resolvedWsUrl.replace("0.0.0.0", rootHost);
                }
                mergedEnv.put("AR_WORKSTREAM_URL", resolvedWsUrl);
            }
            if (!mergedEnv.isEmpty()) {
                ObjectNode envNode = serverNode.putObject("env");
                for (Map.Entry<String, String> entry : mergedEnv.entrySet()) {
                    envNode.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Include project MCP servers discovered from .mcp.json
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            if (centralizedServers.containsKey(entry.getKey())) continue;
            if (pushedTools.containsKey(entry.getKey())) continue;

            ObjectNode serverNode = mcpServers.putObject(entry.getKey());
            serverNode.put("command", "python3");
            ArrayNode argsArray = serverNode.putArray("args");
            argsArray.add(entry.getValue());
        }

        // ar-github: stdio fallback only when not centralized and not pushed
        if (!centralizedServers.containsKey("ar-github") && !pushedTools.containsKey("ar-github")) {
            ObjectNode githubNode = mcpServers.putObject("ar-github");
            githubNode.put("command", "python3");
            ArrayNode argsArray = githubNode.putArray("args");
            argsArray.add("tools/mcp/github/server.py");

            Map<String, String> githubEnv = new LinkedHashMap<>();
            if (workstreamEnv != null) {
                githubEnv.putAll(workstreamEnv);
            }
            if (githubOrg != null && !githubOrg.isEmpty()) {
                githubEnv.put("AR_GITHUB_ORG", githubOrg);
            }
            if (!githubEnv.isEmpty()) {
                ObjectNode envNode = githubNode.putObject("env");
                for (Map.Entry<String, String> entry : githubEnv.entrySet()) {
                    envNode.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // ar-slack: stdio fallback only when not centralized, not pushed, and workstream URL is set
        if (!centralizedServers.containsKey("ar-slack") && !pushedTools.containsKey("ar-slack")) {
            if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
                ObjectNode slackNode = mcpServers.putObject("ar-slack");
                slackNode.put("command", "python3");
                ArrayNode argsArray = slackNode.putArray("args");
                argsArray.add("tools/mcp/slack/server.py");

                Map<String, String> slackEnv = new LinkedHashMap<>();
                if (workstreamEnv != null) {
                    slackEnv.putAll(workstreamEnv);
                }
                String resolvedWsUrl = workstreamUrl;
                if (rootHost != null && !rootHost.isEmpty()) {
                    resolvedWsUrl = resolvedWsUrl.replace("0.0.0.0", rootHost);
                }
                slackEnv.put("AR_WORKSTREAM_URL", resolvedWsUrl);
                ObjectNode envNode = slackNode.putObject("env");
                for (Map.Entry<String, String> entry : slackEnv.entrySet()) {
                    envNode.put(entry.getKey(), entry.getValue());
                }
            }
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
     *   <li>Tools from centralized servers: {@code mcp__{serverName}__{toolName}}</li>
     *   <li>Tools from pushed tools: {@code mcp__{serverName}__{toolName}}</li>
     *   <li>GitHub tools (unless ar-github is centralized or pushed)</li>
     *   <li>Slack tool (unless ar-slack is centralized or pushed, and only when
     *       a workstream URL is set)</li>
     *   <li>Tools from discovered project servers (skipping centralized, pushed,
     *       ar-github, and ar-slack)</li>
     * </ul>
     *
     * @param baseTools the base comma-separated tools string (e.g., "Read,Edit,Bash")
     * @return the complete comma-separated allowed tools string
     */
    public String buildAllowedTools(String baseTools) {
        Map<String, List<String>> centralizedServers = parseCentralizedConfig();
        Map<String, List<String>> pushedTools = parsePushedConfig();

        StringBuilder sb = new StringBuilder(baseTools);

        // Add tools from centralized servers
        for (Map.Entry<String, List<String>> entry : centralizedServers.entrySet()) {
            String serverName = entry.getKey();
            for (String tool : entry.getValue()) {
                sb.append(",mcp__").append(serverName).append("__").append(tool);
            }
            log("Centralized " + entry.getValue().size() + " tools from " + serverName);
        }

        // Add tools from pushed tools
        for (Map.Entry<String, List<String>> entry : pushedTools.entrySet()) {
            String serverName = entry.getKey();
            for (String tool : entry.getValue()) {
                sb.append(",mcp__").append(serverName).append("__").append(tool);
            }
            log("Pushed " + entry.getValue().size() + " tools from " + serverName);
        }

        // Add GitHub tools unless centralized or pushed
        if (!centralizedServers.containsKey("ar-github") && !pushedTools.containsKey("ar-github")) {
            sb.append(",").append(GITHUB_MCP_TOOLS);
        }

        // Add Slack tool unless centralized or pushed (only when workstream URL set)
        if (!centralizedServers.containsKey("ar-slack") && !pushedTools.containsKey("ar-slack")) {
            if (workstreamUrl != null && !workstreamUrl.isEmpty()) {
                sb.append(",").append(SLACK_MCP_TOOL);
            }
        }

        // Discover and include tools from project MCP servers
        Path workDir = resolveWorkingDirectory();
        Map<String, String> projectServers = discoverProjectMcpServers();
        for (Map.Entry<String, String> entry : projectServers.entrySet()) {
            String serverName = entry.getKey();
            if (centralizedServers.containsKey(serverName)) continue;
            if (pushedTools.containsKey(serverName)) continue;

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
     * Parses the {@link #centralizedMcpConfig} JSON to extract server names
     * and their tool lists using Jackson {@link ObjectMapper}.
     *
     * <p>Expected format:
     * {@code {"ar-slack":{"url":"...","tools":["tool1","tool2"]}, ...}}</p>
     *
     * @return map of server name to list of tool names, empty if no config
     */
    public Map<String, List<String>> parseCentralizedConfig() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (centralizedMcpConfig == null || centralizedMcpConfig.isEmpty()) return result;

        try {
            JsonNode root = mapper.readTree(centralizedMcpConfig);
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String serverName = fieldNames.next();
                JsonNode serverNode = root.get(serverName);
                if (serverNode == null || !serverNode.isObject()) continue;

                JsonNode toolsNode = serverNode.get("tools");
                if (toolsNode == null || !toolsNode.isArray()) continue;

                List<String> tools = new ArrayList<>();
                for (JsonNode toolNode : toolsNode) {
                    if (toolNode.isTextual()) {
                        tools.add(toolNode.asText());
                    }
                }

                if (!tools.isEmpty()) {
                    result.put(serverName, tools);
                }
            }
        } catch (IOException e) {
            warn("Failed to parse centralized MCP config: " + e.getMessage());
        }

        return result;
    }

    /**
     * Parses the {@link #pushedToolsConfig} JSON to extract server names
     * and their tool lists using Jackson {@link ObjectMapper}.
     *
     * <p>Uses the same format as {@link #parseCentralizedConfig()}.</p>
     *
     * @return map of server name to list of tool names, empty if no config
     */
    public Map<String, List<String>> parsePushedConfig() {
        return parsePushedConfig(pushedToolsConfig);
    }

    /**
     * Parses the given pushed tools configuration JSON to extract server
     * names and their tool lists using Jackson {@link ObjectMapper}.
     *
     * <p>Uses the same format as {@link #parseCentralizedConfig()}.
     * This overload allows callers to parse a specific JSON string
     * without requiring the field to be set via {@link #setPushedToolsConfig(String)}.</p>
     *
     * @param config the pushed tools configuration JSON string
     * @return map of server name to list of tool names, empty if no config
     */
    public Map<String, List<String>> parsePushedConfig(String config) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (config == null || config.isEmpty()) return result;

        try {
            JsonNode root = mapper.readTree(config);
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String serverName = fieldNames.next();
                JsonNode serverNode = root.get(serverName);
                if (serverNode == null || !serverNode.isObject()) continue;

                JsonNode toolsNode = serverNode.get("tools");
                if (toolsNode == null || !toolsNode.isArray()) continue;

                List<String> tools = new ArrayList<>();
                for (JsonNode toolNode : toolsNode) {
                    if (toolNode.isTextual()) {
                        tools.add(toolNode.asText());
                    }
                }

                if (!tools.isEmpty()) {
                    result.put(serverName, tools);
                }
            }
        } catch (IOException e) {
            warn("Failed to parse pushed tools config: " + e.getMessage());
        }

        return result;
    }

    /**
     * Discovers MCP servers defined in the project's {@code .mcp.json} file
     * and cross-references with {@code .claude/settings.json} to determine
     * which servers are enabled.
     *
     * <p>Servers named {@code ar-github} and {@code ar-slack} are always
     * excluded from this discovery since they are handled separately with
     * special conditional logic. Servers that are already centralized are
     * also excluded.</p>
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

        Map<String, List<String>> centralized = parseCentralizedConfig();
        Map<String, List<String>> pushed = parsePushedConfig();

        for (Map.Entry<String, String> entry : allServers.entrySet()) {
            String name = entry.getKey();
            if ("ar-github".equals(name) || "ar-slack".equals(name)) continue;
            if (centralized.containsKey(name)) continue;
            if (pushed.containsKey(name)) continue;
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
     * Extracts the URL field for a given server name from the centralized
     * MCP configuration JSON using Jackson.
     *
     * @param serverName the server name to look up
     * @return the URL string, or null if not found
     */
    private String extractUrlFromCentralizedConfig(String serverName) {
        if (centralizedMcpConfig == null || centralizedMcpConfig.isEmpty()) return null;

        try {
            JsonNode root = mapper.readTree(centralizedMcpConfig);
            JsonNode serverNode = root.get(serverName);
            if (serverNode == null || !serverNode.isObject()) return null;

            JsonNode urlNode = serverNode.get("url");
            if (urlNode == null || !urlNode.isTextual()) return null;

            return urlNode.asText();
        } catch (IOException e) {
            warn("Failed to extract URL for " + serverName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the env object for a given server name from the pushed
     * tools configuration JSON using Jackson.
     *
     * @param serverName the server name to look up
     * @return map of environment variable names to values, or null if not found
     */
    private Map<String, String> extractEnvFromPushedConfig(String serverName) {
        if (pushedToolsConfig == null || pushedToolsConfig.isEmpty()) return null;

        try {
            JsonNode root = mapper.readTree(pushedToolsConfig);
            JsonNode serverNode = root.get(serverName);
            if (serverNode == null || !serverNode.isObject()) return null;

            JsonNode envNode = serverNode.get("env");
            if (envNode == null || !envNode.isObject()) return null;

            Map<String, String> env = new LinkedHashMap<>();
            Iterator<String> fieldNames = envNode.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                JsonNode valueNode = envNode.get(key);
                if (valueNode.isTextual()) {
                    env.put(key, valueNode.asText());
                }
            }
            return env.isEmpty() ? null : env;
        } catch (IOException e) {
            warn("Failed to extract env for " + serverName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code .mcp.json} to extract server names and their Python
     * source file paths using Jackson {@link ObjectMapper}.
     *
     * <p>Expected structure:
     * {@code {"mcpServers":{"name":{"command":"python3","args":["path/to/server.py"]}}}}</p>
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
     * enabled MCP servers from the {@code enabledMcpjsonServers} field
     * using Jackson {@link ObjectMapper}.
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
