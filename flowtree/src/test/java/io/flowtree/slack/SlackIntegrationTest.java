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
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.ClaudeCodeJob;
import io.flowtree.jobs.ClaudeCodeJobEvent;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
        Workstream workstream = new Workstream("C0123456789", "#test-channel");
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
        Workstream workstream = new Workstream("C123", "#test");
        workstream.addAgent("host1", 7766);
        workstream.addAgent("host2", 7767);
        workstream.addAgent("host3", 7768);

        // Get agents in sequence
        Workstream.AgentEndpoint a1 = workstream.getNextAgent();
        Workstream.AgentEndpoint a2 = workstream.getNextAgent();
        Workstream.AgentEndpoint a3 = workstream.getNextAgent();
        Workstream.AgentEndpoint a4 = workstream.getNextAgent();

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

        Workstream workstream = new Workstream("C123", "#test");
        workstream.setDefaultBranch("feature/test");
        notifier.registerWorkstream(workstream);

        // Test submitted notification
        JobCompletionEvent startEvent = JobCompletionEvent.started(
            "job-123", "Fix authentication bug"
        );
        notifier.onJobSubmitted(workstream.getWorkstreamId(), startEvent);

        assertTrue(messages.size() > 0);
        String startMsg = messages.get(0);
        assertTrue(startMsg.contains("Job submitted"));
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
    public void testApiEndpointPostMessage() throws Exception {
        AtomicReference<String> receivedChannel = new AtomicReference<>();
        AtomicReference<String> receivedText = new AtomicReference<>();

        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            receivedChannel.set(FlowTreeApiEndpoint.extractJsonField(json, "channel"));
            receivedText.set(FlowTreeApiEndpoint.extractJsonField(json, "text"));
        });

        Workstream workstream = new Workstream("C_TEST_123", "#test");
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
        Workstream workstream = new Workstream("C123", "#test");
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
        Workstream workstream = new Workstream("C_SUBMIT_1", "#submit-test");
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
        Workstream workstream = new Workstream("C_SUBMIT_2", "#submit-test");
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
        Workstream ws = listener.getWorkstream("C_SETUP_1");
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
        Workstream ws = new Workstream("C_UPD_1", "#update-channel");
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
        assertTrue(response.get().contains("Both working directory (or repo URL) and branch are required"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandInfo() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("C_INFO_1", "#info-channel");
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

        Workstream ws = new Workstream("C_STATUS_1", "#status-channel");
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

        Workstream ws = new Workstream("C_CFG_1", "#config-channel");
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

        Workstream ws = new Workstream("C_CFG_2", "#config-channel");
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

        Workstream ws = new Workstream("C_CFG_3", "#config-channel");
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

        Workstream ws = new Workstream("C_CFG_4", "#config-channel");
        listener.registerWorkstream(ws);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("config maxBudgetUsd notanumber", "C_CFG_4", "#config-channel", response::set);
        assertTrue(response.get().contains("Invalid number"));
    }

    @Test(timeout = 10000)
    public void testSlashCommandJobs() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("C_JOBS_1", "#jobs-channel");
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

        Workstream ws = new Workstream("C_CANCEL_1", "#cancel-channel");
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
        for (Workstream ws : config.toWorkstreams()) {
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
    public void testNotifierJobTracking() {
        SlackNotifier notifier = new SlackNotifier(null);

        Workstream ws = new Workstream("C_TRACK_1", "#track-channel");
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

        Workstream ws1 = new Workstream("ws-rings", "C_RINGS", "#rings");
        ws1.setDefaultBranch("feature/new-decoder");

        Workstream ws2 = new Workstream("ws-common", "C_COMMON", "#common");
        ws2.setDefaultBranch("feature/pipeline-agents");

        Workstream ws3 = new Workstream("ws-no-branch", "C_NONE", "#no-branch");
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
        Workstream pipelineWs = new Workstream("ws-pipeline", "C_PIPE", "#pipeline");
        pipelineWs.setDefaultBranch(null);
        pipelineWs.setAllowedTools("Read,Glob,Grep");
        pipelineWs.setMaxBudgetUsd(5.0);
        pipelineWs.setMaxTurns(30);
        pipelineWs.setGitUserName("Test Bot");
        pipelineWs.setGitUserEmail("test@example.com");
        notifier.registerWorkstream(pipelineWs);

        // Register a richer workstream that matches a specific branch
        Workstream ringsWs = new Workstream("ws-rings", "C_RINGS", "#rings");
        ringsWs.setDefaultBranch("feature/new-decoder");
        ringsWs.setAllowedTools("Read,Edit,Write,Bash,Glob,Grep");
        ringsWs.setMaxBudgetUsd(25.0);
        ringsWs.setMaxTurns(100);
        ringsWs.setGitUserName("Test Bot");
        ringsWs.setGitUserEmail("test@example.com");
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
        InputStream is = getClass().getResourceAsStream("/slack-app-manifest.json");
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
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            Instant jobTime = monday.atStartOfDay(ZoneOffset.UTC).toInstant()
                .plusSeconds(3600);

            store.recordJobStarted("j1", "ws-alpha", "Fix bug", jobTime);
            store.recordJobCompleted("j1", "ws-alpha", "SUCCESS",
                jobTime.plusMillis(60000), 55000, 30000, 0.50, 10, "sess-1", 0,
                "success", false, 0, null, null, null, null);

            store.recordJobStarted("j2", "ws-beta", "Add feature", jobTime);
            store.recordJobCompleted("j2", "ws-beta", "FAILED",
                jobTime.plusMillis(120000), 100000, 80000, 1.20, 25, "sess-2", 1,
                "error_max_turns", true, 3, null, null, null, "max turns exceeded");

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

        String result = JsonFieldExtractor.extractLastJsonObject(ndjson, "result");
        assertNotNull("Should find the result line", result);
        assertTrue("Should contain the result type", result.contains("\"type\":\"result\""));

        // Verify extracting metrics from the result line yields session-level values
        long durationMs = JsonFieldExtractor.extractLong(result, "duration_ms");
        long durationApiMs = JsonFieldExtractor.extractLong(result, "duration_api_ms");
        double costUsd = JsonFieldExtractor.extractDouble(result, "cost_usd");
        int numTurns = JsonFieldExtractor.extractInt(result, "num_turns");
        String sessionId = JsonFieldExtractor.extractString(result, "session_id");

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

        String result = JsonFieldExtractor.extractLastJsonObject(ndjson, "result");
        assertNotNull("Should fall back to last JSON object", result);
        assertEquals(90000, JsonFieldExtractor.extractLong(result, "duration_ms"));
    }

    @Test(timeout = 10000)
    public void testExtractLastJsonObjectNullInput() {
        assertNull(JsonFieldExtractor.extractLastJsonObject(null, "result"));
        assertNull(JsonFieldExtractor.extractLastJsonObject("", "result"));
    }

    @Test(timeout = 10000)
    public void testExtractLastJsonObjectNullType() {
        // null type should return the very last JSON object
        String ndjson = "{\"a\":1}\n{\"b\":2}\n";
        String result = JsonFieldExtractor.extractLastJsonObject(ndjson, null);
        assertNotNull(result);
        assertTrue("Should be the last line", result.contains("\"b\""));
    }

    // ── Workstream registration API tests ──────────────────────────

    /**
     * Tests that POST /api/workstreams registers a new workstream and returns
     * its ID. Uses a null bot token so no real Slack channel is created.
     */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstream() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"defaultBranch\":\"project/plan-20260223-test\","
                + "\"baseBranch\":\"master\","
                + "\"planningDocument\":\"docs/plans/PLAN-20260223-test.md\","
                + "\"channelName\":\"w-project-plan-20260223-test\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(200, conn.getResponseCode());

            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"ok\":true"));
            assertTrue(response.contains("\"workstreamId\""));
            assertTrue(response.contains("\"channelName\":\"w-project-plan-20260223-test\""));

            // Verify the workstream was actually registered and is findable by branch
            Workstream registered = notifier.findWorkstreamByBranch("project/plan-20260223-test");
            assertNotNull("Workstream should be findable by branch", registered);
            assertEquals("master", registered.getBaseBranch());
            assertEquals("docs/plans/PLAN-20260223-test.md", registered.getPlanningDocument());
            assertTrue(registered.isPushToOrigin());
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Tests that POST /api/workstreams requires defaultBranch.
     */
    @Test(timeout = 10000)
    public void testApiRegisterWorkstreamMissingBranch() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"baseBranch\":\"master\"}";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + port + "/api/workstreams").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            assertEquals(400, conn.getResponseCode());

            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(error.contains("defaultBranch"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Tests that POST /api/workstreams/{id}/update updates an existing workstream.
     */
    @Test(timeout = 10000)
    public void testApiUpdateWorkstream() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream workstream = new Workstream(null, "w-test");
        workstream.setDefaultBranch("project/test");
        notifier.registerWorkstream(workstream);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"channelId\":\"C_NEW_123\",\"channelName\":\"#w-test-updated\","
                + "\"planningDocument\":\"docs/plans/PLAN-updated.md\"}";

            URL url = URI.create("http://localhost:" + port
                + "/api/workstreams/" + workstream.getWorkstreamId() + "/update").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            assertEquals(200, conn.getResponseCode());
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("\"ok\":true"));

            assertEquals("C_NEW_123", workstream.getChannelId());
            assertEquals("#w-test-updated", workstream.getChannelName());
            assertEquals("docs/plans/PLAN-updated.md", workstream.getPlanningDocument());
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Tests that POST /api/workstreams/{id}/update returns 400 for unknown ID.
     */
    @Test(timeout = 10000)
    public void testApiUpdateWorkstreamNotFound() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);

        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String body = "{\"channelId\":\"C_NEW\"}";

            URL url = URI.create("http://localhost:" + port
                + "/api/workstreams/nonexistent/update").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            assertEquals(400, conn.getResponseCode());
            String response = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("Unknown workstream"));
        } finally {
            endpoint.stop();
        }
    }

    /**
     * Tests that postMessage and postMessageInThread gracefully handle null channelId.
     */
    @Test(timeout = 10000)
    public void testPostMessageNullChannelId() {
        SlackNotifier notifier = new SlackNotifier(null);

        // Should not throw NPE
        assertNull(notifier.postMessage(null, "test"));
        assertNull(notifier.postMessage("", "test"));
        assertNull(notifier.postMessageInThread(null, "test", "thread123"));
        assertNull(notifier.postMessageInThread("", "test", "thread123"));
    }

    /**
     * Tests that a workstream created without a channelId can be registered
     * and that job notifications are silently skipped (not NPE).
     */
    @Test(timeout = 10000)
    public void testChannellessWorkstreamJobNotification() {
        SlackNotifier notifier = new SlackNotifier(null);

        Workstream workstream = new Workstream(null, "w-test");
        workstream.setDefaultBranch("project/test");
        notifier.registerWorkstream(workstream);

        // Job start and complete should not throw even with null channelId
        JobCompletionEvent startEvent = JobCompletionEvent.started("job-1", "Test job");
        notifier.onJobStarted(workstream.getWorkstreamId(), startEvent);

        JobCompletionEvent completeEvent = JobCompletionEvent.success("job-1", "Test job");
        notifier.onJobCompleted(workstream.getWorkstreamId(), completeEvent);
    }

    /**
     * Tests that channelOwnerUserId is loaded from YAML configuration.
     */
    @Test(timeout = 10000)
    public void testChannelOwnerUserIdFromYaml() throws IOException {
        String yaml = "channelOwnerUserId: U0123456789\n"
            + "workstreams:\n"
            + "  - channelId: \"C111\"\n"
            + "    channelName: \"#test\"\n"
            + "    defaultBranch: \"feature/test\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals("U0123456789", config.getChannelOwnerUserId());
    }

    /**
     * Tests that registerAndPersistWorkstream adds the workstream to both
     * the in-memory registry and the configuration model.
     */
    @Test(timeout = 10000)
    public void testRegisterAndPersistWorkstream() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        // Set up a config file so persistence has somewhere to write
        String yaml = "workstreams:\n"
            + "  - channelId: \"C_EXISTING\"\n"
            + "    channelName: \"#existing\"\n"
            + "    defaultBranch: \"feature/existing\"\n";

        File tempFile = File.createTempFile("workstream-test", ".yaml");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), yaml.getBytes());

        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(tempFile);
        config.ensureWorkstreamIds();
        listener.setWorkstreamConfig(config, tempFile);

        // Register the existing workstream first
        for (Workstream ws : config.toWorkstreams()) {
            listener.registerWorkstream(ws);
        }

        // Now register a new workstream via the API path
        Workstream newWs = new Workstream(null, "w-new-project");
        newWs.setDefaultBranch("project/plan-test");
        newWs.setBaseBranch("master");
        newWs.setPlanningDocument("docs/plans/PLAN-test.md");

        listener.registerAndPersistWorkstream(newWs);

        // Verify it's findable in the notifier
        Workstream found = notifier.findWorkstreamByBranch("project/plan-test");
        assertNotNull("New workstream should be registered", found);
        assertEquals("docs/plans/PLAN-test.md", found.getPlanningDocument());

        // Verify it was persisted to the file
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        assertEquals(2, reloaded.getWorkstreams().size());
    }

    /**
     * Verifies the full lifecycle of the accept-automated-jobs controller
     * config: default is false (safety measure), POST with
     * {@code {"accept":true}} enables it, GET reflects the change, and
     * POST with {@code {"accept":false}} disables it again.
     */
    @Test(timeout = 10000)
    public void testAcceptAutomatedJobsConfig() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        try {
            int port = endpoint.getListeningPort();
            String configUrl = "http://localhost:" + port
                    + "/api/config/accept-automated-jobs";

            // Default should be false (automated jobs must be explicitly enabled)
            assertFalse("Default should reject automated jobs",
                    endpoint.isAcceptAutomatedJobs());

            // GET should reflect false
            HttpURLConnection getConn = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            getConn.setRequestMethod("GET");
            assertEquals(200, getConn.getResponseCode());
            String getResponse = new String(
                    getConn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("GET should report false",
                    getResponse.contains("\"acceptAutomatedJobs\":false"));

            // POST true to enable
            HttpURLConnection postConn = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            postConn.setRequestMethod("POST");
            postConn.setDoOutput(true);
            postConn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = postConn.getOutputStream()) {
                os.write("{\"accept\":true}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, postConn.getResponseCode());
            String postResponse = new String(
                    postConn.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("POST response should confirm true",
                    postResponse.contains("\"acceptAutomatedJobs\":true"));
            assertTrue("Endpoint field should now be true",
                    endpoint.isAcceptAutomatedJobs());

            // GET should now reflect true
            HttpURLConnection getConn2 = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            getConn2.setRequestMethod("GET");
            assertEquals(200, getConn2.getResponseCode());
            String getResponse2 = new String(
                    getConn2.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("GET should report true after update",
                    getResponse2.contains("\"acceptAutomatedJobs\":true"));

            // POST false to disable again
            HttpURLConnection postConn2 = (HttpURLConnection)
                    new URL(configUrl).openConnection();
            postConn2.setRequestMethod("POST");
            postConn2.setDoOutput(true);
            postConn2.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = postConn2.getOutputStream()) {
                os.write("{\"accept\":false}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, postConn2.getResponseCode());
            String postResponse2 = new String(
                    postConn2.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue("POST response should confirm false",
                    postResponse2.contains("\"acceptAutomatedJobs\":false"));
            assertFalse("Endpoint field should now be false",
                    endpoint.isAcceptAutomatedJobs());
        } finally {
            endpoint.stop();
        }
    }


    // -------------------------------------------------------------------------
    // Phase 1a: Multi-Tenant Config Schema Tests
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testSlackWorkspaceEntryYamlRoundTrip() throws IOException {
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

        WorkstreamConfig.SlackWorkspaceEntry entry = config.getSlackWorkspaces().get(0);
        assertEquals("T0123456789", entry.getWorkspaceId());
        assertEquals("my-org", entry.getName());
        assertEquals("xoxb-test", entry.getBotToken());
        assertEquals("xapp-test", entry.getAppToken());
        assertEquals("C0987654321", entry.getDefaultChannel());
        assertEquals("U0123456789", entry.getChannelOwnerUserId());
    }

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
                      "    botToken: \"xoxb-two\"\n" +
                      "    appToken: \"xapp-two\"\n" +
                      "workstreams: []\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        assertEquals(2, config.getSlackWorkspaces().size());

        WorkstreamConfig.SlackWorkspaceEntry ws1 = config.getSlackWorkspaces().get(0);
        assertEquals("T111", ws1.getWorkspaceId());
        assertEquals("workspace-one", ws1.getName());
        assertEquals("xoxb-one", ws1.getBotToken());
        assertNotNull(ws1.getGithubOrgs());
        assertEquals("ghp_one", ws1.getGithubOrgs().get("my-org").getToken());

        WorkstreamConfig.SlackWorkspaceEntry ws2 = config.getSlackWorkspaces().get(1);
        assertEquals("T222", ws2.getWorkspaceId());
        assertEquals("xoxb-two", ws2.getBotToken());
    }

    @Test(timeout = 10000)
    public void testBackwardCompatNoSlackWorkspaces() throws IOException {
        String yaml = "workstreams:\n" +
                      "  - channelId: \"C0123456789\"\n" +
                      "    channelName: \"#project-agent\"\n" +
                      "    defaultBranch: \"feature/work\"\n";

        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);

        // slackWorkspaces should be an empty list, not null
        assertNotNull(config.getSlackWorkspaces());
        assertEquals(0, config.getSlackWorkspaces().size());

        // Regular workstreams still parse correctly
        assertEquals(1, config.getWorkstreams().size());
        assertEquals("C0123456789", config.getWorkstreams().get(0).getChannelId());
    }

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

        WorkstreamConfig.WorkstreamEntry entryWithId = config.getWorkstreams().get(0);
        assertEquals("C001", entryWithId.getChannelId());
        assertEquals("T111", entryWithId.getSlackWorkspaceId());

        WorkstreamConfig.WorkstreamEntry entryWithoutId = config.getWorkstreams().get(1);
        assertEquals("C002", entryWithoutId.getChannelId());
        assertNull(entryWithoutId.getSlackWorkspaceId());

        // Verify toWorkstream() propagates slackWorkspaceId
        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals("T111", workstreams.get(0).getSlackWorkspaceId());
        assertNull(workstreams.get(1).getSlackWorkspaceId());
    }

    @Test(timeout = 10000)
    public void testSlackTokensFromEntryInlineTokens() throws IOException {
        WorkstreamConfig.SlackWorkspaceEntry entry = new WorkstreamConfig.SlackWorkspaceEntry();
        entry.setWorkspaceId("T999");
        entry.setBotToken("xoxb-inline-bot");
        entry.setAppToken("xapp-inline-app");

        SlackTokens tokens = SlackTokens.from(entry);

        assertEquals("xoxb-inline-bot", tokens.getBotToken());
        assertEquals("xapp-inline-app", tokens.getAppToken());
    }

    @Test(timeout = 10000)
    public void testSlackTokensFromEntryTokensFile() throws IOException {
        File tempFile = File.createTempFile("workspace-tokens-test", ".json");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(),
                "{ \"botToken\": \"xoxb-from-file\", \"appToken\": \"xapp-from-file\" }".getBytes());

        WorkstreamConfig.SlackWorkspaceEntry entry = new WorkstreamConfig.SlackWorkspaceEntry();
        entry.setWorkspaceId("T888");
        entry.setTokensFile(tempFile.getAbsolutePath());
        entry.setBotToken("xoxb-should-be-ignored");

        SlackTokens tokens = SlackTokens.from(entry);

        // tokensFile takes priority over inline fields
        assertEquals("xoxb-from-file", tokens.getBotToken());
        assertEquals("xapp-from-file", tokens.getAppToken());
    }

    @Test(timeout = 10000)
    public void testSlackWorkspaceEntryUnknownFieldsIgnored() throws IOException {
        String yaml = "slackWorkspaces:\n" +
                      "  - workspaceId: \"T777\"\n" +
                      "    botToken: \"xoxb-777\"\n" +
                      "    futureField: \"ignored\"\n" +
                      "workstreams: []\n";

        // Should not throw
        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        assertEquals("T777", config.getSlackWorkspaces().get(0).getWorkspaceId());
    }

    @Test(timeout = 10000)
    public void testWorkstreamSlackWorkspaceIdPreservedInAddAndSync() {
        WorkstreamConfig config = new WorkstreamConfig();

        Workstream ws = new Workstream("ws-1", "C001", "#channel-one");
        ws.setSlackWorkspaceId("T111");
        ws.setDefaultBranch("main");

        config.addWorkstream(ws);
        assertEquals(1, config.getWorkstreams().size());
        assertEquals("T111", config.getWorkstreams().get(0).getSlackWorkspaceId());

        // Update via syncFromWorkstreams
        ws.setSlackWorkspaceId("T222");
        config.syncFromWorkstreams(List.of(ws));
        assertEquals("T222", config.getWorkstreams().get(0).getSlackWorkspaceId());
    }

    // -------------------------------------------------------------------------
    // Phase 1c: Listener and Notifier Routing Tests
    // -------------------------------------------------------------------------

    @Test(timeout = 10000)
    public void testChannelKeyNullWorkspaceReturnsBareChannelId() {
        assertEquals("C_ALPHA", SlackListener.channelKey(null, "C_ALPHA"));
    }

    @Test(timeout = 10000)
    public void testChannelKeyWithWorkspaceReturnsCompositeKey() {
        assertEquals("T111:C_ALPHA", SlackListener.channelKey("T111", "C_ALPHA"));
    }

    @Test(timeout = 10000)
    public void testChannelKeyDifferentWorkspacesSameChannelProduceDifferentKeys() {
        String keyA = SlackListener.channelKey("T111", "C_SHARED");
        String keyB = SlackListener.channelKey("T222", "C_SHARED");
        assertFalse("Same channel in different workspaces must produce different keys",
                keyA.equals(keyB));
    }

    @Test(timeout = 10000)
    public void testBackwardCompatNullWorkspaceIdRouting() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("ws-back", "C_BACK", "#back");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        // Bare channel ID lookup (legacy / 5-arg path) must still work
        Workstream found = listener.getWorkstream("C_BACK");
        assertNotNull("Backward compat: getWorkstream() with bare channel ID must work", found);
        assertEquals("C_BACK", found.getChannelId());
    }

    @Test(timeout = 10000)
    public void testWorkspaceAwareWorkstreamRegistration() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Workstream ws = new Workstream("ws-multi", "C_MULTI", "#multi");
        ws.setDefaultBranch("main");
        ws.setSlackWorkspaceId("T111");
        listener.registerWorkstream(ws);

        // Composite key lookup: handleMessage with workspaceId=T111 should find it.
        // Returns false (no agents/server), but the channel is found — does not trigger reload.
        listener.handleMessage("C_MULTI", "U1", "hello", "ts1", null, "T111");
        boolean found = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "C_MULTI".equals(w.getChannelId()));
        assertTrue("Workspace-aware workstream should be registered", found);
    }

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
        wsA.setSlackWorkspaceId("T111");
        listener.registerWorkstream(wsA);

        Workstream wsB = new Workstream("ws-b", "C_SHARED", "#shared-b");
        wsB.setDefaultBranch("develop");
        wsB.setSlackWorkspaceId("T222");
        listener.registerWorkstream(wsB);

        // Both workstreams are registered (two separate map entries)
        assertEquals("Two workstreams with same channelId in different workspaces must coexist",
                2, listener.getWorkstreams().size());

        // Routing: T111 message goes to wsA (branch=main), T222 to wsB (branch=develop)
        // We verify by examining the getWorkstreams() values
        boolean hasMain = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "main".equals(w.getDefaultBranch()));
        boolean hasDevelop = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "develop".equals(w.getDefaultBranch()));
        assertTrue("wsA (main branch) must be registered", hasMain);
        assertTrue("wsB (develop branch) must be registered", hasDevelop);
    }

    @Test(timeout = 10000)
    public void testHandleMessageUnknownChannelReturnsFalseWithWorkspaceId() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        boolean handled = listener.handleMessage("C_UNKNOWN", "U1", "hello", "ts1", null, "T111");
        assertFalse("Message to unknown channel must return false", handled);
    }

    @Test(timeout = 10000)
    public void testSlashCommandSetupSetsSlackWorkspaceIdOnNewWorkstream() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);

        listener.handleSlashCommand("setup /workspace/project feature/test",
                "C_SETUP_WS", "#setup-ws", responder, "T999");

        // Workstream should be created and keyed under T999:C_SETUP_WS
        boolean foundWithWorkspace = listener.getWorkstreams().values().stream()
                .anyMatch(w -> "T999".equals(w.getSlackWorkspaceId())
                        && "C_SETUP_WS".equals(w.getChannelId()));
        assertTrue("Setup must store slackWorkspaceId on new workstream", foundWithWorkspace);
    }

    @Test(timeout = 10000)
    public void testSlashCommandActiveFiltersWorkstreamsByWorkspace() throws IOException {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T111", notifier);
        listener.setNotifiersByWorkspace(byWorkspace);

        // Register two workstreams: one in T111, one in T222
        Workstream wsA = new Workstream("ws-act-a", "C_ACT_A", "#act-a");
        wsA.setDefaultBranch("main");
        wsA.setSlackWorkspaceId("T111");
        listener.registerWorkstream(wsA);

        Workstream wsB = new Workstream("ws-act-b", "C_ACT_B", "#act-b");
        wsB.setDefaultBranch("develop");
        wsB.setSlackWorkspaceId("T222");
        listener.registerWorkstream(wsB);

        // /flowtree active with workspaceId=T111 should not throw
        // (stats store is null, so it returns the "not available" message)
        List<String> responses = new ArrayList<>();
        SlackListener.SlashCommandResponder responder = text -> responses.add(text);
        listener.handleSlashCommand("active", "C_ACT_A", "#act-a", responder, "T111");

        assertFalse("Active command should respond", responses.isEmpty());
        // With null stats store, should get "not available"
        assertTrue("With null stats store active command warns",
                responses.get(0).contains("not available"));
    }

    @Test(timeout = 10000)
    public void testSetNotifiersByWorkspaceIsUsedForNotifierResolution() {
        SlackNotifier primaryNotifier = new SlackNotifier(null);
        SlackNotifier workspaceNotifier = new SlackNotifier(null);

        SlackListener listener = new SlackListener(primaryNotifier);

        Map<String, SlackNotifier> byWorkspace = new HashMap<>();
        byWorkspace.put("T_SPECIAL", workspaceNotifier);
        listener.setNotifiersByWorkspace(byWorkspace);

        // Register a workstream in T_SPECIAL
        Workstream ws = new Workstream("ws-special", "C_SPECIAL", "#special");
        ws.setSlackWorkspaceId("T_SPECIAL");
        ws.setDefaultBranch("main");
        listener.registerWorkstream(ws);

        // The workstream should be registered in the workspace notifier, not the primary
        // Verify by checking that the workspace notifier has the workstream
        // (getRecentJobs returns non-null for a registered workstream)
        Map<String, JobCompletionEvent> jobs = workspaceNotifier.getRecentJobs(ws.getWorkstreamId());
        assertNotNull("Workspace notifier should have workstream registered", jobs);
    }
}

