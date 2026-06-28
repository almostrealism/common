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
import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.jobs.CodingAgentJobEvent;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.controller.FlowTreeController;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;

import static org.junit.Assert.*;

/**
 * Tests for the FlowTree orchestration and Slack integration components.
 *
 * <p>These tests verify the message parsing, workstream configuration,
 * notification formatting, and HTTP API without requiring a real Slack
 * connection or connected agents.</p>
 */
public class SlackIntegrationTest extends TestSuiteBase {

    /** Workstream configuration stores and retrieves channel, agents, branch, and budget. */
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

    /** Workstream getNextAgent round-robins across registered agents. */
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

    /** SlackListener extracts prompts from Slack message format. */
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

    /** SlackNotifier formats job started/success/failure messages correctly. */
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
        CodingAgentJobEvent failEvent = CodingAgentJobEvent.failed(
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

    // TODO(review): duplicate of SlackCostNotifierTest.testSlackCompletionWithCostBlock — remove this copy once the split is confirmed complete
    /** Slack completion message includes cost block with model and runner breakdown. */
    @Test(timeout = 10000)
    public void testSlackCompletionWithCostBlock() {
        List<String> messages = new ArrayList<>();
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            int start = json.indexOf("\"text\":\"") + 8;
            int end = json.indexOf("\"", start);
            if (start > 7 && end > start) {
                messages.add(json.substring(start, end).replace("\\n", "\n"));
            }
        });

        Workstream workstream = new Workstream("C_COST_1", "#cost-test");
        notifier.registerWorkstream(workstream);

        // Case 1: cost with both costByModel and costByRunner
        CodingAgentJobEvent eventWithCosts = CodingAgentJobEvent.success("job-cost-1", "Costly task");
        Map<String, Double> costByModel = new HashMap<>();
        costByModel.put("claude-opus-4-7", 1.50);
        costByModel.put("openrouter:qwen3-coder:exacto", 0.25);
        eventWithCosts.withCostByModel(costByModel);
        Map<String, Double> costByRunner = new HashMap<>();
        costByRunner.put("claude", 1.20);
        costByRunner.put("opencode", 0.55);
        eventWithCosts.withCostByRunner(costByRunner);
        eventWithCosts.withTimingInfo(0, 0, 1.75, 0);
        notifier.onJobCompleted(workstream.getWorkstreamId(), eventWithCosts);

