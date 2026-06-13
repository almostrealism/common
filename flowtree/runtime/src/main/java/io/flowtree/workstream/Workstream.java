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

package io.flowtree.workstream;

import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.flowtree.controller.FlowTreeController;

/**
 * Configuration for a workstream — a logical unit of work assigned to one or more agents.
 *
 * <p>A workstream groups agents, a git repository, branch lifecycle settings, and optional
 * routing constraints (node labels, dependent repos). It may optionally be connected to a
 * Slack channel ({@code channelId}/{@code channelName}), but this is not required: workstreams
 * can be managed entirely via the ar-manager MCP server or REST API without any Slack
 * integration.</p>
 *
 * <p>The workstream ID can be used as a namespace for MCP memory tool integration,
 * allowing both agents and operators to share context across sessions.</p>
 *
 * @author Michael Murray
 * @see FlowTreeController
 */
public class Workstream {

    /**
     * Connection details for a Flowtree agent.
     *
     * <p>This is retained for optional pre-configured agent lists, but the
     * primary connection model is inbound: agents connect to the controller's
     * {@link io.flowtree.Server} using {@code FLOWTREE_ROOT_HOST} and
     * {@code FLOWTREE_ROOT_PORT}.</p>
     */
    public static class AgentEndpoint {
        /** The hostname or IP address of the agent. */
        private final String host;
        /** The port the agent's FlowTree node is listening on. */
        private final int port;

        /**
         * Creates an agent endpoint with the given host and port.
         *
         * @param host the agent hostname or IP address
         * @param port the agent port number
         */
        public AgentEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** Returns the agent hostname or IP address. */
        public String getHost() {
            return host;
        }

        /** Returns the agent port number. */
        public int getPort() {
            return port;
        }

        /** Returns "host:port" for logging and diagnostics. */
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /** Immutable identifier; used as a memory namespace and for API routing. */
    private final String workstreamId;
    /** The Slack channel ID this workstream receives messages from. */
    private String channelId;
    /** The human-readable Slack channel name for display purposes. */
    private String channelName;
    /** Optional pre-configured agent endpoints (inbound connections are the primary model). */
    private final List<AgentEndpoint> agents;
    /** Branch that agents commit work to by default. */
    private String defaultBranch;
    /** Branch used as the base when the controller creates a new feature branch. */
    private String baseBranch;
    /** Whether agents push completed commits to the remote origin. */
    private boolean pushToOrigin;
    /** Absolute path to the local repository working directory. */
    private String workingDirectory;
    /** Git remote URL; when set the job clones the repo before starting work. */
    private String repoUrl;

    /** Comma-separated list of Claude Code tool names the agent is permitted to use. */
    private String allowedTools;
    /** Maximum agent turns per job; guards against runaway cost. */
    private int maxTurns;
    /** Maximum spending budget per job in USD. */
    private double maxBudgetUsd;

    /**
     * Default {@link io.flowtree.jobs.agent.AgentRunner} for jobs in this
     * workstream when neither the job submission nor any phase-specific
     * override applies. {@code null} or empty falls back to the controller's
     * own default ({@code "claude"}).
     */
    private String defaultRunner;

    /**
     * Per-phase runner overrides keyed by phase {@linkplain
     * Phase#wireName() wire name}. Populated from
     * workstream configuration; phases not listed here inherit
     * {@link #defaultRunner}. Never {@code null}.
     */
    private Map<String, String> runners = new LinkedHashMap<>();

    /**
     * Unified per-phase configuration bundle for this workstream. Sole source
     * of model, effort, and provider; the runner-resolution fields
     * {@link #defaultRunner} and {@link #runners} are kept in sync with it.
     */
    private PhaseConfigBundle phaseConfigBundle = PhaseConfigBundle.EMPTY;

    /** Git author name used in commits generated by this workstream's agents. */
    private String gitUserName;
    /** Git author email used in commits generated by this workstream's agents. */
    private String gitUserEmail;

    /** Per-workstream environment variables injected into pushed tool MCP stdio configs. */
    private Map<String, String> env;

    /** Per-workstream environment variables set on the agent subprocess itself. */
    private Map<String, String> agentEnv;

