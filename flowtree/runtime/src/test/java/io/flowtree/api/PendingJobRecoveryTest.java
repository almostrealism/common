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

package io.flowtree.api;

import io.flowtree.controller.JobStatsStore;
import io.flowtree.controller.PendingJob;
import io.flowtree.job.JobFactory;
import io.flowtree.jobs.CodingAgentJob;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Covers controller-side recovery of persisted {@code SUBMITTED} jobs across a
 * restart: {@link PendingJobRecovery} re-dispatches the serialized factory for
 * a job the {@link JobStatsStore} still reports as {@code SUBMITTED}, exactly
 * once, while a job that has already started or completed — or whose blob was
 * cleared — is not re-dispatched.
 */
public class PendingJobRecoveryTest extends TestSuiteBase {

    /** Creates and initialises a stats store backed by a fresh temp HSQLDB. */
    private JobStatsStore newStatsStore(File dir) {
        JobStatsStore store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** Creates and initialises a recovery store backed by a fresh temp HSQLDB. */
    private PendingJobRecovery newRecovery(File dir) {
        PendingJobRecovery recovery =
                new PendingJobRecovery(new File(dir, "job-recovery").getAbsolutePath());
        recovery.initialize();
        return recovery;
    }

    /** The set of job ids the stats store still reports as {@code SUBMITTED}. */
    private Set<String> pendingIds(JobStatsStore store) {
        Set<String> ids = new HashSet<>();
        for (PendingJob job : store.getPendingJobs(null)) {
            ids.add(job.jobId());
        }
        return ids;
    }

    /**
     * A persisted {@code SUBMITTED} job is re-dispatched exactly once, with its
     * factory faithfully reconstructed; a {@code STARTED} job is not.
     */
    @Test(timeout = 30000)
    public void recoversSubmittedButNotStarted() throws Exception {
        File dir = Files.createTempDirectory("recovery").toFile();
        dir.deleteOnExit();
        JobStatsStore stats = newStatsStore(dir);
        PendingJobRecovery recovery = newRecovery(dir);
        try {
            Instant now = Instant.parse("2026-08-22T12:00:00Z");

            CodingAgentJob.Factory submitted = new CodingAgentJob.Factory("submitted work");
            submitted.setTargetBranch("feature/submitted");
            String submittedId = submitted.getTaskId();
            stats.recordJobSubmitted(submittedId, "ws-1", "submitted work", null, null, now);
            recovery.persist(submittedId, submitted.encode());

            CodingAgentJob.Factory started = new CodingAgentJob.Factory("started work");
            String startedId = started.getTaskId();
            stats.recordJobSubmitted(startedId, "ws-1", "started work", null, null, now);
            stats.recordJobStarted(startedId, "ws-1", "started work", "node-a", now.plusSeconds(60));
            recovery.persist(startedId, started.encode());

            List<JobFactory> dispatched = new ArrayList<>();
            int count = recovery.recover(pendingIds(stats), dispatched::add);

            Assert.assertEquals("only the SUBMITTED job is re-dispatched", 1, count);
            Assert.assertEquals("exactly one factory handed to dispatch", 1, dispatched.size());
            Assert.assertEquals("the re-dispatched job is the submitted one",
                    submittedId, dispatched.get(0).getTaskId());
            Assert.assertTrue("the re-dispatched factory keeps its type",
                    dispatched.get(0) instanceof CodingAgentJob.Factory);
            Assert.assertEquals("the recovered factory's branch survives",
                    "feature/submitted",
                    ((CodingAgentJob.Factory) dispatched.get(0)).getTargetBranch());
        } finally {
            recovery.close();
            stats.close();
        }
    }

    /** A terminal job's persisted factory is cleared and never re-dispatched. */
    @Test(timeout = 30000)
    public void clearedFactoryIsNotRecovered() throws Exception {
        File dir = Files.createTempDirectory("recovery-clear").toFile();
        dir.deleteOnExit();
        JobStatsStore stats = newStatsStore(dir);
        PendingJobRecovery recovery = newRecovery(dir);
        try {
            Instant now = Instant.parse("2026-08-22T12:00:00Z");
            CodingAgentJob.Factory factory = new CodingAgentJob.Factory("work");
            String jobId = factory.getTaskId();
            stats.recordJobSubmitted(jobId, "ws-1", "work", null, null, now);
            recovery.persist(jobId, factory.encode());

            recovery.clear(jobId);

            List<JobFactory> dispatched = new ArrayList<>();
            // The stats row is still SUBMITTED, so only the missing blob keeps it
            // from being recovered — proving clear() removed the factory.
            int count = recovery.recover(pendingIds(stats), dispatched::add);
            Assert.assertEquals("a cleared factory is not re-dispatched", 0, count);
            Assert.assertTrue(dispatched.isEmpty());
        } finally {
            recovery.close();
            stats.close();
        }
    }

    /** Persisted factories survive a close/reopen of the recovery store. */
    @Test(timeout = 30000)
    public void persistedFactorySurvivesReopen() throws Exception {
        File dir = Files.createTempDirectory("recovery-reopen").toFile();
        dir.deleteOnExit();
        JobStatsStore stats = newStatsStore(dir);
        try {
            Instant now = Instant.parse("2026-08-22T12:00:00Z");
            CodingAgentJob.Factory factory = new CodingAgentJob.Factory("durable work");
            String jobId = factory.getTaskId();
            stats.recordJobSubmitted(jobId, "ws-1", "durable work", null, null, now);

            PendingJobRecovery first = newRecovery(dir);
            first.persist(jobId, factory.encode());
            first.close();

            PendingJobRecovery second = newRecovery(dir);
            try {
                List<JobFactory> dispatched = new ArrayList<>();
                int count = second.recover(pendingIds(stats), dispatched::add);
                Assert.assertEquals("the persisted factory survives a reopen", 1, count);
                Assert.assertEquals(jobId, dispatched.get(0).getTaskId());
            } finally {
                second.close();
            }
        } finally {
            stats.close();
        }
    }
}
