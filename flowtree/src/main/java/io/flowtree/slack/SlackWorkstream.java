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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration for a Slack workstream that maps a channel to agents and settings.
 *
 * <p>A workstream represents a logical unit of work managed through Slack.
 * Each workstream is associated with a Slack channel where operators can issue
 * instructions and receive status updates.</p>
 *
 * <p>The workstream ID can be used as a namespace for future MCP memory tool
 * integration, allowing both agents and operators to share context.</p>
 *
 * @author Michael Murray
 * @see FlowTreeController
 */
public class SlackWorkstream {

    /**
     * Connection details for a Flowtree agent.
     */
    public static class AgentEndpoint {
        private final String host;
        private final int port;

        public AgentEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private final String workstreamId;
    private String channelId;
    private String channelName;
    private final List<AgentEndpoint> agents;
    private String defaultBranch;
    private String baseBranch;
    private boolean pushToOrigin;
    private String workingDirectory;
    private String repoUrl;

    // Job configuration
    private String allowedTools;
    private int maxTurns;
    private double maxBudgetUsd;

    // Git identity
    private String gitUserName;
    private String gitUserEmail;

    // Per-workstream env vars for pushed tools
    private Map<String, String> env;

    // Optional planning document path for broader goal context
    private String planningDocument;

    // GitHub organization for org-based token selection
    private String githubOrg;

    /** Default git user name for new workstreams. */
    public static final String DEFAULT_GIT_USER_NAME = "Flowtree Coding Agent";

    /** Default git user email for new workstreams. */
    public static final String DEFAULT_GIT_USER_EMAIL = "michael@almostrealism.com";

    /**
     * Creates a new workstream with default settings.
     */
    public SlackWorkstream() {
        this.workstreamId = UUID.randomUUID().toString();
        this.agents = new ArrayList<>();
        this.pushToOrigin = true;
        this.allowedTools = "Read,Edit,Write,Bash,Glob,Grep";
        this.maxTurns = 800;
        this.maxBudgetUsd = 100.0;
        this.gitUserName = DEFAULT_GIT_USER_NAME;
        this.gitUserEmail = DEFAULT_GIT_USER_EMAIL;
    }

    /**
     * Creates a new workstream with a persistent workstream ID.
     *
     * <p>Use this constructor for workstreams loaded from YAML configuration,
     * where the workstream ID has been previously generated and persisted.</p>
     *
     * @param workstreamId the persistent workstream identifier
     * @param channelId    the Slack channel ID (e.g., "C0123456789")
     * @param channelName  the human-readable channel name (e.g., "#project-agent")
     */
    public SlackWorkstream(String workstreamId, String channelId, String channelName) {
        this.workstreamId = workstreamId;
        this.channelId = channelId;
        this.channelName = channelName;
        this.agents = new ArrayList<>();
        this.pushToOrigin = true;
        this.allowedTools = "Read,Edit,Write,Bash,Glob,Grep";
        this.maxTurns = 800;
        this.maxBudgetUsd = 100.0;
    }

    /**
     * Creates a new workstream for the specified channel.
     *
     * @param channelId   the Slack channel ID (e.g., "C0123456789")
     * @param channelName the human-readable channel name (e.g., "#project-agent")
     */
    public SlackWorkstream(String channelId, String channelName) {
        this();
        this.channelId = channelId;
        this.channelName = channelName;
    }

