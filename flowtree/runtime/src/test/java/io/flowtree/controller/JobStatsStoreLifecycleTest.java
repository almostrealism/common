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

import io.flowtree.jobs.JobCompletionEvent;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the controller-owned job-state lifecycle persisted by
 * {@link JobStatsStore}: the {@code SUBMITTED → STARTED → terminal} progression,
 * the new submission columns, {@link JobStatsStore#getPendingJobs(String)}, and
 * that an existing database opens again without error (backfill-safe migration).
 */
public class JobStatsStoreLifecycleTest extends TestSuiteBase {

    /** Creates and initialises a store backed by a fresh temp HSQLDB at {@code dir/stats}. */
    private JobStatsStore newStore(File dir) {
        JobStatsStore store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** A submitted job is pending (not active); the submitter and labels round-trip. */
    @Test(timeout = 30000)
    public void submittedJobIsPendingWithMetadata() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-submit").toFile();
        dir.deleteOnExit();
        JobStatsStore store = newStore(dir);
        try {
            Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("platform", "macos");
            store.recordJobSubmitted("job-1", "ws-1", "do the thing",
                    "operator@example", labels, submittedAt);

            Assert.assertTrue("a submitted-only job is not active",
                    store.getActiveJobs("ws-1").isEmpty());

            List<PendingJob> pending = store.getPendingJobs("ws-1");
            Assert.assertEquals("the submitted job should be pending", 1, pending.size());
            PendingJob job = pending.get(0);
            Assert.assertEquals("job-1", job.jobId());
            Assert.assertEquals("operator@example", job.submittedBy());
            Assert.assertEquals("platform=macos", job.requiredLabels());
            Assert.assertEquals("do the thing", job.description());
            Assert.assertEquals(submittedAt, job.submittedAt());
        } finally {
            store.close();
        }
    }

    /** SUBMITTED → STARTED moves the job from the pending list to the active list, recording node. */
    @Test(timeout = 30000)
    public void startedJobTransitionsOutOfPending() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-start").toFile();
        dir.deleteOnExit();
        JobStatsStore store = newStore(dir);
        try {
            Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");
            Instant startedAt = submittedAt.plusSeconds(90);
            store.recordJobSubmitted("job-1", "ws-1", "do the thing",
                    null, null, submittedAt);
            store.recordJobStarted("job-1", "ws-1", "do the thing", "halo (10.0.0.2)", startedAt);

            Assert.assertTrue("a started job is no longer pending",
                    store.getPendingJobs("ws-1").isEmpty());

            List<ActiveJob> active = store.getActiveJobs("ws-1");
            Assert.assertEquals("the started job should be active", 1, active.size());
            ActiveJob job = active.get(0);
            Assert.assertEquals("job-1", job.jobId());
            Assert.assertEquals("the real start time overwrites the submit placeholder",
                    startedAt, job.startedAt());
            Assert.assertEquals("the worker node identity is recorded", "halo (10.0.0.2)", job.node());
        } finally {
            store.close();
        }
    }

    /** A second STARTED for an already-started job does not move its start time. */
    @Test(timeout = 30000)
    public void repeatedStartedDoesNotMoveStartTime() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-restart").toFile();
        dir.deleteOnExit();
        JobStatsStore store = newStore(dir);
        try {
            Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");
            Instant firstStart = submittedAt.plusSeconds(90);
            Instant secondStart = submittedAt.plusSeconds(600);
            store.recordJobSubmitted("job-1", "ws-1", "job", null, null, submittedAt);
            store.recordJobStarted("job-1", "ws-1", "job", "node-a", firstStart);
            store.recordJobStarted("job-1", "ws-1", "job", "node-b", secondStart);

            ActiveJob job = store.getActiveJobs("ws-1").get(0);
            Assert.assertEquals("a re-reported start must not move started_at", firstStart, job.startedAt());
        } finally {
            store.close();
        }
    }

    /** The full submit → start → complete path ends in a terminal, non-active, non-pending row. */
    @Test(timeout = 30000)
    public void fullLifecycleEndsTerminal() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-full").toFile();
        dir.deleteOnExit();
        JobStatsStore store = newStore(dir);
        try {
            Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");
            store.recordJobSubmitted("job-1", "ws-1", "job", "submitter", null, submittedAt);
            store.recordJobStarted("job-1", "ws-1", "job", "node-a", submittedAt.plusSeconds(60));
            store.recordJobCompleted("ws-1",
                    JobCompletionEvent.success("job-1", "job"));

            Assert.assertTrue("a completed job is not pending", store.getPendingJobs("ws-1").isEmpty());
            Assert.assertTrue("a completed job is not active", store.getActiveJobs("ws-1").isEmpty());
            JobCompletionEvent persisted = store.getJob("job-1");
            Assert.assertNotNull("the job row must survive to terminal", persisted);
            Assert.assertEquals(JobCompletionEvent.Status.SUCCESS, persisted.getStatus());
        } finally {
            store.close();
        }
    }

    /**
     * A heartbeat can record the worker node on a still-{@code SUBMITTED} row
     * before the {@code STARTED} event lands; the subsequent node-less
     * {@code STARTED} transition must preserve that identity rather than clear it.
     */
    @Test(timeout = 30000)
    public void startedWithoutNodePreservesHeartbeatNode() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-node").toFile();
        dir.deleteOnExit();
        JobStatsStore store = newStore(dir);
        try {
            Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");
            store.recordJobSubmitted("job-1", "ws-1", "job", null, null, submittedAt);
            store.recordHeartbeat("job-1", submittedAt.plusSeconds(30), "worker (10.0.0.9)");
            store.recordJobStarted("job-1", "ws-1", "job", null, submittedAt.plusSeconds(60));

            ActiveJob job = store.getActiveJobs("ws-1").get(0);
            Assert.assertEquals("a node-less STARTED must not clear the heartbeat node",
                    "worker (10.0.0.9)", job.node());
        } finally {
            store.close();
        }
    }

    /**
     * A STARTED event that arrives with no prior SUBMITTED row still produces an
     * active row (controller restart / missing submission record).
     */
    @Test(timeout = 30000)
    public void startedWithoutSubmitInsertsActiveRow() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-nosubmit").toFile();
        dir.deleteOnExit();
        JobStatsStore store = newStore(dir);
        try {
            store.recordJobStarted("job-x", "ws-1", "job", "node-a", Instant.now());
            Assert.assertEquals("a bare started job is active", 1, store.getActiveJobs("ws-1").size());
            Assert.assertTrue("and never appears as pending", store.getPendingJobs("ws-1").isEmpty());
        } finally {
            store.close();
        }
    }

    /** An existing database opens again without error and old rows read back (migration safety). */
    @Test(timeout = 30000)
    public void existingDatabaseReopensWithNewColumns() throws Exception {
        File dir = Files.createTempDirectory("lifecycle-reopen").toFile();
        dir.deleteOnExit();

        JobStatsStore first = newStore(dir);
        Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");
        first.recordJobSubmitted("job-1", "ws-1", "job", "submitter", null, submittedAt);
        first.close();

        JobStatsStore second = newStore(dir);
        try {
            List<PendingJob> pending = second.getPendingJobs("ws-1");
            Assert.assertEquals("the pending row survives a reopen", 1, pending.size());
            Assert.assertEquals("submitter", pending.get(0).submittedBy());
            assertTrue("the submit time survives a reopen",
                    submittedAt.equals(pending.get(0).submittedAt()));
        } finally {
            second.close();
        }
    }
}
