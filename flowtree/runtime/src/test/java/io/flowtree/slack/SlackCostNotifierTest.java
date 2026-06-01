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
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.jobs.CodingAgentJobEvent;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
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

import static org.junit.Assert.*;

/**
 * Tests for {@link SlackNotifier} cost reporting, job completion formatting,
 * and Slack API message broadcasting behavior.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Cost breakdown blocks (per-model and per-runner) in completion messages</li>
 *   <li>Stats API endpoint responses (weekly aggregates, period filtering)</li>
 *   <li>Thread timestamp fallback to {@link JobStatsStore} after job completion</li>
 *   <li>reply_broadcast flag behavior on completion vs. status messages</li>
 * </ul>
 */
public class SlackCostNotifierTest extends TestSuiteBase {

    /**
     * Verifies that a job completion notification with cost data includes a cost block
     * containing per-model and per-runner breakdowns in the Slack message.
     */
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
            msg.contains(":dollar:") || msg.contains("$"));
        assertTrue("Slack completion should include per-model cost breakdown",
            msg.contains("claude-opus-4-7"));
        assertTrue("Slack completion should include runner breakdown",
            msg.contains("claude") && msg.contains("opencode"));
    }

    /**
     * Verifies that the stats API endpoint returns aggregated weekly job stats
     * including per-workstream data, and that workstream filtering works correctly.
     */
    @Test(timeout = 10000)
    public void testApiStatsEndpoint() throws Exception {
        File tempDir = Files.createTempDirectory("stats-test").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();

        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();

        try {
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

    /**
     * Verifies that the stats API endpoint returns a 400 error when an unsupported
     * period parameter (e.g., "monthly") is supplied.
     */
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

    /**
     * Verifies that the stats API endpoint returns a 200 response indicating stats are
     * not configured when no {@link JobStatsStore} has been set on the endpoint.
     */
    @Test(timeout = 10000)
    public void testApiStatsNotConfigured() throws Exception {
        SlackNotifier notifier = new SlackNotifier(null);
        FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
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

    /**
     * Verifies that a job completion message is posted with {@code reply_broadcast=true}
     * so it appears in the channel as well as the thread.
     */
    @Test(timeout = 10000)
    public void testCompletionMessageBroadcastsToChannel() {
        List<String> messages = new ArrayList<>();
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            messages.add(json);
        });

        Workstream workstream = new Workstream("C123", "#test");
        workstream.setDefaultBranch("feature/test");
        notifier.registerWorkstream(workstream);

        JobCompletionEvent startEvent = JobCompletionEvent.started("job-broadcast", "Test task");
        notifier.onJobSubmitted(workstream.getWorkstreamId(), startEvent);

        messages.clear();

        JobCompletionEvent completeEvent = JobCompletionEvent.success("job-broadcast", "Test task");
        completeEvent.withGitInfo("feature/test", "abc1234567890",
            List.of("test.py"), List.of(), true);
        completeEvent.withPullRequestUrl("https://github.com/test/repo/pull/123");
        notifier.onJobCompleted(workstream.getWorkstreamId(), completeEvent);

        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertTrue("Message should have reply_broadcast=true",
            JsonFieldExtractor.extractBoolean(message, "reply_broadcast"));
        assertNotNull("Message should have thread_ts field",
            JsonFieldExtractor.extractString(message, "thread_ts"));
        assertTrue("Message should contain PR URL",
            message.contains("https://github.com/test/repo/pull/123"));
    }

    /**
     * Verifies that an intermediate job-started status message is posted without
     * {@code reply_broadcast}, so it stays only in the thread and not the channel.
     */
    @Test(timeout = 10000)
    public void testStatusMessageDoesNotBroadcast() {
        List<String> messages = new ArrayList<>();
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            messages.add(json);
        });

        Workstream workstream = new Workstream("C123", "#test");
        workstream.setDefaultBranch("feature/test");
        notifier.registerWorkstream(workstream);

        JobCompletionEvent startEvent = JobCompletionEvent.started("job-status", "Test task");
        notifier.onJobSubmitted(workstream.getWorkstreamId(), startEvent);

        messages.clear();

        notifier.onJobStarted(workstream.getWorkstreamId(), startEvent);

        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertFalse("Status message should NOT have reply_broadcast=true",
            JsonFieldExtractor.extractBoolean(message, "reply_broadcast"));
        assertNotNull("Status message should have thread_ts field",
            JsonFieldExtractor.extractString(message, "thread_ts"));
    }

    /**
     * Verifies that {@link SlackNotifier#postMessageInThread} does not set
     * {@code reply_broadcast} by default.
     */
    @Test(timeout = 10000)
    public void testPostMessageInThreadDefaultIsNoBroadcast() {
        List<String> messages = new ArrayList<>();
        SlackNotifier notifier = new SlackNotifier(null);
        notifier.setMessageCallback(json -> {
            messages.add(json);
        });

        notifier.postMessageInThread("C123", "test message", "thread123");

        assertEquals(1, messages.size());
        String message = messages.get(0);
        assertFalse("Default should NOT broadcast",
            JsonFieldExtractor.extractBoolean(message, "reply_broadcast"));
    }

    /**
     * Verifies that {@link SlackNotifier#getThreadTs} falls back to the durable
     * {@link JobStatsStore} when the in-memory map has no entry for the given job id.
     */
    @Test(timeout = 10000)
    public void testGetThreadTsFallsBackToStatsStoreAfterJobCompletion() throws Exception {
        File tempDir = Files.createTempDirectory("thread-ts-fallback").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();

        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();
        try {
            store.recordJobStarted("job-A", "ws-A", "demo", Instant.now());
            store.updateJobSlackTs("job-A", "1700000000.000001");

            Assert.assertEquals("Persisted slack_message_ts must be readable",
                    "1700000000.000001", store.getJobSlackTs("job-A"));

            SlackNotifier notifier = new SlackNotifier(null);
            notifier.setStatsStore(store);
            Assert.assertEquals("getThreadTs must fall back to the durable store"
                    + " when the in-memory map has no entry for the jobId",
                    "1700000000.000001", notifier.getThreadTs("job-A"));

            assertNull("Unknown job IDs must remain null",
                    notifier.getThreadTs("job-Unknown"));
            assertNull("Null jobId must remain null", notifier.getThreadTs(null));
        } finally {
            store.close();
        }
    }

    /**
     * Verifies that {@link SlackNotifier#getThreadTs} returns null when no stats store
     * is configured and the job id has no in-memory entry.
     */
    @Test(timeout = 10000)
    public void testGetThreadTsReturnsNullWhenNoStoreAndNoInMemoryEntry() {
        SlackNotifier notifier = new SlackNotifier(null);
        assertNull(notifier.getThreadTs("job-Z"));
    }
}
