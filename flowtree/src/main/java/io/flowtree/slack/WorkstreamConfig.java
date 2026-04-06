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
import java.util.Collection;
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
 * @see Workstream
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkstreamConfig {

    /** Global default path for repo checkouts; used when no workingDirectory is set. */
    private String defaultWorkspacePath;
    /** Slack user ID automatically invited to newly created workstream channels. */
    private String channelOwnerUserId;
    /** Fallback Slack channel ID used when a workstream has no channel or posting fails. */
    private String defaultChannel;
    /** Named centralized MCP server entries (legacy; ar-manager supersedes these). */
    private Map<String, McpServerEntry> mcpServers = new LinkedHashMap<>();
    /** Named pushed MCP tool entries served as files via the API endpoint. */
    private Map<String, PushedToolEntry> pushedTools = new LinkedHashMap<>();
    /** Per-organization GitHub personal access tokens, keyed by org name. */
    private Map<String, GitHubOrgEntry> githubOrgs = new LinkedHashMap<>();
    /** Ordered list of workstream configuration entries. */
    private List<WorkstreamEntry> workstreams = new ArrayList<>();

    /**
     * Configuration entry for a single workstream.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkstreamEntry {
        /** Persistent identifier; generated if absent and saved back to YAML. */
        private String workstreamId;
        /** The Slack channel ID this workstream is bound to. */
        private String channelId;
        /** The human-readable Slack channel name. */
        private String channelName;
        /** Optional agent endpoints; typically empty since agents connect inbound. */
        private List<AgentEntry> agents = new ArrayList<>();
        /** Branch agents commit to by default. */
        private String defaultBranch;
        /** Branch used as the base when the controller creates a new branch. */
        private String baseBranch;
        /** Whether agents push commits to the remote origin. */
        private boolean pushToOrigin = true;
        /** Local filesystem path to the checked-out repository. */
        private String workingDirectory;
        /** Git clone URL for automatic repository checkout. */
        private String repoUrl;
        /** Comma-separated list of Claude Code tool names the agent may use. */
        private String allowedTools = "Read,Edit,Write,Bash,Glob,Grep";
        /** Maximum number of agent turns per job. */
        private int maxTurns = 800;
        /** Maximum spending budget per job in USD. */
        private double maxBudgetUsd = 100.0;
        /** Git author name for commits. */
        private String gitUserName;
        /** Git author email for commits. */
        private String gitUserEmail;
        /** Per-workstream environment variables injected into pushed tool MCP configs. */
        private Map<String, String> env;
        /** Optional path to a planning document the agent consults for context. */
        private String planningDocument;
        /** GitHub organization name for org-based token selection. */
        private String githubOrg;
        /** Additional repository URLs cloned alongside the primary repo. */
        private List<String> dependentRepos;
        /** Node labels that jobs submitted to this workstream must match by default. */
        private Map<String, String> requiredLabels;

        /** Returns the persistent workstream identifier. */
        public String getWorkstreamId() { return workstreamId; }
        /** Sets the persistent workstream identifier. */
        public void setWorkstreamId(String workstreamId) { this.workstreamId = workstreamId; }

        /** Returns the Slack channel ID (e.g., "C0123456789"). */
        public String getChannelId() { return channelId; }
        /** Sets the Slack channel ID. */
        public void setChannelId(String channelId) { this.channelId = channelId; }

        /** Returns the human-readable Slack channel name (e.g., "#project-agent"). */
        public String getChannelName() { return channelName; }
        /** Sets the human-readable Slack channel name. */
        public void setChannelName(String channelName) { this.channelName = channelName; }

        /** Returns the list of agent endpoint entries for this workstream. */
        public List<AgentEntry> getAgents() { return agents; }
        /** Sets the list of agent endpoint entries for this workstream. */
        public void setAgents(List<AgentEntry> agents) { this.agents = agents; }

        /** Returns the default git branch for agent commits. */
        public String getDefaultBranch() { return defaultBranch; }
        /** Sets the default git branch for agent commits. */
        public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

        /** Returns the base branch used as the starting point for new branch creation. */
        public String getBaseBranch() { return baseBranch; }
        /** Sets the base branch used when creating new target branches. */
        public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }

        /** Returns whether agents should push commits to the remote origin. */
        public boolean isPushToOrigin() { return pushToOrigin; }
        /** Sets whether agents should push commits to the remote origin. */
        public void setPushToOrigin(boolean pushToOrigin) { this.pushToOrigin = pushToOrigin; }

        /** Returns the local working directory for the agent's git repository. */
        public String getWorkingDirectory() { return workingDirectory; }
        /** Sets the local working directory for the agent's git repository. */
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

        /** Returns the git repository URL for automatic checkout. */
        public String getRepoUrl() { return repoUrl; }
        /** Sets the git repository URL for automatic checkout. */
        public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

        /** Returns the comma-separated list of Claude Code tool names the agent may use. */
        public String getAllowedTools() { return allowedTools; }
        /** Sets the comma-separated list of allowed Claude Code tool names. */
        public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

        /** Returns the maximum number of turns a Claude Code agent may take per job. */
        public int getMaxTurns() { return maxTurns; }
        /** Sets the maximum number of turns per job. */
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

        /** Returns the maximum spending budget per job in USD. */
        public double getMaxBudgetUsd() { return maxBudgetUsd; }
        /** Sets the maximum spending budget per job in USD. */
        public void setMaxBudgetUsd(double maxBudgetUsd) { this.maxBudgetUsd = maxBudgetUsd; }

        /** Returns the git author name used for commits made by this workstream. */
        public String getGitUserName() { return gitUserName; }
        /** Sets the git author name used for commits. */
        public void setGitUserName(String gitUserName) { this.gitUserName = gitUserName; }

        /** Returns the git author email used for commits made by this workstream. */
        public String getGitUserEmail() { return gitUserEmail; }
        /** Sets the git author email used for commits. */
        public void setGitUserEmail(String gitUserEmail) { this.gitUserEmail = gitUserEmail; }

        /** Returns per-workstream environment variables injected into pushed tool MCP configs. */
        public Map<String, String> getEnv() { return env; }
        /** Sets per-workstream environment variables for pushed tools. */
        public void setEnv(Map<String, String> env) { this.env = env; }

        /** Returns the optional planning document path for broader goal context. */
        public String getPlanningDocument() { return planningDocument; }
        /** Sets the planning document path for this workstream. */
        public void setPlanningDocument(String planningDocument) { this.planningDocument = planningDocument; }

        /** Returns the GitHub organization name for org-based token selection. */
        public String getGithubOrg() { return githubOrg; }
        /** Sets the GitHub organization name for org-based token selection. */
        public void setGithubOrg(String githubOrg) { this.githubOrg = githubOrg; }

        /**
         * Returns the list of dependent repository URLs that should be
         * checked out alongside the primary repo. Each repo is cloned
         * as a sibling directory and managed with the same branch/commit
         * lifecycle as the primary repo.
         */
        public List<String> getDependentRepos() { return dependentRepos; }
        /** Sets the dependent repository URLs. */
        public void setDependentRepos(List<String> dependentRepos) { this.dependentRepos = dependentRepos; }

        /**
         * Returns the Node labels that jobs submitted to this workstream must match by default.
         * When a job submission does not include {@code requiredLabels}, these are applied.
         */
        public Map<String, String> getRequiredLabels() { return requiredLabels; }
        /** Sets the default Node label requirements for jobs in this workstream. */
        public void setRequiredLabels(Map<String, String> requiredLabels) { this.requiredLabels = requiredLabels; }

        /**
         * Converts this entry to a {@link Workstream} instance.
         *
         * <p>If a {@code workstreamId} is present, it is used as the persistent
         * identifier. Otherwise, a random UUID is generated.</p>
         */
        public Workstream toWorkstream() {
            Workstream ws;
            if (workstreamId != null && !workstreamId.isEmpty()) {
                ws = new Workstream(workstreamId, channelId, channelName);
            } else {
                ws = new Workstream(channelId, channelName);
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
            ws.setGithubOrg(githubOrg);
            ws.setDependentRepos(dependentRepos);
            ws.setRequiredLabels(requiredLabels);
            return ws;
        }
    }

    /**
     * Configuration entry for an agent endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentEntry {
        /** The agent hostname or IP address (default: {@code "localhost"}). */
        private String host = "localhost";
        /** The port the agent's FlowTree node listens on (default: 7766). */
        private int port = 7766;

        /** No-arg constructor for Jackson deserialization. */
        public AgentEntry() {}

        /**
         * Creates a new agent entry with the specified host and port.
         *
         * @param host the agent hostname or IP address
         * @param port the agent port number
         */
        public AgentEntry(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** Returns the agent hostname or IP address. */
        public String getHost() { return host; }
        /** Sets the agent hostname or IP address. */
        public void setHost(String host) { this.host = host; }

        /** Returns the agent port number. */
        public int getPort() { return port; }
        /** Sets the agent port number. */
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
        /** Python source file path relative to the project root (for managed servers). */
        private String source;
        /** HTTP port the managed server process listens on. */
        private int port;
        /** URL of an already-running external server; when set, no subprocess is launched. */
        private String url;
        /** Explicit tool names exposed by this server; required when {@code url} is set. */
        private List<String> tools;

        /** Returns the Python source file path (relative to project root). */
        public String getSource() { return source; }
        /** Sets the Python source file path (relative to project root). */
        public void setSource(String source) { this.source = source; }

        /** Returns the HTTP port to listen on. */
        public int getPort() { return port; }
        /** Sets the HTTP port for the managed MCP server process. */
        public void setPort(int port) { this.port = port; }

        /**
         * Returns the URL of an already-running centralized server.
         * When set, the controller does not launch a subprocess —
         * it simply passes the URL through to agents.
         */
        public String getUrl() { return url; }
        /** Sets the URL of an already-running external MCP server. */
        public void setUrl(String url) { this.url = url; }

        /**
         * Returns the explicit tool names for this server.
         * Required when {@code url} is set (no source file to discover from).
         */
        public List<String> getTools() { return tools; }
        /** Sets the explicit tool names exposed by this server. */
        public void setTools(List<String> tools) { this.tools = tools; }

        /** Returns true if this entry references an external server by URL. */
        public boolean isExternal() {
            return url != null && !url.isEmpty();
        }
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
        /** Python source file path relative to the config directory. */
        private String source;
        /** Per-tool environment variables injected into the agent's MCP stdio config. */
        private Map<String, String> env;

        /** Returns the Python source file path (relative to config directory). */
        public String getSource() { return source; }
        /** Sets the Python source file path (relative to config directory). */
        public void setSource(String source) { this.source = source; }

        /** Returns per-tool environment variables to inject into the MCP stdio config. */
        public Map<String, String> getEnv() { return env; }
        /** Sets per-tool environment variables for the MCP stdio config. */
        public void setEnv(Map<String, String> env) { this.env = env; }
    }

    /**
     * Configuration entry for a GitHub organization token.
     *
     * <p>Maps an organization name to a GitHub personal access token.
     * When a workstream specifies a {@code githubOrg}, the controller
     * proxy selects the matching token for GitHub API calls.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubOrgEntry {
        /** The GitHub personal access token for authenticating as this organization. */
        private String token;

        /** Returns the GitHub personal access token for this organization. */
        public String getToken() { return token; }
        /** Sets the GitHub personal access token for this organization. */
        public void setToken(String token) { this.token = token; }
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

    /**
     * Returns the Slack user ID to invite to auto-created channels.
     *
     * <p>When set, newly created workstream channels will automatically
     * invite this user. This is typically the project owner's Slack user ID.</p>
     */
    public String getChannelOwnerUserId() { return channelOwnerUserId; }
    public void setChannelOwnerUserId(String channelOwnerUserId) { this.channelOwnerUserId = channelOwnerUserId; }

    /**
     * Returns the default Slack channel ID to use as a fallback when a
     * workstream has no channel configured or when publishing to the
     * configured channel fails.
     *
     * <p>This is a global setting. When set in the YAML configuration,
     * all workstreams without a valid channel will fall back to this
     * channel instead of silently dropping messages.</p>
     */
    public String getDefaultChannel() { return defaultChannel; }
    public void setDefaultChannel(String defaultChannel) { this.defaultChannel = defaultChannel; }

    /** Returns the centralized MCP server configurations. */
    public Map<String, McpServerEntry> getMcpServers() { return mcpServers; }
    public void setMcpServers(Map<String, McpServerEntry> mcpServers) { this.mcpServers = mcpServers; }

    /** Returns the pushed MCP tool configurations. */
    public Map<String, PushedToolEntry> getPushedTools() { return pushedTools; }
    public void setPushedTools(Map<String, PushedToolEntry> pushedTools) { this.pushedTools = pushedTools; }

    /** Returns the per-organization GitHub token configurations. */
    public Map<String, GitHubOrgEntry> getGithubOrgs() { return githubOrgs; }
    public void setGithubOrgs(Map<String, GitHubOrgEntry> githubOrgs) { this.githubOrgs = githubOrgs; }

    /** Returns the list of workstream configuration entries. */
    public List<WorkstreamEntry> getWorkstreams() { return workstreams; }
    /** Sets the list of workstream configuration entries. */
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
     * Converts all entries to Workstream instances.
     *
     * @return list of workstreams
     */
    public List<Workstream> toWorkstreams() {
        List<Workstream> result = new ArrayList<>();
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
     * Adds a new workstream to the configuration from a {@link Workstream} instance.
     *
     * <p>This creates a new {@link WorkstreamEntry} from the workstream's current
     * state and appends it to the workstreams list. Used by {@code /flowtree setup}
     * when creating a workstream from Slack.</p>
     *
     * @param ws the workstream to add
     */
    public void addWorkstream(Workstream ws) {
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
        entry.setGithubOrg(ws.getGithubOrg());
        entry.setDependentRepos(ws.getDependentRepos());
        entry.setRequiredLabels(ws.getRequiredLabels());
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
    public void syncFromWorkstreams(Collection<Workstream> activeWorkstreams) {
        for (Workstream ws : activeWorkstreams) {
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
                    entry.setGithubOrg(ws.getGithubOrg());
                    entry.setDependentRepos(ws.getDependentRepos());
                    entry.setRequiredLabels(ws.getRequiredLabels());
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
