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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the per-runner cost breakdown added to {@link JobStatsStore}:
 * aggregation across multiple phases/runners within and across jobs, the
 * compact {@code /flowtree stats} breakdown formatting, and the
 * {@code /api/stats} JSON surface that backs {@code workstream_get_status}.
 */
public class JobStatsStoreRunnerCostTest extends TestSuiteBase {

    /** Creates and initialises a JobStatsStore backed by a fresh temp HSQLDB. */
    private JobStatsStore newStore() throws Exception {
        File tempDir = Files.createTempDirectory("runner-cost-test").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();
        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();
        return store;
    }

    /** Returns the Monday of the current ISO week, in UTC. */
    private static LocalDate currentMonday() {
        return LocalDate.now(ZoneOffset.UTC).with(DayOfWeek.MONDAY);
    }

    /** Records a started+completed job so its job_timing row exists and is not STARTED. */
    private static void recordCompletedJob(JobStatsStore store, String jobId,
                                           String workstreamId, Instant when, double totalCost) {
        store.recordJobStarted(jobId, workstreamId, "job " + jobId, when);
        store.recordJobCompleted(jobId, workstreamId, "SUCCESS",
                when.plusMillis(60000), 55000, 30000, totalCost, 10, "sess-" + jobId,
                0, "success", false, 0, null, null, null, null);
    }

    @Test(timeout = 30000)
    public void singleJobWithTwoRunnersBreaksDownByRunner() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

            recordCompletedJob(store, "job-1", "ws-mixed", when, 0.45);
            // The same job ran two phases on two different runners.
            Map<String, Double> perRunner = new LinkedHashMap<>();
            perRunner.put("claude", 0.42);
            perRunner.put("opencode", 0.03);
            store.recordRunnerCosts("job-1", perRunner);

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-mixed", monday);
            assertEquals(0.42, stats.costByRunner.get("claude"), 1e-9);
            assertEquals(0.03, stats.costByRunner.get("opencode"), 1e-9);
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void costSumsAcrossJobsPerRunner() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

            recordCompletedJob(store, "job-a", "ws-sum", when, 1.0);
            recordCompletedJob(store, "job-b", "ws-sum", when, 1.0);

            Map<String, Double> a = new LinkedHashMap<>();
            a.put("claude", 0.50);
            a.put("opencode", 0.01);
            store.recordRunnerCosts("job-a", a);

            Map<String, Double> b = new LinkedHashMap<>();
            b.put("claude", 0.30);
            store.recordRunnerCosts("job-b", b);

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-sum", monday);
            assertEquals("claude cost sums across both jobs",
                    0.80, stats.costByRunner.get("claude"), 1e-9);
            assertEquals("opencode appears only from job-a",
                    0.01, stats.costByRunner.get("opencode"), 1e-9);
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void reRecordingReplacesRatherThanDoubleCounts() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
            recordCompletedJob(store, "job-x", "ws-idem", when, 0.2);

            Map<String, Double> first = new LinkedHashMap<>();
            first.put("claude", 0.10);
            store.recordRunnerCosts("job-x", first);

            // A re-reported completion event must replace, not accumulate.
            Map<String, Double> second = new LinkedHashMap<>();
            second.put("claude", 0.25);
            store.recordRunnerCosts("job-x", second);

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-idem", monday);
            assertEquals(0.25, stats.costByRunner.get("claude"), 1e-9);
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void formatCostBreakdownIsCompactAndOmitsWhenEmpty() {
        assertEquals("", JobStatsStore.formatCostBreakdown(null));
        assertEquals("", JobStatsStore.formatCostBreakdown(new LinkedHashMap<>()));

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("claude", 42.0);
        costs.put("opencode", 3.0);
        String formatted = JobStatsStore.formatCostBreakdown(costs);
        assertEquals(" (claude $42.00, opencode $3.00)", formatted);
    }

    @Test(timeout = 30000)
    public void apiStatsEndpointIncludesPerRunnerBreakdown() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
            recordCompletedJob(store, "job-api", "ws-api", when, 0.45);

            Map<String, Double> perRunner = new LinkedHashMap<>();
            perRunner.put("claude", 0.42);
            perRunner.put("opencode", 0.03);
            store.recordRunnerCosts("job-api", perRunner);

            SlackNotifier notifier = new SlackNotifier(null);
            FlowTreeApiEndpoint endpoint = new FlowTreeApiEndpoint(0, notifier);
            endpoint.setStatsStore(store);
            endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

            try {
                int port = endpoint.getListeningPort();
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "http://localhost:" + port + "/api/stats?workstream=ws-api").openConnection();
                conn.setRequestMethod("GET");
                assertEquals(200, conn.getResponseCode());

                String response = new String(conn.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue("response exposes a costByRunner object",
                        response.contains("\"costByRunner\""));
                assertTrue("response names the claude runner", response.contains("\"claude\""));
                assertTrue("response names the opencode runner", response.contains("\"opencode\""));
                assertTrue("the existing aggregate total is still present",
                        response.contains("\"totalCostUsd\""));
            } finally {
                endpoint.stop();
            }
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void allWorkstreamsPathPopulatesCostByRunner() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

            recordCompletedJob(store, "job-ws1", "ws-one", when, 0.50);
            recordCompletedJob(store, "job-ws2", "ws-two", when, 0.30);

            Map<String, Double> runners1 = new LinkedHashMap<>();
            runners1.put("claude", 0.45);
            runners1.put("opencode", 0.05);
            store.recordRunnerCosts("job-ws1", runners1);

            Map<String, Double> runners2 = new LinkedHashMap<>();
            runners2.put("claude", 0.30);
            store.recordRunnerCosts("job-ws2", runners2);

            Map<String, JobStatsStore.WeeklyStats> byWs = store.getWeeklyStatsByWorkstream(monday);
            assertTrue("ws-one present", byWs.containsKey("ws-one"));
            assertTrue("ws-two present", byWs.containsKey("ws-two"));

            assertEquals("ws-one claude cost", 0.45, byWs.get("ws-one").costByRunner.get("claude"), 1e-9);
            assertEquals("ws-one opencode cost", 0.05, byWs.get("ws-one").costByRunner.get("opencode"), 1e-9);
            assertEquals("ws-two claude cost", 0.30, byWs.get("ws-two").costByRunner.get("claude"), 1e-9);
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void emptyBreakdownClearsExistingRows() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
            recordCompletedJob(store, "job-clear", "ws-clear", when, 0.2);

            Map<String, Double> costs = new LinkedHashMap<>();
            costs.put("claude", 0.10);
            store.recordRunnerCosts("job-clear", costs);
            store.recordRunnerCosts("job-clear", new LinkedHashMap<>());

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-clear", monday);
            assertFalse("an empty re-record clears prior rows",
                    stats.costByRunner.containsKey("claude"));
        } finally {
            store.close();
        }
    }
}