    /**
     * Returns the unique workstream identifier.
     * This can be used as a memory namespace for MCP tool integration.
     */
    public String getWorkstreamId() {
        return workstreamId;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public List<AgentEndpoint> getAgents() {
        return agents;
    }

    /**
     * Adds an agent endpoint to this workstream.
     *
     * @param host the agent host
     * @param port the agent port
     * @return this workstream for method chaining
     */
    public SlackWorkstream addAgent(String host, int port) {
        agents.add(new AgentEndpoint(host, port));
        return this;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    /**
     * Sets the default git branch for this workstream.
     * Jobs will commit to this branch unless overridden.
     *
     * @param defaultBranch the branch name (e.g., "feature/my-work")
     */
    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    /**
     * Returns the base branch used as the starting point when creating
     * a new target branch. Defaults to {@code "master"} if not set.
     */
    public String getBaseBranch() {
        return baseBranch;
    }

    /**
     * Sets the base branch for new branch creation.
     *
     * @param baseBranch the branch name to base new branches on (e.g., "master", "main", "develop")
     */
    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public boolean isPushToOrigin() {
        return pushToOrigin;
    }

    public void setPushToOrigin(boolean pushToOrigin) {
        this.pushToOrigin = pushToOrigin;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Returns the git repository URL for automatic checkout.
     *
     * <p>When set (and {@code workingDirectory} is not), the job will
     * clone this repo into a resolved workspace path before starting work.</p>
     */
    public String getRepoUrl() {
        return repoUrl;
    }

    /**
     * Sets the git repository URL for automatic checkout.
     *
     * @param repoUrl the git clone URL (e.g., "https://github.com/owner/repo.git")
     */
    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(String allowedTools) {
        this.allowedTools = allowedTools;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    public void setMaxBudgetUsd(double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
    }

    /**
     * Returns the git user name for commits made by this workstream.
     */
    public String getGitUserName() {
        return gitUserName;
    }

    /**
     * Sets the git user name for commits made by this workstream.
     *
     * @param gitUserName the name to use in git commits (e.g., "CI Bot")
     */
    public void setGitUserName(String gitUserName) {
        this.gitUserName = gitUserName;
    }

    /**
     * Returns the git user email for commits made by this workstream.
     */
    public String getGitUserEmail() {
        return gitUserEmail;
    }

    /**
     * Sets the git user email for commits made by this workstream.
     *
     * @param gitUserEmail the email to use in git commits (e.g., "ci-bot@example.com")
     */
    public void setGitUserEmail(String gitUserEmail) {
        this.gitUserEmail = gitUserEmail;
    }

    /**
     * Returns per-workstream environment variables that are injected into
     * pushed tool MCP stdio configs. These override any global env vars
     * defined on the pushed tool entry itself.
     */
    public Map<String, String> getEnv() {
        return env;
    }

    /**
     * Sets per-workstream environment variables for pushed tools.
     *
     * @param env map of environment variable names to values
     */
    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    /**
     * Returns the optional planning document path for this workstream.
     * When set, agents are instructed to consult this document for the
     * broader goal of the branch.
     */
    public String getPlanningDocument() {
        return planningDocument;
    }

    /**
     * Sets the planning document path for this workstream.
     *
     * @param planningDocument path to the planning document relative to the working directory
     */
    public void setPlanningDocument(String planningDocument) {
        this.planningDocument = planningDocument;
    }

    /**
     * Returns the GitHub organization name for this workstream.
     * When set, the controller proxy uses the org-specific token
     * for GitHub API calls.
     */
    public String getGithubOrg() {
        return githubOrg;
    }

    /**
     * Sets the GitHub organization name for org-based token selection.
     *
     * @param githubOrg the GitHub organization name (e.g., "my-org")
     */
    public void setGithubOrg(String githubOrg) {
        this.githubOrg = githubOrg;
    }

    /**
     * Returns the next agent endpoint using round-robin selection.
     * This method is thread-safe.
     *
     * @return the next agent, or null if no agents are configured
     */
    public AgentEndpoint getNextAgent() {
        if (agents.isEmpty()) {
            return null;
        }
        // Simple round-robin - could be enhanced with availability checking
        synchronized (agents) {
            AgentEndpoint agent = agents.remove(0);
            agents.add(agent);
            return agent;
        }
    }

    @Override
    public String toString() {
        return "SlackWorkstream{" +
               "workstreamId='" + workstreamId + '\'' +
               ", channelName='" + channelName + '\'' +
               ", agents=" + agents.size() +
               ", defaultBranch='" + defaultBranch + '\'' +
               '}';
    }
}
