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

import static org.junit.Assert.assertEquals;

/**
 * Proves that {@link JobLifecycleRecorder} persists the full job lifecycle
 * with <em>no</em> notifier in the loop — the crux of the controller-owned
 * tracking work. Every transition is driven straight through the recorder
 * against a real store, exactly as the controller does, so a workspace with no
 * Slack notifier still gets a complete job row at submit, start, and terminal.
 */
public class JobLifecycleRecorderTest extends TestSuiteBase {

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private JobStatsStore newStore() throws Exception {
        File dir = Files.createTempDirectory("recorder-test").toFile();
        dir.deleteOnExit();
        JobStatsStore store = new JobStatsStore(new File(dir, "stats").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** The recorder writes submit → start → terminal with no notifier present. */
    @Test(timeout = 30000)
    public void recordsFullLifecycleWithoutNotifier() throws Exception {
        JobStatsStore store = newStore();
        try {
            JobLifecycleRecorder recorder = new JobLifecycleRecorder(store);
            Instant submittedAt = Instant.parse("2026-08-22T12:00:00Z");

            recorder.recordSubmitted("job-1", "ws-1", "job", "submitter", null, submittedAt);
            Assert.assertEquals("submit writes a pending row", 1, store.getPendingJobs("ws-1").size());

            recorder.recordStarted("job-1", "ws-1", "job", "node-a", submittedAt.plusSeconds(60));
            Assert.assertTrue("start clears the pending row", store.getPendingJobs("ws-1").isEmpty());
            Assert.assertEquals("start writes an active row", 1, store.getActiveJobs("ws-1").size());

            recorder.recordTerminal("ws-1", JobCompletionEvent.failed("job-1", "job", "boom", null));
            Assert.assertTrue("terminal clears the active row", store.getActiveJobs("ws-1").isEmpty());
            Assert.assertEquals("terminal status is persisted",
                    JobCompletionEvent.Status.FAILED, store.getJob("job-1").getStatus());
        } finally {
            store.close();
        }
    }

    /**
     * A scanner-style synthetic failure recorded through the terminal path
     * persists even when the job was only ever SUBMITTED (never STARTED) — the
     * DEAD path must not depend on a notifier either.
     */
    @Test(timeout = 30000)
    public void recordsTerminalForNeverStartedJob() throws Exception {
        JobStatsStore store = newStore();
        try {
            JobLifecycleRecorder recorder = new JobLifecycleRecorder(store);
            recorder.recordSubmitted("job-1", "ws-1", "job", null, null,
                    Instant.parse("2026-08-22T12:00:00Z"));
            recorder.recordTerminal("ws-1",
                    JobCompletionEvent.failed("job-1", "job", "stalled", null));
            Assert.assertEquals(JobCompletionEvent.Status.FAILED, store.getJob("job-1").getStatus());
        } finally {
            store.close();
        }
    }

    /** A null store is tolerated silently so a stats-less controller records nothing. */
    @Test(timeout = 30000)
    public void nullStoreIsSilent() {
        JobLifecycleRecorder recorder = new JobLifecycleRecorder(null);
        recorder.recordSubmitted("job-1", "ws-1", "job", null, null, Instant.now());
        recorder.recordStarted("job-1", "ws-1", "job", null, Instant.now());
        recorder.recordTerminal("ws-1", JobCompletionEvent.success("job-1", "job"));
        Assert.assertTrue("every lifecycle call must no-op without throwing", true);
    }
}
