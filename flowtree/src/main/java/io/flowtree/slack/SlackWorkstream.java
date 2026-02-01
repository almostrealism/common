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
 * @see SlackBotController
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
    private boolean pushToOrigin;
    private String workingDirectory;

    // Job configuration
    private String allowedTools;
    private int maxTurns;
    private double maxBudgetUsd;

    /**
     * Creates a new workstream with default settings.
     */
    public SlackWorkstream() {
        this.workstreamId = UUID.randomUUID().toString();
        this.agents = new ArrayList<>();
        this.pushToOrigin = true;
        this.allowedTools = "Read,Edit,Write,Bash,Glob,Grep";
        this.maxTurns = 50;
        this.maxBudgetUsd = 10.0;
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
