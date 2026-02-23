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

package io.flowtree.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration for Slack workstreams, loadable from YAML or JSON files.
 *
 * <p>Agents connect inbound to the controller's FlowTree server, so the
 * {@code agents} field is optional and typically omitted.</p>
 *
 * <p>Example YAML configuration:</p>
 * <pre>
 * workstreams:
 *   - channelId: "C0123456789"
 *     channelName: "#project-agent"
 *     defaultBranch: "feature/work"
 *     pushToOrigin: true
 *     allowedTools: "Read,Edit,Write,Bash,Glob,Grep"
 *     maxTurns: 800
 *     maxBudgetUsd: 100.0
 *     gitUserName: "CI Bot"
 *     gitUserEmail: "ci-bot@example.com"
 * </pre>
 *
 * @author Michael Murray
 * @see SlackWorkstream
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkstreamConfig {

    private String defaultWorkspacePath;
    private Map<String, McpServerEntry> mcpServers = new LinkedHashMap<>();
    private Map<String, PushedToolEntry> pushedTools = new LinkedHashMap<>();
    private List<WorkstreamEntry> workstreams = new ArrayList<>();

    /**
     * Configuration entry for a single workstream.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkstreamEntry {
        private String workstreamId;
        private String channelId;
        private String channelName;
        private List<AgentEntry> agents = new ArrayList<>();
        private String defaultBranch;
        private String baseBranch;
        private boolean pushToOrigin = true;
        private String workingDirectory;
        private String repoUrl;
        private String allowedTools = "Read,Edit,Write,Bash,Glob,Grep";
        private int maxTurns = 800;
        private double maxBudgetUsd = 100.0;
        private String gitUserName;
        private String gitUserEmail;
        private Map<String, String> env;
        private String planningDocument;

        public String getWorkstreamId() { return workstreamId; }
        public void setWorkstreamId(String workstreamId) { this.workstreamId = workstreamId; }

        public String getChannelId() { return channelId; }
        public void setChannelId(String channelId) { this.channelId = channelId; }

        public String getChannelName() { return channelName; }
        public void setChannelName(String channelName) { this.channelName = channelName; }

        public List<AgentEntry> getAgents() { return agents; }
        public void setAgents(List<AgentEntry> agents) { this.agents = agents; }

        public String getDefaultBranch() { return defaultBranch; }
        public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

        public String getBaseBranch() { return baseBranch; }
        public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }

        public boolean isPushToOrigin() { return pushToOrigin; }
        public void setPushToOrigin(boolean pushToOrigin) { this.pushToOrigin = pushToOrigin; }

        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

        /** Returns the git repository URL for automatic checkout. */
        public String getRepoUrl() { return repoUrl; }
        public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

        public String getAllowedTools() { return allowedTools; }
        public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

        public double getMaxBudgetUsd() { return maxBudgetUsd; }
        public void setMaxBudgetUsd(double maxBudgetUsd) { this.maxBudgetUsd = maxBudgetUsd; }

        public String getGitUserName() { return gitUserName; }
        public void setGitUserName(String gitUserName) { this.gitUserName = gitUserName; }

        public String getGitUserEmail() { return gitUserEmail; }
        public void setGitUserEmail(String gitUserEmail) { this.gitUserEmail = gitUserEmail; }

        /** Returns per-workstream environment variables injected into pushed tool MCP configs. */
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }

        /** Returns the optional planning document path for broader goal context. */
        public String getPlanningDocument() { return planningDocument; }
        public void setPlanningDocument(String planningDocument) { this.planningDocument = planningDocument; }

        /**
         * Converts this entry to a {@link SlackWorkstream} instance.
         *
         * <p>If a {@code workstreamId} is present, it is used as the persistent
         * identifier. Otherwise, a random UUID is generated.</p>
         */
        public SlackWorkstream toWorkstream() {
            SlackWorkstream ws;
            if (workstreamId != null && !workstreamId.isEmpty()) {
                ws = new SlackWorkstream(workstreamId, channelId, channelName);
            } else {
                ws = new SlackWorkstream(channelId, channelName);
            }
            for (AgentEntry agent : agents) {
                ws.addAgent(agent.getHost(), agent.getPort());
            }
            ws.setDefaultBranch(defaultBranch);
            ws.setBaseBranch(baseBranch);
            ws.setPushToOrigin(pushToOrigin);
            ws.setWorkingDirectory(workingDirectory);
            ws.setRepoUrl(repoUrl);
            ws.setAllowedTools(allowedTools);
            ws.setMaxTurns(maxTurns);
            ws.setMaxBudgetUsd(maxBudgetUsd);
            ws.setGitUserName(gitUserName);
            ws.setGitUserEmail(gitUserEmail);
            ws.setEnv(env);
            ws.setPlanningDocument(planningDocument);
            return ws;
        }
    }

    /**
     * Configuration entry for an agent endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentEntry {
        private String host = "localhost";
        private int port = 7766;

        public AgentEntry() {}

        public AgentEntry(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    /**
     * Configuration entry for a centralized MCP server.
     *
     * <p>When present in the YAML configuration, the controller starts
     * each server as an HTTP process and agents connect over HTTP
     * instead of stdio.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpServerEntry {
        private String source;
        private int port;

        /** Returns the Python source file path (relative to project root). */
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        /** Returns the HTTP port to listen on. */
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    /**
     * Configuration entry for a pushed MCP tool.
     *
     * <p>Pushed tools are served as files by the controller and downloaded
     * into dev containers on first use. Unlike centralized servers (which
     * run as HTTP processes on the controller), pushed tools run locally
     * inside each container via stdio.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PushedToolEntry {
        private String source;
        private Map<String, String> env;

        /** Returns the Python source file path (relative to config directory). */
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        /** Returns per-tool environment variables to inject into the MCP stdio config. */
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
    }

    /**
     * Returns the global default workspace path for repo checkouts.
     *
     * <p>When a workstream specifies {@code repoUrl} but no
     * {@code workingDirectory}, the repo is cloned into this path.
     * If not set, defaults to {@code /workspace/project} (if it exists)
     * or a temporary directory under {@code /tmp}.</p>
     */
    public String getDefaultWorkspacePath() { return defaultWorkspacePath; }
    public void setDefaultWorkspacePath(String defaultWorkspacePath) { this.defaultWorkspacePath = defaultWorkspacePath; }

    /** Returns the centralized MCP server configurations. */
    public Map<String, McpServerEntry> getMcpServers() { return mcpServers; }
    public void setMcpServers(Map<String, McpServerEntry> mcpServers) { this.mcpServers = mcpServers; }

    /** Returns the pushed MCP tool configurations. */
    public Map<String, PushedToolEntry> getPushedTools() { return pushedTools; }
    public void setPushedTools(Map<String, PushedToolEntry> pushedTools) { this.pushedTools = pushedTools; }

    public List<WorkstreamEntry> getWorkstreams() { return workstreams; }
    public void setWorkstreams(List<WorkstreamEntry> workstreams) { this.workstreams = workstreams; }

    /**
     * Loads configuration from a YAML file.
     *
     * @param file the YAML configuration file
     * @return the parsed configuration
     * @throws IOException if the file cannot be read or parsed
     */
    public static WorkstreamConfig loadFromYaml(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(file, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a YAML input stream.
     *
     * @param inputStream the YAML input stream
     * @return the parsed configuration
     * @throws IOException if the stream cannot be read or parsed
     */
    public static WorkstreamConfig loadFromYaml(InputStream inputStream) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(inputStream, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a YAML string.
     *
     * @param yaml the YAML content
     * @return the parsed configuration
     * @throws IOException if the content cannot be parsed
     */
    public static WorkstreamConfig loadFromYamlString(String yaml) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(yaml, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a JSON file.
     *
     * @param file the JSON configuration file
     * @return the parsed configuration
     * @throws IOException if the file cannot be read or parsed
     */
    public static WorkstreamConfig loadFromJson(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(file, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a JSON string.
     *
     * @param json the JSON content
     * @return the parsed configuration
     * @throws IOException if the content cannot be parsed
     */
    public static WorkstreamConfig loadFromJsonString(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, WorkstreamConfig.class);
    }

    /**
     * Converts all entries to SlackWorkstream instances.
     *
     * @return list of workstreams
     */
    public List<SlackWorkstream> toWorkstreams() {
        List<SlackWorkstream> result = new ArrayList<>();
        for (WorkstreamEntry entry : workstreams) {
            result.add(entry.toWorkstream());
        }
        return result;
    }

    /**
     * Populates missing workstream IDs with randomly generated UUIDs.
     *
     * @return true if any IDs were generated, indicating the config should be saved
     */
    public boolean ensureWorkstreamIds() {
        boolean changed = false;
        for (WorkstreamEntry entry : workstreams) {
            if (entry.getWorkstreamId() == null || entry.getWorkstreamId().isEmpty()) {
                entry.setWorkstreamId(UUID.randomUUID().toString());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Adds a new workstream to the configuration from a {@link SlackWorkstream} instance.
     *
     * <p>This creates a new {@link WorkstreamEntry} from the workstream's current
     * state and appends it to the workstreams list. Used by {@code /flowtree setup}
     * when creating a workstream from Slack.</p>
     *
     * @param ws the workstream to add
     */
    public void addWorkstream(SlackWorkstream ws) {
        WorkstreamEntry entry = new WorkstreamEntry();
        entry.setWorkstreamId(ws.getWorkstreamId());
        entry.setChannelId(ws.getChannelId());
        entry.setChannelName(ws.getChannelName());
        entry.setDefaultBranch(ws.getDefaultBranch());
        entry.setBaseBranch(ws.getBaseBranch());
        entry.setPushToOrigin(ws.isPushToOrigin());
        entry.setWorkingDirectory(ws.getWorkingDirectory());
        entry.setRepoUrl(ws.getRepoUrl());
        entry.setAllowedTools(ws.getAllowedTools());
        entry.setMaxTurns(ws.getMaxTurns());
        entry.setMaxBudgetUsd(ws.getMaxBudgetUsd());
        entry.setGitUserName(ws.getGitUserName());
        entry.setGitUserEmail(ws.getGitUserEmail());
        entry.setEnv(ws.getEnv());
        entry.setPlanningDocument(ws.getPlanningDocument());
        workstreams.add(entry);
    }

    /**
     * Synchronizes the configuration entries from the in-memory workstream state.
     *
     * <p>Updates existing entries that match by channel ID and adds any new
     * workstreams that are not yet represented in the config. This ensures
     * that runtime changes (via {@code /flowtree config}) are persisted.</p>
     *
     * @param activeWorkstreams the current in-memory workstreams
     */
    public void syncFromWorkstreams(java.util.Collection<SlackWorkstream> activeWorkstreams) {
        for (SlackWorkstream ws : activeWorkstreams) {
            boolean found = false;
            for (WorkstreamEntry entry : workstreams) {
                if (ws.getChannelId().equals(entry.getChannelId())) {
                    entry.setWorkstreamId(ws.getWorkstreamId());
                    entry.setChannelName(ws.getChannelName());
                    entry.setDefaultBranch(ws.getDefaultBranch());
                    entry.setBaseBranch(ws.getBaseBranch());
                    entry.setPushToOrigin(ws.isPushToOrigin());
                    entry.setWorkingDirectory(ws.getWorkingDirectory());
                    entry.setRepoUrl(ws.getRepoUrl());
                    entry.setAllowedTools(ws.getAllowedTools());
                    entry.setMaxTurns(ws.getMaxTurns());
                    entry.setMaxBudgetUsd(ws.getMaxBudgetUsd());
                    entry.setGitUserName(ws.getGitUserName());
                    entry.setGitUserEmail(ws.getGitUserEmail());
                    entry.setEnv(ws.getEnv());
                    entry.setPlanningDocument(ws.getPlanningDocument());
                    found = true;
                    break;
                }
            }
            if (!found) {
                addWorkstream(ws);
            }
        }
    }

    /**
     * Writes the configuration back to a YAML file.
     *
     * <p>Uses {@link JsonInclude.Include#NON_EMPTY} to omit null fields
     * and empty collections, keeping the output readable.</p>
     *
     * @param file the target YAML file
     * @throws IOException if the file cannot be written
     */
    public void saveToYaml(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.writeValue(file, this);
    }
}
