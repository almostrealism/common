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

import fi.iki.elonen.NanoHTTPD;
import io.flowtree.jobs.ClaudeCodeJob;
import io.flowtree.jobs.ClaudeCodeJobEvent;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Tests for the FlowTree orchestration and Slack integration components.
 *
 * <p>These tests verify the message parsing, workstream configuration,
 * notification formatting, and HTTP API without requiring a real Slack
 * connection or connected agents.</p>
 */
public class SlackIntegrationTest extends TestSuiteBase {

    @Test(timeout = 10000)
    public void testWorkstreamConfiguration() {
        SlackWorkstream workstream = new SlackWorkstream("C0123456789", "#test-channel");
        workstream.addAgent("localhost", 7766);
        workstream.addAgent("localhost", 7767);
        workstream.setDefaultBranch("feature/test");
        workstream.setMaxBudgetUsd(25.0);

        assertEquals("C0123456789", workstream.getChannelId());
        assertEquals("#test-channel", workstream.getChannelName());
        assertEquals(2, workstream.getAgents().size());
        assertEquals("feature/test", workstream.getDefaultBranch());
        assertEquals(25.0, workstream.getMaxBudgetUsd(), 0.001);
        assertNotNull(workstream.getWorkstreamId());
    }

    @Test(timeout = 10000)
    public void testAgentRoundRobin() {
        SlackWorkstream workstream = new SlackWorkstream("C123", "#test");
        workstream.addAgent("host1", 7766);
        workstream.addAgent("host2", 7767);
        workstream.addAgent("host3", 7768);

        // Get agents in sequence
        SlackWorkstream.AgentEndpoint a1 = workstream.getNextAgent();
        SlackWorkstream.AgentEndpoint a2 = workstream.getNextAgent();
        SlackWorkstream.AgentEndpoint a3 = workstream.getNextAgent();
        SlackWorkstream.AgentEndpoint a4 = workstream.getNextAgent();

        assertEquals("host1", a1.getHost());
        assertEquals("host2", a2.getHost());
        assertEquals("host3", a3.getHost());
        assertEquals("host1", a4.getHost()); // Wraps around
    }

    @Test(timeout = 10000)
    public void testPromptExtraction() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        // Test basic mention
        assertEquals("Fix the bug", listener.extractPrompt("<@U12345> Fix the bug"));

        // Test mention with /task command
        assertEquals("Implement caching", listener.extractPrompt("<@U12345> /task Implement caching"));

        // Test multi-line prompt
        String multiLine = "<@U12345> Please fix this issue:\n- Bug in auth\n- Timeout handling";
        String extracted = listener.extractPrompt(multiLine);
        assertTrue(extracted.contains("Please fix this issue"));
        assertTrue(extracted.contains("Bug in auth"));

