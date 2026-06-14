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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * @author Michael Murray
 * @see WorkstreamConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceEntry {

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
     * same phase and no workstream-level {@code defaultRunner}. Legacy
     * field: accepted on load and auto-migrated into {@link #phaseConfigs},
     * but write-only for serialization.
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
