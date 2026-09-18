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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.flowtree.submission.SubmissionRunnerResolver;

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
 *     allowedTools: "Read,Edit,Write,Bash,Glob,Grep,TaskOutput,TaskStop"
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
    /** Named alert recipients, from the top-level {@code alertRecipients:} key. */
    private List<AlertRecipientEntry> alertRecipients = new ArrayList<>();
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
         *
         * <p>Legacy field: still accepted on load and auto-migrated into
         * {@link #defaultPhaseConfig}, but never written back — serialization
         * is write-only so a save-then-load cycle drops it in favour of the
         * per-phase shape.</p>
         */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String defaultRunner;
        /**
         * Workspace-level per-phase runner overrides keyed by phase wire name
         * (e.g. {@code primary}, {@code commit-message}). Consulted by the
         * resolver only when the workstream has no per-phase entry for the
         * same phase <em>and</em> no workstream-level {@code defaultRunner};
         * see {@link SubmissionRunnerResolver} for the full ladder.
         *
         * <p>Legacy field: accepted on load and auto-migrated into
         * {@link #phaseConfigs}, but write-only for serialization.</p>
         */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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
            return PhaseConfigBundle.mergeLegacyWithNew(defaultRunner, runners,
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
     *
     * <p>The fields, accessors, and {@link WorkstreamEntryData#toWorkstream()}
     * conversion live on {@link WorkstreamEntryData}, extracted to keep this
     * file within the project's file-length limit. This subclass exists so
     * the type's fully-qualified name ({@code WorkstreamConfig.WorkstreamEntry})
     * and default constructor are unchanged for existing callers.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkstreamEntry extends WorkstreamEntryData {
    }

    /**
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
     * <p>Keys carry the casing the YAML used; since GitHub org names are
     * case-insensitive, resolve with {@code GitHubOrgs.lookup}, not a map get.</p>
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

    /** Returns the named alert recipients; never {@code null}. */
    public List<AlertRecipientEntry> getAlertRecipients() { return alertRecipients; }

    /** Sets the named alert recipients, treating {@code null} as empty. */
    public void setAlertRecipients(List<AlertRecipientEntry> alertRecipients) {
        this.alertRecipients = alertRecipients == null ? new ArrayList<>() : alertRecipients;
    }

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
        return renameWorkspace(oldId, newId, null);
    }

    /**
     * Renames a workspace and propagates the new ID to live {@link Workstream}
     * instances in addition to the persisted entries. Callers that hold live
     * {@code Workstream} objects (e.g. {@code SlackListener.channelToWorkstream})
     * MUST use this overload so that a subsequent
     * {@link #syncFromWorkstreams(Collection)} does not see a stale workspaceId
     * on the live object and revert the rename.
     *
     * @param oldId current workspace ID; must match an existing entry
     * @param newId new workspace ID; must not collide with another entry
     * @param liveWorkstreams live workstream objects to update in place; may
     *        be {@code null} when the caller manages no live state
     * @return {@code true} when the rename happened, {@code false} when the
     *         old ID was not found
     * @throws IllegalArgumentException when {@code newId} collides with an
     *         existing different workspace
     */
    public boolean renameWorkspace(String oldId, String newId,
                                   Collection<Workstream> liveWorkstreams) {
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
        if (liveWorkstreams != null) {
            for (Workstream ws : liveWorkstreams) {
                if (oldId.equals(ws.getWorkspaceId())) {
                    ws.setWorkspaceId(newId);
                }
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
     * Returns INFO-level deprecation messages for any legacy configuration
     * fields still present in the loaded configuration. The legacy runner
     * fields ({@code defaultRunner}, {@code runners})
     * are accepted on load and auto-migrated into the per-phase shape
     * ({@code defaultPhaseConfig} / {@code phaseConfigs}) by
     * {@link WorkstreamEntry#toPhaseConfigBundle()} and
     * {@link WorkspaceEntry#toPhaseConfigBundle()}, but are never written
     * back — a save-then-load cycle drops them. Operators should migrate
     * their YAML to the per-phase shape.
     *
     * @return one message per entry that still carries a legacy field; empty
     *         when the configuration is already fully migrated
     */
    public List<String> legacyConfigWarnings() {
        List<String> warnings = new ArrayList<>();
        for (WorkstreamEntry entry : workstreams) {
            List<String> fields = new ArrayList<>();
            if (entry.getDefaultRunner() != null && !entry.getDefaultRunner().isEmpty()) fields.add("defaultRunner");
            if (entry.getRunners() != null && !entry.getRunners().isEmpty()) fields.add("runners");
            if (!fields.isEmpty()) {
                String id = entry.getWorkstreamId() != null
                        ? entry.getWorkstreamId() : entry.getChannelName();
                warnings.add("Workstream '" + id + "' uses deprecated config field(s) " + fields
                        + "; auto-migrated to defaultPhaseConfig/phaseConfigs and dropped on next save."
                        + " Migrate the YAML to the per-phase shape to silence this notice.");
            }
        }
        if (workspaces != null) {
            for (WorkspaceEntry entry : workspaces) {
                List<String> fields = new ArrayList<>();
                if (entry.getDefaultRunner() != null && !entry.getDefaultRunner().isEmpty()) fields.add("defaultRunner");
                if (entry.getRunners() != null && !entry.getRunners().isEmpty()) fields.add("runners");
                if (!fields.isEmpty()) {
                    warnings.add("Workspace '" + entry.getId() + "' uses deprecated config field(s) " + fields
                            + "; auto-migrated to defaultPhaseConfig/phaseConfigs and dropped on next save.");
                }
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
     * <p>Idempotent by {@code workstreamId}: when an entry with the same
     * non-null ID already exists it is replaced in place rather than a
     * second entry being appended. This prevents the unbounded duplicate
     * accumulation that occurs when a workstream is registered, lost from
     * memory, and re-registered (each prior registration having appended
     * its own entry).</p>
     *
     * @param ws the workstream to add
     */
    public synchronized void addWorkstream(Workstream ws) {
        WorkstreamEntry entry = new WorkstreamEntry();
        populateEntry(entry, ws);
        String id = entry.getWorkstreamId();
        if (id != null) {
            for (int i = 0; i < workstreams.size(); i++) {
                if (id.equals(workstreams.get(i).getWorkstreamId())) {
                    workstreams.set(i, entry);
                    return;
                }
            }
        }
        workstreams.add(entry);
    }

    /**
     * Copies every mutable field from a live {@link Workstream} onto the
     * supplied {@link WorkstreamEntry}. Shared by {@link #addWorkstream} (which
     * creates a new entry) and {@link #syncFromWorkstreams} (which updates an
     * existing one) so the canonical field list is maintained in exactly one place.
     *
     * @param entry the entry to populate
     * @param ws    the live workstream whose current state to copy
     */
    private static void populateEntry(WorkstreamEntry entry, Workstream ws) {
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
        entry.setPlanInstructions(ws.getPlanInstructions());
        entry.setGithubOrg(ws.getGithubOrg());
        entry.setDependentRepos(ws.getDependentRepos());
        entry.setRequiredLabels(ws.getRequiredLabels());
        entry.setWorkspaceId(ws.getWorkspaceId());
        entry.setDefaultRunner(ws.getDefaultRunner());
        entry.setRunners(ws.getRunners());
        // Per-phase configuration round-trips through phaseConfigs alone: the
        // legacy runners map is write-only and no longer serialized, so a
        // per-phase runner not carried by the bundle would be lost on save.
        PhaseConfigBundle bundle = ws.getPhaseConfigBundle();
        (bundle == null ? PhaseConfigBundle.EMPTY : bundle)
                .applyTo(entry::setDefaultPhaseConfig, entry::setPhaseConfigs);
        entry.setArchived(ws.isArchived());
        entry.setCompletionListeners(ws.getCompletionListeners());
        entry.setDispatchCapable(ws.isDispatchCapable());
        entry.setUseTmux(ws.isUseTmux());
        entry.setMaxWallClockHours(ws.getMaxWallClockHours());
        entry.setDormantForCompletionListeners(ws.isDormantForCompletionListeners());
    }

    /**
     * Synchronizes the configuration entries from the in-memory workstream state.
     *
     * <p>For each active workstream, locates the matching entry via
     * {@link #findEntryToSync} (workstreamId first, channelId as fallback) and
     * updates its mutable fields; otherwise adds a new entry via
     * {@link #addWorkstream}. Matching by ID prevents the duplicate-entry
     * defect where a {@code /flowtree setup} call wrote an entry with a null
     * {@code channelId} and a later sync — after Slack assigned the channel —
     * could not find that entry by channel and appended a second entry with
     * the same workstreamId.</p>
     *
     * @param activeWorkstreams the current in-memory workstreams
     */
    public synchronized void syncFromWorkstreams(Collection<Workstream> activeWorkstreams) {
        for (Workstream ws : activeWorkstreams) {
            WorkstreamEntry entry = findEntryToSync(ws);
            if (entry == null) {
                addWorkstream(ws);
                continue;
            }
            populateEntry(entry, ws);
        }
    }

    /**
     * Synchronizes the in-memory workstream state into the configuration
     * entries and writes the result to {@code file} as a single atomic
     * critical section. Holding one lock across both steps prevents a
     * concurrent registration or a controller reload from interleaving
     * between the sync and the save — the interleaving that otherwise lets
     * a half-written file or a stale snapshot drop a just-registered
     * workstream.
     *
     * <p>Duplicate entries are collapsed <em>before</em> the sync, not just at
     * save time. This matters because {@link #findEntryToSync} updates the
     * first entry matching a {@code workstreamId} while the save-time dedupe
     * keeps the last: with duplicates still present, a field the sync writes
     * (for example an {@code archived} flag flipped by an archive request)
     * would land on the first entry and then be discarded when the last
     * survives. Collapsing first guarantees the sync updates the single entry
     * that is persisted.</p>
     *
     * @param activeWorkstreams the current in-memory workstreams
     * @param file              the target YAML file
     * @throws IOException if the file cannot be written
     */
    public synchronized void syncAndSave(Collection<Workstream> activeWorkstreams,
                                         File file) throws IOException {
        dedupeWorkstreamsKeepingLast();
        syncFromWorkstreams(activeWorkstreams);
        saveToYaml(file);
    }

    /**
     * Removes duplicate {@link WorkstreamEntry} instances that share a
     * {@code workstreamId}, keeping the last occurrence (the most recently
     * appended state). Entries with a {@code null} ID are left untouched.
     * Idempotent: a list with no duplicate IDs is unchanged.
     *
     * <p>Run before every save so a YAML file that has already accumulated
     * duplicate entries — from historic unsynchronized append-then-save
     * races — self-heals to a single entry per workstream on the next
     * write.</p>
     *
     * @return the number of duplicate entries removed
     */
    synchronized int dedupeWorkstreamsKeepingLast() {
        Set<String> seen = new HashSet<>();
        List<WorkstreamEntry> deduped = new ArrayList<>(workstreams.size());
        // Walk back-to-front so the LAST occurrence of each id survives,
        // then restore the original ordering of the survivors.
        for (int i = workstreams.size() - 1; i >= 0; i--) {
            WorkstreamEntry entry = workstreams.get(i);
            String id = entry.getWorkstreamId();
            if (id == null || seen.add(id)) {
                deduped.add(entry);
            }
        }
        Collections.reverse(deduped);
        int removed = workstreams.size() - deduped.size();
        if (removed > 0) {
            workstreams.clear();
            workstreams.addAll(deduped);
        }
        return removed;
    }

    /**
     * Locates the {@link WorkstreamEntry} that should receive sync updates for
     * the given live {@link Workstream}. Matching by {@code workstreamId} is
     * preferred; {@code channelId} is a fallback for legacy entries created
     * before IDs were universal.
     *
     * @param ws the active workstream being synced
     * @return the matching entry, or {@code null} when no entry exists yet
     */
    private WorkstreamEntry findEntryToSync(Workstream ws) {
        String id = ws.getWorkstreamId();
        if (id != null) {
            for (WorkstreamEntry e : workstreams) {
                if (id.equals(e.getWorkstreamId())) return e;
            }
        }
        String chan = ws.getChannelId();
        if (chan == null) return null;
        for (WorkstreamEntry e : workstreams) {
            if (chan.equals(e.getChannelId())) return e;
        }
        return null;
    }

    /**
     * Writes the configuration back to a YAML file.
     *
     * <p>Uses {@link JsonInclude.Include#NON_EMPTY} to omit null fields
     * and empty collections, keeping the output readable.</p>
     *
     * <p>The write is atomic: the YAML is serialized to a temporary file in
     * the same directory and then moved onto the target with
     * {@link StandardCopyOption#ATOMIC_MOVE}. A reader (such as a concurrent
     * controller reload) therefore always observes either the complete prior
     * file or the complete new one, never a half-written file. Duplicate
     * entries are collapsed first so a previously corrupted file self-heals.</p>
     *
     * @param file the target YAML file
     * @throws IOException if the file cannot be written
     */
    public synchronized void saveToYaml(File file) throws IOException {
        dedupeWorkstreamsKeepingLast();
        migrateLegacyConfigToPhaseConfig();
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

        Path target = file.toPath();
        Path directory = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(directory, file.getName() + ".", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), this);
            // Files.createTempFile makes the temp file 0600, and ATOMIC_MOVE
            // would impose that on the destination — silently narrowing a
            // previously world-readable config. Carry the destination's own
            // permissions onto the temp file first (or a sane default for a
            // brand-new file). No-op on non-POSIX filesystems.
            try {
                if (Files.exists(target)) {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
                }
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem; permissions are managed by the OS.
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystem does not support atomic moves; fall back to a
                // best-effort replace. Still far better than writing the
                // target in place because the serialization itself completed
                // against the temp file.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Folds every container's legacy configuration fields
     * ({@code model} / {@code effort} / {@code defaultRunner} /
     * {@code runners}) into the new per-phase shape
     * ({@code defaultPhaseConfig} / {@code phaseConfigs}) and clears the
     * legacy fields.
     *
     * <p>Called by {@link #saveToYaml(File)} so that persisted YAML only ever
     * contains the new shape: a save-then-load cycle migrates legacy YAML in
     * place without losing data. The fold reads {@link
     * WorkstreamEntry#toPhaseConfigBundle()} /
     * {@link WorkspaceEntry#toPhaseConfigBundle()}, which already merge the
     * legacy and new fields field-by-field (new wins), so any value present
     * in either form survives. Idempotent: a container already in the new
     * shape is left unchanged.</p>
     *
     * <p>This is the only migration trigger. Loading legacy YAML leaves the
     * legacy fields readable (so the controller keeps functioning across a
     * restart on an unmigrated file); the rewrite to the new shape happens
     * the next time the configuration is written back.</p>
     */
    void migrateLegacyConfigToPhaseConfig() {
        for (WorkstreamEntry entry : workstreams) {
            entry.toPhaseConfigBundle().applyTo(
                    entry::setDefaultPhaseConfig, entry::setPhaseConfigs);
            entry.setDefaultRunner(null);
            entry.setRunners(null);
        }
        if (workspaces != null) {
            for (WorkspaceEntry entry : workspaces) {
                entry.toPhaseConfigBundle().applyTo(
                        entry::setDefaultPhaseConfig, entry::setPhaseConfigs);
                entry.setDefaultRunner(null);
                entry.setRunners(null);
            }
        }
    }
}
