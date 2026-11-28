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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.flowtree.jobs.JobCompletionEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link WindowStatsAggregator} and {@link JobStatsStore#getWindowStats}:
 * the plan's §2.4 counting rules (boundary straddle, running and unresponsive
 * jobs, lost submissions, overlap-based agent time), the per-kind/per-node
 * breakdowns, token coverage, and a reconciliation of the aggregate against the
 * per-job records (plan Stage 4).
 */
public class WindowStatsAggregatorTest extends TestSuiteBase {

    /** Silence threshold for the unresponsive label and running-job time cutoff. */
    private static final Duration SILENT = Duration.ofMinutes(15);
    /** Wait past which a still-submitted job counts as lost. */
    private static final Duration LOST = Duration.ofHours(3);
    /** The window start, a fixed instant for deterministic seeding. */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    /** The reference "now" and window end, six hours after {@link #T0}. */
    private static final Instant NOW = T0.plus(Duration.ofHours(6));

    /** Creates and initialises a JobStatsStore backed by a fresh temp HSQLDB. */
    private JobStatsStore newStore() throws Exception {
        File tempDir = Files.createTempDirectory("window-stats-test").toFile();
        tempDir.deleteOnExit();
        String dbPath = new File(tempDir, "stats").getAbsolutePath();
        JobStatsStore store = new JobStatsStore(dbPath);
        store.initialize();
        return store;
    }

    /** Seeds a started-then-completed job with an explicit span, trigger and node. */
    private void seedCompleted(JobStatsStore store, String jobId, String ws, String trigger,
                               String node, Instant startedAt, Instant completedAt,
                               JobCompletionEvent.Status status, double cost, int turns) {
        store.setTriggerReason(jobId, trigger);
        store.recordJobStarted(jobId, ws, jobId, node, startedAt);
        store.recordJobCompleted(jobId, ws, status.name(), completedAt, jobId,
                0L, 0L, cost, turns, "sess-" + jobId, 0, "success", false, 0,
                null, null, null, null);
    }