    /** Path to a planning document the agent consults for broader goal context. */
    private String planningDocument;

    /** GitHub organization name; selects the org-specific token for GitHub API calls. */
    private String githubOrg;

    /** Additional repository URLs to clone alongside the primary repo. */
    private List<String> dependentRepos;

    /**
     * Workstream IDs that should be woken up automatically when a job on this
     * workstream reaches a terminal status. Each entry is a workstream ID, not
     * a branch or repo URL — workstream IDs are unambiguous and the same
     * identifier the controller already uses for routing and notifier lookups.
     *
     * <p>The listener graph must be a DAG. A registration or update that would
     * create a cycle (including a self-listing) is rejected at config time by
     * {@link io.flowtree.workstream.ListenerCycleChecker} with a 400 response.
     * Cycles are rejected by construction so the wake-up cascade cannot
     * ping-pong between two workstreams.</p>
     *
     * <p>Wake-up jobs spawned on a listener are submitted with
     * {@code automated: true}, so the
     * {@link io.flowtree.api.FlowTreeApiEndpoint#setAcceptAutomatedJobs(boolean)
     * acceptAutomatedJobs} gate doubles as the kill switch: setting it to
     * {@code false} halts all wake-up generation globally while leaving
     * manual job submissions working. The wake-up handler is required to
     * reconcile the full state of every workstream it has delegated to on
     * every wake, so a dropped or coalesced wake-up loses no work — the next
     * successful wake (or the first manual job after the kill switch is
     * re-enabled) re-reads the world and resumes.</p>
     */
    private List<String> completionListeners;

    /** Default Node labels applied to jobs when no job-level labels are specified. */
    private Map<String, String> requiredLabels;

    /**
     * Workspace ID this workstream is bound to. Operator-chosen — when the
     * workspace was migrated from a legacy {@code slackWorkspaces:} entry the
     * ID equals the original Slack team ID; otherwise it is whatever ID the
     * operator chose. {@code null} for single-workspace (legacy) mode.
     */
    private String workspaceId;

    /**
     * Whether this workstream is archived. Archived workstreams remain in the
     * config (so historical job records and memories stay queryable) but are
     * hidden from default list responses. Persisted by
     * {@link WorkstreamConfig.WorkstreamEntry#toWorkstream()} and the inverse
     * sync path, so editing {@code archived: true} into a workstream's YAML
     * entry brings it back hidden on the next controller load. The archive /
     * unarchive MCP tools are the intended runtime entry points.
     */
    private boolean archived;

    /** Default git user name for new workstreams. */
    public static final String DEFAULT_GIT_USER_NAME = "Flowtree Coding Agent";

    /** Default git user email for new workstreams. */
    public static final String DEFAULT_GIT_USER_EMAIL = "michael@almostrealism.com";

