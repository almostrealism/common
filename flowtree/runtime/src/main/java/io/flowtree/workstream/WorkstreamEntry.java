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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration entry for a single workstream.
 *
 * <p>A workstream binds a git branch to a Slack channel (or runs headlessly
 * without Slack) and declares the operational parameters for agent jobs
 * submitted against that branch. Instances are loaded from the
 * {@code workstreams:} YAML list and converted to live {@link Workstream}
 * objects via {@link #toWorkstream()}.</p>
 *
 * @author Michael Murray
 * @see WorkstreamConfig
 * @see Workstream
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkstreamEntry {

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
    /** Per-workstream environment variables set on the agent subprocess itself. */
    private Map<String, String> agentEnv;
    /** Optional path to a planning document the agent consults for context. */
    private String planningDocument;
    /** GitHub organization name for org-based token selection. */
    private String githubOrg;
    /** Additional repository URLs cloned alongside the primary repo. */
    private List<String> dependentRepos;
    /**
     * Workstream IDs of completion listeners that should be woken up
     * automatically when a job on this workstream reaches a terminal
     * status. The listener graph must be a DAG; the cycle check runs
     * on register / update and rejects configurations that would
     * introduce a cycle, including self-listing.
     */
    private List<String> completionListeners;
    /** Node labels that jobs submitted to this workstream must match by default. */
    private Map<String, String> requiredLabels;
    /**
     * The workspace ID this workstream is bound to. Accepts the legacy
     * YAML key {@code slackWorkspaceId} via {@link JsonAlias} so existing
     * configs continue to load; the field now holds an operator-chosen
     * workspace ID rather than a Slack team ID.
     */
    @JsonAlias({"slackWorkspaceId"})
    private String workspaceId;
    /**
     * Default {@link io.flowtree.jobs.agent.AgentRunner} applied to jobs
     * in this workstream when no per-phase or per-job override is set.
     * Legacy field: accepted on load and auto-migrated into
     * {@link #defaultPhaseConfig}, but write-only for serialization.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String defaultRunner;
    /**
     * Per-phase runner overrides keyed by phase wire name (e.g.
     * {@code primary}, {@code deduplication}). Phases not listed inherit
     * {@link #defaultRunner}. Legacy field: accepted on load and
     * auto-migrated into {@link #phaseConfigs}, but write-only for
     * serialization.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Map<String, String> runners = new LinkedHashMap<>();
    /**
     * Workstream-level default {@link PhaseConfig}; new form for the
     * unified per-phase config ladder. Optional; {@code null} omits the
     * field from serialized YAML.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PhaseConfig defaultPhaseConfig;
    /**
     * Workstream-level per-phase {@link PhaseConfig} overrides keyed by
     * phase wire name. Optional; an empty map is omitted from
     * serialized YAML.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, PhaseConfig> phaseConfigs = new LinkedHashMap<>();
    /**
     * Whether this workstream is archived. Archived workstreams are
     * hidden from default {@code workstream_list} responses but their
     * job history and memories remain queryable.
     */
    private boolean archived;

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

    /** Returns per-workstream environment variables set on the agent subprocess. */
    public Map<String, String> getAgentEnv() { return agentEnv; }
    /** Sets per-workstream environment variables for the agent subprocess. */
    public void setAgentEnv(Map<String, String> agentEnv) { this.agentEnv = agentEnv; }

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
     * Returns the workstream IDs of completion listeners that should
     * be woken up automatically when a job on this workstream reaches
     * a terminal status. The cycle check rejects configurations that
     * would create a cycle in the listener graph at config time.
     *
     * @return listener workstream IDs; never {@code null}, may be empty
     */
    public List<String> getCompletionListeners() {
        return completionListeners == null
                ? Collections.emptyList() : completionListeners;
    }
    /**
     * Sets the listener workstream IDs. Pass {@code null} or empty
     * to clear (the inert default, which spawns no wake-up jobs).
     */
    public void setCompletionListeners(List<String> completionListeners) {
        this.completionListeners = (completionListeners == null)
                ? new ArrayList<>() : new ArrayList<>(completionListeners);
    }

    /**
     * Returns the Node labels that jobs submitted to this workstream must match by default.
     * When a job submission does not include {@code requiredLabels}, these are applied.
     */
    public Map<String, String> getRequiredLabels() { return requiredLabels; }
    /** Sets the default Node label requirements for jobs in this workstream. */
    public void setRequiredLabels(Map<String, String> requiredLabels) { this.requiredLabels = requiredLabels; }

    /**
     * Returns the workspace ID this workstream is assigned to. When absent,
     * the workstream is assigned to the first workspace connection.
     */
    public String getWorkspaceId() { return workspaceId; }
    /** Sets the workspace ID for this workstream. */
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    /** Returns the default agent runner for jobs in this workstream, or {@code null}. */
    public String getDefaultRunner() { return defaultRunner; }
    /** Sets the default agent runner for jobs in this workstream. */
    public void setDefaultRunner(String defaultRunner) { this.defaultRunner = defaultRunner; }

    /** Returns the per-phase runner override map (keyed by phase wire name); never {@code null}. */
    public Map<String, String> getRunners() { return runners; }
    /** Replaces the per-phase runner override map; {@code null} is treated as empty. */
    public void setRunners(Map<String, String> runners) {
        this.runners = runners != null ? new LinkedHashMap<>(runners) : new LinkedHashMap<>();
    }

    /** Returns the workstream-level default {@link PhaseConfig}, or {@code null}. */
    public PhaseConfig getDefaultPhaseConfig() { return defaultPhaseConfig; }
    /** Sets the workstream-level default {@link PhaseConfig}. */
    public void setDefaultPhaseConfig(PhaseConfig defaultPhaseConfig) {
        this.defaultPhaseConfig = defaultPhaseConfig;
    }

    /** Returns the workstream-level per-phase {@link PhaseConfig} overrides; never {@code null}. */
    public Map<String, PhaseConfig> getPhaseConfigs() { return phaseConfigs; }
    /** Replaces the workstream-level per-phase {@link PhaseConfig} overrides. */
    public void setPhaseConfigs(Map<String, PhaseConfig> phaseConfigs) {
        this.phaseConfigs = phaseConfigs != null ? new LinkedHashMap<>(phaseConfigs) : new LinkedHashMap<>();
    }

    /**
     * Builds the effective {@link PhaseConfigBundle} for this workstream
     * entry, merging the new fields with the legacy
     * {@code defaultRunner}/{@code runners} runner fields. The new fields
     * take precedence field-by-field.
     *
     * @return the merged bundle; never {@code null}
     */
    public PhaseConfigBundle toPhaseConfigBundle() {
        return PhaseConfigBundle.mergeLegacyWithNew(
                defaultRunner, runners, defaultPhaseConfig, phaseConfigs);
    }

    /** Returns {@code true} when this workstream is archived. */
    public boolean isArchived() { return archived; }
    /** Sets the archived flag. */
    public void setArchived(boolean archived) { this.archived = archived; }

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
        ws.setAgentEnv(agentEnv);
        ws.setPlanningDocument(planningDocument);
        ws.setGithubOrg(githubOrg);
        ws.setDependentRepos(dependentRepos);
        ws.setRequiredLabels(requiredLabels);
        ws.setWorkspaceId(workspaceId);
        ws.setDefaultRunner(defaultRunner);
        ws.setRunners(runners);
        PhaseConfigBundle bundle = toPhaseConfigBundle();
        if (!bundle.isEmpty()) {
            ws.setPhaseConfigBundle(bundle);
        }
        ws.setArchived(archived);
        ws.setCompletionListeners(completionListeners);
        return ws;
    }
}
