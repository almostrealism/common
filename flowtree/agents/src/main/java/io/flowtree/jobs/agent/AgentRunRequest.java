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

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameter object describing a single agent invocation.
 *
 * <p>The orchestrator builds one of these per phase and hands it to the
 * selected {@link AgentRunner}. It carries everything the runner needs to
 * launch a session: prompt, working directory, MCP policy, runner-specific
 * model and effort, budget caps, and the inactivity timeout shared by all
 * runners.</p>
 *
 * <p>This class is immutable; use {@link Builder} to construct instances.</p>
 */
public final class AgentRunRequest {

    /** Full instruction prompt handed to the agent. */
    private final String prompt;
    /** Working directory in which the agent process runs. */
    private final Path workingDirectory;
    /** Orchestrator-owned tool allowlist (CSV). */
    private final String allowedTools;
    /** MCP server configuration JSON in the canonical {@code {"mcpServers":{...}}} shape. */
    private final String mcpConfigJson;
    /** Additional environment variables set on the agent subprocess. */
    private final Map<String, String> environment;
    /** Requested model identifier; {@code null} means runner default. */
    private final String model;
    /** Requested effort/thinking level; {@code null} means runner default. */
    private final String effort;
    /** Requested provider identifier; {@code null} means runner default. */
    private final String provider;
    /** Maximum number of agentic turns; {@code 0} means runner default. */
    private final int maxTurns;
    /** Maximum USD budget; {@code <= 0} means unlimited. */
    private final double maxBudgetUsd;
    /** Stdout silence timeout in milliseconds before the inactivity watchdog fires. */
    private final long inactivityTimeoutMillis;
    /** Current inactivity-restart attempt index (0 for first attempt). */
    private final int inactivityRestartAttempt;
    /** Maximum number of inactivity-triggered relaunches. */
    private final int maxInactivityRestarts;
    /** Task identifier propagated to log lines. */
    private final String taskId;
    /** Activity tag exposed to in-container MCP helpers via {@code AR_AGENT_ACTIVITY}. */
    private final String activityTag;
    /** File path where the runner should dump its raw output. */
    private final Path outputCapturePath;
    /** When {@code true}, the runner launches the agent inside a tmux session (real tty). */
    private final boolean useTmux;

    /**
     * Key in {@link #getEnvironment()} that carries the workstream identifier
     * forwarded by the orchestrator. Defined here so callers that need to read
     * the workstream ID do not hard-code the string literal.
     */
    public static final String ENV_WORKSTREAM_ID = "AR_WORKSTREAM_ID";

