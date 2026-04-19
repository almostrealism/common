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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link WorkstreamConfig} loading, defaults, persistence, and
 * {@link SlackTokens} token resolution.
 */
public class WorkstreamConfigTest extends TestSuiteBase {

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
        WorkstreamConfig.SlackWorkspaceEntry ws = reloaded.getSlackWorkspaces().get(0);
        assertEquals("T111", ws.getWorkspaceId());
        assertEquals("primary", ws.getName());
        assertEquals("xoxb-one", ws.getBotToken());
        assertEquals("xapp-one", ws.getAppToken());

        // slackWorkspaceId on workstream entries must survive the round-trip
        assertEquals(1, reloaded.getWorkstreams().size());
        assertEquals("T111", reloaded.getWorkstreams().get(0).getSlackWorkspaceId());
    }

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
        WorkstreamConfig.SlackWorkspaceEntry migratedWs = config.getSlackWorkspaces().get(0);
        assertEquals("T111", migratedWs.getWorkspaceId());
        assertEquals("primary", migratedWs.getName());
        assertEquals("/config/slack-tokens.json", migratedWs.getTokensFile());
        assertNotNull("githubOrgs should be parsed from migrated YAML", migratedWs.getGithubOrgs());
        assertEquals(2, migratedWs.getGithubOrgs().size());
        assertEquals(3, config.getWorkstreams().size());

        // All workstreams must have slackWorkspaceId
        for (WorkstreamConfig.WorkstreamEntry entry : config.getWorkstreams()) {
            assertEquals("T111", entry.getSlackWorkspaceId());
        }

        // Convert to runtime workstreams and verify slackWorkspaceId propagates
        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals(3, workstreams.size());
        for (Workstream ws : workstreams) {
            assertEquals("T111", ws.getSlackWorkspaceId());
        }
    }
}
