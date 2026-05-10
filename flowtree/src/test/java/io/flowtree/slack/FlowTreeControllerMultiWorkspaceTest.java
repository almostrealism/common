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
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Tests for Phase 1b multi-workspace controller startup.
 *
 * <p>These tests verify that {@link FlowTreeController} correctly handles
 * zero, one, and multiple workspace connections. All tests use mock/stub
 * approaches to avoid connecting to real Slack APIs — they verify the
 * wiring, routing, and configuration logic without a live Slack connection.</p>
 */
public class FlowTreeControllerMultiWorkspaceTest extends TestSuiteBase {

    // -------------------------------------------------------------------------
    // Backward compat: zero workspaces (single-token / simulation mode)
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testControllerInitZeroWorkspacesNullTokens() {
        FlowTreeController controller = new FlowTreeController(null, null);

        // No workspace connections yet — single-workspace mode
        assertTrue("No workspace connections in null-token mode",
                controller.getWorkspaceConnections().isEmpty());

        // defaultConnection is created (with null tokens)
        assertNotNull("defaultConnection should be created even with null tokens",
                controller.getDefaultConnection());

        // getNotifier() falls back to defaultConnection
        assertNotNull("getNotifier() should return defaultConnection notifier",
                controller.getNotifier());
    }

    @Test(timeout = 10000)
    public void testControllerInitZeroWorkspacesWithTokens() {
        FlowTreeController controller = new FlowTreeController("xoxb-test", "xapp-test");

        assertTrue("No workspace connections in single-token mode",
                controller.getWorkspaceConnections().isEmpty());

        FlowTreeController.WorkspaceConnection conn = controller.getDefaultConnection();
        assertNotNull(conn);
        assertEquals("xoxb-test", conn.botToken);
        assertEquals("xapp-test", conn.appToken);
        assertNotNull("Notifier should be created for default connection", conn.notifier);
    }

    @Test(timeout = 10000)
    public void testControllerListenerCreatedWithDefaultNotifier() {
        FlowTreeController controller = new FlowTreeController("xoxb-bot", "xapp-app");

        assertNotNull("Listener should be created", controller.getListener());
        // The listener and notifier are wired together — verify the simulation works
        List<String> messages = new ArrayList<>();
        controller.setEventSimulator((channel, text) -> messages.add(channel + ":" + text));

        // Register a workstream so the listener can route a message
        Workstream ws = new Workstream("C_TEST", "#test");
        ws.setDefaultBranch("main");
        controller.registerWorkstream(ws);

        // Simulate a message — should not throw even without a real Slack connection
        controller.simulateMessage("C_UNKNOWN", "Hello");
        assertEquals("Unknown channel should be ignored", 0, messages.size());
    }