    /** Constructs an instance from {@code b}. */
    private AgentRunRequest(Builder b) {
        this.prompt = b.prompt;
        this.workingDirectory = b.workingDirectory;
        this.allowedTools = b.allowedTools;
        this.mcpConfigJson = b.mcpConfigJson;
        this.environment = b.environment == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.environment));
        this.model = b.model;
        this.effort = b.effort;
        this.provider = b.provider;
        this.maxTurns = b.maxTurns;
        this.maxBudgetUsd = b.maxBudgetUsd;
        this.inactivityTimeoutMillis = b.inactivityTimeoutMillis;
        this.inactivityRestartAttempt = b.inactivityRestartAttempt;
        this.maxInactivityRestarts = b.maxInactivityRestarts;
        this.taskId = b.taskId;
        this.activityTag = b.activityTag;
        this.outputCapturePath = b.outputCapturePath;
        this.useTmux = b.useTmux;
    }

    /** Returns the full instruction prompt to send to the agent. */
    public String getPrompt() { return prompt; }

    /** Returns the working directory in which the agent should run. */
    public Path getWorkingDirectory() { return workingDirectory; }

    /**
     * Returns the orchestrator-owned, comma-separated logical tool allowlist.
     * The runner is responsible for translating this into whatever its CLI
     * accepts.
     */
    public String getAllowedTools() { return allowedTools; }

    /**
     * Returns the MCP server configuration JSON in Claude Code's
     * {@code {"mcpServers":{...}}} shape. Runners that consume a different
     * native schema translate from this canonical form.
     */
    public String getMcpConfigJson() { return mcpConfigJson; }

    /** Returns any additional environment variables to set on the agent subprocess. */
    public Map<String, String> getEnvironment() { return environment; }

    /** Returns the requested model identifier; {@code null} uses the runner default. */
    public String getModel() { return model; }

    /** Returns the requested effort/thinking level; {@code null} uses the runner default. */
    public String getEffort() { return effort; }

    /** Returns the requested provider identifier; {@code null} uses the runner default. */
    public String getProvider() { return provider; }

    /** Returns the maximum number of agentic turns; {@code 0} means runner default. */
    public int getMaxTurns() { return maxTurns; }

    /** Returns the maximum USD budget for this invocation; {@code <= 0} means unlimited. */
    public double getMaxBudgetUsd() { return maxBudgetUsd; }

    /** Returns the stdout-silence timeout in milliseconds. */
    public long getInactivityTimeoutMillis() { return inactivityTimeoutMillis; }

    /** Returns the current inactivity-restart attempt index (0 for the first attempt). */
    public int getInactivityRestartAttempt() { return inactivityRestartAttempt; }

    /** Returns the maximum number of inactivity-triggered relaunches. */
    public int getMaxInactivityRestarts() { return maxInactivityRestarts; }

    /** Returns the task identifier used for log decoration. */
    public String getTaskId() { return taskId; }

    /**
     * Returns the activity tag to expose to in-container MCP helpers (e.g.,
     * {@code AR_AGENT_ACTIVITY}). {@code null} or empty means primary work.
     */
    public String getActivityTag() { return activityTag; }

    /** Returns the file path where the runner should dump its raw output. */
    public Path getOutputCapturePath() { return outputCapturePath; }

    /**
     * Returns whether the runner should launch the agent inside a tmux session
     * so the child process receives a real controlling tty. {@code false} by
     * default; runners may additionally honour their own environment-based
     * opt-in (e.g. {@code AR_AGENT_USE_TMUX}).
     *
     * @return {@code true} to request a tmux-backed launch
     */
    public boolean isUseTmux() { return useTmux; }

    /**
     * Returns the workstream identifier from the request environment, or
     * {@code null} if {@link #ENV_WORKSTREAM_ID} is absent from the map.
     */
    public String getWorkstreamId() {
        return environment.get(ENV_WORKSTREAM_ID);
    }

    /** Returns a fresh {@link Builder}. */
    public static Builder builder() { return new Builder(); }

    /**
     * Mutable builder for {@link AgentRunRequest} instances.
     */
    public static final class Builder {
        /** Pending prompt; see {@link AgentRunRequest#getPrompt()}. */
        private String prompt;
        /** Pending working directory; see {@link AgentRunRequest#getWorkingDirectory()}. */
        private Path workingDirectory;
        /** Pending allowed-tools CSV; see {@link AgentRunRequest#getAllowedTools()}. */
        private String allowedTools;
        /** Pending MCP config JSON; see {@link AgentRunRequest#getMcpConfigJson()}. */
        private String mcpConfigJson;
        /** Pending environment overrides; see {@link AgentRunRequest#getEnvironment()}. */
        private Map<String, String> environment;
        /** Pending model identifier; see {@link AgentRunRequest#getModel()}. */
        private String model;
        /** Pending effort level; see {@link AgentRunRequest#getEffort()}. */
        private String effort;
        /** Pending provider identifier; see {@link AgentRunRequest#getProvider()}. */
        private String provider;
        /** Pending agentic-turn cap; see {@link AgentRunRequest#getMaxTurns()}. */
        private int maxTurns;
        /** Pending USD budget cap; see {@link AgentRunRequest#getMaxBudgetUsd()}. */
        private double maxBudgetUsd;
        /** Pending inactivity timeout in ms; see {@link AgentRunRequest#getInactivityTimeoutMillis()}. */
        private long inactivityTimeoutMillis;
        /** Pending inactivity-restart attempt index; see {@link AgentRunRequest#getInactivityRestartAttempt()}. */
        private int inactivityRestartAttempt;
        /** Pending max inactivity restarts; see {@link AgentRunRequest#getMaxInactivityRestarts()}. */
        private int maxInactivityRestarts;
        /** Pending task identifier; see {@link AgentRunRequest#getTaskId()}. */
        private String taskId;
        /** Pending activity tag; see {@link AgentRunRequest#getActivityTag()}. */
        private String activityTag;
        /** Pending raw-output capture path; see {@link AgentRunRequest#getOutputCapturePath()}. */
        private Path outputCapturePath;
        /** Pending tmux-launch flag; see {@link AgentRunRequest#isUseTmux()}. */
        private boolean useTmux;

        /** Hidden default constructor; obtain instances via {@link AgentRunRequest#builder()}. */
        private Builder() {}

        /** Sets the instruction prompt. */
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        /** Sets the working directory. */
        public Builder workingDirectory(Path workingDirectory) { this.workingDirectory = workingDirectory; return this; }
        /** Sets the logical CSV tool allowlist. */
        public Builder allowedTools(String allowedTools) { this.allowedTools = allowedTools; return this; }
        /** Sets the MCP config JSON. */
        public Builder mcpConfigJson(String mcpConfigJson) { this.mcpConfigJson = mcpConfigJson; return this; }
        /** Sets additional environment variables. */
        public Builder environment(Map<String, String> environment) { this.environment = environment; return this; }
        /** Sets the requested model. */
        public Builder model(String model) { this.model = model; return this; }
        /** Sets the requested effort level. */
        public Builder effort(String effort) { this.effort = effort; return this; }
        /** Sets the requested provider. */
        public Builder provider(String provider) { this.provider = provider; return this; }
        /** Sets the maximum number of agentic turns. */
        public Builder maxTurns(int maxTurns) { this.maxTurns = maxTurns; return this; }
        /** Sets the maximum USD budget. */
        public Builder maxBudgetUsd(double maxBudgetUsd) { this.maxBudgetUsd = maxBudgetUsd; return this; }
        /** Sets the inactivity-kill timeout in milliseconds. */
        public Builder inactivityTimeoutMillis(long inactivityTimeoutMillis) { this.inactivityTimeoutMillis = inactivityTimeoutMillis; return this; }
        /** Sets the current inactivity-restart attempt (0 for first try). */
        public Builder inactivityRestartAttempt(int inactivityRestartAttempt) { this.inactivityRestartAttempt = inactivityRestartAttempt; return this; }
        /** Sets the maximum number of inactivity-triggered relaunches. */
        public Builder maxInactivityRestarts(int maxInactivityRestarts) { this.maxInactivityRestarts = maxInactivityRestarts; return this; }
        /** Sets the task identifier for log decoration. */
        public Builder taskId(String taskId) { this.taskId = taskId; return this; }
        /** Sets the activity tag for correction sessions. */
        public Builder activityTag(String activityTag) { this.activityTag = activityTag; return this; }
        /** Sets the file path to which the runner should dump its raw output. */
        public Builder outputCapturePath(Path outputCapturePath) { this.outputCapturePath = outputCapturePath; return this; }
        /** Sets whether the runner should launch the agent inside a tmux session. */
        public Builder useTmux(boolean useTmux) { this.useTmux = useTmux; return this; }

        /** Builds the immutable request. */
        public AgentRunRequest build() { return new AgentRunRequest(this); }
    }
}
