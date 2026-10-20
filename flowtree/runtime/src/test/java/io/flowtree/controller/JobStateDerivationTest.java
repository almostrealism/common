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
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the read-time derived states the controller labels jobs with:
 * {@code RUNNING}/{@code UNRESPONSIVE} for a running job silent past the
 * silence threshold, and {@code SUBMITTED}/{@code LOST} for a submitted job
 * that waits past the lost threshold.
 */
public class JobStateDerivationTest extends TestSuiteBase {

    /** An active job silent past the threshold is UNRESPONSIVE; within it, RUNNING. */
    @Test(timeout = 10000)
    public void activeJobDerivesRunningThenUnresponsive() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        ActiveJob fresh = new ActiveJob("job-1", "ws-1",
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(2)), "job", "node-a");
        ActiveJob silent = new ActiveJob("job-2", "ws-1",
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(40)), "job", "node-a");

        Duration silentThreshold = Duration.ofMinutes(15);
        Assert.assertEquals(ActiveJob.RUNNING, fresh.derivedState(silentThreshold, now));
        Assert.assertEquals(ActiveJob.UNRESPONSIVE, silent.derivedState(silentThreshold, now));
        assertTrue("the state label appears in the rendered JSON",
                silent.toJson(now, silentThreshold).contains("\"state\":\"UNRESPONSIVE\""));
        assertTrue("the node identity appears in the rendered JSON",
                silent.toJson(now, silentThreshold).contains("\"node\":\"node-a\""));
    }

    /** A pending job that waits past the lost threshold is LOST; within it, SUBMITTED. */
    @Test(timeout = 10000)
    public void pendingJobDerivesSubmittedThenLost() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        PendingJob recent = new PendingJob("job-1", "ws-1",
                now.minus(Duration.ofMinutes(5)), "submitter", "platform=macos", "job");
        PendingJob old = new PendingJob("job-2", "ws-1",
                now.minus(Duration.ofHours(2)), "submitter", null, "job");

        Duration lostThreshold = Duration.ofMinutes(30);
        Assert.assertEquals(PendingJob.SUBMITTED, recent.derivedState(lostThreshold, now));
        assertFalse(recent.isLost(lostThreshold, now));
        Assert.assertEquals(PendingJob.LOST, old.derivedState(lostThreshold, now));
        assertTrue(old.isLost(lostThreshold, now));
        assertTrue("the state label appears in the rendered JSON",
                old.toJson(now, lostThreshold).contains("\"state\":\"LOST\""));
        assertTrue("the submitter appears in the rendered JSON",
                recent.toJson(now, lostThreshold).contains("\"submittedBy\":\"submitter\""));
    }

    /** A non-positive threshold never flags a job, keeping the derivation conservative. */
    @Test(timeout = 10000)
    public void nonPositiveThresholdNeverFlags() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        ActiveJob silent = new ActiveJob("job-1", "ws-1",
                now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(5)), "job", null);
        PendingJob old = new PendingJob("job-2", "ws-1",
                now.minus(Duration.ofHours(5)), null, null, "job");
        Assert.assertEquals(ActiveJob.RUNNING, silent.derivedState(Duration.ZERO, now));
        Assert.assertEquals(PendingJob.SUBMITTED, old.derivedState(Duration.ofSeconds(-1), now));
    }
}