        // Test direct message (no mention)
        assertEquals("Direct prompt", listener.extractPrompt("Direct prompt"));
    }

    @Test(timeout = 10000)
    public void testNotifierMessageFormatting() {
        List<String> messages = new ArrayList<>();
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            // Extract text from JSON
            int start = json.indexOf("\"text\":\"") + 8;
            int end = json.indexOf("\"", start);
            if (start > 7 && end > start) {
                messages.add(json.substring(start, end).replace("\\n", "\n"));
            }
        });

        SlackWorkstream workstream = new SlackWorkstream("C123", "#test");
        workstream.setDefaultBranch("feature/test");
        notifier.registerWorkstream(workstream);

        // Test started notification
        JobCompletionEvent startEvent = JobCompletionEvent.started(
            "job-123", "Fix authentication bug"
        );
        notifier.onJobStarted(workstream.getWorkstreamId(), startEvent);

        assertTrue(messages.size() > 0);
        String startMsg = messages.get(0);
        assertTrue(startMsg.contains("Starting work"));
        assertTrue(startMsg.contains("Fix authentication bug"));

        messages.clear();

        // Test success notification
        JobCompletionEvent successEvent = JobCompletionEvent.success(
            "job-123", "Fix authentication bug"
        );
        successEvent.withGitInfo("feature/test", "abc1234567890",
            List.of("auth.py", "tests/test_auth.py"), List.of(), true);
        notifier.onJobCompleted(workstream.getWorkstreamId(), successEvent);

        assertTrue(messages.size() > 0);
        String successMsg = messages.get(0);
        assertTrue(successMsg.contains("Work complete"));
        assertTrue(successMsg.contains("abc1234")); // Shortened commit hash
        assertTrue(successMsg.contains("feature/test"));

        messages.clear();

        // Test failure notification
        ClaudeCodeJobEvent failEvent = ClaudeCodeJobEvent.failed(
            "job-456", "Build the thing",
            "Compilation failed", new RuntimeException("Syntax error")
        );
        failEvent.withClaudeCodeInfo("Build the thing", "session-789", 1);
        notifier.onJobCompleted(workstream.getWorkstreamId(), failEvent);

        assertTrue(messages.size() > 0);
        String failMsg = messages.get(0);
        assertTrue(failMsg.contains("Work failed"));
        assertTrue(failMsg.contains("Compilation failed"));
    }

    @Test(timeout = 10000)
    public void testJobCompletionEvent() {
        // Test started event
        JobCompletionEvent started = JobCompletionEvent.started("j1", "Test task");
        assertEquals(JobCompletionEvent.Status.STARTED, started.getStatus());
        assertEquals("j1", started.getJobId());

        // Test success event
        JobCompletionEvent success = JobCompletionEvent.success("j2", "Done");
        assertEquals(JobCompletionEvent.Status.SUCCESS, success.getStatus());

        // Test failed event
        Exception ex = new RuntimeException("Test error");
        JobCompletionEvent failed = JobCompletionEvent.failed("j3", "Failed", "Test error", ex);
        assertEquals(JobCompletionEvent.Status.FAILED, failed.getStatus());
        assertEquals("Test error", failed.getErrorMessage());
        assertEquals(ex, failed.getException());

        // Test builder methods
        success.withGitInfo("main", "abc123", List.of("a.txt"), List.of("b.bin"), true);
        assertEquals("main", success.getTargetBranch());
        assertEquals("abc123", success.getCommitHash());
        assertEquals(1, success.getStagedFiles().size());
        assertTrue(success.isPushed());

        // Claude-specific fields require ClaudeCodeJobEvent
        ClaudeCodeJobEvent ccEvent = ClaudeCodeJobEvent.success("j4", "Done");
        ccEvent.withClaudeCodeInfo("Fix bug", "sess-1", 0);
        assertEquals("Fix bug", ccEvent.getPrompt());
        assertEquals("sess-1", ccEvent.getSessionId());
        assertEquals(0, ccEvent.getExitCode());
    }

    @Test(timeout = 10000)
    public void testControllerSimulation() throws Exception {
        FlowTreeController controller = new FlowTreeController(null, null);

        List<String> receivedMessages = new ArrayList<>();
        controller.setEventSimulator((channel, message) -> {
            receivedMessages.add(channel + ": " + message);
        });

        // Simulate message without registered workstream - should be ignored
        controller.simulateMessage("C_UNKNOWN", "<@BOT> Hello");
        assertEquals(0, receivedMessages.size());

        // Note: Full simulation test would require mocking the ClaudeCodeClient
        // which connects to actual agents. This tests the basic plumbing.
    }

    @Test(timeout = 10000)
    public void testCompletionListenerInterface() {
        List<JobCompletionEvent> events = new ArrayList<>();

        JobCompletionListener listener = new JobCompletionListener() {
            @Override
            public void onJobCompleted(String workstreamId, JobCompletionEvent event) {
                events.add(event);
            }

            @Override
            public void onJobStarted(String workstreamId, JobCompletionEvent event) {
                events.add(event);
            }
        };

        // Fire events
        listener.onJobStarted("w1", JobCompletionEvent.started("j1", "Starting"));
        listener.onJobCompleted("w1", JobCompletionEvent.success("j1", "Done"));

        assertEquals(2, events.size());
        assertEquals(JobCompletionEvent.Status.STARTED, events.get(0).getStatus());
        assertEquals(JobCompletionEvent.Status.SUCCESS, events.get(1).getStatus());
    }

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

        // Convert to SlackWorkstream objects
        List<SlackWorkstream> workstreams = config.toWorkstreams();
        assertEquals(2, workstreams.size());

        SlackWorkstream ws1 = workstreams.get(0);
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
    public void testApiEndpointPostMessage() throws Exception {
        AtomicReference<String> receivedChannel = new AtomicReference<>();
        AtomicReference<String> receivedText = new AtomicReference<>();

        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            receivedChannel.set(FlowTreeApiEndpoint.extractJsonField(json, "channel"));
            receivedText.set(FlowTreeApiEndpoint.extractJsonField(json, "text"));
        });

        SlackWorkstream workstream = new SlackWorkstream("C_TEST_123", "#test");
        notifier.registerWorkstream(workstream);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"text\":\"Hello from agent\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/"
                    + workstream.getWorkstreamId() + "/messages").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(200, conn.getResponseCode());
            assertEquals("C_TEST_123", receivedChannel.get());
            assertEquals("Hello from agent", receivedText.get());
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testApiEndpointHealthCheck() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/health").openConnection();
            conn.setRequestMethod("GET");

            assertEquals(200, conn.getResponseCode());

            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"status\":\"ok\""));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testApiEndpointMissingText() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackWorkstream workstream = new SlackWorkstream("C123", "#test");
        notifier.registerWorkstream(workstream);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();

            // Missing text field
            String body = "{\"something\":\"else\"}";
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/"
                    + workstream.getWorkstreamId() + "/messages").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());

            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(error.contains("text"));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testApiEndpointSubmitMissingPrompt() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackWorkstream workstream = new SlackWorkstream("C_SUBMIT_1", "#submit-test");
        notifier.registerWorkstream(workstream);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();

            // Missing prompt field
            String body = "{\"targetBranch\":\"main\"}";
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/"
                    + workstream.getWorkstreamId() + "/submit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());

            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(error.contains("prompt"));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testApiEndpointSubmitUnknownWorkstream() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();

            String body = "{\"prompt\":\"Do something\"}";
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/nonexistent/submit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());

            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(error.contains("Unknown workstream"));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testApiEndpointSubmitNoServer() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackWorkstream workstream = new SlackWorkstream("C_SUBMIT_2", "#submit-test");
        notifier.registerWorkstream(workstream);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        // Note: no server set
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();

            String body = "{\"prompt\":\"Do something\"}";
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/"
                    + workstream.getWorkstreamId() + "/submit").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());

            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(error.contains("No FlowTree server"));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testFactoryWorkstreamUrlConfiguration() {
        ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory("Test prompt");
        factory.setWorkstreamUrl("http://localhost:7780/api/workstreams/ws1/jobs/j1");

        assertEquals("http://localhost:7780/api/workstreams/ws1/jobs/j1", factory.getWorkstreamUrl());

        // Verify propagation to created job
        ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
        assertNotNull(job);
        assertEquals("http://localhost:7780/api/workstreams/ws1/jobs/j1", job.getWorkstreamUrl());
    }

    // --- Slash command tests ---

    @Test(timeout = 10000)
    public void testSlashCommandHelp() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("", "C123", "#test", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Flowtree Commands"));
        assertTrue(response.get().contains("/flowtree setup"));
        assertTrue(response.get().contains("/flowtree info"));
        assertTrue(response.get().contains("/flowtree status"));
        assertTrue(response.get().contains("/flowtree task"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandUnknownSubcommand() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("foobar", "C123", "#test", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Flowtree Commands"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandSetupCreatesWorkstream() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("setup /workspace/project feature/test",
            "C_SETUP_1", "#setup-channel", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Workstream created"));
        assertTrue(response.get().contains("/workspace/project"));
        assertTrue(response.get().contains("feature/test"));

        // Verify workstream was registered
        SlackWorkstream ws = listener.getWorkstream("C_SETUP_1");
        assertNotNull(ws);
        assertEquals("/workspace/project", ws.getWorkingDirectory());
        assertEquals("feature/test", ws.getDefaultBranch());
        assertEquals("#setup-channel", ws.getChannelName());
        assertEquals(100.0, ws.getMaxBudgetUsd(), 0.001);
        assertEquals(800, ws.getMaxTurns());
    }

    @Test(timeout = 10000)
    public void testSlashCommandSetupUpdatesExisting() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        // Create initial workstream
        SlackWorkstream ws = new SlackWorkstream("C_UPD_1", "#update-channel");
        ws.setWorkingDirectory("/old/dir");
        ws.setDefaultBranch("old-branch");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("setup /new/dir new-branch",
            "C_UPD_1", "#update-channel", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Workstream updated"));
        assertTrue(response.get().contains("/old/dir"));
        assertTrue(response.get().contains("/new/dir"));
        assertTrue(response.get().contains("old-branch"));
        assertTrue(response.get().contains("new-branch"));

        // Verify the workstream was updated in place
        assertEquals("/new/dir", ws.getWorkingDirectory());
        assertEquals("new-branch", ws.getDefaultBranch());
    }

    @Test(timeout = 10000)
    public void testSlashCommandSetupMissingArgs() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        AtomicReference<String> response = new AtomicReference<>();

        // No arguments
        listener.handleSlashCommand("setup", "C123", "#test", response::set);
        assertTrue(response.get().contains("Usage"));

        // Only one argument
        response.set(null);
        listener.handleSlashCommand("setup /workspace/only", "C123", "#test", response::set);
        assertTrue(response.get().contains("Both working directory and branch are required"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandInfo() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_INFO_1", "#info-channel");
        ws.setWorkingDirectory("/workspace/project");
        ws.setDefaultBranch("feature/info");
        ws.setMaxBudgetUsd(25.0);
        ws.setMaxTurns(100);
        ws.setGitUserName("CI Bot");
        ws.setGitUserEmail("ci@example.com");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("info", "C_INFO_1", "#info-channel", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Workstream Details"));
        assertTrue(response.get().contains(ws.getWorkstreamId()));
        assertTrue(response.get().contains("/workspace/project"));
        assertTrue(response.get().contains("feature/info"));
        assertTrue(response.get().contains("25.00"));
        assertTrue(response.get().contains("100"));
        assertTrue(response.get().contains("CI Bot"));
        assertTrue(response.get().contains("ci@example.com"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandInfoNoWorkstream() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("info", "C_NONE", "#no-ws", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("No workstream configured"));
        assertTrue(response.get().contains("/flowtree setup"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandStatus() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_STATUS_1", "#status-channel");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("status", "C_STATUS_1", "#status-channel", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Agent Status"));
        assertTrue(response.get().contains("Connected agents: 0"));
        assertTrue(response.get().contains("#status-channel"));
        assertTrue(response.get().contains("main"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandConfigShowAll() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_CFG_1", "#config-channel");
        ws.setMaxBudgetUsd(15.0);
        ws.setMaxTurns(75);
        ws.setDefaultBranch("develop");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("config", "C_CFG_1", "#config-channel", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Workstream Configuration"));
        assertTrue(response.get().contains("maxBudgetUsd"));
        assertTrue(response.get().contains("15.00"));
        assertTrue(response.get().contains("maxTurns"));
        assertTrue(response.get().contains("75"));
        assertTrue(response.get().contains("develop"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandConfigShowSingle() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_CFG_2", "#config-channel");
        ws.setMaxBudgetUsd(20.0);
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("config maxBudgetUsd", "C_CFG_2", "#config-channel", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("20.00"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandConfigUpdate() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_CFG_3", "#config-channel");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();

        // Update maxBudgetUsd
        listener.handleSlashCommand("config maxBudgetUsd 30.0", "C_CFG_3", "#config-channel", response::set);
        assertTrue(response.get().contains("Updated"));
        assertTrue(response.get().contains("30.0"));
        assertEquals(30.0, ws.getMaxBudgetUsd(), 0.001);

        // Update maxTurns
        response.set(null);
        listener.handleSlashCommand("config maxTurns 200", "C_CFG_3", "#config-channel", response::set);
        assertTrue(response.get().contains("Updated"));
        assertEquals(200, ws.getMaxTurns());

        // Update defaultBranch
        response.set(null);
        listener.handleSlashCommand("config defaultBranch feature/new", "C_CFG_3", "#config-channel", response::set);
        assertTrue(response.get().contains("Updated"));
        assertEquals("feature/new", ws.getDefaultBranch());

        // Try read-only field
        response.set(null);
        listener.handleSlashCommand("config workstreamId new-id", "C_CFG_3", "#config-channel", response::set);
        assertTrue(response.get().contains("read-only"));

        // Try unknown field
        response.set(null);
        listener.handleSlashCommand("config unknownField value", "C_CFG_3", "#config-channel", response::set);
        assertTrue(response.get().contains("Unknown setting"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandConfigInvalidNumber() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_CFG_4", "#config-channel");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("config maxBudgetUsd notanumber", "C_CFG_4", "#config-channel", response::set);
        assertTrue(response.get().contains("Invalid number"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandJobs() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_JOBS_1", "#jobs-channel");
        listener.registerWorkstream(ws);

        // No jobs initially
        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("jobs", "C_JOBS_1", "#jobs-channel", response::set);
        assertNotNull(response.get());
        assertTrue(response.get().contains("No recent jobs"));

        // Add a tracked job via notifier
        JobCompletionEvent startEvent = JobCompletionEvent.started("job-abc", "Fix auth bug");
        notifier.onJobStarted(ws.getWorkstreamId(), startEvent);

        response.set(null);
        listener.handleSlashCommand("jobs", "C_JOBS_1", "#jobs-channel", response::set);
        assertNotNull(response.get());
        assertTrue(response.get().contains("Recent Jobs"));
        assertTrue(response.get().contains("job-abc"));
        assertTrue(response.get().contains("Fix auth bug"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandCancel() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        SlackWorkstream ws = new SlackWorkstream("C_CANCEL_1", "#cancel-channel");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("cancel", "C_CANCEL_1", "#cancel-channel", response::set);
        assertNotNull(response.get());
        assertTrue(response.get().contains("not yet implemented"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandSetupPersistence() throws IOException {
        // Create a temp YAML config file
        File tempFile = File.createTempFile("workstreams-test", ".yaml");
        tempFile.deleteOnExit();

        String yaml = "workstreams:\n"
            + "  - channelId: \"C_EXISTING\"\n"
            + "    channelName: \"#existing\"\n"
            + "    defaultBranch: \"main\"\n";
        Files.write(tempFile.toPath(), yaml.getBytes());

        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(tempFile);

        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);
        listener.setWorkstreamConfig(config, tempFile);

        // Register existing workstreams
        for (SlackWorkstream ws : config.toWorkstreams()) {
            listener.registerWorkstream(ws);
        }

        // Create a new workstream via setup command
        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("setup /workspace/new feature/new",
            "C_NEW", "#new-channel", response::set);

        assertTrue(response.get().contains("Workstream created"));

        // Verify the config was persisted
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        assertEquals(2, reloaded.getWorkstreams().size());

        // Find the new entry
        WorkstreamConfig.WorkstreamEntry newEntry = null;
        for (WorkstreamConfig.WorkstreamEntry entry : reloaded.getWorkstreams()) {
            if ("C_NEW".equals(entry.getChannelId())) {
                newEntry = entry;
                break;
            }
        }
        assertNotNull(newEntry);
        assertEquals("#new-channel", newEntry.getChannelName());
        assertEquals("/workspace/new", newEntry.getWorkingDirectory());
        assertEquals("feature/new", newEntry.getDefaultBranch());
    }

    @Test(timeout = 10000)
    public void testWorkstreamConfigAddWorkstream() {
        WorkstreamConfig config = new WorkstreamConfig();

        SlackWorkstream ws = new SlackWorkstream("C_ADD_1", "#add-channel");
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
        List<SlackWorkstream> wsList = config.toWorkstreams();
        assertEquals(1, wsList.size());

        // Modify the in-memory workstream
        SlackWorkstream ws = wsList.get(0);
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
    public void testNotifierJobTracking() {
        SlackNotifier notifier = new SlackNotifier(null);

        SlackWorkstream ws = new SlackWorkstream("C_TRACK_1", "#track-channel");
        notifier.registerWorkstream(ws);

        // No jobs initially
        Map<String, JobCompletionEvent> jobs = notifier.getRecentJobs(ws.getWorkstreamId());
        assertTrue(jobs.isEmpty());

        // Track a started job
        JobCompletionEvent startEvent = JobCompletionEvent.started("job-1", "Task one");
        notifier.onJobStarted(ws.getWorkstreamId(), startEvent);

        jobs = notifier.getRecentJobs(ws.getWorkstreamId());
        assertEquals(1, jobs.size());
        assertEquals(JobCompletionEvent.Status.STARTED, jobs.get("job-1").getStatus());

        // Complete the job - should update the entry
        JobCompletionEvent completeEvent = JobCompletionEvent.success("job-1", "Task one");
        notifier.onJobCompleted(ws.getWorkstreamId(), completeEvent);

        jobs = notifier.getRecentJobs(ws.getWorkstreamId());
        assertEquals(1, jobs.size());
        assertEquals(JobCompletionEvent.Status.SUCCESS, jobs.get("job-1").getStatus());

        // Unknown workstream returns empty
        assertTrue(notifier.getRecentJobs("unknown-ws").isEmpty());
    }

    @Test(timeout = 10000)
    public void testFindWorkstreamByBranch() {
        SlackNotifier notifier = new SlackNotifier(null);

        SlackWorkstream ws1 = new SlackWorkstream("ws-rings", "C_RINGS", "#rings");
        ws1.setDefaultBranch("feature/new-decoder");

        SlackWorkstream ws2 = new SlackWorkstream("ws-common", "C_COMMON", "#common");
        ws2.setDefaultBranch("feature/pipeline-agents");

        SlackWorkstream ws3 = new SlackWorkstream("ws-no-branch", "C_NONE", "#no-branch");
        // defaultBranch is null

        notifier.registerWorkstream(ws1);
        notifier.registerWorkstream(ws2);
        notifier.registerWorkstream(ws3);

        // Exact match finds the right workstream
        assertSame(ws1, notifier.findWorkstreamByBranch("feature/new-decoder"));
        assertSame(ws2, notifier.findWorkstreamByBranch("feature/pipeline-agents"));

        // No match returns null
        assertNull(notifier.findWorkstreamByBranch("feature/unknown"));

        // Null and empty branch return null
        assertNull(notifier.findWorkstreamByBranch(null));
        assertNull(notifier.findWorkstreamByBranch(""));

        // Prefix match does NOT work (exact only)
        assertNull(notifier.findWorkstreamByBranch("feature/new"));
        assertNull(notifier.findWorkstreamByBranch("feature/new-decoder-v2"));
    }

    @Test(timeout = 10000)
    public void testSubmitBranchToWorkstreamResolution() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        // Register a pipeline fallback workstream
        SlackWorkstream pipelineWs = new SlackWorkstream("ws-pipeline", "C_PIPE", "#pipeline");
        pipelineWs.setDefaultBranch(null);
        pipelineWs.setAllowedTools("Read,Glob,Grep");
        pipelineWs.setMaxBudgetUsd(5.0);
        pipelineWs.setMaxTurns(30);
        notifier.registerWorkstream(pipelineWs);

        // Register a richer workstream that matches a specific branch
        SlackWorkstream ringsWs = new SlackWorkstream("ws-rings", "C_RINGS", "#rings");
        ringsWs.setDefaultBranch("feature/new-decoder");
        ringsWs.setAllowedTools("Read,Edit,Write,Bash,Glob,Grep");
        ringsWs.setMaxBudgetUsd(25.0);
        ringsWs.setMaxTurns(100);
        notifier.registerWorkstream(ringsWs);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        // No server set - we only test up to the "No FlowTree server" error
        // but verify the workstream resolution happened via the error message
        // (workstream resolves before server check)
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();

            // Case 1: targetBranch matches ws-rings, should resolve to ws-rings
            // even though the URL path points to ws-pipeline
            String body1 = "{\"prompt\":\"Review the code\",\"targetBranch\":\"feature/new-decoder\"}";
            HttpURLConnection conn1 = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/ws-pipeline/submit").openConnection();
            conn1.setRequestMethod("POST");
            conn1.setDoOutput(true);
            conn1.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn1.getOutputStream()) {
                os.write(body1.getBytes(StandardCharsets.UTF_8));
            }
            // Should reach "No FlowTree server" - meaning the workstream resolved OK
            assertEquals(400, conn1.getResponseCode());
            String error1 = new String(conn1.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Should resolve workstream and reach server check",
                error1.contains("No FlowTree server"));

            // Case 2: targetBranch does not match any workstream, falls back to URL path
            String body2 = "{\"prompt\":\"Review the code\",\"targetBranch\":\"feature/unrelated\"}";
            HttpURLConnection conn2 = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/ws-pipeline/submit").openConnection();
            conn2.setRequestMethod("POST");
            conn2.setDoOutput(true);
            conn2.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn2.getOutputStream()) {
                os.write(body2.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(400, conn2.getResponseCode());
            String error2 = new String(conn2.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Should fall back to URL path workstream and reach server check",
                error2.contains("No FlowTree server"));

            // Case 3: Explicit workstreamId in body overrides both branch match and URL path
            String body3 = "{\"prompt\":\"Review the code\",\"targetBranch\":\"feature/new-decoder\","
                + "\"workstreamId\":\"ws-pipeline\"}";
            HttpURLConnection conn3 = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/ws-rings/submit").openConnection();
            conn3.setRequestMethod("POST");
            conn3.setDoOutput(true);
            conn3.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn3.getOutputStream()) {
                os.write(body3.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(400, conn3.getResponseCode());
            String error3 = new String(conn3.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Explicit workstreamId in body should resolve and reach server check",
                error3.contains("No FlowTree server"));

            // Case 4: Unknown explicit workstreamId in body, but branch matches - should use branch match
            String body4 = "{\"prompt\":\"Review the code\",\"targetBranch\":\"feature/new-decoder\","
                + "\"workstreamId\":\"ws-nonexistent\"}";
            HttpURLConnection conn4 = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams/ws-pipeline/submit").openConnection();
            conn4.setRequestMethod("POST");
            conn4.setDoOutput(true);
            conn4.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn4.getOutputStream()) {
                os.write(body4.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(400, conn4.getResponseCode());
            String error4 = new String(conn4.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Should fall through to branch match when body workstreamId is unknown",
                error4.contains("No FlowTree server"));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testSlackManifestIncludesSlashCommand() throws IOException {
        // Load manifest from classpath (it's a resource in the same module)
        java.io.InputStream is = getClass().getResourceAsStream("/slack-app-manifest.json");
        assertNotNull("Manifest should be on classpath", is);

        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(content.contains("slash_commands"));
        assertTrue(content.contains("/flowtree"));
        assertTrue(content.contains("commands"));
    }

    @Test(timeout = 10000)
    public void testApiStatsEndpoint() throws Exception {
        File tempDir = Files.createTempDirectory("stats-test").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();

        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();

        try {
            // Seed jobs in the current week
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
            Instant jobTime = monday.atStartOfDay(ZoneOffset.UTC).toInstant()
                .plusSeconds(3600);

            store.recordJobStarted("j1", "ws-alpha", "Fix bug", jobTime);
            store.recordJobCompleted("j1", "ws-alpha", "SUCCESS",
                jobTime.plusMillis(60000), 55000, 30000, 0.50, 10, "sess-1", 0,
                "success", false, 0);

            store.recordJobStarted("j2", "ws-beta", "Add feature", jobTime);
            store.recordJobCompleted("j2", "ws-beta", "FAILED",
                jobTime.plusMillis(120000), 100000, 80000, 1.20, 25, "sess-2", 1,
                "error_max_turns", true, 3);

            SlackNotifier notifier = new SlackNotifier(null);
            FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
            endpoint.setStatsStore(store);
            endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

            try {
                int port = endpoint.getListeningPort();

                // Unfiltered query - should return both workstreams
                HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/stats").openConnection();
                conn.setRequestMethod("GET");
                assertEquals(200, conn.getResponseCode());

                String response = new String(conn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                assertTrue("Should contain thisWeek", response.contains("\"thisWeek\""));
                assertTrue("Should contain lastWeek", response.contains("\"lastWeek\""));
                assertTrue("Should contain ws-alpha", response.contains("ws-alpha"));
                assertTrue("Should contain ws-beta", response.contains("ws-beta"));
                assertTrue("Should contain jobCount", response.contains("\"jobCount\""));

                // Filtered query - only ws-alpha
                HttpURLConnection conn2 = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/stats?workstream=ws-alpha").openConnection();
                conn2.setRequestMethod("GET");
                assertEquals(200, conn2.getResponseCode());

                String filtered = new String(conn2.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                assertTrue("Filtered should contain ws-alpha",
                    filtered.contains("ws-alpha"));
                assertFalse("Filtered should not contain ws-beta",
                    filtered.contains("ws-beta"));
            } finally {
                endpoint.stop();
            }
        } finally {
            store.close();
        }
    }

    @Test(timeout = 10000)
    public void testApiStatsUnsupportedPeriod() throws Exception {
        File tempDir = Files.createTempDirectory("stats-period-test").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();

        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();

        try {
            SlackNotifier notifier = new SlackNotifier(null);
            FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
            endpoint.setStatsStore(store);
            endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

            try {
                int port = endpoint.getListeningPort();

                HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/stats?period=monthly").openConnection();
                conn.setRequestMethod("GET");
                assertEquals(400, conn.getResponseCode());

                String error = new String(conn.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                assertTrue("Should mention unsupported period",
                    error.contains("Unsupported period"));
            } finally {
                endpoint.stop();
            }
        } finally {
            store.close();
        }
    }

    @Test(timeout = 10000)
    public void testApiStatsNotConfigured() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        // No stats store set
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://localhost:" + port + "/api/stats").openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());

            String response = new String(conn.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue("Should indicate stats not configured",
                response.contains("Stats not configured"));
        } finally {
            endpoint.stop();
        }
    }

    @Test(timeout = 10000)
    public void testExtractLastJsonObjectFindsResultLine() {
        // Simulates Claude Code NDJSON output: per-turn objects first, result last
        String ndjson = "{\"type\":\"assistant\",\"duration_ms\":4000,\"session_id\":\"sess-early\"}\n"
            + "{\"type\":\"assistant\",\"duration_ms\":3500}\n"
            + "{\"type\":\"result\",\"duration_ms\":1800000,\"duration_api_ms\":1500000,"
            + "\"cost_usd\":2.50,\"num_turns\":42,\"session_id\":\"sess-final\","
            + "\"subtype\":\"success\",\"is_error\":false}\n";

        String result = io.flowtree.JsonFieldExtractor.extractLastJsonObject(ndjson, "result");
        assertNotNull("Should find the result line", result);
        assertTrue("Should contain the result type", result.contains("\"type\":\"result\""));

        // Verify extracting metrics from the result line yields session-level values
        long durationMs = io.flowtree.JsonFieldExtractor.extractLong(result, "duration_ms");
        long durationApiMs = io.flowtree.JsonFieldExtractor.extractLong(result, "duration_api_ms");
        double costUsd = io.flowtree.JsonFieldExtractor.extractDouble(result, "cost_usd");
        int numTurns = io.flowtree.JsonFieldExtractor.extractInt(result, "num_turns");
        String sessionId = io.flowtree.JsonFieldExtractor.extractString(result, "session_id");

        assertEquals("Should get session-level duration, not per-turn", 1800000, durationMs);
        assertEquals(1500000, durationApiMs);
        assertEquals(2.50, costUsd, 0.001);
        assertEquals(42, numTurns);
        assertEquals("sess-final", sessionId);
    }

    @Test(timeout = 10000)
    public void testExtractLastJsonObjectFallsBackToLastLine() {
        // No "type":"result" marker -- should fall back to last JSON object
        String ndjson = "{\"duration_ms\":4000}\n"
            + "{\"duration_ms\":90000,\"cost_usd\":1.0}\n";

        String result = io.flowtree.JsonFieldExtractor.extractLastJsonObject(ndjson, "result");
        assertNotNull("Should fall back to last JSON object", result);
        assertEquals(90000, io.flowtree.JsonFieldExtractor.extractLong(result, "duration_ms"));
    }

    @Test(timeout = 10000)
    public void testExtractLastJsonObjectNullInput() {
        assertNull(io.flowtree.JsonFieldExtractor.extractLastJsonObject(null, "result"));
        assertNull(io.flowtree.JsonFieldExtractor.extractLastJsonObject("", "result"));
    }

    @Test(timeout = 10000)
    public void testExtractLastJsonObjectNullType() {
        // null type should return the very last JSON object
        String ndjson = "{\"a\":1}\n{\"b\":2}\n";
        String result = io.flowtree.JsonFieldExtractor.extractLastJsonObject(ndjson, null);
        assertNotNull(result);
        assertTrue("Should be the last line", result.contains("\"b\""));
    }

}
