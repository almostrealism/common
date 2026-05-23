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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /**
     * Legacy single Slack user ID automatically invited to newly created
     * workstream channels. Superseded by {@link #channelOwnerUserIds} when
     * that list is non-empty; retained for backwards compatibility with
     * configs that predate multi-user invites.
     */
    private String channelOwnerUserId;
    /**
     * Slack user IDs automatically invited to newly created workstream
     * channels. When set, takes precedence over {@link #channelOwnerUserId}.
     */
    private List<String> channelOwnerUserIds;
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
     * Workspace configuration entries (operator-chosen IDs, optional Slack
     * connection). Populated from the {@code workspaces:} top-level YAML key
     * and — for backward compatibility — also from the legacy
     * {@code slackWorkspaces:} key (which is migrated on load so each legacy
     * entry's {@code id} doubles as its {@code slackTeamId}).
     */
    private List<WorkspaceEntry> workspaces = new ArrayList<>();

    /**
     * Configuration entry for a workspace — the operator's organizational
     * unit, optionally connected to a Slack team.
     *
     * <p>The {@code id} field is operator-chosen and is the identifier
     * referenced by {@link WorkstreamEntry#getWorkspaceId()}. The optional
     * {@code slackTeamId} field carries the Slack team ID (e.g.
     * {@code "T0123456789"}) when the workspace is connected to Slack;
     * when absent the workspace has no Slack integration and channel/notifier
     * operations skip cleanly. Legacy {@code slackWorkspaces:} entries are
     * auto-migrated on load so {@code id == slackTeamId}.</p>
     *
     * <p>Slack-credential fields ({@code tokensFile}/{@code botToken}/
     * {@code appToken}) only have effect when {@code slackTeamId} is set.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkspaceEntry {
        /**
         * Operator-chosen workspace identifier. For legacy {@code slackWorkspaces:}
         * entries the YAML key {@code workspaceId} is accepted via
         * {@link JsonAlias} and double-loaded as both {@code id} and
         * {@code slackTeamId} so that older configs continue to resolve.
         */
        @JsonAlias({"workspaceId"})
        private String id;
        /**
         * Optional Slack team ID (e.g. {@code "T0123456789"}) identifying the
         * Slack workspace this entry routes to. {@code null} when this
         * workspace has no Slack connection.
         */
        private String slackTeamId;
        /** Human-readable label for this workspace (used in logs and diagnostics). */
        private String name;
        /** Path to a JSON file with {@code botToken} and {@code appToken}. */
        private String tokensFile;
        /** Inline bot token (xoxb-...); used when {@code tokensFile} is absent. */
        private String botToken;
        /** Inline app token (xapp-...); used when {@code tokensFile} is absent. */
        private String appToken;
        /** Default Slack channel ID for fallback message delivery in this workspace. */
        private String defaultChannel;
        /**
         * Single Slack user ID auto-invited to newly created channels in this
         * workspace. Superseded by {@link #channelOwnerUserIds} when that list
         * is non-empty; retained for backwards compatibility with configs that
         * predate multi-user invites.
         */
        private String channelOwnerUserId;
        /**
         * Slack user IDs auto-invited to newly created channels in this
         * workspace. When set, takes precedence over {@link #channelOwnerUserId}.
         */
        private List<String> channelOwnerUserIds;
        /** Per-organization GitHub tokens scoped to this workspace. */
        private Map<String, GitHubOrgEntry> githubOrgs = new LinkedHashMap<>();
        /** Secrets declared as available to workstreams in this workspace. */
        private List<WorkspaceSecretEntry> secrets = new ArrayList<>();
        /**
         * Workspace-level default {@link io.flowtree.jobs.agent.AgentRunner}
         * applied to workstreams in this workspace when neither the workstream
         * itself nor the per-job override sets one. Sits between the
         * workstream default and the controller default in the routing
         * ladder. Optional; {@code null} omits the field from serialized YAML.
         */
        private String defaultRunner;
        /**
         * Workspace-level per-phase runner overrides keyed by phase wire name
         * (e.g. {@code primary}, {@code commit-message}). Consulted by the
         * resolver only when the workstream has no per-phase entry for the
         * same phase <em>and</em> no workstream-level {@code defaultRunner};
         * see {@link SubmissionRunnerResolver} for the full ladder. Optional;
         * an empty map is omitted from serialized YAML.
         */
        private Map<String, String> runners = new LinkedHashMap<>();

        /**
         * Workspace-level default {@link PhaseConfig} for the unified
         * per-phase config ladder. Optional; {@code null} omits the field
         * from serialized YAML. New form — supersedes the legacy
         * {@link #defaultRunner} for runner selection, and is the only
         * source of workspace-level {@code model} / {@code effort}.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private PhaseConfig defaultPhaseConfig;
        /**
         * Workspace-level per-phase {@link PhaseConfig} overrides keyed by
         * phase wire name (e.g. {@code review}). Supersedes the legacy
         * {@link #runners} map. Optional; an empty map is omitted from
         * serialized YAML.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private Map<String, PhaseConfig> phaseConfigs = new LinkedHashMap<>();

        /** Returns the operator-chosen workspace ID. */
        public String getId() { return id; }
        /** Sets the operator-chosen workspace ID. */
        public void setId(String id) { this.id = id; }

        /**
         * Returns the Slack team ID this workspace is connected to, or
         * {@code null} when the workspace has no Slack integration.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getSlackTeamId() { return slackTeamId; }
        /**
         * Sets the Slack team ID for this workspace. Pass {@code null} or
         * empty to clear the Slack connection — channel-routing operations
         * will then skip this workspace cleanly.
         */
        public void setSlackTeamId(String slackTeamId) {
            this.slackTeamId = (slackTeamId == null || slackTeamId.isEmpty()) ? null : slackTeamId;
        }

        /** Returns the human-readable workspace label. */
        public String getName() { return name; }
        /** Sets the human-readable workspace label. */
        public void setName(String name) { this.name = name; }

        /** Returns the path to the JSON tokens file, or {@code null} for inline tokens. */
        public String getTokensFile() { return tokensFile; }
        /** Sets the path to the JSON tokens file. */
        public void setTokensFile(String tokensFile) { this.tokensFile = tokensFile; }

        /** Returns the inline bot token (xoxb-...). */
        public String getBotToken() { return botToken; }
        /** Sets the inline bot token. */
        public void setBotToken(String botToken) { this.botToken = botToken; }

        /** Returns the inline app token (xapp-...). */
        public String getAppToken() { return appToken; }
        /** Sets the inline app token. */
        public void setAppToken(String appToken) { this.appToken = appToken; }

        /** Returns the default fallback channel ID for this workspace. */
        public String getDefaultChannel() { return defaultChannel; }
        /** Sets the default fallback channel ID. */
        public void setDefaultChannel(String defaultChannel) { this.defaultChannel = defaultChannel; }

        /** Returns the legacy single Slack user ID auto-invited to new channels in this workspace. */
        public String getChannelOwnerUserId() { return channelOwnerUserId; }
        /** Sets the legacy single Slack user ID for auto-invite on channel creation. */
        public void setChannelOwnerUserId(String channelOwnerUserId) { this.channelOwnerUserId = channelOwnerUserId; }

        /** Returns the list of Slack user IDs auto-invited to new channels (nullable). */
        public List<String> getChannelOwnerUserIds() { return channelOwnerUserIds; }
        /** Sets the list of Slack user IDs for auto-invite on channel creation. */
        public void setChannelOwnerUserIds(List<String> channelOwnerUserIds) {
            this.channelOwnerUserIds = channelOwnerUserIds;
        }

        /**
         * Returns the effective list of Slack user IDs to auto-invite when a
         * new channel is created in this workspace. Resolves the legacy single
         * {@code channelOwnerUserId} field and the plural
         * {@code channelOwnerUserIds} list into one canonical list: the plural
         * list wins when non-empty; otherwise the singular value becomes a
         * one-element list; otherwise an empty list is returned.
         *
         * @return never {@code null}; empty when no auto-invite is configured
         */
        public List<String> effectiveChannelOwnerUserIds() {
            if (channelOwnerUserIds != null && !channelOwnerUserIds.isEmpty()) {
                return channelOwnerUserIds;
            }
            if (channelOwnerUserId != null && !channelOwnerUserId.isEmpty()) {
                return List.of(channelOwnerUserId);
            }
            return List.of();
        }

        /** Returns the per-organization GitHub token map for this workspace. */
        public Map<String, GitHubOrgEntry> getGithubOrgs() { return githubOrgs; }
        /** Sets the per-organization GitHub token map. */
        public void setGithubOrgs(Map<String, GitHubOrgEntry> githubOrgs) { this.githubOrgs = githubOrgs; }

        /** Returns the list of secrets declared for this workspace. */
        public List<WorkspaceSecretEntry> getSecrets() { return secrets; }
        /** Sets the list of secrets declared for this workspace. */
        public void setSecrets(List<WorkspaceSecretEntry> secrets) {
            this.secrets = secrets != null ? secrets : new ArrayList<>();
        }

        /** Returns the workspace-level default agent runner, or {@code null} when none is configured. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getDefaultRunner() { return defaultRunner; }
        /** Sets the workspace-level default agent runner. */
        public void setDefaultRunner(String defaultRunner) { this.defaultRunner = defaultRunner; }

        /** Returns the workspace-level per-phase runner overrides (keyed by phase wire name); never {@code null}. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, String> getRunners() { return runners; }
        /** Replaces the workspace-level per-phase runner override map; {@code null} is treated as empty. */
        public void setRunners(Map<String, String> runners) {
            this.runners = runners != null ? new LinkedHashMap<>(runners) : new LinkedHashMap<>();
        }

        /** Returns the workspace-level default {@link PhaseConfig}, or {@code null}. */
        public PhaseConfig getDefaultPhaseConfig() { return defaultPhaseConfig; }
        /** Sets the workspace-level default {@link PhaseConfig}. */
        public void setDefaultPhaseConfig(PhaseConfig defaultPhaseConfig) {
            this.defaultPhaseConfig = defaultPhaseConfig;
        }

        /** Returns the workspace-level per-phase {@link PhaseConfig} overrides; never {@code null}. */
        public Map<String, PhaseConfig> getPhaseConfigs() { return phaseConfigs; }
        /** Replaces the workspace-level per-phase {@link PhaseConfig} overrides. */
        public void setPhaseConfigs(Map<String, PhaseConfig> phaseConfigs) {
            this.phaseConfigs = phaseConfigs != null ? new LinkedHashMap<>(phaseConfigs) : new LinkedHashMap<>();
        }

        /**
         * Builds the effective {@link PhaseConfigBundle} for this workspace,
         * merging the new {@code defaultPhaseConfig}/{@code phaseConfigs}
         * fields with the legacy {@code defaultRunner}/{@code runners} fields.
         * The new fields take precedence field-by-field when both are
         * supplied; legacy fields fill in {@code null} positions.
         *
         * @return the merged bundle; never {@code null}
         */
        public PhaseConfigBundle toPhaseConfigBundle() {
            return WorkstreamConfig.mergeBundle(defaultRunner, runners,
                    defaultPhaseConfig, phaseConfigs);
        }
    }

    /**
     * Declares a single workspace-scoped secret available to agent workstreams.
     *
     * <p>Each entry maps a logical name to the JSON file on disk that holds
     * the secret payload. The file must exist and must be readable only by the
     * controller process (permissions {@code 0600}). The controller logs a
     * warning at startup when a declared file is world- or group-readable.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkspaceSecretEntry {
        /** Unique name within the workspace; URL-safe (lowercase letters, digits, hyphens). */
        private String name;
        /** Absolute path to the JSON payload file on disk. */
        private String file;

        /** Returns the secret name. */
        public String getName() { return name; }
        /** Sets the secret name. */
        public void setName(String name) { this.name = name; }

        /** Returns the absolute path to the JSON payload file. */
        public String getFile() { return file; }
        /** Sets the path to the JSON payload file. */
        public void setFile(String file) { this.file = file; }
    }

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
        /**
         * The workspace ID this workstream is bound to. Accepts the legacy
         * YAML key {@code slackWorkspaceId} via {@link JsonAlias} so existing
         * configs continue to load; the field now holds an operator-chosen
         * workspace ID rather than a Slack team ID.
         */
        @JsonAlias({"slackWorkspaceId"})
        private String workspaceId;
        /** Default Claude Code model alias or full name applied to jobs in this workstream. */
        private String model;
        /** Default Claude Code effort/thinking level applied to jobs in this workstream. */
        private String effort;
        /**
         * Default {@link io.flowtree.jobs.agent.AgentRunner} applied to jobs
         * in this workstream when no per-phase or per-job override is set.
         */
        private String defaultRunner;
        /**
         * Per-phase runner overrides keyed by phase wire name (e.g.
         * {@code primary}, {@code deduplication}). Phases not listed inherit
         * {@link #defaultRunner}.
         */
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
         * Returns the workspace ID this workstream is assigned to. Accepts
         * the legacy YAML alias {@code slackWorkspaceId} on load; the value is
         * now an operator-chosen workspace ID, not necessarily a Slack team
         * ID. When absent, the workstream is assigned to the first (or only)
         * workspace connection in the {@code workspaces} list.
         */
        public String getWorkspaceId() { return workspaceId; }
        /** Sets the workspace ID for this workstream. */
        public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

        /** Returns the default Claude Code model for jobs in this workstream, or {@code null}. */
        public String getModel() { return model; }
        /** Sets the default Claude Code model for jobs in this workstream. */
        public void setModel(String model) { this.model = model; }

        /** Returns the default Claude Code effort/thinking level for jobs in this workstream, or {@code null}. */
        public String getEffort() { return effort; }
        /** Sets the default Claude Code effort/thinking level for jobs in this workstream. */
        public void setEffort(String effort) { this.effort = effort; }

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
         * {@code defaultRunner}/{@code runners}/{@code model}/{@code effort}
         * fields. The new fields take precedence field-by-field.
         *
         * @return the merged bundle; never {@code null}
         */
        public PhaseConfigBundle toPhaseConfigBundle() {
            PhaseConfigBundle bundle = WorkstreamConfig.mergeBundle(
                    defaultRunner, runners, defaultPhaseConfig, phaseConfigs);
            // Legacy workstream-level model/effort populate the bundle's
            // default field. New defaultPhaseConfig wins when both are
            // supplied (handled by mergeBundle).
            PhaseConfig def = bundle.defaultPhaseConfig();
            String mergedModel = def.model() != null ? def.model() : model;
            String mergedEffort = def.effort() != null ? def.effort() : effort;
            if (!Objects.equals(mergedModel, def.model())
                    || !Objects.equals(mergedEffort, def.effort())) {
                bundle = bundle.withDefault(new PhaseConfig(
                        def.runner(), mergedModel, mergedEffort));
            }
            return bundle;
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
            ws.setPlanningDocument(planningDocument);
            ws.setGithubOrg(githubOrg);
            ws.setDependentRepos(dependentRepos);
            ws.setRequiredLabels(requiredLabels);
            ws.setWorkspaceId(workspaceId);
            ws.setModel(model);
            ws.setEffort(effort);
            ws.setDefaultRunner(defaultRunner);
            ws.setRunners(runners);
            // Layer the new bundle fields on top of the legacy values just
            // applied above. The bundle setter rewrites all four legacy
            // fields, so any model / effort / runner present in the bundle
            // wins over the legacy values (matching the documented "new
            // wins" precedence on YAML load).
            PhaseConfigBundle bundle = toPhaseConfigBundle();
            if (!bundle.isEmpty()) {
                ws.setPhaseConfigBundle(bundle);
            }
            ws.setArchived(archived);
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

    /** Returns the list of Slack user IDs auto-invited to new channels (nullable). */
    public List<String> getChannelOwnerUserIds() { return channelOwnerUserIds; }
    /** Sets the list of Slack user IDs for auto-invite on channel creation. */
    public void setChannelOwnerUserIds(List<String> channelOwnerUserIds) {
        this.channelOwnerUserIds = channelOwnerUserIds;
    }

    /**
     * Returns the effective list of Slack user IDs to auto-invite on new
     * channel creation. Resolves the legacy single {@link #channelOwnerUserId}
     * and the plural {@link #channelOwnerUserIds} into one canonical list:
     * the plural list wins when non-empty; otherwise the singular value
     * becomes a one-element list; otherwise an empty list is returned.
     *
     * @return never {@code null}; empty when no auto-invite is configured
     */
    public List<String> effectiveChannelOwnerUserIds() {
        if (channelOwnerUserIds != null && !channelOwnerUserIds.isEmpty()) {
            return channelOwnerUserIds;
        }
        if (channelOwnerUserId != null && !channelOwnerUserId.isEmpty()) {
            return List.of(channelOwnerUserId);
        }
        return List.of();
    }

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

    /**
     * Returns a merged map of GitHub org name to token, combining the global
     * {@code githubOrgs} with per-workspace {@code githubOrgs} overrides. When
     * the same org name appears in both, the per-workspace entry takes precedence
     * (last write wins across workspaces). Only entries with non-null, non-empty
     * tokens are included.
     *
     * @return merged org-name → token map, in insertion order
     */
    public Map<String, String> mergedGithubOrgTokens() {
        Map<String, String> orgTokens = new LinkedHashMap<>();
        if (githubOrgs != null) {
            for (Map.Entry<String, GitHubOrgEntry> entry : githubOrgs.entrySet()) {
                String token = entry.getValue().getToken();
                if (token != null && !token.isEmpty()) {
                    orgTokens.put(entry.getKey(), token);
                }
            }
        }
        if (workspaces != null) {
            for (WorkspaceEntry wsEntry : workspaces) {
                if (wsEntry.getGithubOrgs() == null) continue;
                for (Map.Entry<String, GitHubOrgEntry> entry : wsEntry.getGithubOrgs().entrySet()) {
                    String token = entry.getValue().getToken();
                    if (token != null && !token.isEmpty()) {
                        orgTokens.put(entry.getKey(), token);
                    }
                }
            }
        }
        return orgTokens;
    }

    /**
     * Returns a map from GitHub org name to the workspace ID that owns it.
     * Only orgs that are declared inside a workspace's {@code githubOrgs}
     * section produce a mapping; globally-declared orgs (top-level
     * {@code githubOrgs}) are excluded because the multi-workspace schema
     * treats top-level orgs as unscoped fallbacks.
     *
     * <p>When the same org is declared under multiple workspaces the last
     * workspace wins, matching the merge order of {@link #mergedGithubOrgTokens()}.</p>
     *
     * @return org-name → workspace-ID map, in insertion order
     */
    public Map<String, String> orgToWorkspaceId() {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (workspaces != null) {
            for (WorkspaceEntry wsEntry : workspaces) {
                if (wsEntry.getGithubOrgs() == null) continue;
                for (String org : wsEntry.getGithubOrgs().keySet()) {
                    mapping.put(org, wsEntry.getId());
                }
            }
        }
        return mapping;
    }

    /** Returns the list of workstream configuration entries. */
    public List<WorkstreamEntry> getWorkstreams() { return workstreams; }
    /** Sets the list of workstream configuration entries. */
    public void setWorkstreams(List<WorkstreamEntry> workstreams) { this.workstreams = workstreams; }

    /**
     * Returns the list of workspace configuration entries.
     *
     * <p>When this list is non-empty the controller creates one Bolt {@code App}
     * and {@code SocketModeApp} per entry that has a {@code slackTeamId} set.
     * Entries without a {@code slackTeamId} have no Slack connection and skip
     * channel/notifier operations cleanly. When the list is empty the
     * controller falls back to the legacy single-token resolution path.</p>
     */
    @JsonProperty("workspaces")
    public List<WorkspaceEntry> getWorkspaces() { return workspaces; }

    /**
     * Sets the list of workspace configuration entries. Used by Jackson when
     * deserializing the {@code workspaces:} YAML key. Merges with any
     * entries already present (e.g. those previously added by the legacy
     * {@code slackWorkspaces:} setter) so that both YAML keys can appear in
     * the same file during the migration window. Entries with the same
     * {@code id} as a pre-existing entry are skipped on the assumption that
     * the existing entry is the authoritative one.
     */
    @JsonProperty("workspaces")
    public void setWorkspaces(List<WorkspaceEntry> workspaces) {
        if (workspaces == null) {
            this.workspaces = new ArrayList<>();
            return;
        }
        if (this.workspaces == null || this.workspaces.isEmpty()) {
            this.workspaces = new ArrayList<>(workspaces);
            return;
        }
        Set<String> existing = new HashSet<>();
        for (WorkspaceEntry e : this.workspaces) {
            if (e.getId() != null) existing.add(e.getId());
        }
        for (WorkspaceEntry e : workspaces) {
            if (e.getId() == null || !existing.contains(e.getId())) {
                this.workspaces.add(e);
            }
        }
    }

    /**
     * Returns the workspace entries as a "Slack workspaces" view for callers
     * that still iterate the legacy projection (each entry treated as a Slack
     * workspace connection). Identical to {@link #getWorkspaces()} for now;
     * preserved so older callers compile.
     */
    @JsonIgnore
    public List<WorkspaceEntry> getSlackWorkspaces() { return workspaces; }

    /**
     * Accepts the legacy {@code slackWorkspaces:} YAML key and merges its
     * entries into the unified {@link #workspaces} list. Each legacy entry is
     * migrated on the fly so that its operator-chosen {@code id} doubles as
     * its {@code slackTeamId} — this preserves the historical invariant that
     * the workspace identifier IS the Slack team ID, while letting operators
     * later rename the workspace via the {@code workspace_update_config} MCP
     * tool without losing the Slack connection.
     *
     * <p>De-duplicates by {@code id} against any entries already present in
     * {@link #workspaces}. Jackson invokes the setters in the order the keys
     * appear in the YAML file, so when a file lists both {@code workspaces:}
     * and {@code slackWorkspaces:} the canonical {@code workspaces:} entries
     * win regardless of which key comes first; legacy entries that share an
     * id with a canonical entry are silently dropped.</p>
     *
     * @param legacy parsed entries from the legacy YAML key; never serialized back
     */
    @JsonProperty("slackWorkspaces")
    public void setSlackWorkspaces(List<WorkspaceEntry> legacy) {
        if (legacy == null || legacy.isEmpty()) return;
        Set<String> existing = new HashSet<>();
        for (WorkspaceEntry e : workspaces) {
            if (e.getId() != null) existing.add(e.getId());
        }
        for (WorkspaceEntry entry : legacy) {
            if ((entry.getSlackTeamId() == null || entry.getSlackTeamId().isEmpty())
                    && entry.getId() != null && !entry.getId().isEmpty()) {
                entry.setSlackTeamId(entry.getId());
            }
            if (entry.getId() != null && existing.contains(entry.getId())) {
                continue;
            }
            workspaces.add(entry);
            if (entry.getId() != null) existing.add(entry.getId());
        }
    }

    /**
     * Loads configuration from a YAML file.
     *
     * @param file the YAML configuration file
     * @return the parsed configuration
     * @throws IOException if the file cannot be read or parsed
     */
    public static WorkstreamConfig loadFromYaml(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        WorkstreamConfig config = mapper.readValue(file, WorkstreamConfig.class);
        config.validateWorkspaceRunners();
        return config;
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
        WorkstreamConfig config = mapper.readValue(inputStream, WorkstreamConfig.class);
        config.validateWorkspaceRunners();
        return config;
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
        WorkstreamConfig config = mapper.readValue(yaml, WorkstreamConfig.class);
        config.validateWorkspaceRunners();
        return config;
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
        WorkstreamConfig config = mapper.readValue(file, WorkstreamConfig.class);
        config.validateWorkspaceRunners();
        return config;
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
        WorkstreamConfig config = mapper.readValue(json, WorkstreamConfig.class);
        config.validateWorkspaceRunners();
        return config;
    }

    /**
     * Returns the {@link WorkspaceEntry} matching the given workspace ID, or
     * {@code null} when no entry has been configured with that ID. The lookup
     * is linear over {@link #workspaces} — acceptable because the list is
     * small (one entry per workspace).
     *
     * @param id the operator-chosen workspace ID (or, for legacy entries that
     *           have not been renamed, the Slack team ID); {@code null} or
     *           empty always returns {@code null}
     * @return the matching entry, or {@code null} when no match
     */
    public WorkspaceEntry findWorkspace(String id) {
        if (id == null || id.isEmpty()) return null;
        if (workspaces == null) return null;
        for (WorkspaceEntry entry : workspaces) {
            if (id.equals(entry.getId())) return entry;
        }
        return null;
    }

    /**
     * Backward-compatible alias for {@link #findWorkspace(String)}. Retained so
     * callers (including external scripts and the test suite) that have not
     * migrated to the new name continue to compile.
     *
     * @param id the workspace ID
     * @return the matching entry, or {@code null}
     */
    public WorkspaceEntry findSlackWorkspace(String id) {
        return findWorkspace(id);
    }

    /**
     * Renames a workspace, updating every workstream that referenced the old
     * ID. Used by the {@code workspace_update_config} MCP tool when an
     * operator passes a new {@code new_id}. The Slack-team-ID connection
     * (when set) is preserved unchanged so renaming a workspace from
     * {@code "T0123456789"} to {@code "almostrealism"} does not disrupt
     * channel routing.
     *
     * @param oldId current workspace ID; must match an existing entry
     * @param newId new workspace ID; must not collide with another entry
     * @return {@code true} when the rename happened, {@code false} when the
     *         old ID was not found
     * @throws IllegalArgumentException when {@code newId} collides with an
     *         existing different workspace
     */
    public boolean renameWorkspace(String oldId, String newId) {
        if (oldId == null || oldId.isEmpty() || newId == null || newId.isEmpty()) {
            return false;
        }
        if (oldId.equals(newId)) return true;
        WorkspaceEntry target = findWorkspace(oldId);
        if (target == null) return false;
        if (findWorkspace(newId) != null) {
            throw new IllegalArgumentException("Workspace ID '" + newId
                    + "' is already taken");
        }
        target.setId(newId);
        for (WorkstreamEntry entry : workstreams) {
            if (oldId.equals(entry.getWorkspaceId())) {
                entry.setWorkspaceId(newId);
            }
        }
        return true;
    }

    /**
     * Validates the workspace-level runner configuration. Unknown phase keys
     * in any {@code slackWorkspaces[].runners} map are rejected with a clear
     * message naming the offending workspace; the rest of the config is left
     * intact. Called automatically after every {@code loadFromYaml*} /
     * {@code loadFromJson*}.
     *
     * <p>Workstream-level {@code runners} maps are <strong>not</strong>
     * validated here; bad keys there are silently skipped by
     * {@link SubmissionRunnerResolver} at submission time so a single mistyped
     * workstream cannot brick the entire controller. Workspace-level entries
     * apply to many workstreams at once, so the same forgiveness would
     * silently mis-route every workstream sharing the workspace — the
     * stricter load-time check is the safer default.</p>
     *
     * @throws IOException when any workspace runner map references an unknown
     *                     phase wire name
     */
    public void validateWorkspaceRunners() throws IOException {
        if (workspaces == null) return;
        for (WorkspaceEntry entry : workspaces) {
            Map<String, String> entryRunners = entry.getRunners();
            if (entryRunners == null || entryRunners.isEmpty()) continue;
            String label = entry.getId() != null
                    ? entry.getId() : entry.getName();
            for (String phaseKey : entryRunners.keySet()) {
                try {
                    Phase.fromWireName(phaseKey);
                } catch (IllegalArgumentException ex) {
                    StringBuilder known = new StringBuilder("[");
                    for (Phase p : Phase.values()) {
                        if (known.length() > 1) known.append(", ");
                        known.append(p.wireName());
                    }
                    known.append("]");
                    throw new IOException("Unknown phase '" + phaseKey
                            + "' in workspaces["
                            + (label != null ? label : "<unnamed>")
                            + "].runners; expected one of "
                            + known);
                }
            }
        }
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
     * Clears any workstream entry fields whose values fail validation
     * against the canonical lists in {@link CodingAgentJob}.  Currently
     * checks {@code model} against {@link CodingAgentJob#VALID_MODELS}
     * and {@code effort} against {@link CodingAgentJob#VALID_EFFORT_LEVELS};
     * invalid values are reset to {@code null} so the runtime falls back
     * to the CLI default rather than aborting startup with an
     * {@link IllegalArgumentException} from {@link Workstream#setModel}
     * or {@link Workstream#setEffort}.
     *
     * @return human-readable warnings describing each cleared value;
     *         empty when no changes were made
     */
    public List<String> sanitize() {
        List<String> warnings = new ArrayList<>();
        for (WorkstreamEntry entry : workstreams) {
            String entryId = entry.getWorkstreamId() != null
                    ? entry.getWorkstreamId() : entry.getChannelName();
            String model = entry.getModel();
            if (model != null && !model.isEmpty()
                    && !CodingAgentJob.VALID_MODELS.contains(model)) {
                warnings.add("Workstream '" + entryId + "' has invalid model '"
                        + model + "'; clearing (must be one of "
                        + CodingAgentJob.VALID_MODELS + ")");
                entry.setModel(null);
            }
            String effort = entry.getEffort();
            if (effort != null && !effort.isEmpty()
                    && !CodingAgentJob.VALID_EFFORT_LEVELS.contains(effort)) {
                warnings.add("Workstream '" + entryId + "' has invalid effort '"
                        + effort + "'; clearing (must be one of "
                        + CodingAgentJob.VALID_EFFORT_LEVELS + ")");
                entry.setEffort(null);
            }
        }
        return warnings;
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
        entry.setWorkspaceId(ws.getWorkspaceId());
        entry.setModel(ws.getModel());
        entry.setEffort(ws.getEffort());
        entry.setDefaultRunner(ws.getDefaultRunner());
        entry.setRunners(ws.getRunners());
        applyBundleToEntry(entry, ws.getPhaseConfigBundle());
        entry.setArchived(ws.isArchived());
        workstreams.add(entry);
    }

    /**
     * Copies the per-phase entries of {@code bundle} (model and effort,
     * specifically — runner values are already mirrored to the legacy
     * {@code runners} map) onto {@code entry}'s new {@code phaseConfigs}
     * field so they round-trip through YAML serialization.
     */
    private static void applyBundleToEntry(WorkstreamEntry entry, PhaseConfigBundle bundle) {
        if (bundle == null || bundle.phaseConfigs().isEmpty()) {
            entry.setPhaseConfigs(new LinkedHashMap<>());
            entry.setDefaultPhaseConfig(null);
            return;
        }
        Map<String, PhaseConfig> phaseConfigs = new LinkedHashMap<>();
        for (Map.Entry<Phase, PhaseConfig> e : bundle.phaseConfigs().entrySet()) {
            // Only emit per-phase entries that carry model or effort —
            // runner-only entries are already represented in the legacy
            // runners map.
            PhaseConfig pc = e.getValue();
            if (pc.model() != null || pc.effort() != null) {
                phaseConfigs.put(e.getKey().wireName(), pc);
            }
        }
        entry.setPhaseConfigs(phaseConfigs);
        // Default carries fields that are not represented elsewhere on the
        // entry; skip when redundant with model/effort/defaultRunner.
        PhaseConfig def = bundle.defaultPhaseConfig();
        if (def != null && !def.isEmpty()) {
            entry.setDefaultPhaseConfig(def);
        } else {
            entry.setDefaultPhaseConfig(null);
        }
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
                    entry.setWorkspaceId(ws.getWorkspaceId());
                    entry.setModel(ws.getModel());
                    entry.setEffort(ws.getEffort());
                    entry.setDefaultRunner(ws.getDefaultRunner());
                    entry.setRunners(ws.getRunners());
                    applyBundleToEntry(entry, ws.getPhaseConfigBundle());
                    entry.setArchived(ws.isArchived());
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
     * Merges legacy {@code defaultRunner} / {@code runners} fields with the
     * new {@code defaultPhaseConfig} / {@code phaseConfigs} fields into a
     * single {@link PhaseConfigBundle}. New fields take precedence
     * field-by-field — when both forms set the same field, the new form
     * wins; the legacy form fills in any field the new form leaves null.
     *
     * @param legacyDefaultRunner the legacy single-runner default, or {@code null}
     * @param legacyRunners       the legacy per-phase runner map, keyed by
     *                            phase wire name; may be {@code null}
     * @param newDefault          the new default {@link PhaseConfig}, or {@code null}
     * @param newPhaseConfigs     the new per-phase {@link PhaseConfig} map,
     *                            keyed by phase wire name; may be {@code null}
     * @return the merged bundle; never {@code null}
     */
    static PhaseConfigBundle mergeBundle(String legacyDefaultRunner,
                                         Map<String, String> legacyRunners,
                                         PhaseConfig newDefault,
                                         Map<String, PhaseConfig> newPhaseConfigs) {
        PhaseConfigBundle bundle = PhaseConfigBundle.fromLegacyRunners(
                legacyDefaultRunner, legacyRunners);
        if (newDefault != null && !newDefault.isEmpty()) {
            // New default overlays the legacy-derived default field-by-field
            // (new wins; legacy fills in missing fields).
            bundle = bundle.withDefault(
                    newDefault.overlayOn(bundle.defaultPhaseConfig()));
        }
        if (newPhaseConfigs != null && !newPhaseConfigs.isEmpty()) {
            for (Map.Entry<String, PhaseConfig> e : newPhaseConfigs.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                Phase phase;
                try {
                    phase = Phase.fromWireName(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                PhaseConfig existing = bundle.phaseConfigs().get(phase);
                PhaseConfig merged = existing == null
                        ? e.getValue()
                        : e.getValue().overlayOn(existing);
                bundle = bundle.withPhase(phase, merged);
            }
        }
        return bundle;
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