    /** Builds the mixed fixture described in the class javadoc and returns the window stats. */
    private WindowStats seedFixtureAndCompute(JobStatsStore store) {
        // A: fully inside the window, SUCCESS, coding, with tokens, node n1.
        seedCompleted(store, "A", "ws-1", "manual", "n1",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)),
                JobCompletionEvent.Status.SUCCESS, 1.0, 10);
        store.recordTokens("A", 100, 200, 0, 50);
        Map<String, Double> aRunner = new LinkedHashMap<>();
        aRunner.put("claude", 1.0);
        store.recordRunnerCosts("A", aRunner);

        // B: started before the window, finished inside it (boundary straddle),
        // FAILED, auto_resolve -> CI_REVIEW, node n1, no tokens.
        seedCompleted(store, "B", "ws-1", "auto_resolve", "n1",
                T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(1)),
                JobCompletionEvent.Status.FAILED, 0.5, 5);
        Map<String, Double> bRunner = new LinkedHashMap<>();
        bRunner.put("opencode", 0.5);
        store.recordRunnerCosts("B", bRunner);

        // C: still running, last heartbeat 2.5h ago at NOW -> unresponsive, node n2.
        store.recordJobStarted("C", "ws-2", "C", "n2", T0.plus(Duration.ofHours(3)));
        store.recordHeartbeat("C", T0.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(30)), "n2");

        // D: submitted 4h before NOW, never started -> LOST.
        store.recordJobSubmitted("D", "ws-2", "D", "sub", null, T0.plus(Duration.ofHours(2)));

        // E: submitted 30m before NOW, still within the lost threshold.
        store.recordJobSubmitted("E", "ws-2", "E", "sub", null,
                T0.plus(Duration.ofHours(5)).plus(Duration.ofMinutes(30)));

        WindowQuery query = new WindowQuery(T0, NOW, null, SILENT);
        return store.getWindowStats(query, NOW, LOST);
    }

    /** Verifies every outcome counter against the hand-built fixture. */
    @Test(timeout = 60000)
    public void outcomesFollowCountingRules() throws Exception {
        JobStatsStore store = newStore();
        try {
            WindowStats s = seedFixtureAndCompute(store);
            assertEquals("finished", 2, s.finished);
            assertEquals("success", 1, s.success);
            assertEquals("failed", 1, s.failed);
            assertEquals("started", 2, s.started);
            assertEquals("running", 1, s.running);
            assertEquals("unresponsive", 1, s.unresponsive);
            assertEquals("submitted", 2, s.submitted);
            assertEquals("lost", 1, s.lost);
        } finally {
            store.close();
        }
    }

    /** Verifies spend, turns, per-runner and per-kind breakdowns. */
    @Test(timeout = 60000)
    public void spendAndKindBreakdowns() throws Exception {
        JobStatsStore store = newStore();
        try {
            WindowStats s = seedFixtureAndCompute(store);
            assertEquals(1.5, s.totalCostUsd, 1e-9);
            assertEquals(15L, s.totalTurns);
            assertEquals(1.0, s.costByRunner.get("claude"), 1e-9);
            assertEquals(0.5, s.costByRunner.get("opencode"), 1e-9);
            assertEquals(Long.valueOf(1L), s.countByKind.get(JobKind.CODING.name()));
            assertEquals(Long.valueOf(1L), s.countByKind.get(JobKind.CI_REVIEW.name()));
            assertEquals(1.0, s.costByKind.get(JobKind.CODING.name()), 1e-9);
            assertEquals(0.5, s.costByKind.get(JobKind.CI_REVIEW.name()), 1e-9);
        } finally {
            store.close();
        }
    }

    /** Verifies overlap-based agent time: A=1h, B=1h (straddle), C=30m (to last beat). */
    @Test(timeout = 60000)
    public void agentTimeIsWindowOverlap() throws Exception {
        JobStatsStore store = newStore();
        try {
            WindowStats s = seedFixtureAndCompute(store);
            long oneHour = Duration.ofHours(1).toMillis();
            long halfHour = Duration.ofMinutes(30).toMillis();
            assertEquals(2 * oneHour + halfHour, s.agentWallClockMs);
            assertEquals(Long.valueOf(2 * oneHour), s.busyMsByNode.get("n1"));
            assertEquals(Long.valueOf(halfHour), s.busyMsByNode.get("n2"));
        } finally {
            store.close();
        }
    }

    /** Verifies token sums and the partial-coverage label (only A had a token row). */
    @Test(timeout = 60000)
    public void tokensSumWithHonestCoverage() throws Exception {
        JobStatsStore store = newStore();
        try {
            WindowStats s = seedFixtureAndCompute(store);
            assertEquals(1, s.finishedWithTokens);
            assertEquals(Long.valueOf(100L), s.inputTokens);
            assertEquals(Long.valueOf(200L), s.outputTokens);
            assertEquals(Long.valueOf(0L), s.cacheReadTokens);
            assertEquals(Long.valueOf(50L), s.cacheWriteTokens);
            assertEquals("partial:1/2", s.tokenCoverage());
        } finally {
            store.close();
        }
    }

    /** A window with no captured tokens reports null measures and "none" coverage. */
    @Test(timeout = 60000)
    public void noTokensReportsNull() throws Exception {
        JobStatsStore store = newStore();
        try {
            seedCompleted(store, "X", "ws-1", "manual", "n1",
                    T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)),
                    JobCompletionEvent.Status.SUCCESS, 0.2, 3);
            WindowStats s = store.getWindowStats(new WindowQuery(T0, NOW, null, SILENT), NOW, LOST);
            assertEquals(1, s.finished);
            assertEquals(0, s.finishedWithTokens);
            assertNull(s.inputTokens);
            assertEquals("none", s.tokenCoverage());
        } finally {
            store.close();
        }
    }

    /** The workstream filter restricts every measure to the named workstream. */
    @Test(timeout = 60000)
    public void workstreamFilterRestrictsCounts() throws Exception {
        JobStatsStore store = newStore();
        try {
            seedFixtureAndCompute(store);
            WindowStats s = store.getWindowStats(new WindowQuery(T0, NOW, "ws-1", SILENT), NOW, LOST);
            // ws-1 holds only A and B (both finished); C/D/E are ws-2.
            assertEquals(2, s.finished);
            assertEquals(0, s.running);
            assertEquals(0, s.submitted);
        } finally {
            store.close();
        }
    }

    /** The rendered JSON is non-empty and carries the window bounds and outcome block. */
    @Test(timeout = 60000)
    public void jsonRendersWindow() throws Exception {
        JobStatsStore store = newStore();
        try {
            WindowStats s = seedFixtureAndCompute(store);
            String json = s.toJson();
            assertTrue(json.contains("\"from\":\"" + T0 + "\""));
            assertTrue(json.contains("\"outcomes\":{"));
            assertTrue(json.contains("\"coverage\":\"partial:1/2\""));
        } finally {
            store.close();
        }
    }

    /**
     * Reconciliation (plan Stage 4): the aggregate's finished count and total
     * spend are re-derived independently by iterating the per-job records
     * {@link JobStatsStore#getRecentJobs} returns — a different read path than
     * the aggregator's {@code GROUP BY} — and must match. Spend is read from
     * {@link JobCompletionEvent#getTotalCostUsd()}, the cost a reconstructed row
     * carries.
     */
    @Test(timeout = 60000)
    public void aggregateReconcilesWithPerJobRecords() throws Exception {
        JobStatsStore store = newStore();
        try {
            WindowStats s = seedFixtureAndCompute(store);

            int finished = 0;
            double cost = 0.0;
            for (String ws : new String[] {"ws-1", "ws-2"}) {
                List<JobCompletionEvent> recent = store.getRecentJobs(ws, 100);
                for (JobCompletionEvent e : recent) {
                    Instant completed = e.getFinishedAt();
                    if (completed == null) continue;
                    if (completed.isBefore(T0) || !completed.isBefore(NOW)) continue;
                    finished++;
                    cost += e.getTotalCostUsd();
                }
            }

            assertEquals(s.finished, finished);
            assertEquals(s.totalCostUsd, cost, 1e-9);
        } finally {
            store.close();
        }
    }
}