    /**
     * Creates a new workstream with default settings.
     */
    public Workstream() {
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
    public Workstream(String workstreamId, String channelId, String channelName) {
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
    public Workstream(String channelId, String channelName) {
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

    /** Returns the Slack channel ID (e.g., "C0123456789"). */
    public String getChannelId() {
        return channelId;
    }

    /** Sets the Slack channel ID. */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /** Returns the human-readable Slack channel name (e.g., "#project-agent"). */
    public String getChannelName() {
        return channelName;
    }

    /** Sets the human-readable Slack channel name. */
    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    /** Returns the list of pre-configured agent endpoints for this workstream. */
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
    public Workstream addAgent(String host, int port) {
        agents.add(new AgentEndpoint(host, port));
        return this;
    }

    /** Returns the default git branch that agents commit work to. */
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

    /** Returns whether agents push completed commits to the remote origin. */
    public boolean isPushToOrigin() {
        return pushToOrigin;
    }

    /** Sets whether agents should push commits to the remote origin after completing work. */
    public void setPushToOrigin(boolean pushToOrigin) {
        this.pushToOrigin = pushToOrigin;
    }

    /** Returns the local filesystem path to the checked-out repository. */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /** Sets the local filesystem path to the checked-out repository. */
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

    /** Returns the comma-separated list of Claude Code tool names the agent may use. */
    public String getAllowedTools() {
        return allowedTools;
    }

    /** Sets the comma-separated list of allowed Claude Code tool names. */
    public void setAllowedTools(String allowedTools) {
        this.allowedTools = allowedTools;
    }

    /** Returns the maximum number of agent turns allowed per job. */
    public int getMaxTurns() {
        return maxTurns;
    }

    /** Sets the maximum number of agent turns allowed per job. */
    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    /** Returns the maximum spending budget per job in USD. */
    public double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    /** Sets the maximum spending budget per job in USD. */
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
     * Returns per-workstream environment variables set directly on the agent
     * subprocess. Unlike {@link #getEnv()} (which targets pushed-tool MCP
     * stdio configs), these are inherited by every process the agent spawns,
     * including project-local MCP servers declared in the repo.
     *
     * @return the agent-subprocess environment map, or {@code null} if unset
     */
    public Map<String, String> getAgentEnv() {
        return agentEnv;
    }

    /**
     * Sets per-workstream environment variables for the agent subprocess.
     *
     * @param agentEnv map of environment variable names to values
     */
    public void setAgentEnv(Map<String, String> agentEnv) {
        this.agentEnv = agentEnv;
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
     * Returns the list of dependent repository URLs that should be
     * checked out alongside the primary repo. Each dependent repo is
     * cloned as a sibling directory and managed with the same branch
     * and commit lifecycle as the primary repo.
     */
    public List<String> getDependentRepos() {
        return dependentRepos;
    }

    /**
     * Sets the dependent repository URLs.
     *
     * @param dependentRepos list of git clone URLs for dependent repos
     */
    public void setDependentRepos(List<String> dependentRepos) {
        this.dependentRepos = dependentRepos;
    }

    /**
     * Returns the workstream IDs of completion listeners that should be
     * woken up automatically when a job on this workstream reaches a
     * terminal status. The returned list is never {@code null} and never
     * contains {@code null} entries; the empty list means "no listeners
     * configured," which is the default and produces no fan-out at all.
     *
     * @return immutable view of the listener list; may be empty
     */
    public List<String> getCompletionListeners() {
        if (completionListeners == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(completionListeners);
    }

    /**
     * Sets the workstream IDs of completion listeners that should be woken
     * up automatically when a job on this workstream reaches a terminal
     * status. Each entry must be a workstream ID; the controller's cycle
     * checker rejects configurations that would create a cycle in the
     * listener graph (including self-listing) at config time, so it is
     * safe to call this without re-checking the graph for the immediate
     * setter call.
     *
     * <p>Pass {@code null} or an empty list to clear all listeners; the
     * resulting workstream is the inert default and will spawn no
     * wake-up jobs on completion.</p>
     *
     * <p>Entries that are {@code null} or blank are dropped so a stray
     * entry from YAML or a tool caller cannot end up as a phantom
     * listener ID; the invariant
     * ({@link #getCompletionListeners()} never returns {@code null}
     * entries) is enforced at the setter, not just on read.</p>
     *
     * <p>Kill switch: this is gated by
     * {@link io.flowtree.api.FlowTreeApiEndpoint#setAcceptAutomatedJobs(boolean)
     * acceptAutomatedJobs}. Setting that flag to {@code false} halts all
     * wake-up generation globally, regardless of the listener list.</p>
     *
     * @param completionListeners the listener workstream IDs, or {@code null}
     */
    public void setCompletionListeners(List<String> completionListeners) {
        List<String> copy = new ArrayList<>();
        if (completionListeners != null) {
            for (String id : completionListeners) {
                if (id == null) continue;
                String trimmed = id.trim();
                if (trimmed.isEmpty()) continue;
                copy.add(trimmed);
            }
        }
        this.completionListeners = copy;
    }

    /**
     * Returns the Node labels that jobs submitted to this workstream must match by default.
     * When a job submission does not specify {@code requiredLabels}, these labels are applied.
     * Job-level labels always take precedence over workstream-level defaults.
     */
    public Map<String, String> getRequiredLabels() {
        return requiredLabels;
    }

    /**
     * Sets the default Node labels for jobs submitted to this workstream.
     *
     * @param requiredLabels map of label key-value pairs (e.g., {"platform": "macos"})
     */
    public void setRequiredLabels(Map<String, String> requiredLabels) {
        this.requiredLabels = requiredLabels;
    }

    /**
     * Returns the workspace ID this workstream is bound to. When {@code null},
     * the workstream belongs to the first (or only) workspace, maintaining
     * backward compatibility with single-workspace mode. For workspaces
     * migrated from the legacy {@code slackWorkspaces:} key the ID equals the
     * Slack team ID; for renamed or freshly-created workspaces it is an
     * operator-chosen identifier and the Slack team ID (when present) lives
     * on the workspace entry's {@code slackTeamId} field.
     */
    public String getWorkspaceId() {
        return workspaceId;
    }

    /**
     * Sets the workspace ID for this workstream.
     *
     * @param workspaceId the operator-chosen workspace ID, or {@code null}
     */
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    /**
     * Returns {@code true} when this workstream has been archived. Archived
     * workstreams are hidden from default {@code workstream_list} responses
     * but their job history and memories remain queryable.
     */
    public boolean isArchived() {
        return archived;
    }

    /**
     * Sets the archived flag for this workstream. Archiving does not delete
     * any data — it only suppresses the workstream from default listings and
     * blocks future {@code workstream_delete} calls until the flag is cleared
     * (or {@code force=true} is passed).
     *
     * @param archived {@code true} to mark archived, {@code false} to restore
     */
    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /**
     * Returns the default agent runner identifier for this workstream, or
     * {@code null} when no workstream-level default is set.
     */
    public String getDefaultRunner() { return defaultRunner; }

    /**
     * Sets the default agent runner for this workstream. Empty/{@code null}
     * clears the default so submissions fall back to the controller default.
     *
     * @param defaultRunner the runner identifier or {@code null}/empty
     */
    public void setDefaultRunner(String defaultRunner) {
        this.defaultRunner = (defaultRunner == null || defaultRunner.isEmpty())
                ? null : defaultRunner;
        this.phaseConfigBundle = phaseConfigBundle.withDefaultRunner(this.defaultRunner);
    }

    /**
     * Returns the per-phase runner override map keyed by phase wire name.
     * Never {@code null}; may be empty when no overrides are configured.
     */
    public Map<String, String> getRunners() { return runners; }

    /**
     * Replaces the per-phase runner override map. {@code null} is treated as
     * the empty map.
     *
     * @param runners override map keyed by phase wire name
     */
    public void setRunners(Map<String, String> runners) {
        this.runners = runners != null ? new LinkedHashMap<>(runners) : new LinkedHashMap<>();
        // Sync bundle runner overrides from the legacy map, preserving any
        // model/effort entries already present per phase.
        PhaseConfigBundle next = new PhaseConfigBundle(
                phaseConfigBundle.defaultPhaseConfig(), Collections.emptyMap());
        for (Map.Entry<Phase, PhaseConfig> e
                : phaseConfigBundle.phaseConfigs().entrySet()) {
            // Preserve non-runner fields; clear runner unless re-supplied below.
            PhaseConfig preserved = e.getValue().withRunner(null);
            if (!preserved.isEmpty()) {
                next = next.withPhase(e.getKey(), preserved);
            }
        }
        if (this.runners != null) {
            for (Map.Entry<String, String> e : this.runners.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                Phase phase;
                try {
                    phase = Phase.fromWireName(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                PhaseConfig existing = next.phaseConfigs().get(phase);
                PhaseConfig updated = (existing != null ? existing : PhaseConfig.EMPTY)
                        .withRunner(e.getValue());
                next = next.withPhase(phase, updated);
            }
        }
        this.phaseConfigBundle = next;
    }

    /**
     * Returns the unified per-phase configuration bundle for this workstream.
     * This is the sole source of model, effort, and provider; the
     * runner-resolution fields {@code defaultRunner} / {@code runners} are
     * kept in sync with it.
     *
     * @return the bundle, never {@code null}
     */
    public PhaseConfigBundle getPhaseConfigBundle() {
        return phaseConfigBundle;
    }

    /**
     * Replaces the per-phase configuration bundle. Updates the runner-resolution
     * fields ({@code defaultRunner}, {@code runners}) to mirror the new bundle.
     * Model, effort, and provider live solely in the bundle.
     *
     * @param bundle the new bundle; {@code null} resets to
     *               {@link PhaseConfigBundle#EMPTY}
     */
    public void setPhaseConfigBundle(PhaseConfigBundle bundle) {
        this.phaseConfigBundle = bundle != null ? bundle : PhaseConfigBundle.EMPTY;
        PhaseConfig def = phaseConfigBundle.defaultPhaseConfig();
        this.defaultRunner = (def.runner() != null && !def.runner().isEmpty()) ? def.runner() : null;
        this.runners = new LinkedHashMap<>();
        for (Map.Entry<Phase, PhaseConfig> e
                : phaseConfigBundle.phaseConfigs().entrySet()) {
            String r = e.getValue().runner();
            if (r != null && !r.isEmpty()) {
                this.runners.put(e.getKey().wireName(), r);
            }
        }
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

    /**
     * Returns a JSON object representing this workstream's configuration and capabilities,
     * suitable for inclusion in the {@code GET /api/workstreams} list response.
     *
     * <p>All string fields are JSON-escaped. Optional fields are omitted when null or empty.
     * Computed fields: {@code hasPlanningDocument}, {@code pipelineCapable}.</p>
     *
     * @return a JSON object string (not an array)
     */
    public String toSummaryJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"workstreamId\":\"").append(escapeForJson(workstreamId)).append("\"");

        if (channelName != null) {
            json.append(",\"channelName\":\"").append(escapeForJson(channelName)).append("\"");
        }
        if (defaultBranch != null) {
            json.append(",\"defaultBranch\":\"").append(escapeForJson(defaultBranch)).append("\"");
        }
        if (baseBranch != null) {
            json.append(",\"baseBranch\":\"").append(escapeForJson(baseBranch)).append("\"");
        }
        if (repoUrl != null) {
            json.append(",\"repoUrl\":\"").append(escapeForJson(repoUrl)).append("\"");
        }
        if (githubOrg != null) {
            json.append(",\"githubOrg\":\"").append(escapeForJson(githubOrg)).append("\"");
        }
        if (workspaceId != null) {
            json.append(",\"workspaceId\":\"").append(escapeForJson(workspaceId)).append("\"");
            // Legacy alias retained so older clients that read this field by
            // its previous name continue to work; remove in a future release.
            json.append(",\"slackWorkspaceId\":\"").append(escapeForJson(workspaceId)).append("\"");
        }
        if (planningDocument != null && !planningDocument.isEmpty()) {
            json.append(",\"planningDocument\":\"").append(escapeForJson(planningDocument)).append("\"");
        }

        boolean pipelineCapable = repoUrl != null && !repoUrl.isEmpty();
        json.append(",\"hasPlanningDocument\":").append(planningDocument != null && !planningDocument.isEmpty());
        json.append(",\"pipelineCapable\":").append(pipelineCapable);
        if (archived) {
            json.append(",\"archived\":true");
        }

        if (dependentRepos != null && !dependentRepos.isEmpty()) {
            json.append(",\"dependentRepos\":[");
            boolean first = true;
            for (String repo : dependentRepos) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(escapeForJson(repo)).append("\"");
            }
            json.append("]");
        }

        if (requiredLabels != null && !requiredLabels.isEmpty()) {
            json.append(",\"requiredLabels\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : requiredLabels.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(escapeForJson(entry.getKey())).append("\":");
                json.append("\"").append(escapeForJson(entry.getValue())).append("\"");
            }
            json.append("}");
        }

        json.append("}");
        return json.toString();
    }

    /**
     * Escapes a string for safe inclusion as a JSON string value.
     *
     * @param s the string to escape, or {@code null}
     * @return the escaped string, never {@code null}
     */
    private static String escapeForJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public String toString() {
        return "Workstream{" +
               "workstreamId='" + workstreamId + '\'' +
               ", channelName='" + channelName + '\'' +
               ", agents=" + agents.size() +
               ", defaultBranch='" + defaultBranch + '\'' +
               '}';
    }
}
