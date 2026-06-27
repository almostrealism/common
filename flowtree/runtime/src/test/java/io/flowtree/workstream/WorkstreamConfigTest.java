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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.slack.SlackTokens;

import static org.junit.Assert.*;

/**
 * Tests for {@link WorkstreamConfig} loading, defaults, persistence, and
 * {@link SlackTokens} token resolution.
 */
public class WorkstreamConfigTest extends TestSuiteBase {

    /**
     * Verifies that a multi-workstream YAML string loads all entries with the
     * correct channel IDs, agent lists, branches, turn limits, and budget values.
     */
    @Test(timeout = 10000)
    public void testYamlConfigLoading() throws IOException {
        String yaml = "workstreams:\n" +
                      "  - channelId: \"C0123456789\"\n" +
                      "    channelName: \"#project-alpha\"\n" +
                      "    agents:\n" +
                      "      - host: \"localhost\"\n" +
                      "        port: 7766\n" +
                      "      - host: \"localhost\"\n" +
                      "        port: 7767\n" +
                      "    defaultBranch: \"feature/alpha\"\n" +
                      "    pushToOrigin: true\n" +
                      "    maxTurns: 100\n" +
                      "    maxBudgetUsd: 25.0\n" +
                      "  - channelId: \"C9876543210\"\n" +
                      "    channelName: \"#project-beta\"\n" +
                      "    agents:\n" +
                      "      - host: \"192.168.1.100\"\n" +
                      "        port: 7768\n" +
                      "    defaultBranch: \"feature/beta\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        assertEquals(2, config.getWorkstreams().size());

        // First workstream
        WorkstreamConfig.WorkstreamEntry entry1 = config.getWorkstreams().get(0);
        assertEquals("C0123456789", entry1.getChannelId());
        assertEquals("#project-alpha", entry1.getChannelName());
        assertEquals(2, entry1.getAgents().size());
        assertEquals("localhost", entry1.getAgents().get(0).getHost());
        assertEquals(7766, entry1.getAgents().get(0).getPort());
        assertEquals("feature/alpha", entry1.getDefaultBranch());
        assertEquals(100, entry1.getMaxTurns());
        assertEquals(25.0, entry1.getMaxBudgetUsd(), 0.001);

        // Second workstream
        WorkstreamConfig.WorkstreamEntry entry2 = config.getWorkstreams().get(1);
        assertEquals("C9876543210", entry2.getChannelId());
        assertEquals("192.168.1.100", entry2.getAgents().get(0).getHost());

        // Convert to Workstream objects
        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals(2, workstreams.size());

        Workstream ws1 = workstreams.get(0);
        assertEquals("C0123456789", ws1.getChannelId());
        assertEquals("#project-alpha", ws1.getChannelName());
        assertEquals(2, ws1.getAgents().size());
        assertEquals("feature/alpha", ws1.getDefaultBranch());
    }

    /**
     * Verifies that a JSON-format config string is parsed into the correct
     * workstream entries.
     */
    @Test(timeout = 10000)
    public void testJsonConfigLoading() throws IOException {
        String json = "{\"workstreams\":[" +
                      "{\"channelId\":\"C111\",\"channelName\":\"#test\"," +
                      "\"agents\":[{\"host\":\"localhost\",\"port\":7766}]," +
                      "\"defaultBranch\":\"main\"}]}";

        WorkstreamConfig config = WorkstreamConfig.loadFromJsonString(json);

        assertEquals(1, config.getWorkstreams().size());
        assertEquals("C111", config.getWorkstreams().get(0).getChannelId());
        assertEquals("main", config.getWorkstreams().get(0).getDefaultBranch());
    }

    /**
     * Verifies that {@link SlackTokens#loadFromFile} reads bot and app tokens
     * correctly from a JSON file on disk.
     */
    @Test(timeout = 10000)
    public void testTokensLoadFromFile() throws IOException {
        File tempFile = File.createTempFile("slack-tokens-test", ".json");
        tempFile.deleteOnExit();

        String json = "{ \"botToken\": \"xoxb-test-bot-token\", " +
                       "\"appToken\": \"xapp-test-app-token\" }";
        Files.write(tempFile.toPath(), json.getBytes());

        SlackTokens tokens = SlackTokens.loadFromFile(tempFile);

        assertEquals("xoxb-test-bot-token", tokens.getBotToken());
        assertEquals("xapp-test-app-token", tokens.getAppToken());
    }

    /**
     * Verifies that {@link SlackTokens#resolve(File)} loads tokens from an
     * explicitly supplied file path.
     */
    @Test(timeout = 10000)
    public void testTokensResolveFromExplicitFile() throws IOException {
        File tempFile = File.createTempFile("slack-tokens-explicit", ".json");
        tempFile.deleteOnExit();

        String json = "{ \"botToken\": \"xoxb-explicit\", \"appToken\": \"xapp-explicit\" }";
        Files.write(tempFile.toPath(), json.getBytes());

        SlackTokens tokens = SlackTokens.resolve(tempFile);

        assertEquals("xoxb-explicit", tokens.getBotToken());
        assertEquals("xapp-explicit", tokens.getAppToken());
    }

    /**
     * Verifies that extra unknown fields in a Slack tokens JSON file are
     * silently ignored and do not prevent the known fields from loading.
     */
    @Test(timeout = 10000)
    public void testTokensIgnoresUnknownFields() throws IOException {
        File tempFile = File.createTempFile("slack-tokens-extra", ".json");
        tempFile.deleteOnExit();

        String json = "{ \"botToken\": \"xoxb-123\", \"appToken\": \"xapp-456\", " +
                       "\"unknownField\": \"ignored\" }";
        Files.write(tempFile.toPath(), json.getBytes());

        SlackTokens tokens = SlackTokens.loadFromFile(tempFile);

        assertEquals("xoxb-123", tokens.getBotToken());
        assertEquals("xapp-456", tokens.getAppToken());
    }

