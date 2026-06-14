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

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Configuration for Slack workstreams, loadable from YAML or JSON files.
 *
 * <p>Agents connect inbound to the controller's FlowTree server, so the
 * {@code agents} field is optional and typically omitted.</p>
 *
 * <p>The configuration is organized into three tiers of entries:</p>
 * <ul>
 *   <li>{@link WorkspaceEntry} — operator's organizational unit, optionally
 *       connected to a Slack team. Holds Slack credentials, GitHub org tokens,
 *       and workspace-level phase config defaults.</li>
 *   <li>{@link WorkstreamEntry} — binds a git branch to a Slack channel and
 *       declares job parameters. One entry per active branch/project.</li>
 *   <li>{@link PushedToolEntry} / {@link McpServerEntry} — MCP tool serving
 *       configuration pushed to agent containers.</li>
 * </ul>
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
 * @see WorkspaceEntry
 * @see WorkstreamEntry
 */
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
     * Returns the global default workspace path for repo checkouts.
     *
     * <p>When a workstream specifies {@code repoUrl} but no
     * {@code workingDirectory}, the repo is cloned into this path.
     * If not set, defaults to {@code /workspace/project} (if it exists)
     * or a temporary directory under {@code /tmp}.</p>
     */
    public String getDefaultWorkspacePath() { return defaultWorkspacePath; }
    /** Sets the global default workspace path. */
    public void setDefaultWorkspacePath(String defaultWorkspacePath) { this.defaultWorkspacePath = defaultWorkspacePath; }

    /**
     * Returns the Slack user ID to invite to auto-created channels.
     *
     * <p>When set, newly created workstream channels will automatically
     * invite this user. This is typically the project owner's Slack user ID.</p>
     */
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
     */
    public String getDefaultChannel() { return defaultChannel; }
    /** Sets the global fallback Slack channel ID. */
    public void setDefaultChannel(String defaultChannel) { this.defaultChannel = defaultChannel; }

    /** Returns the centralized MCP server configurations. */
    public Map<String, McpServerEntry> getMcpServers() { return mcpServers; }
    /** Sets the centralized MCP server configurations. */
    public void setMcpServers(Map<String, McpServerEntry> mcpServers) { this.mcpServers = mcpServers; }

    /** Returns the pushed MCP tool configurations. */
    public Map<String, PushedToolEntry> getPushedTools() { return pushedTools; }
    /** Sets the pushed MCP tool configurations. */
    public void setPushedTools(Map<String, PushedToolEntry> pushedTools) { this.pushedTools = pushedTools; }

    /** Returns the per-organization GitHub token configurations. */
    public Map<String, GitHubOrgEntry> getGithubOrgs() { return githubOrgs; }
    /** Sets the per-organization GitHub token configurations. */
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
     * Only orgs declared inside a workspace's {@code githubOrgs} section
     * produce a mapping; globally-declared orgs are excluded.
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
     * {@code id} as a pre-existing entry are skipped.
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
     * that still iterate the legacy projection. Identical to
     * {@link #getWorkspaces()} for now; preserved so older callers compile.
     */
    @JsonIgnore
    public List<WorkspaceEntry> getSlackWorkspaces() { return workspaces; }

    /**
     * Accepts the legacy {@code slackWorkspaces:} YAML key and merges its
     * entries into the unified {@link #workspaces} list. Each legacy entry is
     * migrated on the fly so that its operator-chosen {@code id} doubles as
     * its {@code slackTeamId} — this preserves the historical invariant that
     * the workspace identifier IS the Slack team ID.
     *
     * <p>De-duplicates by {@code id} against any entries already present in
     * {@link #workspaces}.</p>
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
     * {@code null} when no entry has been configured with that ID.
     *
     * @param id the operator-chosen workspace ID; {@code null} or empty always
     *           returns {@code null}
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
     * Backward-compatible alias for {@link #findWorkspace(String)}.
     *
     * @param id the workspace ID
     * @return the matching entry, or {@code null}
     */
    public WorkspaceEntry findSlackWorkspace(String id) {
        return findWorkspace(id);
    }

    /**
     * Renames a workspace, updating every workstream that referenced the old
     * ID. The Slack-team-ID connection (when set) is preserved unchanged.
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
     * instances in addition to the persisted entries.
     *
     * @param oldId          current workspace ID; must match an existing entry
     * @param newId          new workspace ID; must not collide with another entry
     * @param liveWorkstreams live workstream objects to update in place; may
     *        be {@code null} when the caller manages no live state
     * @return {@code true} when the rename happened, {@code false} when the
     *         old ID was not found
     * @throws IllegalArgumentException when {@code newId} collides
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
            throw new IllegalArgumentException("Workspace ID '" + newId + "' is already taken");
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
     * in any workspace's {@code runners} map are rejected with a clear message.
     * Called automatically after every {@code loadFromYaml*} / {@code loadFromJson*}.
     *
     * @throws IOException when any workspace runner map references an unknown
     *                     phase wire name
     */
    public void validateWorkspaceRunners() throws IOException {
        if (workspaces == null) return;
        for (WorkspaceEntry entry : workspaces) {
            Map<String, String> entryRunners = entry.getRunners();
            if (entryRunners == null || entryRunners.isEmpty()) continue;
            String label = entry.getId() != null ? entry.getId() : entry.getName();
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
                            + "].runners; expected one of " + known);
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
     * fields still present in the loaded configuration.
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
     * @param ws the workstream to add
     */
    public void addWorkstream(Workstream ws) {
        WorkstreamEntry entry = new WorkstreamEntry();
        populateEntry(entry, ws);
        workstreams.add(entry);
    }

    /**
     * Copies every mutable field from a live {@link Workstream} onto the
     * supplied {@link WorkstreamEntry}. Shared by {@link #addWorkstream} and
     * {@link #syncFromWorkstreams} so the canonical field list is maintained
     * in exactly one place.
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
        entry.setGithubOrg(ws.getGithubOrg());
        entry.setDependentRepos(ws.getDependentRepos());
        entry.setRequiredLabels(ws.getRequiredLabels());
        entry.setWorkspaceId(ws.getWorkspaceId());
        entry.setDefaultRunner(ws.getDefaultRunner());
        entry.setRunners(ws.getRunners());
        applyBundleToEntry(entry, ws.getPhaseConfigBundle());
        entry.setArchived(ws.isArchived());
        entry.setCompletionListeners(ws.getCompletionListeners());
    }

    /**
     * Copies every non-empty per-phase entry of {@code bundle} onto
     * {@code entry}'s new {@code phaseConfigs} field so the full configuration
     * round-trips through YAML serialization.
     */
    private static void applyBundleToEntry(WorkstreamEntry entry, PhaseConfigBundle bundle) {
        applyBundleToFields(bundle, entry::setDefaultPhaseConfig, entry::setPhaseConfigs);
    }

    /**
     * Writes the contents of {@code bundle} into a container's
     * {@code defaultPhaseConfig} / {@code phaseConfigs} fields via the
     * supplied setters.
     *
     * @param bundle          the bundle to copy; {@code null} clears both fields
     * @param setDefault      receives the bundle's default, or {@code null}
     * @param setPhaseConfigs receives the per-phase map (never {@code null})
     */
    private static void applyBundleToFields(PhaseConfigBundle bundle,
                                            Consumer<PhaseConfig> setDefault,
                                            Consumer<Map<String, PhaseConfig>> setPhaseConfigs) {
        Map<String, PhaseConfig> phaseConfigs = new LinkedHashMap<>();
        if (bundle != null) {
            for (Map.Entry<Phase, PhaseConfig> e : bundle.phaseConfigs().entrySet()) {
                PhaseConfig pc = e.getValue();
                if (!pc.isEmpty()) {
                    phaseConfigs.put(e.getKey().wireName(), pc);
                }
            }
        }
        setPhaseConfigs.accept(phaseConfigs);
        PhaseConfig def = bundle != null ? bundle.defaultPhaseConfig() : null;
        setDefault.accept(def != null && !def.isEmpty() ? def : null);
    }

    /**
     * Synchronizes the configuration entries from the in-memory workstream state.
     *
     * <p>For each active workstream, locates the matching entry via
     * {@link #findEntryToSync} and updates its mutable fields; otherwise adds
     * a new entry via {@link #addWorkstream}.</p>
     *
     * @param activeWorkstreams the current in-memory workstreams
     */
    public void syncFromWorkstreams(Collection<Workstream> activeWorkstreams) {
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
     * Locates the {@link WorkstreamEntry} that should receive sync updates for
     * the given live {@link Workstream}. Matching by {@code workstreamId} is
     * preferred; {@code channelId} is a fallback for legacy entries.
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
     * @param file the target YAML file
     * @throws IOException if the file cannot be written
     */
    public void saveToYaml(File file) throws IOException {
        migrateLegacyConfigToPhaseConfig();
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.writeValue(file, this);
    }

    /**
     * Folds every container's legacy configuration fields into the new per-phase
     * shape and clears the legacy fields. Called by {@link #saveToYaml(File)}.
     * Idempotent: a container already in the new shape is left unchanged.
     */
    void migrateLegacyConfigToPhaseConfig() {
        for (WorkstreamEntry entry : workstreams) {
            applyBundleToFields(entry.toPhaseConfigBundle(),
                    entry::setDefaultPhaseConfig, entry::setPhaseConfigs);
            entry.setDefaultRunner(null);
            entry.setRunners(null);
        }
        if (workspaces != null) {
            for (WorkspaceEntry entry : workspaces) {
                applyBundleToFields(entry.toPhaseConfigBundle(),
                        entry::setDefaultPhaseConfig, entry::setPhaseConfigs);
                entry.setDefaultRunner(null);
                entry.setRunners(null);
            }
        }
    }
}
