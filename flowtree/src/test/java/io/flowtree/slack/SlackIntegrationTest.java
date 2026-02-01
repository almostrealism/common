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
import io.flowtree.jobs.JobCompletionListener;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the Slack integration components.
 *
 * <p>These tests verify the message parsing, workstream configuration,
 * and notification formatting without requiring a real Slack connection.</p>
 */
public class SlackIntegrationTest extends TestSuiteBase {

    @Test
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

    @Test
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

    @Test
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

    @Test
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
            "job-123", workstream.getWorkstreamId(), "Fix authentication bug"
        );
        notifier.onJobStarted(startEvent);

        assertTrue(messages.size() > 0);
        String startMsg = messages.get(0);
        assertTrue(startMsg.contains("Starting work"));
        assertTrue(startMsg.contains("Fix authentication bug"));

        messages.clear();

        // Test success notification
        JobCompletionEvent successEvent = JobCompletionEvent.success(
            "job-123", workstream.getWorkstreamId(), "Fix authentication bug"
        );
        successEvent.withGitInfo("feature/test", "abc1234567890",
            List.of("auth.py", "tests/test_auth.py"), List.of(), true);
        notifier.onJobCompleted(successEvent);

        assertTrue(messages.size() > 0);
        String successMsg = messages.get(0);
        assertTrue(successMsg.contains("Work complete"));
        assertTrue(successMsg.contains("abc1234")); // Shortened commit hash
        assertTrue(successMsg.contains("feature/test"));

        messages.clear();

        // Test failure notification
        JobCompletionEvent failEvent = JobCompletionEvent.failed(
            "job-456", workstream.getWorkstreamId(), "Build the thing",
            "Compilation failed", new RuntimeException("Syntax error")
        );
        failEvent.withClaudeCodeInfo("Build the thing", "session-789", 1);
        notifier.onJobCompleted(failEvent);

        assertTrue(messages.size() > 0);
        String failMsg = messages.get(0);
        assertTrue(failMsg.contains("Work failed"));
        assertTrue(failMsg.contains("Compilation failed"));
    }

    @Test
    public void testJobCompletionEvent() {
        // Test started event
        JobCompletionEvent started = JobCompletionEvent.started("j1", "w1", "Test task");
        assertEquals(JobCompletionEvent.Status.STARTED, started.getStatus());
        assertEquals("j1", started.getJobId());
        assertEquals("w1", started.getWorkstreamId());

        // Test success event
        JobCompletionEvent success = JobCompletionEvent.success("j2", "w1", "Done");
        assertEquals(JobCompletionEvent.Status.SUCCESS, success.getStatus());

        // Test failed event
        Exception ex = new RuntimeException("Test error");
        JobCompletionEvent failed = JobCompletionEvent.failed("j3", "w1", "Failed", "Test error", ex);
        assertEquals(JobCompletionEvent.Status.FAILED, failed.getStatus());
        assertEquals("Test error", failed.getErrorMessage());
        assertEquals(ex, failed.getException());

        // Test builder methods
        success.withGitInfo("main", "abc123", List.of("a.txt"), List.of("b.bin"), true);
        assertEquals("main", success.getTargetBranch());
        assertEquals("abc123", success.getCommitHash());
        assertEquals(1, success.getStagedFiles().size());
        assertTrue(success.isPushed());

        success.withClaudeCodeInfo("Fix bug", "sess-1", 0);
        assertEquals("Fix bug", success.getPrompt());
        assertEquals("sess-1", success.getSessionId());
        assertEquals(0, success.getExitCode());
    }

    @Test
    public void testControllerSimulation() throws Exception {
        SlackBotController controller = new SlackBotController(null, null);

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

    @Test
    public void testCompletionListenerInterface() {
        List<JobCompletionEvent> events = new ArrayList<>();

        JobCompletionListener listener = new JobCompletionListener() {
            @Override
            public void onJobCompleted(JobCompletionEvent event) {
                events.add(event);
            }

            @Override
            public void onJobStarted(JobCompletionEvent event) {
                events.add(event);
            }
        };

        // Fire events
        listener.onJobStarted(JobCompletionEvent.started("j1", "w1", "Starting"));
        listener.onJobCompleted(JobCompletionEvent.success("j1", "w1", "Done"));

        assertEquals(2, events.size());
        assertEquals(JobCompletionEvent.Status.STARTED, events.get(0).getStatus());
        assertEquals(JobCompletionEvent.Status.SUCCESS, events.get(1).getStatus());
    }

    @Test
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

    @Test
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

    @Test
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
        assertEquals(50, entry.getMaxTurns()); // default turns
        assertEquals(10.0, entry.getMaxBudgetUsd(), 0.001); // default budget
        assertTrue(entry.isPushToOrigin()); // default push
        assertEquals("Read,Edit,Write,Bash,Glob,Grep", entry.getAllowedTools()); // default tools
    }
}