    /**
     * Verifies that a minimal YAML config with only required fields produces
     * correct default values for port, max turns, budget, push-to-origin, and
     * allowed tools.
     */
    @Test(timeout = 10000)
    public void testConfigDefaults() throws IOException {
        // Minimal config - should use defaults
        String yaml = "workstreams:\n" +
                      "  - channelId: \"C123\"\n" +
                      "    agents:\n" +
                      "      - host: \"localhost\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);

        // Check defaults
        assertEquals(7766, entry.getAgents().get(0).getPort()); // default port
        assertEquals(800, entry.getMaxTurns()); // default turns
        assertEquals(100.0, entry.getMaxBudgetUsd(), 0.001); // default budget
        assertTrue(entry.isPushToOrigin()); // default push
        assertEquals("Read,Edit,Write,Bash,Glob,Grep", entry.getAllowedTools()); // default tools
    }

    /**
     * Verifies that {@link WorkstreamConfig#addWorkstream} stores a
     * {@link Workstream} as a correctly populated entry with all fields
     * preserved.
     */
    @Test(timeout = 10000)
    public void testWorkstreamConfigAddWorkstream() {
        WorkstreamConfig config = new WorkstreamConfig();

        Workstream ws = new Workstream("C_ADD_1", "#add-channel");
        ws.setWorkingDirectory("/workspace/test");
        ws.setDefaultBranch("develop");
        ws.setMaxBudgetUsd(20.0);
        ws.setMaxTurns(75);

        config.addWorkstream(ws);

        assertEquals(1, config.getWorkstreams().size());
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertEquals("C_ADD_1", entry.getChannelId());
        assertEquals("#add-channel", entry.getChannelName());
        assertEquals("/workspace/test", entry.getWorkingDirectory());
        assertEquals("develop", entry.getDefaultBranch());
        assertEquals(20.0, entry.getMaxBudgetUsd(), 0.001);
        assertEquals(75, entry.getMaxTurns());
        assertEquals(ws.getWorkstreamId(), entry.getWorkstreamId());
    }

    /**
     * Verifies that {@link WorkstreamConfig#syncFromWorkstreams} propagates
     * in-memory changes (branch, budget, working directory) back into the
     * config entries.
     */
    @Test(timeout = 10000)
    public void testWorkstreamConfigSyncFromWorkstreams() throws IOException {
        // Start with a config that has one workstream
        String yaml = "workstreams:\n"
            + "  - channelId: \"C_SYNC\"\n"
            + "    channelName: \"#sync-channel\"\n"
            + "    defaultBranch: \"main\"\n"
            + "    maxBudgetUsd: 10.0\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        List<Workstream> wsList = config.toWorkstreams();
        assertEquals(1, wsList.size());

        // Modify the in-memory workstream
        Workstream ws = wsList.get(0);
        ws.setDefaultBranch("develop");
        ws.setMaxBudgetUsd(25.0);
        ws.setWorkingDirectory("/new/path");

        // Sync back
        config.syncFromWorkstreams(wsList);

        // Verify the entry was updated
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertEquals("develop", entry.getDefaultBranch());
        assertEquals(25.0, entry.getMaxBudgetUsd(), 0.001);
        assertEquals("/new/path", entry.getWorkingDirectory());
    }

    /**
     * Verifies that a config containing {@code slackWorkspaces} and a
     * {@code slackWorkspaceId}-linked workstream survives a save-and-reload
     * round-trip with all fields intact.
     */
    @Test(timeout = 10000)
    public void testMultiWorkspaceSaveAndReload() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    name: \"primary\"\n" +
                      "    botToken: \"xoxb-one\"\n" +
                      "    appToken: \"xapp-one\"\n" +
                      "workstreams:\n" +
                      "  - channelId: \"C0001\"\n" +
                      "    channelName: \"#alpha\"\n" +
                      "    slackWorkspaceId: \"T111\"\n" +
                      "    defaultBranch: \"main\"\n";

        WorkstreamConfig original = WorkstreamConfig.loadFromYamlString(yaml);

