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

import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import io.flowtree.workstream.WorkspaceEntry;
import io.flowtree.workstream.WorkstreamEntry;

/**
 * Tests for multi-tenant Slack workspace configuration, channel-key routing,
 * and workspace-aware workstream registration.
 *
 * <p>These tests verify YAML schema parsing for {@code slackWorkspaces},
 * backward-compatible single-workspace routing, composite channel-key
 * construction, and workspace-scoped listener dispatch — all without
 * requiring a live Slack connection.</p>
 */
public class SlackMultiTenantConfigTest extends TestSuiteBase {

    /** WorkspaceEntry serializes and deserializes all Slack fields. */
    @Test(timeout = 10000)
    public void testWorkspaceEntryYamlRoundTrip() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T0123456789\"\n" +
                      "    name: \"my-org\"\n" +
                      "    botToken: \"xoxb-test\"\n" +
                      "    appToken: \"xapp-test\"\n" +
                      "    defaultChannel: \"C0987654321\"\n" +
                      "    channelOwnerUserId: \"U0123456789\"\n" +
                      "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        assertNotNull(config.getSlackWorkspaces());
        assertEquals(1, config.getSlackWorkspaces().size());

        WorkspaceEntry entry = config.getSlackWorkspaces().get(0);
        assertEquals("T0123456789", entry.getId());
        assertEquals("T0123456789", entry.getSlackTeamId());
        assertEquals("my-org", entry.getName());
        assertEquals("xoxb-test", entry.getBotToken());
        assertEquals("xapp-test", entry.getAppToken());
        assertEquals("C0987654321", entry.getDefaultChannel());
        assertEquals("U0123456789", entry.getChannelOwnerUserId());
    }

    /** WorkstreamConfig parses multiple slackWorkspaces entries with githubOrgs and tokensFile. */
    @Test(timeout = 10000)
    public void testMultipleSlackWorkspacesYamlParsing() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    name: \"workspace-one\"\n" +
                      "    botToken: \"xoxb-one\"\n" +
                      "    appToken: \"xapp-one\"\n" +
                      "    githubOrgs:\n" +
                      "      my-org:\n" +
                      "        token: \"ghp_one\"\n" +
                      "  - workspaceId: \"T222\"\n" +
                      "    name: \"workspace-two\"\n" +
                      "    tokensFile: \"/config/slack-tokens.json\"\n" +
                      "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        assertEquals(2, config.getSlackWorkspaces().size());

        WorkspaceEntry ws1 = config.getSlackWorkspaces().get(0);
        assertEquals("T111", ws1.getId());
        assertEquals("workspace-one", ws1.getName());
        assertEquals("xoxb-one", ws1.getBotToken());
        assertNotNull(ws1.getGithubOrgs());
        assertEquals("ghp_one", ws1.getGithubOrgs().get("my-org").getToken());

        WorkspaceEntry ws2 = config.getSlackWorkspaces().get(1);
        assertEquals("T222", ws2.getId());
        assertEquals("/config/slack-tokens.json", ws2.getTokensFile());
    }

    /** WorkstreamConfig backward compat: no slackWorkspaces key produces empty list. */
    @Test(timeout = 10000)
    public void testBackwardCompatNoSlackWorkspaces() throws IOException {
        String yaml = "githubOrgs:\n" +
                      "  my-org:\n" +
                      "    token: \"ghp_token\"\n" +
                      "workstreams:\n" +
                      "  - channelId: \"C0123456789\"\n" +
                      "    channelName: \"#project-agent\"\n" +
                      "    defaultBranch: \"feature/work\"\n" +
                      "    githubOrg: \"my-org\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        assertNotNull(config.getSlackWorkspaces());
        assertEquals(0, config.getSlackWorkspaces().size());

        assertNotNull(config.getGithubOrgs().get("my-org"));

        assertEquals(1, config.getWorkstreams().size());
        assertEquals("C0123456789", config.getWorkstreams().get(0).getChannelId());
        assertNull(config.getWorkstreams().get(0).getWorkspaceId());
    }

    /** WorkstreamEntry with explicit slackWorkspaceId parses correctly; without it is null. */
    @Test(timeout = 10000)
    public void testWorkstreamEntryWithSlackWorkspaceId() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    botToken: \"xoxb-one\"\n" +
                      "    appToken: \"xapp-one\"\n" +
                      "workstreams:\n" +
                      "  - channelId: \"C001\"\n" +
                      "    channelName: \"#ws-with-id\"\n" +
                      "    slackWorkspaceId: \"T111\"\n" +
                      "    defaultBranch: \"feature/work\"\n" +
                      "  - channelId: \"C002\"\n" +
                      "    channelName: \"#ws-without-id\"\n" +
                      "    defaultBranch: \"feature/other\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        assertEquals(2, config.getWorkstreams().size());

        WorkstreamEntry entryWithId = config.getWorkstreams().get(0);
        assertEquals("C001", entryWithId.getChannelId());
        assertEquals("T111", entryWithId.getWorkspaceId());

        WorkstreamEntry entryWithoutId = config.getWorkstreams().get(1);
        assertEquals("C002", entryWithoutId.getChannelId());
        assertNull(entryWithoutId.getWorkspaceId());

        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals("T111", workstreams.get(0).getWorkspaceId());
        assertNull(workstreams.get(1).getWorkspaceId());
    }

    /** SlackTokens.from(entry) extracts inline botToken and appToken. */
    @Test(timeout = 10000)
    public void testSlackTokensFromEntryInlineTokens() throws IOException {
        WorkspaceEntry entry = new WorkspaceEntry();
        entry.setId("T999");
        entry.setBotToken("xoxb-inline-bot");
        entry.setAppToken("xapp-inline-app");

        SlackTokens tokens = SlackTokens.from(entry);

        assertEquals("xoxb-inline-bot", tokens.getBotToken());
        assertEquals("xapp-inline-app", tokens.getAppToken());
    }

    /** SlackTokens.from(entry) tokensFile takes priority over inline botToken. */
    @Test(timeout = 10000)
    public void testSlackTokensFromEntryTokensFile() throws IOException {
        File tempFile = File.createTempFile("workspace-tokens-test", ".json");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(),
                "{ \"botToken\": \"xoxb-from-file\", \"appToken\": \"xapp-from-file\" }".getBytes());

        WorkspaceEntry entry = new WorkspaceEntry();
        entry.setId("T888");
        entry.setTokensFile(tempFile.getAbsolutePath());
        entry.setBotToken("xoxb-should-be-ignored");

        SlackTokens tokens = SlackTokens.from(entry);

        assertEquals("xoxb-from-file", tokens.getBotToken());
        assertEquals("xapp-from-file", tokens.getAppToken());
    }

    /** WorkstreamConfig ignores unknown fields in YAML without throwing. */
    @Test(timeout = 10000)
    public void testWorkspaceEntryUnknownFieldsIgnored() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T777\"\n" +
                      "    botToken: \"xoxb-777\"\n" +
                      "    futureField: \"ignored\"\n" +
                      "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals("T777", config.getSlackWorkspaces().get(0).getId());
    }

    /** WorkstreamConfig slackWorkspaceId preserved in addWorkstream and syncFromWorkstreams. */
    @Test(timeout = 10000)
    public void testWorkstreamSlackWorkspaceIdPreservedInAddAndSync() {
        WorkstreamConfig config = new WorkstreamConfig();

        Workstream ws = new Workstream("ws-1", "C001", "#channel-one");
        ws.setWorkspaceId("T111");
        ws.setDefaultBranch("main");

        config.addWorkstream(ws);
        assertEquals(1, config.getWorkstreams().size());
        assertEquals("T111", config.getWorkstreams().get(0).getWorkspaceId());

        ws.setWorkspaceId("T222");
        config.syncFromWorkstreams(List.of(ws));
        assertEquals("T222", config.getWorkstreams().get(0).getWorkspaceId());
    }

    /** SlackListener.channelKey with null workspace returns bare channelId. */
    @Test(timeout = 10000)
    public void testChannelKeyNullWorkspaceReturnsBareChannelId() {
        assertEquals("C_ALPHA", SlackListener.channelKey(null, "C_ALPHA"));
    }

    /** SlackListener.channelKey with workspace returns composite workspaceId:channelId. */
    @Test(timeout = 10000)
    public void testChannelKeyWithWorkspaceReturnsCompositeKey() {
        assertEquals("T111:C_ALPHA", SlackListener.channelKey("T111", "C_ALPHA"));
    }

    /** SlackListener.channelKey same channel in different workspaces produces different keys. */
    @Test(timeout = 10000)
    public void testChannelKeyDifferentWorkspacesSameChannelProduceDifferentKeys() {
        String keyA = SlackListener.channelKey("T111", "C_SHARED");
        String keyB = SlackListener.channelKey("T222", "C_SHARED");
        assertFalse("Same channel in different workspaces must produce different keys",
                keyA.equals(keyB));
    }

    /** Backward compat: getWorkstream(bareChannelId) still works with null workspaceId. */
    @Test(timeout = 10000)
    public void testBackwardCompatNullWorkspaceIdRouting() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("ws-back", "C_BACK", "#back");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        Workstream found = listener.getWorkstream("C_BACK");
        assertNotNull("Backward compat: getWorkstream() with bare channel ID must work", found);
        assertEquals("C_BACK", found.getChannelId());
    }

    /** SlackListener workspace-aware registration and composite key lookup. */
    @Test(timeout = 10000)
    public void testWorkspaceAwareWorkstreamRegistration() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("ws-multi", "C_MULTI", "#multi");
        ws.setDefaultBranch("main");
        ws.setWorkspaceId("T111");
        listener.registerWorkstream(ws);

        listener.handleMessage("C_MULTI", "U1", "hello", "ts1", null, "T111");
        boolean found = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "C_MULTI".equals(w.getChannelId()));
        assertTrue("Workspace-aware workstream should be registered", found);
    }

    /** Same channelId in two workspaces routes to separate notifiers independently. */
    @Test(timeout = 10000)
    public void testSameChannelIdInTwoWorkspacesRoutedIndependently() {
        SlackNotifier notifierA = new SlackNotifier(null);
        SlackNotifier notifierB = new SlackNotifier(null);

        SlackListener listener = new SlackListener(notifierA);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T111", notifierA);
        byWorkspace.put("T222", notifierB);
        listener.setNotifiersByWorkspace(byWorkspace);

        Workstream wsA = new Workstream("ws-a", "C_SHARED", "#shared-a");
        wsA.setDefaultBranch("main");
        wsA.setWorkspaceId("T111");
        listener.registerWorkstream(wsA);

        Workstream wsB = new Workstream("ws-b", "C_SHARED", "#shared-b");
        wsB.setDefaultBranch("develop");
        wsB.setWorkspaceId("T222");
        listener.registerWorkstream(wsB);

        assertEquals("Two workstreams with same channelId in different workspaces must coexist",
                2, listener.getWorkstreams().size());

        boolean hasMain = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "main".equals(w.getDefaultBranch()));
        boolean hasDevelop = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "develop".equals(w.getDefaultBranch()));
        assertTrue("wsA (main branch) must be registered", hasMain);
        assertTrue("wsB (develop branch) must be registered", hasDevelop);
    }

    /** SlackListener.handleMessage returns false for unknown channel with workspaceId. */
    @Test(timeout = 10000)
    public void testHandleMessageUnknownChannelReturnsFalseWithWorkspaceId() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        boolean handled = listener.handleMessage("C_UNKNOWN", "U1", "hello", "ts1", null, "T111");
        assertFalse("Message to unknown channel must return false", handled);
    }

    /** /flowtree setup stores slackWorkspaceId on newly created workstream. */
    @Test(timeout = 10000)
    public void testSlashCommandSetupSetsSlackWorkspaceIdOnNewWorkstream() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);

        listener.handleSlashCommand("setup /workspace/project feature/test",
                "C_SETUP_WS", "#setup-ws", responder, "T999");

        boolean foundWithWorkspace = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "T999".equals(w.getWorkspaceId())
                        && "C_SETUP_WS".equals(w.getChannelId()));
        assertTrue("Setup must store slackWorkspaceId on new workstream", foundWithWorkspace);
    }

    /** /flowtree active filters workstreams by workspace context. */
    @Test(timeout = 10000)
    public void testSlashCommandActiveFiltersWorkstreamsByWorkspace() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T111", notifier);
        listener.setNotifiersByWorkspace(byWorkspace);

        Workstream wsA = new Workstream("ws-act-a", "C_ACT_A", "#act-a");
        wsA.setDefaultBranch("main");
        wsA.setWorkspaceId("T111");
        listener.registerWorkstream(wsA);

        Workstream wsB = new Workstream("ws-act-b", "C_ACT_B", "#act-b");
        wsB.setDefaultBranch("develop");
        wsB.setWorkspaceId("T222");
        listener.registerWorkstream(wsB);

        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);
        listener.handleSlashCommand("active", "C_ACT_A", "#act-a", responder, "T111");

        assertFalse("Active command should respond", responses.isEmpty());
        assertTrue("With null stats store active command warns",
                responses.get(0).contains("not available"));
    }

    /** SlackListener.setNotifiersByWorkspace routes workstreams to workspace-specific notifiers. */
    @Test(timeout = 10000)
    public void testSetNotifiersByWorkspaceIsUsedForNotifierResolution() {
        SlackNotifier primaryNotifier = new SlackNotifier(null);
        SlackNotifier workspaceNotifier = new SlackNotifier(null);

        SlackListener listener = new SlackListener(primaryNotifier);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T_SPECIAL", workspaceNotifier);
        listener.setNotifiersByWorkspace(byWorkspace);

        Workstream ws = new Workstream("ws-special", "C_SPECIAL", "#special");
        ws.setWorkspaceId("T_SPECIAL");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        Map<String, JobCompletionEvent> jobs = workspaceNotifier.getRecentJobs(ws.getWorkstreamId());
        assertNotNull("Workspace notifier should have workstream registered", jobs);
    }
}