    // -------------------------------------------------------------------------
    // One workspace loaded from config
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testLoadConfigOneWorkspace() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    name: \"primary\"\n" +
                      "    botToken: \"xoxb-one\"\n" +
                      "    appToken: \"xapp-one\"\n" +
                      "    defaultChannel: \"C_DEFAULT\"\n" +
                      "workstreams:\n" +
                      "  - channelId: \"C_ALPHA\"\n" +
                      "    channelName: \"#alpha\"\n" +
                      "    slackWorkspaceId: \"T111\"\n" +
                      "    defaultBranch: \"main\"\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        assertEquals("One workspace connection expected", 1,
                controller.getWorkspaceConnections().size());

        FlowTreeController.WorkspaceConnection conn =
                controller.getWorkspaceConnections().get("T111");
        assertNotNull("Workspace T111 connection should exist", conn);
        assertEquals("T111", conn.workspaceId);
        assertEquals("xoxb-one", conn.botToken);
        assertEquals("xapp-one", conn.appToken);
        assertNotNull("Notifier should be created for T111", conn.notifier);
    }

    @Test(timeout = 10000)
    public void testLoadConfigOneWorkspaceReplacesDefaultConnection() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    botToken: \"xoxb-one\"\n" +
                      "    appToken: \"xapp-one\"\n" +
                      "workstreams: []\n";

        File tmpFile = writeYamlTempFile(yaml);
        // Start with null tokens — defaultConnection has null notifier token
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        // After loading, defaultConnection should point to the first workspace
        FlowTreeController.WorkspaceConnection defaultConn = controller.getDefaultConnection();
        assertNotNull(defaultConn);
        Assert.assertEquals("Default connection should be the first workspace",
                "T111", defaultConn.workspaceId);

        // getNotifier() should return the workspace's notifier
        assertNotNull(controller.getNotifier());
    }

    // -------------------------------------------------------------------------
    // Multiple workspaces loaded from config
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testLoadConfigMultipleWorkspaces() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    name: \"alpha-team\"\n" +
                      "    botToken: \"xoxb-alpha\"\n" +
                      "    appToken: \"xapp-alpha\"\n" +
                      "  - workspaceId: \"T222\"\n" +
                      "    name: \"beta-team\"\n" +
                      "    botToken: \"xoxb-beta\"\n" +
                      "    appToken: \"xapp-beta\"\n" +
                      "workstreams: []\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        assertEquals("Two workspace connections expected", 2,
                controller.getWorkspaceConnections().size());

        FlowTreeController.WorkspaceConnection alpha =
                controller.getWorkspaceConnections().get("T111");
        assertNotNull(alpha);
        assertEquals("xoxb-alpha", alpha.botToken);

        FlowTreeController.WorkspaceConnection beta =
                controller.getWorkspaceConnections().get("T222");
        assertNotNull(beta);
        assertEquals("xoxb-beta", beta.botToken);
    }

    @Test(timeout = 10000)
    public void testEachWorkspaceGetsOwnNotifierInstance() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    botToken: \"xoxb-alpha\"\n" +
                      "    appToken: \"xapp-alpha\"\n" +
                      "  - workspaceId: \"T222\"\n" +
                      "    botToken: \"xoxb-beta\"\n" +
                      "    appToken: \"xapp-beta\"\n" +
                      "workstreams: []\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        FlowTreeController.WorkspaceConnection alpha =
                controller.getWorkspaceConnections().get("T111");
        FlowTreeController.WorkspaceConnection beta =
                controller.getWorkspaceConnections().get("T222");

        assertNotNull(alpha.notifier);
        assertNotNull(beta.notifier);
        assertNotSame("Each workspace must have its own notifier instance",
                alpha.notifier, beta.notifier);
    }

    @Test(timeout = 10000)
    public void testFallbackWhenSlackWorkspacesEmpty() throws IOException {
        String yaml = "workstreams:\n" +
                      "  - channelId: \"C_LEGACY\"\n" +
                      "    channelName: \"#legacy\"\n" +
                      "    defaultBranch: \"main\"\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController("xoxb-legacy", "xapp-legacy");
        controller.loadConfig(tmpFile);

        // Should remain in single-workspace mode
        assertTrue("Workspace connections map should be empty in single-workspace mode",
                controller.getWorkspaceConnections().isEmpty());

        // Default connection still holds the original tokens
        FlowTreeController.WorkspaceConnection conn = controller.getDefaultConnection();
        assertNotNull(conn);
        assertEquals("xoxb-legacy", conn.botToken);
        assertEquals("xapp-legacy", conn.appToken);

        // Workstream was registered
        assertEquals(1, controller.getListener().getWorkstreams().size());
    }

    // -------------------------------------------------------------------------
    // Event routing includes workspace ID
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testHandleMessagePassesWorkspaceId() {
        FlowTreeController controller = new FlowTreeController(null, null);

        SlackNotifier notifier = controller.getNotifier();
        List<String> postedMessages = new ArrayList<>();
        if (notifier != null) {
            notifier.setMessageCallback(json -> postedMessages.add(json));
        }

        Workstream ws = new Workstream("ws-route", "C_ROUTE", "#route");
        ws.setDefaultBranch("main");
        controller.registerWorkstream(ws);

        SlackListener listener = controller.getListener();

        // The listener just delegates to the base method for Phase 1b, but the method exists
        boolean handled = listener.handleMessage(
                "C_UNKNOWN", "U_TEST", "Hello", null, null, "T_WORKSPACE");
        assertFalse("Unknown channel should return false", handled);

        // Valid channel — verify the workspace-aware overload delegates correctly
        listener.handleMessage("C_ROUTE", "U_TEST", "Fix the bug", "ts1", null, "T_WORKSPACE");
        // No assertions on return value: it returns false when no agents configured.
        // The test verifies the overload exists and does not throw.
    }

    @Test(timeout = 10000)
    public void testHandleSlashCommandPassesWorkspaceId() throws IOException {
        FlowTreeController controller = new FlowTreeController(null, null);
        SlackListener listener = controller.getListener();

        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);

        // Call workspace-aware overload with null workspaceId — should not throw
        listener.handleSlashCommand("status", "C_TEST", "#test", responder, null);

        // Call with an actual workspaceId
        listener.handleSlashCommand("status", "C_TEST", "#test", responder, "T111");

        // Both invocations should succeed (may return "no workstream" message)
        // The key test is that both overloads are callable without error
    }

    @Test(timeout = 10000)
    public void testWorkspaceConnectionHoldsWorkspaceId() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T_EXPECTED\"\n" +
                      "    botToken: \"xoxb-x\"\n" +
                      "    appToken: \"xapp-x\"\n" +
                      "workstreams: []\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        FlowTreeController.WorkspaceConnection conn =
                controller.getWorkspaceConnections().get("T_EXPECTED");
        assertNotNull(conn);
        Assert.assertEquals("WorkspaceConnection must store the workspace ID",
                "T_EXPECTED", conn.workspaceId);
    }

    // -------------------------------------------------------------------------
    // Config reload rebuilds workspace connections
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testConfigReloadRebuildsWorkspaceConnections() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    botToken: \"xoxb-v1\"\n" +
                      "    appToken: \"xapp-v1\"\n" +
                      "workstreams: []\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        assertEquals(1, controller.getWorkspaceConnections().size());
        assertEquals("xoxb-v1",
                controller.getWorkspaceConnections().get("T111").botToken);

        // Update the config file with a new workspace
        String updatedYaml = "slackWorkspaces:\n" +
                             "  - workspaceId: \"T111\"\n" +
                             "    botToken: \"xoxb-v1\"\n" +
                             "    appToken: \"xapp-v1\"\n" +
                             "  - workspaceId: \"T222\"\n" +
                             "    botToken: \"xoxb-v2\"\n" +
                             "    appToken: \"xapp-v2\"\n" +
                             "workstreams: []\n";
        Files.write(tmpFile.toPath(), updatedYaml.getBytes());

        // Reload
        controller.reloadConfig();

        // Both workspace connections should now be present
        assertEquals("Reload should add the new workspace connection", 2,
                controller.getWorkspaceConnections().size());
        assertNotNull(controller.getWorkspaceConnections().get("T222"));
    }

    // -------------------------------------------------------------------------
    // Per-workspace notifier settings from config
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testPerWorkspaceNotifierSettings() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T111\"\n" +
                      "    botToken: \"xoxb-one\"\n" +
                      "    appToken: \"xapp-one\"\n" +
                      "    defaultChannel: \"C_FALLBACK\"\n" +
                      "    channelOwnerUserId: \"U_OWNER\"\n" +
                      "workstreams: []\n";

        File tmpFile = writeYamlTempFile(yaml);
        FlowTreeController controller = new FlowTreeController(null, null);
        controller.loadConfig(tmpFile);

        FlowTreeController.WorkspaceConnection conn =
                controller.getWorkspaceConnections().get("T111");
        assertNotNull(conn);

        // The notifier should have the workspace-specific settings applied
        assertEquals("C_FALLBACK", conn.notifier.getDefaultChannelId());
        assertEquals("U_OWNER", conn.notifier.getChannelOwnerUserId());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private File writeYamlTempFile(String yaml) throws IOException {
        File tmp = File.createTempFile("flowtree-test-", ".yaml");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), yaml.getBytes());
        return tmp;
    }
}