        // Save to a temp file and reload
        File tempFile = File.createTempFile("workstream-config-roundtrip", ".yaml");
        tempFile.deleteOnExit();
        original.saveToYaml(tempFile);

        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);

        // slackWorkspaces must survive the round-trip
        assertEquals(1, reloaded.getSlackWorkspaces().size());
        WorkstreamConfig.WorkspaceEntry ws = reloaded.getSlackWorkspaces().get(0);
        assertEquals("T111", ws.getId());
        // Legacy slackWorkspaces entries auto-migrate so slackTeamId mirrors
        // the original workspace ID — preserving channel routing after the
        // load + save round-trip.
        assertEquals("T111", ws.getSlackTeamId());
        assertEquals("primary", ws.getName());
        assertEquals("xoxb-one", ws.getBotToken());
        assertEquals("xapp-one", ws.getAppToken());

        // slackWorkspaceId on workstream entries must survive the round-trip
        assertEquals(1, reloaded.getWorkstreams().size());
        assertEquals("T111", reloaded.getWorkstreams().get(0).getWorkspaceId());
    }

    /**
     * Verifies that a YAML file in the post-migration format (produced by the
     * Python migration tool) loads correctly, including multi-workspace entries,
     * GitHub org tokens, and per-workstream workspace IDs.
     */
    @Test(timeout = 10000)
    public void testMigratedConfigParsesCorrectly() throws IOException {
        // Simulate the output of the Python migration tool and verify it loads
        String migratedYaml = "slackWorkspaces:\n" +
                              "- workspaceId: T111\n" +
                              "  name: primary\n" +
                              "  tokensFile: /config/slack-tokens.json\n" +
                              "  githubOrgs:\n" +
                              "    my-org:\n" +
                              "      token: ghp_token\n" +
                              "    other-org:\n" +
                              "      token: ghp_other\n" +
                              "workstreams:\n" +
                              "- channelId: C0000001\n" +
                              "  channelName: '#alpha'\n" +
                              "  defaultBranch: feature/alpha\n" +
                              "  githubOrg: my-org\n" +
                              "  slackWorkspaceId: T111\n" +
                              "- channelId: C0000002\n" +
                              "  channelName: '#beta'\n" +
                              "  defaultBranch: feature/beta\n" +
                              "  githubOrg: other-org\n" +
                              "  slackWorkspaceId: T111\n" +
                              "- channelId: C0000003\n" +
                              "  channelName: '#gamma'\n" +
                              "  defaultBranch: feature/gamma\n" +
                              "  slackWorkspaceId: T111\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(migratedYaml);

        // Verify the migrated structure loads correctly
        assertEquals(1, config.getSlackWorkspaces().size());
        WorkstreamConfig.WorkspaceEntry migratedWs = config.getSlackWorkspaces().get(0);
        assertEquals("T111", migratedWs.getId());
        // Legacy migration also populates slackTeamId from the YAML
        // workspaceId so existing Slack routing continues to work.
        assertEquals("T111", migratedWs.getSlackTeamId());
        assertEquals("primary", migratedWs.getName());
        assertEquals("/config/slack-tokens.json", migratedWs.getTokensFile());
        assertNotNull("githubOrgs should be parsed from migrated YAML", migratedWs.getGithubOrgs());
        assertEquals(2, migratedWs.getGithubOrgs().size());
        assertEquals(3, config.getWorkstreams().size());

        // All workstreams must have slackWorkspaceId
        for (WorkstreamConfig.WorkstreamEntry entry : config.getWorkstreams()) {
            assertEquals("T111", entry.getWorkspaceId());
        }

        // Convert to runtime workstreams and verify slackWorkspaceId propagates
        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals(3, workstreams.size());
        for (Workstream ws : workstreams) {
            assertEquals("T111", ws.getWorkspaceId());
        }
    }

    /**
     * Verifies that {@code effectiveChannelOwnerUserIds()} wraps the legacy
     * singular {@code channelOwnerUserId} field into a one-element list at
     * both the top-level config and workspace-entry level.
     */
    @Test(timeout = 10000)
    public void testEffectiveChannelOwnerUserIdsResolvesFromSingular() throws IOException {
        // Legacy config shape: only the singular channelOwnerUserId is set.
        // effectiveChannelOwnerUserIds() must treat it as a one-element list.
        WorkstreamConfig top = WorkstreamConfig.loadFromYamlString(
                "channelOwnerUserId: U0111\n");
        assertEquals(List.of("U0111"), top.effectiveChannelOwnerUserIds());

        String yaml = "slackWorkspaces:\n"
                + "- workspaceId: T1\n"
                + "  tokensFile: /t.json\n"
                + "  channelOwnerUserId: U0222\n";
        WorkstreamConfig cfg = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry ws = cfg.getSlackWorkspaces().get(0);
        assertEquals(List.of("U0222"), ws.effectiveChannelOwnerUserIds());
    }

    /**
     * Verifies that the plural {@code channelOwnerUserIds} list takes
     * precedence over the legacy singular field when both are set, at both the
     * top-level config and workspace-entry level.
     */
    @Test(timeout = 10000)
    public void testEffectiveChannelOwnerUserIdsResolvesFromPlural() throws IOException {
        // When both the plural list and the legacy singular are set, the
        // plural list wins — preserving forward-compat once configs migrate.
        String yaml = "channelOwnerUserId: U0111\n"
                + "channelOwnerUserIds:\n"
                + "- U0222\n"
                + "- U0333\n";
        WorkstreamConfig top = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals(List.of("U0222", "U0333"), top.effectiveChannelOwnerUserIds());

        String wsYaml = "slackWorkspaces:\n"
                + "- workspaceId: T1\n"
                + "  tokensFile: /t.json\n"
                + "  channelOwnerUserId: U0AAA\n"
                + "  channelOwnerUserIds:\n"
                + "  - U0BBB\n"
                + "  - U0CCC\n";
        WorkstreamConfig cfg = WorkstreamConfig.loadFromYamlString(wsYaml);
        WorkstreamConfig.WorkspaceEntry ws = cfg.getSlackWorkspaces().get(0);
        assertEquals(List.of("U0BBB", "U0CCC"), ws.effectiveChannelOwnerUserIds());
    }

    /**
     * Verifies that {@code effectiveChannelOwnerUserIds()} returns an empty
     * list when neither the singular nor the plural field is set.
     */
    @Test(timeout = 10000)
    public void testEffectiveChannelOwnerUserIdsEmptyWhenUnset() throws IOException {
        WorkstreamConfig cfg = WorkstreamConfig.loadFromYamlString("workstreams: []\n");
        assertTrue(cfg.effectiveChannelOwnerUserIds().isEmpty());
    }

    /**
     * Verifies that the legacy {@link SlackNotifier#setChannelOwnerUserId(String)}
     * setter is internally treated as a one-element list and that the plural
     * setter supersedes it.
     */
    @Test(timeout = 10000)
    public void testSlackNotifierPreservesBackwardCompatSetter() {
        // The legacy setChannelOwnerUserId(String) must still behave as a
        // one-element list internally and round-trip through the legacy
        // getter — existing callers (e.g. older plugins) continue to work.
        SlackNotifier n = new SlackNotifier(null);
        n.setChannelOwnerUserId("U0123");
        assertEquals("U0123", n.getChannelOwnerUserId());
        assertEquals(List.of("U0123"), n.getChannelOwnerUserIds());

        // The plural setter supersedes the legacy singular value.
        n.setChannelOwnerUserIds(List.of("U0A", "U0B"));
        assertEquals(List.of("U0A", "U0B"), n.getChannelOwnerUserIds());
        assertEquals("U0A", n.getChannelOwnerUserId());

        // Clearing via null or empty removes all invitees.
        n.setChannelOwnerUserIds(null);
        assertTrue(n.getChannelOwnerUserIds().isEmpty());
        assertNull(n.getChannelOwnerUserId());
    }

    // ── model/effort defaults on Workstream ───────────────────────────────────

    /**
     * Verifies that {@link Workstream#toSummaryJson()} includes an
     * {@code "archived":true} flag when the workstream is archived.
     */
    @Test(timeout = 10000)
    public void testWorkstreamSummaryJsonEmitsArchivedFlagWhenSet() {
        Workstream ws = new Workstream("C_A", "#archived");
        ws.setArchived(true);
        String json = ws.toSummaryJson();
        assertTrue("expected archived flag: " + json,
            json.contains("\"archived\":true"));
    }

    /**
     * Verifies that {@link Workstream#toSummaryJson()} omits the
     * {@code "archived"} field entirely when the workstream is not archived.
     */
    @Test(timeout = 10000)
    public void testWorkstreamSummaryJsonOmitsArchivedWhenFalse() {
        Workstream ws = new Workstream("C_A", "#live");
        String json = ws.toSummaryJson();
        assertFalse("unexpected archived field: " + json,
            json.contains("\"archived\""));
    }

    /**
     * Verifies that the {@code archived} flag on a workstream entry survives
     * YAML deserialization and is carried through to the runtime
     * {@link Workstream} object.
     */
    @Test(timeout = 10000)
    public void testYamlRoundTripsArchivedFlag() throws IOException {
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-arc\"\n"
            + "    channelId: \"C_ARC\"\n"
            + "    channelName: \"#arc\"\n"
            + "    defaultBranch: \"feature/done\"\n"
            + "    archived: true\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertTrue("entry must reflect archived=true", entry.isArchived());

        Workstream ws = entry.toWorkstream();
        assertTrue("workstream conversion must preserve archived", ws.isArchived());
    }

    /**
     * Verifies that {@link WorkstreamConfig#addWorkstream} preserves the
     * {@code archived} flag from the supplied {@link Workstream}.
     */
    @Test(timeout = 10000)
    public void testAddWorkstreamCarriesArchivedFlag() {
        WorkstreamConfig config = new WorkstreamConfig();
        Workstream ws = new Workstream("C_ARC", "#arc");
        ws.setArchived(true);
        config.addWorkstream(ws);
        assertTrue("addWorkstream must persist archived",
            config.getWorkstreams().get(0).isArchived());
    }

    // ── legacy-runner clean-break persistence in WorkstreamConfig ─────────────

    /**
     * YAML written by the controller after a save must contain ONLY the new
     * per-phase shape ({@code defaultPhaseConfig} / {@code phaseConfigs}); the
     * legacy {@code defaultRunner} / {@code runners} keys must be absent from
     * the serialized output even when they were present on load. This is the
     * clean break: configs that get touched are rewritten to the new shape.
     */
    @Test(timeout = 10000)
    public void testSaveEmitsOnlyNewShapeNotLegacyFields() throws IOException {
        String yaml = "workstreams:\n"
            + "  - channelId: \"C-legacy\"\n"
            + "    defaultBranch: \"feature/legacy\"\n"
            + "    defaultRunner: \"opencode\"\n"
            + "    runners:\n"
            + "      review: \"claude\"\n"
            + "slackWorkspaces:\n"
            + "  - workspaceId: \"T-LEGACY\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n"
            + "    defaultRunner: \"opencode\"\n"
            + "    runners:\n"
            + "      primary: \"claude\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        File tempFile = File.createTempFile("workstream-config-clean-break", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);

        // Raw-text check: the unambiguous legacy entry-level keys must be
        // absent. (`runner:` also appears nested inside the new
        // defaultPhaseConfig / phaseConfigs objects, so its absence at entry
        // level is verified below via the reloaded legacy getters.)
        String written = new String(Files.readAllBytes(tempFile.toPath()));
        assertFalse("saved YAML must not contain legacy 'defaultRunner:'",
                written.contains("defaultRunner:"));
        assertFalse("saved YAML must not contain legacy 'runners:'", written.contains("runners:"));
        assertTrue("saved YAML must contain new defaultPhaseConfig",
                written.contains("defaultPhaseConfig"));
        assertTrue("saved YAML must contain new phaseConfigs", written.contains("phaseConfigs"));

        // Reloaded legacy getters being empty proves no legacy entry-level
        // key was written (a legacy key in the output would repopulate them).
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkstreamEntry wsEntry = reloaded.getWorkstreams().get(0);
        assertNull(wsEntry.getDefaultRunner());
        assertTrue(wsEntry.getRunners().isEmpty());
        WorkstreamConfig.WorkspaceEntry wspEntry = reloaded.findSlackWorkspace("T-LEGACY");
        assertNull(wspEntry.getDefaultRunner());
        assertTrue(wspEntry.getRunners().isEmpty());

        // The runner configuration is preserved through the migration.
        PhaseConfigBundle wsBundle = wsEntry.toPhaseConfigBundle();
        assertEquals("opencode", wsBundle.defaultPhaseConfig().runner());
        assertEquals("claude", wsBundle.forPhase(Phase.REVIEW).runner());
        PhaseConfigBundle wspBundle = wspEntry.toPhaseConfigBundle();
        assertEquals("opencode", wspBundle.defaultPhaseConfig().runner());
        assertEquals("claude", wspBundle.forPhase(Phase.PRIMARY).runner());
    }

    /**
     * Workstream YAML must accept ``defaultRunner`` and a per-phase
     * ``runners`` map and round-trip both through {@link Workstream}.
     */
    @Test(timeout = 10000)
    public void testYamlRunnerConfigurationRoundTrips() throws IOException {
        String yaml = "workstreams:\n"
            + "  - channelId: \"C-runners\"\n"
            + "    defaultBranch: \"feature/with-runners\"\n"
            + "    defaultRunner: \"opencode\"\n"
            + "    runners:\n"
            + "      primary: \"claude\"\n"
            + "      deduplication: \"opencode\"\n"
            + "      commit-message: \"claude\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertEquals("opencode", entry.getDefaultRunner());
        assertEquals("claude", entry.getRunners().get("primary"));
        assertEquals("opencode", entry.getRunners().get("deduplication"));
        assertEquals("claude", entry.getRunners().get("commit-message"));

        // Convert to runtime Workstream — values propagate.
        Workstream ws = config.toWorkstreams().get(0);
        assertEquals("opencode", ws.getDefaultRunner());
        assertEquals("opencode", ws.getRunners().get("deduplication"));

        // Save and reload — legacy runner fields are dropped on save (clean
        // break) but the runner configuration is migrated into the per-phase
        // shape, so it survives the round-trip in the new form.
        File tempFile = File.createTempFile("workstream-config-runners", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkstreamEntry rEntry = reloaded.getWorkstreams().get(0);
        assertNull("legacy defaultRunner must not survive a save", rEntry.getDefaultRunner());
        assertTrue("legacy runners map must not survive a save", rEntry.getRunners().isEmpty());
        PhaseConfigBundle rBundle = rEntry.toPhaseConfigBundle();
        assertEquals("opencode", rBundle.defaultPhaseConfig().runner());
        assertEquals("claude", rBundle.forPhase(Phase.PRIMARY).runner());
        assertEquals("opencode", rBundle.forPhase(Phase.DEDUPLICATION).runner());
    }

    /**
     * Workstream YAML without a ``runners`` section continues to load —
     * default runner is null and the per-phase map is empty.
     */
    @Test(timeout = 10000)
    public void testYamlWithoutRunnersConfigurationLoadsCleanly() throws IOException {
        String yaml = "workstreams:\n"
            + "  - channelId: \"C-no-runners\"\n"
            + "    defaultBranch: \"feature/no-runners\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertNull(entry.getDefaultRunner());
        assertTrue("runners map must default to empty",
                entry.getRunners().isEmpty());

        Workstream ws = config.toWorkstreams().get(0);
        assertNull(ws.getDefaultRunner());
        assertTrue(ws.getRunners().isEmpty());
    }

    /**
     * A workspace entry with both {@code defaultRunner} and {@code runners}
     * set round-trips identically through YAML, both fields populated on
     * reload.
     */
    @Test(timeout = 10000)
    public void testYamlWorkspaceRunnerConfigurationRoundTrips() throws IOException {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-RUNNERS\"\n"
            + "    name: \"team-runners\"\n"
            + "    botToken: \"xoxb-test\"\n"
            + "    appToken: \"xapp-test\"\n"
            + "    defaultRunner: \"opencode\"\n"
            + "    runners:\n"
            + "      primary: \"claude\"\n"
            + "      commit-message: \"opencode\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry entry =
                config.findSlackWorkspace("T-RUNNERS");
        assertNotNull(entry);
        assertEquals("opencode", entry.getDefaultRunner());
        assertEquals("claude", entry.getRunners().get("primary"));
        assertEquals("opencode", entry.getRunners().get("commit-message"));

        File tempFile = File.createTempFile("workspace-runners", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkspaceEntry rEntry =
                reloaded.findSlackWorkspace("T-RUNNERS");
        assertNotNull(rEntry);
        // Legacy runner fields are dropped on save; the configuration is
        // migrated into the per-phase shape and survives in the new form.
        assertNull("legacy defaultRunner must not survive a save", rEntry.getDefaultRunner());
        assertTrue("legacy runners map must not survive a save", rEntry.getRunners().isEmpty());
        PhaseConfigBundle rBundle = rEntry.toPhaseConfigBundle();
        assertEquals("opencode", rBundle.defaultPhaseConfig().runner());
        assertEquals("claude", rBundle.forPhase(Phase.PRIMARY).runner());
        assertEquals("opencode", rBundle.forPhase(Phase.COMMIT_MESSAGE).runner());
    }

    /**
     * A workspace entry with only {@code defaultRunner} set (no per-phase
     * map) round-trips: defaultRunner survives, runners map remains empty.
     */
    @Test(timeout = 10000)
    public void testYamlWorkspaceWithDefaultRunnerOnly() throws IOException {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-DEF\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n"
            + "    defaultRunner: \"opencode\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry entry =
                config.findSlackWorkspace("T-DEF");
        assertNotNull(entry);
        assertEquals("opencode", entry.getDefaultRunner());
        assertTrue("runners must default to empty when omitted",
                entry.getRunners().isEmpty());

        File tempFile = File.createTempFile("workspace-default-only", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkspaceEntry rEntry =
                reloaded.findSlackWorkspace("T-DEF");
        assertNotNull(rEntry);
        // The legacy defaultRunner is dropped on save but migrated into the
        // per-phase default, which carries it across the round-trip.
        assertNull("legacy defaultRunner must not survive a save", rEntry.getDefaultRunner());
        assertTrue(rEntry.getRunners().isEmpty());
        assertEquals("opencode", rEntry.toPhaseConfigBundle().defaultPhaseConfig().runner());
    }

    /**
     * A workspace entry with neither workspace-level field set still
     * round-trips — neither field appears in the reloaded YAML and the
     * reloaded entry's getters return the unset values.
     */
    @Test(timeout = 10000)
    public void testYamlWorkspaceWithoutRunnerFields() throws IOException {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-PLAIN\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry entry =
                config.findSlackWorkspace("T-PLAIN");
        assertNotNull(entry);
        assertNull(entry.getDefaultRunner());
        assertTrue(entry.getRunners().isEmpty());

        File tempFile = File.createTempFile("workspace-no-runner", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkspaceEntry rEntry =
                reloaded.findSlackWorkspace("T-PLAIN");
        assertNotNull(rEntry);
        assertNull(rEntry.getDefaultRunner());
        assertTrue(rEntry.getRunners().isEmpty());
    }

    /**
     * Workspace {@code defaultPhaseConfig} and {@code phaseConfigs} fields
     * survive a YAML save / reload cycle. This is the persistence check
     * the gap-fix sessions did not perform — in-memory mocks would pass
     * even if Jackson silently dropped the new fields on serialisation.
     */
    @Test(timeout = 10000)
    public void testYamlWorkspacePhaseConfigsRoundTrip() throws IOException {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-PC\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n"
            + "    defaultPhaseConfig:\n"
            + "      runner: \"claude\"\n"
            + "      model: \"claude-opus-4-7\"\n"
            + "      effort: \"high\"\n"
            + "    phaseConfigs:\n"
            + "      review:\n"
            + "        runner: \"claude\"\n"
            + "        model: \"claude-haiku-4-5-20251001\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry entry =
                config.findSlackWorkspace("T-PC");
        assertNotNull(entry);
        assertNotNull(entry.getDefaultPhaseConfig());
        assertEquals("claude", entry.getDefaultPhaseConfig().runner());
        assertEquals("claude-opus-4-7", entry.getDefaultPhaseConfig().model());
        assertEquals("high", entry.getDefaultPhaseConfig().effort());
        assertEquals("claude-haiku-4-5-20251001",
                entry.getPhaseConfigs().get("review").model());

        File tempFile = File.createTempFile("workspace-phase-configs", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkspaceEntry rEntry =
                reloaded.findSlackWorkspace("T-PC");
        assertNotNull(rEntry);
        assertNotNull("defaultPhaseConfig must survive round-trip",
                rEntry.getDefaultPhaseConfig());
        assertEquals("claude", rEntry.getDefaultPhaseConfig().runner());
        assertEquals("claude-opus-4-7", rEntry.getDefaultPhaseConfig().model());
        assertEquals("high", rEntry.getDefaultPhaseConfig().effort());
        assertNotNull("phaseConfigs[review] must survive round-trip",
                rEntry.getPhaseConfigs().get("review"));
        assertEquals("claude-haiku-4-5-20251001",
                rEntry.getPhaseConfigs().get("review").model());
    }

    /**
     * Workstream-level {@code defaultPhaseConfig} and {@code phaseConfigs}
     * fields survive a YAML save / reload cycle.
     */
    @Test(timeout = 10000)
    public void testYamlWorkstreamPhaseConfigsRoundTrip() throws IOException {
        String yaml = "workstreams:\n"
            + "  - channelId: \"C-PC\"\n"
            + "    defaultBranch: \"main\"\n"
            + "    defaultPhaseConfig:\n"
            + "      runner: \"claude\"\n"
            + "      model: \"claude-opus-4-7\"\n"
            + "      effort: \"high\"\n"
            + "    phaseConfigs:\n"
            + "      review:\n"
            + "        runner: \"claude\"\n"
            + "        model: \"claude-haiku-4-5-20251001\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
        assertNotNull(entry.getDefaultPhaseConfig());
        assertEquals("claude-opus-4-7", entry.getDefaultPhaseConfig().model());
        assertEquals("claude-haiku-4-5-20251001",
                entry.getPhaseConfigs().get("review").model());

        File tempFile = File.createTempFile("workstream-phase-configs", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkstreamEntry rEntry =
                reloaded.getWorkstreams().get(0);
        assertNotNull("defaultPhaseConfig must survive round-trip",
                rEntry.getDefaultPhaseConfig());
        assertEquals("claude-opus-4-7", rEntry.getDefaultPhaseConfig().model());
        assertNotNull("phaseConfigs[review] must survive round-trip",
                rEntry.getPhaseConfigs().get("review"));
        assertEquals("claude-haiku-4-5-20251001",
                rEntry.getPhaseConfigs().get("review").model());
    }

    /**
     * Unknown phase keys in a workspace's {@code runners} map fail at load
     * time with a clear error naming the offending workspace.
     */
    @Test(timeout = 10000)
    public void testYamlWorkspaceUnknownPhaseFailsAtLoad() {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-BAD\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n"
            + "    runners:\n"
            + "      not-a-phase: \"claude\"\n";

        try {
            WorkstreamConfig.loadFromYamlString(yaml);
            fail("Expected IOException for unknown phase in workspace runners");
        } catch (IOException ex) {
            assertTrue("error must mention the bad phase key: " + ex.getMessage(),
                    ex.getMessage().contains("not-a-phase"));
            assertTrue("error must mention the offending workspace: "
                            + ex.getMessage(),
                    ex.getMessage().contains("T-BAD"));
        }
    }

    /**
     * {@link WorkstreamConfig#findSlackWorkspace(String)} returns
     * {@code null} for unknown IDs, empty strings, and {@code null}.
     */
    @Test(timeout = 10000)
    public void testFindSlackWorkspaceMissesReturnNull() throws IOException {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-KNOWN\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertNotNull(config.findSlackWorkspace("T-KNOWN"));
        assertNull(config.findSlackWorkspace("T-OTHER"));
        assertNull(config.findSlackWorkspace(""));
        assertNull(config.findSlackWorkspace(null));
    }

    // ------------------------------------------------------------------
    // Workspace/Slack decoupling: new YAML shape + auto-migration tests
    // ------------------------------------------------------------------

    /**
     * The new {@code workspaces:} top-level key loads with an operator-chosen
     * {@code id} and an explicit, distinct {@code slackTeamId}.
     */
    @Test(timeout = 10000)
    public void testNewWorkspacesYamlShape() throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"almostrealism\"\n"
            + "    slackTeamId: \"T0123456789\"\n"
            + "    name: \"Almost Realism\"\n"
            + "    botToken: \"xoxb\"\n"
            + "    appToken: \"xapp\"\n"
            + "workstreams:\n"
            + "  - channelId: \"C001\"\n"
            + "    channelName: \"#alpha\"\n"
            + "    workspaceId: \"almostrealism\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals(1, config.getWorkspaces().size());
        WorkstreamConfig.WorkspaceEntry ws = config.getWorkspaces().get(0);
        assertEquals("almostrealism", ws.getId());
        assertEquals("T0123456789", ws.getSlackTeamId());
        assertEquals(1, config.getWorkstreams().size());
        assertEquals("almostrealism",
                config.getWorkstreams().get(0).getWorkspaceId());
    }

    /**
     * A workspace declared under the new {@code workspaces:} key without a
     * {@code slackTeamId} has no Slack integration. It still resolves
     * normally for lookups.
     */
    @Test(timeout = 10000)
    public void testNewWorkspacesYamlWithoutSlackTeamId() throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"local-only\"\n"
            + "    name: \"Local Dev\"\n"
            + "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry ws =
                config.findWorkspace("local-only");
        assertNotNull(ws);
        assertNull("slackTeamId must be absent when no Slack integration",
                ws.getSlackTeamId());
    }

    /**
     * When both {@code slackWorkspaces:} (legacy) and {@code workspaces:}
     * (new) are present, entries from both keys merge into the unified
     * workspace list.
     */
    @Test(timeout = 10000)
    public void testMixedLegacyAndNewWorkspacesYaml() throws IOException {
        String yaml = "slackWorkspaces:\n"
            + "  - workspaceId: \"T-LEGACY\"\n"
            + "    botToken: \"xoxb-legacy\"\n"
            + "    appToken: \"xapp-legacy\"\n"
            + "workspaces:\n"
            + "  - id: \"new-style\"\n"
            + "    name: \"New Style\"\n"
            + "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals(2, config.getWorkspaces().size());
        assertNotNull(config.findWorkspace("T-LEGACY"));
        assertNotNull(config.findWorkspace("new-style"));
        // Legacy entries auto-migrate slackTeamId from id.
        assertEquals("T-LEGACY",
                config.findWorkspace("T-LEGACY").getSlackTeamId());
        // New-style entries without slackTeamId stay absent.
        assertNull(config.findWorkspace("new-style").getSlackTeamId());
    }

    /**
     * A workstream's {@code slackWorkspaceId} legacy field is accepted as a
     * deserialization alias for {@code workspaceId} so existing configs
     * continue to load unchanged.
     */
    @Test(timeout = 10000)
    public void testWorkstreamSlackWorkspaceIdAliasOnLoad() throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"ws-renamed\"\n"
            + "workstreams:\n"
            + "  - channelId: \"C002\"\n"
            + "    channelName: \"#beta\"\n"
            + "    slackWorkspaceId: \"ws-renamed\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals(1, config.getWorkstreams().size());
        assertEquals("ws-renamed",
                config.getWorkstreams().get(0).getWorkspaceId());
    }

    /**
     * Renaming a workspace via {@link WorkstreamConfig#renameWorkspace}
     * updates every workstream that referenced the old ID.
     */
    @Test(timeout = 10000)
    public void testRenameWorkspaceUpdatesReferencingWorkstreams()
            throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"T0123456789\"\n"
            + "    slackTeamId: \"T0123456789\"\n"
            + "workstreams:\n"
            + "  - channelId: \"C001\"\n"
            + "    channelName: \"#alpha\"\n"
            + "    workspaceId: \"T0123456789\"\n"
            + "    defaultBranch: \"main\"\n"
            + "  - channelId: \"C002\"\n"
            + "    channelName: \"#beta\"\n"
            + "    workspaceId: \"T0123456789\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertTrue(config.renameWorkspace("T0123456789", "almostrealism"));
        assertNotNull(config.findWorkspace("almostrealism"));
        assertNull(config.findWorkspace("T0123456789"));
        // Slack team binding is preserved across the rename.
        assertEquals("T0123456789",
                config.findWorkspace("almostrealism").getSlackTeamId());
        for (WorkstreamConfig.WorkstreamEntry entry : config.getWorkstreams()) {
            assertEquals("almostrealism", entry.getWorkspaceId());
        }
    }

    /**
     * Regression test for the lost-provider defect: a workspace-level
     * {@code phaseConfigs.primary.provider} entry must survive YAML
     * deserialization into the {@link WorkstreamConfig.WorkspaceEntry}
     * and emerge from {@link WorkstreamConfig.WorkspaceEntry#toPhaseConfigBundle()}
     * in the per-phase entry where the resolver can read it. Without this,
     * agents fall back to the runner's default provider (e.g. opencode →
     * "local"), bypassing the configured openrouter/anthropic route.
     */
    @Test(timeout = 10000)
    public void testWorkspacePrimaryProviderSurvivesYamlLoad() throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"almostrealism\"\n"
            + "    slackTeamId: \"T0123456789\"\n"
            + "    defaultPhaseConfig:\n"
            + "      runner: \"claude\"\n"
            + "      model: \"sonnet\"\n"
            + "      effort: \"medium\"\n"
            + "    phaseConfigs:\n"
            + "      primary:\n"
            + "        runner: \"opencode\"\n"
            + "        model: \"qwen/qwen3-coder:exacto\"\n"
            + "        provider: \"openrouter\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        WorkstreamConfig.WorkspaceEntry ws = config.findWorkspace("almostrealism");
        assertNotNull(ws);

        PhaseConfigBundle bundle = ws.toPhaseConfigBundle();
        PhaseConfig primary = bundle.forPhase(Phase.PRIMARY);
        assertEquals("opencode", primary.runner());
        assertEquals("qwen/qwen3-coder:exacto", primary.model());
        assertEquals("openrouter", primary.provider());
    }

    /**
     * Regression test for the duplicate-entry defect:
     * {@link WorkstreamConfig#syncFromWorkstreams} used to match entries by
     * channelId only, so a workstream first persisted with a null channelId
     * (e.g. by /flowtree setup before Slack returned the channel) and later
     * synced after the channelId was assigned could not be located by channel
     * and produced a second entry sharing the same workstreamId.
     */
    @Test(timeout = 10000)
    public void testSyncDoesNotDuplicateWhenChannelIdAppearsLate()
            throws IOException {
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-1\"\n"
            + "    channelName: \"#late-channel\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertNull(config.getWorkstreams().get(0).getChannelId());

        // Simulate the live Workstream getting its channelId assigned after
        // Slack created the channel.
        Workstream live = config.toWorkstreams().get(0);
        live.setChannelId("C0LATEONE");

        config.syncFromWorkstreams(Collections.singletonList(live));

        assertEquals("expected exactly one entry per workstreamId",
                1, config.getWorkstreams().size());
        assertEquals("C0LATEONE",
                config.getWorkstreams().get(0).getChannelId());
    }

    /**
     * {@link WorkstreamConfig#addWorkstream} must be idempotent by
     * {@code workstreamId}: re-adding a workstream with an ID that already
     * has an entry replaces that entry in place rather than appending a
     * second one. This is the guard against the unbounded duplicate
     * accumulation that bloated the persisted YAML when a workstream was
     * registered, lost from memory, and re-registered.
     */
    @Test(timeout = 10000)
    public void testAddWorkstreamIsIdempotentByWorkstreamId() throws IOException {
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-dup\"\n"
            + "    channelId: \"C100\"\n"
            + "    channelName: \"#dup\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals(1, config.getWorkstreams().size());

        Workstream live = config.toWorkstreams().get(0);
        live.setDefaultBranch("feature/updated");
        config.addWorkstream(live);

        assertEquals("addWorkstream must not append a duplicate workstreamId",
                1, config.getWorkstreams().size());
        assertEquals("feature/updated", config.getWorkstreams().get(0).getDefaultBranch());
    }

    /**
     * Saving must collapse duplicate entries that share a
     * {@code workstreamId}, keeping the last occurrence, so a YAML file that
     * has already accumulated duplicates from historic unsynchronized
     * append-then-save races self-heals to one entry per workstream on the
     * next write.
     */
    @Test(timeout = 10000)
    public void testSaveCollapsesDuplicateWorkstreamIdEntries() throws IOException {
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-x\"\n"
            + "    channelId: \"C200\"\n"
            + "    channelName: \"#x-old\"\n"
            + "    defaultBranch: \"main\"\n"
            + "  - workstreamId: \"ws-y\"\n"
            + "    channelId: \"C201\"\n"
            + "    channelName: \"#y\"\n"
            + "    defaultBranch: \"main\"\n"
            + "  - workstreamId: \"ws-x\"\n"
            + "    channelId: \"C200\"\n"
            + "    channelName: \"#x-new\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals("fixture should load all three raw entries",
                3, config.getWorkstreams().size());

        File tempFile = File.createTempFile("workstreams-dedupe", ".yaml");
        tempFile.deleteOnExit();
        config.saveToYaml(tempFile);

        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        assertEquals("save must collapse duplicate workstreamId entries",
                2, reloaded.getWorkstreams().size());

        boolean foundNew = false;
        boolean foundOld = false;
        for (WorkstreamConfig.WorkstreamEntry entry : reloaded.getWorkstreams()) {
            if ("#x-new".equals(entry.getChannelName())) foundNew = true;
            if ("#x-old".equals(entry.getChannelName())) foundOld = true;
        }
        assertTrue("the last duplicate must win", foundNew);
        assertFalse("the stale duplicate must be removed", foundOld);
    }

    /**
     * The atomic write must leave no temporary artifacts behind: the YAML is
     * serialized to a sibling temp file and moved onto the target, and the
     * temp file is always cleaned up. A leftover {@code .tmp} would indicate
     * the move failed silently.
     */
    @Test(timeout = 10000)
    public void testSaveToYamlLeavesNoTempArtifacts() throws IOException {
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-z\"\n"
            + "    channelId: \"C300\"\n"
            + "    channelName: \"#z\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        File dir = Files.createTempDirectory("ws-atomic").toFile();
        dir.deleteOnExit();
        File target = new File(dir, "workstreams.yaml");
        config.saveToYaml(target);

        assertTrue("target file must exist after save", target.exists());
        File[] leftovers = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        assertNotNull(leftovers);
        assertEquals("atomic save must not leave .tmp files behind",
                0, leftovers.length);

        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(target);
        assertEquals(1, reloaded.getWorkstreams().size());
        assertEquals("#z", reloaded.getWorkstreams().get(0).getChannelName());
    }

    /**
     * Regression test for the archive-flag-loss defect: when a workstream
     * still has duplicate YAML entries, {@link WorkstreamConfig#syncAndSave}
     * must persist a flag flipped on the live workstream (here {@code
     * archived}) so it survives a reload. The defect was that the sync
     * updated the first matching entry while the save-time dedupe kept the
     * last, silently dropping the flag and resurrecting the workstream on
     * restart. {@code syncAndSave} now collapses duplicates before syncing.
     */
    @Test(timeout = 10000)
    public void testSyncAndSavePersistsArchivedFlagDespiteDuplicates() throws IOException {
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-arch\"\n"
            + "    channelId: \"C400\"\n"
            + "    channelName: \"#arch\"\n"
            + "    defaultBranch: \"main\"\n"
            + "    archived: false\n"
            + "  - workstreamId: \"ws-arch\"\n"
            + "    channelId: \"C400\"\n"
            + "    channelName: \"#arch\"\n"
            + "    defaultBranch: \"main\"\n"
            + "    archived: false\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals("fixture should load both duplicate entries",
                2, config.getWorkstreams().size());

        // Archive the live workstream and persist through the combined path
        // the controller's archive endpoint uses.
        Workstream live = config.toWorkstreams().get(0);
        live.setArchived(true);

        File tempFile = File.createTempFile("workstreams-archive", ".yaml");
        tempFile.deleteOnExit();
        config.syncAndSave(Collections.singletonList(live), tempFile);

        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        assertEquals("duplicates must collapse to a single entry",
                1, reloaded.getWorkstreams().size());
        assertTrue("archived flag must survive persistence despite duplicates",
                reloaded.getWorkstreams().get(0).isArchived());
    }

    /**
     * The atomic write must preserve the destination file's permissions.
     * {@link Files#createTempFile} creates the staging file as {@code 0600};
     * without restoring the target's permissions, {@code ATOMIC_MOVE} would
     * silently narrow a world-readable config to owner-only.
     */
    @Test(timeout = 10000)
    public void testSaveToYamlPreservesFilePermissions() throws IOException {
        Assume.assumeTrue("POSIX-only test", FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix"));

        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-perm\"\n"
            + "    channelId: \"C500\"\n"
            + "    channelName: \"#perm\"\n"
            + "    defaultBranch: \"main\"\n";
        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        File dir = Files.createTempDirectory("ws-perms").toFile();
        dir.deleteOnExit();
        File target = new File(dir, "workstreams.yaml");

        // Create the file, then explicitly set 0644 so the next save has a
        // world-readable destination to preserve.
        config.saveToYaml(target);
        Files.setPosixFilePermissions(target.toPath(),
                PosixFilePermissions.fromString("rw-r--r--"));

        config.saveToYaml(target);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target.toPath());
        assertEquals("rw-r--r--", PosixFilePermissions.toString(perms));
    }

    /**
     * Regression test for the rename-revert bug: when callers use the
     * 3-arg {@link WorkstreamConfig#renameWorkspace(String, String,
     * java.util.Collection)} overload to propagate the new workspaceId to
     * live {@link Workstream} instances at rename time, a subsequent
     * {@link WorkstreamConfig#syncFromWorkstreams} call must NOT revert the
     * entries to the pre-rename ID.
     */
    @Test(timeout = 10000)
    public void testSyncFromWorkstreamsDoesNotRevertRename() throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"T0123456789\"\n"
            + "    slackTeamId: \"T0123456789\"\n"
            + "workstreams:\n"
            + "  - channelId: \"C001\"\n"
            + "    channelName: \"#alpha\"\n"
            + "    workspaceId: \"T0123456789\"\n"
            + "    defaultBranch: \"main\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        List<Workstream> liveWorkstreams = config.toWorkstreams();
        assertTrue(config.renameWorkspace("T0123456789", "almostrealism",
                liveWorkstreams));

        // Live workstreams now carry the new ID, so the sync step is safe.
        config.syncFromWorkstreams(liveWorkstreams);

        assertEquals("almostrealism",
                config.getWorkstreams().get(0).getWorkspaceId());
    }

    /**
     * Renaming a workspace to an ID already in use throws
     * {@link IllegalArgumentException} so the caller can surface a 400.
     */
    @Test(timeout = 10000)
    public void testRenameWorkspaceCollisionRejected() throws IOException {
        String yaml = "workspaces:\n"
            + "  - id: \"a\"\n"
            + "  - id: \"b\"\n"
            + "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        try {
            config.renameWorkspace("a", "b");
            fail("Expected IllegalArgumentException for ID collision");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }
}