        assertTrue(messages.size() > 0);
        String msg = messages.get(0);
        assertTrue("Slack completion should include moneybag cost block",
            msg.contains(":moneybag:"));
        assertTrue("Slack completion should include dollar total",
            msg.contains(":dollar:"));
        assertTrue("Slack completion should include per-model cost breakdown",
            msg.contains("claude-opus-4-7"));
        assertTrue("Slack completion should include runner breakdown",
            msg.contains("claude") && msg.contains("opencode"));
    }

    /** JobCompletionEvent builder produces correct status, git info, and error fields. */
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

        // Claude-specific fields require CodingAgentJobEvent
        CodingAgentJobEvent ccEvent = CodingAgentJobEvent.success("j4", "Done");
        ccEvent.withClaudeCodeInfo("Fix bug", "sess-1", 0);
        assertEquals("Fix bug", ccEvent.getPrompt());
        assertEquals("sess-1", ccEvent.getSessionId());
        assertEquals(0, ccEvent.getExitCode());
    }

    /** FlowTreeController simulateMessage dispatches to registered workstreams. */
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

        // Note: Full simulation test would require mocking the CodingAgentClient
        // which connects to actual agents. This tests the basic plumbing.
    }

    /** JobCompletionListener receives and accumulates job started/completed events. */
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

    /** FlowTreeApiEndpoint POST /messages delivers text to the correct channel. */
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

    /** FlowTreeApiEndpoint GET /api/health returns 200 with ISO-8601 server_time. */
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
            assertTrue("Response must include server_time field",
                    response.contains("\"server_time\""));
            // server_time must be ISO-8601 UTC (ends with Z)
            assertTrue("server_time must end with Z (UTC)",
                    response.matches(".*\"server_time\":\"[^\"]+Z\".*"));
        } finally {
            endpoint.stop();
        }
    }

    /** FlowTreeApiEndpoint POST /messages returns 400 when text field is missing. */
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

    /** FlowTreeApiEndpoint POST /submit returns 400 when prompt field is missing. */
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

    /** FlowTreeApiEndpoint POST /submit returns 400 for unknown workstream id. */
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

    /** FlowTreeApiEndpoint POST /submit returns 400 when no server is configured. */
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

    /** CodingAgentJob.Factory setWorkstreamUrl propagates to nextJob(). */
    @Test(timeout = 10000)
    public void testFactoryWorkstreamUrlConfiguration() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Test prompt");
        factory.setWorkstreamUrl("http://localhost:7780/api/workstreams/ws1/jobs/j1");

        assertEquals("http://localhost:7780/api/workstreams/ws1/jobs/j1", factory.getWorkstreamUrl());

        // Verify propagation to created job
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals("http://localhost:7780/api/workstreams/ws1/jobs/j1", job.getWorkstreamUrl());
    }

    // --- Slash command tests ---

    /** SlackListener /flowtree help returns command list. */
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

    /** SlackListener /flowtree unknown subcommand returns command list. */
    @Test(timeout = 10000)
    public void testSlashCommandUnknownSubcommand() {
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);

        AtomicReference<String> response = new AtomicReference<>();
        listener.handleSlashCommand("foobar", "C123", "#test", response::set);

        assertNotNull(response.get());
        assertTrue(response.get().contains("Flowtree Commands"));
    }

    /** SlackListener /flowtree setup creates a new workstream with correct defaults. */
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

    /** SlackListener /flowtree setup updates an existing workstream in place. */
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

    /** SlackListener /flowtree setup returns usage when arguments are missing. */
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

    /** SlackListener /flowtree info returns workstream details for registered channel. */
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

    /** SlackListener /flowtree info shows setup instructions when no workstream exists. */
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

    /** SlackListener /flowtree status returns agent status for registered workstream. */
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

    /** SlackListener /flowtree config with no args shows all settings for a workstream. */
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

    /** SlackListener /flowtree config key shows single setting value. */
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

    /** SlackListener /flowtree config key value updates workstream settings. */
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

    /** SlackListener /flowtree config rejects non-numeric values for numeric fields. */
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

    /** SlackListener /flowtree jobs lists recent jobs or shows empty state. */
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

    /** SlackListener /flowtree cancel currently returns not yet implemented. */
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

    /** SlackListener /flowtree setup persists new workstream to YAML config file. */
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

    /**
     * Regression test for the null-channel archive-persistence defect. A
     * workstream with no channelId is absent from {@code channelToWorkstream},
     * so the old {@code persistConfig} — which synced only that map — never
     * wrote the workstream's state changes (such as an archive). It now
     * snapshots every registered workstream, so the archived flag is durable.
     */
    @Test(timeout = 10000)
    public void testArchivePersistsForNullChannelWorkstream() throws IOException {
        File tempFile = File.createTempFile("workstreams-nullchan", ".yaml");
        tempFile.deleteOnExit();
        String yaml = "workstreams:\n"
            + "  - workstreamId: \"ws-nochan\"\n"
            + "    channelName: \"#no-channel\"\n"
            + "    defaultBranch: \"main\"\n";
        Files.write(tempFile.toPath(), yaml.getBytes());

        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(tempFile);
        SlackNotifier notifier = new SlackNotifier(null);
        SlackListener listener = new SlackListener(notifier);
        listener.setWorkstreamConfig(config, tempFile);
        for (Workstream ws : config.toWorkstreams()) {
            listener.registerWorkstream(ws);
        }

        Workstream live = notifier.getWorkstream("ws-nochan");
        assertNotNull("fixture workstream must be registered", live);
        assertNull("fixture must have no channelId", live.getChannelId());
        live.setArchived(true);
        assertTrue("persist must report success", listener.persistConfig());

        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
        WorkstreamConfig.WorkstreamEntry entry = null;
        for (WorkstreamConfig.WorkstreamEntry e : reloaded.getWorkstreams()) {
            if ("ws-nochan".equals(e.getWorkstreamId())) {
                entry = e;
                break;
            }
        }
        assertNotNull(entry);
        assertTrue("archived flag must persist for a channel-less workstream",
                entry.isArchived());
    }

    /** SlackNotifier tracks and updates job lifecycle events per workstream. */
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

    /** SlackNotifier findWorkstreamByBranch matches workstream by target branch. */
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

    /** FlowTreeApiEndpoint resolves workstreamId from body.targetBranch before URL path. */
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

    /** Slack app manifest includes slash_commands and /flowtree command. */
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
    /** JsonFieldExtractor.extractLastJsonObject finds result line in Claude Code NDJSON output. */
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

    /** JsonFieldExtractor.extractLastJsonObject falls back to last line when no type marker. */
    @Test(timeout = 10000)
    public void testExtractLastJsonObjectFallsBackToLastLine() {
        // No "type":"result" marker -- should fall back to last JSON object
        String ndjson = "{\"duration_ms\":4000}\n"
            + "{\"duration_ms\":90000,\"cost_usd\":1.0}\n";

        String result = JsonFieldExtractor.extractLastJsonObject(ndjson, "result");
        assertNotNull("Should fall back to last JSON object", result);
        assertEquals(90000, JsonFieldExtractor.extractLong(result, "duration_ms"));
    }

    /** JsonFieldExtractor.extractLastJsonObject returns null for null and empty input. */
    @Test(timeout = 10000)
    public void testExtractLastJsonObjectNullInput() {
        assertNull(JsonFieldExtractor.extractLastJsonObject(null, "result"));
        assertNull(JsonFieldExtractor.extractLastJsonObject("", "result"));
    }

    /** JsonFieldExtractor.extractLastJsonObject with null type returns the last JSON object. */
    @Test(timeout = 10000)
    public void testExtractLastJsonObjectNullType() {
        // null type should return the very last JSON object
        String ndjson = "{\"a\":1}\n{\"b\":2}\n";
        String result = JsonFieldExtractor.extractLastJsonObject(ndjson, null);
        assertNotNull(result);
        assertTrue("Should be the last line", result.contains("\"b\""));
    }

    /**
     * Tests that postMessage and postMessageInThread gracefully handle null channelId.
     */
    @Test(timeout = 10000)
    public void testPostMessageNullChannelId() {
        SlackNotifier notifier = new SlackNotifier(null);

        assertNull(notifier.postMessage(null, "test"));
        assertNull(notifier.postMessage("", "test"));
        assertNull(notifier.postMessageInThread(null, "test", "thread123"));
        assertNull(notifier.postMessageInThread("", "test", "thread123"));
    }

    /**
     * Tests that a workstream created without a channelId can be registered
     * and that job notifications are silently skipped without throwing.
     */
    @Test(timeout = 10000)
    public void testChannellessWorkstreamJobNotification() {
        SlackNotifier notifier = new SlackNotifier(null);

        Workstream workstream = new Workstream(null, "w-test");
        workstream.setDefaultBranch("project/test");
        notifier.registerWorkstream(workstream);

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
}

