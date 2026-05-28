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

package io.flowtree.controller;

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
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.slack.SlackNotifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the per-model cost breakdown added to {@link JobStatsStore}:
 * aggregation across multiple phases/models within and across jobs, the
 * compact {@code /flowtree stats} breakdown formatting, and the
 * {@code /api/stats} JSON surface that backs {@code workstream_get_status}.
 * Analogous to {@link JobStatsStoreRunnerCostTest} but for the model-cost path.
 */
public class JobStatsStoreModelCostTest extends TestSuiteBase {

    /** Creates and initialises a JobStatsStore backed by a fresh temp HSQLDB. */
    private JobStatsStore newStore() throws Exception {
        File tempDir = Files.createTempDirectory("model-cost-test").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();
        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();
        return store;
    }

    /** Returns the Monday of the current ISO week, in UTC. Shared by {@link JobStatsStoreRunnerCostTest}. */
    static LocalDate currentMonday() {
        return LocalDate.now(ZoneOffset.UTC).with(DayOfWeek.MONDAY);
    }

    /**
     * Records a started+completed job so its job_timing row exists and is not STARTED.
     * Shared by {@link JobStatsStoreRunnerCostTest} (same package).
     */
    static void recordCompletedJob(JobStatsStore store, String jobId,
                                         String workstreamId, Instant when, double totalCost) {
        store.recordJobStarted(jobId, workstreamId, "job " + jobId, when);
        store.recordJobCompleted(jobId, workstreamId, "SUCCESS",
                when.plusMillis(60000), 55000, 30000, totalCost, 10, "sess-" + jobId,
                0, "success", false, 0, null, null, null, null);
    }

    @Test(timeout = 30000)
    public void singleJobWithTwoModelsBreaksDownByModel() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

            recordCompletedJob(store, "job-1", "ws-mixed", when, 0.45);
            Map<String, Double> perModel = new LinkedHashMap<>();
            perModel.put("claude-opus-4-7", 0.42);
            perModel.put("openrouter:qwen3-coder:exacto", 0.03);
            store.recordModelCosts("job-1", perModel);

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-mixed", monday);
            assertEquals(0.42, stats.costByModel.get("claude-opus-4-7"), 1e-9);
            assertEquals(0.03, stats.costByModel.get("openrouter:qwen3-coder:exacto"), 1e-9);
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void costSumsAcrossJobsPerModel() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

            recordCompletedJob(store, "job-a", "ws-sum", when, 1.0);
            recordCompletedJob(store, "job-b", "ws-sum", when, 1.0);

            Map<String, Double> a = new LinkedHashMap<>();
            a.put("claude-opus-4-7", 0.50);
            a.put("openrouter:qwen3-coder:exacto", 0.01);
            store.recordModelCosts("job-a", a);

            Map<String, Double> b = new LinkedHashMap<>();
            b.put("claude-opus-4-7", 0.30);
            store.recordModelCosts("job-b", b);

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-sum", monday);
            assertEquals("claude-opus-4-7 cost sums across both jobs",
                    0.80, stats.costByModel.get("claude-opus-4-7"), 1e-9);
            assertEquals("openrouter:qwen3-coder:exacto appears only from job-a",
                    0.01, stats.costByModel.get("openrouter:qwen3-coder:exacto"), 1e-9);
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
            first.put("claude-opus-4-7", 0.10);
            store.recordModelCosts("job-x", first);

            Map<String, Double> second = new LinkedHashMap<>();
            second.put("claude-opus-4-7", 0.25);
            store.recordModelCosts("job-x", second);

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-idem", monday);
            assertEquals(0.25, stats.costByModel.get("claude-opus-4-7"), 1e-9);
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
            costs.put("claude-opus-4-7", 0.10);
            store.recordModelCosts("job-clear", costs);
            store.recordModelCosts("job-clear", new LinkedHashMap<>());

            JobStatsStore.WeeklyStats stats = store.getWeeklyStats("ws-clear", monday);
            assertFalse("an empty re-record clears prior rows",
                    stats.costByModel.containsKey("claude-opus-4-7"));
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void apiStatsEndpointIncludesPerModelBreakdown() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
            recordCompletedJob(store, "job-api", "ws-api", when, 0.45);

            Map<String, Double> perModel = new LinkedHashMap<>();
            perModel.put("claude-opus-4-7", 0.42);
            perModel.put("openrouter:qwen3-coder:exacto", 0.03);
            store.recordModelCosts("job-api", perModel);

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
                assertTrue("response exposes a costByModel object",
                        response.contains("\"costByModel\""));
                assertTrue("response names the claude-opus-4-7 model",
                        response.contains("\"claude-opus-4-7\""));
                assertTrue("response names the openrouter:qwen3-coder:exacto model",
                        response.contains("\"openrouter:qwen3-coder:exacto\""));
            } finally {
                endpoint.stop();
            }
        } finally {
            store.close();
        }
    }

    @Test(timeout = 30000)
    public void allWorkstreamsPathPopulatesCostByModel() throws Exception {
        JobStatsStore store = newStore();
        try {
            LocalDate monday = currentMonday();
            Instant when = monday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

            recordCompletedJob(store, "job-ws1", "ws-one", when, 0.50);
            recordCompletedJob(store, "job-ws2", "ws-two", when, 0.30);

            Map<String, Double> models1 = new LinkedHashMap<>();
            models1.put("claude-opus-4-7", 0.45);
            models1.put("openrouter:qwen3-coder:exacto", 0.05);
            store.recordModelCosts("job-ws1", models1);

            Map<String, Double> models2 = new LinkedHashMap<>();
            models2.put("claude-opus-4-7", 0.30);
            store.recordModelCosts("job-ws2", models2);

            Map<String, JobStatsStore.WeeklyStats> byWs = store.getWeeklyStatsByWorkstream(monday);
            assertTrue("ws-one present", byWs.containsKey("ws-one"));
            assertTrue("ws-two present", byWs.containsKey("ws-two"));

            assertEquals("ws-one claude-opus-4-7 cost", 0.45, byWs.get("ws-one").costByModel.get("claude-opus-4-7"), 1e-9);
            assertEquals("ws-one openrouter:qwen3-coder:exacto cost", 0.05, byWs.get("ws-one").costByModel.get("openrouter:qwen3-coder:exacto"), 1e-9);
            assertEquals("ws-two claude-opus-4-7 cost", 0.30, byWs.get("ws-two").costByModel.get("claude-opus-4-7"), 1e-9);
        } finally {
            store.close();
        }
    }
}
