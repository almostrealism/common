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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AbandonedTestRunDetector} and the DEGRADED-status
 * detection wired into {@link CodingAgentJob#createEvent}.
 *
 * <p>Each test creates a temporary ``runs/`` directory with synthetic
 * ``metadata.json`` files representing test-runner runs in various states,
 * then invokes the detector and asserts the resulting list of abandoned
 * run IDs.</p>
 */
public class AbandonedTestRunDetectorTest extends TestSuiteBase {

    private Path runsDir;

    @Before
    public void setUp() throws IOException {
        runsDir = Files.createTempDirectory("ar-test-runner-runs-");
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursive(runsDir);
    }

    /** Writes a fake ``metadata.json`` for a run under ``runs/<runId>/``. */
    private void writeRun(String runId, String status, Instant startedAt) throws IOException {
        writeRunMetadata(runsDir, runId, status, startedAt);
    }

    /**
     * Shared metadata-writing helper used by {@link #writeRun} and
     * {@link #makeTempJobWorkDirWithRun}. Creates the run directory under
     * {@code runsDir} and writes a minimal ``metadata.json`` containing
     * {@code run_id}, {@code status}, and {@code started_at}.
     */
    private static void writeRunMetadata(Path runsDir, String runId, String status,
                                         Instant startedAt) throws IOException {
        Path runDir = runsDir.resolve(runId);
        Files.createDirectories(runDir);
        String startedStr = LocalDateTime.ofInstant(startedAt, ZoneId.systemDefault()).toString();
        String json = "{\n"
            + "  \"run_id\": \"" + runId + "\",\n"
            + "  \"status\": \"" + status + "\",\n"
            + "  \"started_at\": \"" + startedStr + "\"\n"
            + "}\n";
        Files.write(runDir.resolve("metadata.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    // ── Detector behavior ──────────────────────────────────────────────────

    @Test(timeout = 30000)
    public void emptyRunsDirReturnsEmpty() {
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(
                runsDir, Instant.now().minusSeconds(60));
        assertNotNull(abandoned);
        assertTrue(abandoned.isEmpty());
    }

    @Test(timeout = 30000)
    public void missingRunsDirReturnsEmpty() {
        Path nonexistent = runsDir.resolve("nonexistent");
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(
                nonexistent, Instant.now().minusSeconds(60));
        assertNotNull(abandoned);
        assertTrue(abandoned.isEmpty());
    }

    @Test(timeout = 30000)
    public void runningRunInsideSessionWindowIsAbandoned() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(120);
        writeRun("aa000001", "running", sessionStart.plusSeconds(10));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertEquals(List.of("aa000001"), abandoned);
    }

    @Test(timeout = 30000)
    public void pendingRunIsAbandoned() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(120);
        writeRun("aa000002", "pending", sessionStart.plusSeconds(10));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertEquals(List.of("aa000002"), abandoned);
    }

    @Test(timeout = 30000)
    public void atexitAbandonedRunStillCountsAsAbandoned() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(120);
        writeRun("aa000003", "abandoned", sessionStart.plusSeconds(10));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertEquals(List.of("aa000003"), abandoned);
    }

    @Test(timeout = 30000)
    public void completedRunIsNotAbandoned() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(120);
        writeRun("aa000004", "completed", sessionStart.plusSeconds(10));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertTrue(abandoned.isEmpty());
    }

    @Test(timeout = 30000)
    public void failedAndTimeoutRunsAreNotAbandoned() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(120);
        writeRun("aa000005", "failed", sessionStart.plusSeconds(10));
        writeRun("aa000006", "timeout", sessionStart.plusSeconds(10));
        writeRun("aa000007", "cancelled", sessionStart.plusSeconds(10));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertTrue("Expected empty, got: " + abandoned, abandoned.isEmpty());
    }

    @Test(timeout = 30000)
    public void runStartedBeforeSessionWindowIsIgnored() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(60);
        writeRun("aa000008", "running", sessionStart.minusSeconds(120));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertTrue("Pre-session running runs must be ignored, got: " + abandoned,
                abandoned.isEmpty());
    }

    @Test(timeout = 30000)
    public void multipleAbandonedRunsAreReportedSorted() throws IOException {
        Instant sessionStart = Instant.now().minusSeconds(120);
        writeRun("zz000001", "running", sessionStart.plusSeconds(5));
        writeRun("aa000001", "running", sessionStart.plusSeconds(10));
        writeRun("mm000001", "pending", sessionStart.plusSeconds(15));
        // Mix in one terminal run that should NOT appear.
        writeRun("aa000999", "completed", sessionStart.plusSeconds(20));

        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, sessionStart);
        assertEquals(List.of("aa000001", "mm000001", "zz000001"), abandoned);
    }

    @Test(timeout = 30000)
    public void malformedMetadataIsSkipped() throws IOException {
        Path badRun = runsDir.resolve("baddata1");
        Files.createDirectories(badRun);
        Files.write(badRun.resolve("metadata.json"), "this is not json".getBytes(StandardCharsets.UTF_8));
        // And one well-formed running run that SHOULD show up.
        writeRun("aa000010", "running", Instant.now());

        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(
                runsDir, Instant.now().minusSeconds(60));
        assertEquals(List.of("aa000010"), abandoned);
    }

    @Test(timeout = 30000)
    public void nullSessionStartDisablesTimeFilter() throws IOException {
        // Run from "long ago" should still be detected when sessionStart is null.
        writeRun("aa000011", "running", Instant.now().minusSeconds(100_000));
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRuns(runsDir, null);
        assertEquals(List.of("aa000011"), abandoned);
    }

    // ── CodingAgentJob integration: createEvent returns DEGRADED ────────────

    /**
     * Test subclass that exposes the protected {@link CodingAgentJob#createEvent}
     * for direct invocation. The integration tests do NOT call {@link #doWork()}
     * because that would launch a real claude subprocess; instead, they leave
     * {@code sessionStartedAt} as null (the detector treats null as "no time
     * filter") and rely on workdir contents alone.
     */
    static class TestableCodingAgentJob extends CodingAgentJob {
        /** @param taskId task identifier; @param prompt initial prompt. */
        TestableCodingAgentJob(String taskId, String prompt) { super(taskId, prompt); }
        /** @param error optional error; @return the event produced by createEvent. */
        JobCompletionEvent createEventNow(Exception error) { return createEvent(error); }
    }

    private static Path makeTempJobWorkDirWithRun(String runId, String status)
            throws IOException {
        Path workDir = Files.createTempDirectory("ccj-workdir-");
        Path testRunnerRuns = workDir.resolve("tools/mcp/test-runner/runs");
        writeRunMetadata(testRunnerRuns, runId, status, Instant.now());
        return workDir;
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    @Test(timeout = 30000)
    public void claudeCodeJobCreateEventReturnsDegradedWhenRunAbandoned()
            throws IOException {
        Path workDir = makeTempJobWorkDirWithRun("ab000001", "running");
        try {
            TestableCodingAgentJob job = new TestableCodingAgentJob("test-job-1", "do something");
            job.setWorkingDirectory(workDir.toString());
            JobCompletionEvent event = job.createEventNow(null);
            assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
            assertNotNull(event.getErrorMessage());
            assertTrue("Error message should mention run id, got: " + event.getErrorMessage(),
                    event.getErrorMessage().contains("ab000001"));
        } finally {
            deleteRecursive(workDir);
        }
    }

    @Test(timeout = 30000)
    public void claudeCodeJobCreateEventReturnsSuccessWhenAllRunsTerminal()
            throws IOException {
        Path workDir = makeTempJobWorkDirWithRun("ab000002", "completed");
        try {
            TestableCodingAgentJob job = new TestableCodingAgentJob("test-job-2", "do something else");
            job.setWorkingDirectory(workDir.toString());
            JobCompletionEvent event = job.createEventNow(null);
            assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
        } finally {
            deleteRecursive(workDir);
        }
    }

    @Test(timeout = 30000)
    public void claudeCodeJobCreateEventReturnsFailedOnErrorRegardlessOfAbandoned()
            throws IOException {
        // Even with an abandoned run, an actual error must dominate.
        Path workDir = makeTempJobWorkDirWithRun("ab000003", "running");
        try {
            TestableCodingAgentJob job = new TestableCodingAgentJob("test-job-3", "do something");
            job.setWorkingDirectory(workDir.toString());
            JobCompletionEvent event = job.createEventNow(new RuntimeException("boom"));
            assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
        } finally {
            deleteRecursive(workDir);
        }
    }

    // ── DEGRADED factory methods on the event types ────────────────────────

    @Test(timeout = 30000)
    public void degradedFactoryHasDegradedStatus() {
        JobCompletionEvent event = JobCompletionEvent.degraded("j", "d", "agent abandoned 1 run");
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertEquals("agent abandoned 1 run", event.getErrorMessage());
    }

    @Test(timeout = 30000)
    public void claudeCodeDegradedFactoryHasDegradedStatus() {
        CodingAgentJobEvent event = CodingAgentJobEvent.degraded("j", "d", "abandoned 2");
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertEquals("abandoned 2", event.getErrorMessage());
    }

    @Test(timeout = 30000)
    public void degradedIsDistinctFromSuccessAndFailed() {
        assertFalse(JobCompletionEvent.Status.DEGRADED == JobCompletionEvent.Status.SUCCESS);
        assertFalse(JobCompletionEvent.Status.DEGRADED == JobCompletionEvent.Status.FAILED);
    }
}
