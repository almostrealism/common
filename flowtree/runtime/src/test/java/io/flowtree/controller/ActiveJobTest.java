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

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the staleness rule on {@link ActiveJob}: both the instance methods
 * and the static overloads that share the rule with callers, such as
 * {@link JobInterval}, that hold the timestamps without an {@code ActiveJob}.
 */
public class ActiveJobTest extends TestSuiteBase {

    /** Fifteen minutes, the controller's default silence threshold. */
    private static final Duration SILENT = Duration.ofMinutes(15);
    /** A reference instant for the derivations. */
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    /** The static overload falls back to the start time when no heartbeat was recorded. */
    @Test(timeout = 10000)
    public void sinceHeartbeatFallsBackToStart() {
        Instant start = NOW.minus(Duration.ofMinutes(30));
        assertEquals(Duration.ofMinutes(30), ActiveJob.sinceHeartbeat(start, null, NOW));
        assertEquals(Duration.ofMinutes(5),
                ActiveJob.sinceHeartbeat(start, NOW.minus(Duration.ofMinutes(5)), NOW));
    }

    /** With neither timestamp known the elapsed silence is zero, so the job is never stale. */
    @Test(timeout = 10000)
    public void sinceHeartbeatIsZeroWhenNothingIsKnown() {
        assertEquals(Duration.ZERO, ActiveJob.sinceHeartbeat(null, null, NOW));
        assertFalse(ActiveJob.isStale(null, null, SILENT, NOW));
    }

    /** A job silent past the threshold is stale; one within it is not, and the boundary is not stale. */
    @Test(timeout = 10000)
    public void staleOnlyWhenSilenceExceedsTheThreshold() {
        assertTrue(ActiveJob.isStale(null, NOW.minus(Duration.ofMinutes(16)), SILENT, NOW));
        assertFalse(ActiveJob.isStale(null, NOW.minus(Duration.ofMinutes(14)), SILENT, NOW));
        // Exactly at the threshold is not yet stale: the rule is strictly greater-than.
        assertFalse(ActiveJob.isStale(null, NOW.minus(SILENT), SILENT, NOW));
    }

    /** A null, zero, or negative threshold disables the check. */
    @Test(timeout = 10000)
    public void nonPositiveThresholdDisablesTheCheck() {
        Instant longSilent = NOW.minus(Duration.ofHours(10));
        assertFalse(ActiveJob.isStale(null, longSilent, null, NOW));
        assertFalse(ActiveJob.isStale(null, longSilent, Duration.ZERO, NOW));
        assertFalse(ActiveJob.isStale(null, longSilent, Duration.ofMinutes(-1), NOW));
    }

    /** The instance methods agree with the static rule they delegate to. */
    @Test(timeout = 10000)
    public void instanceMethodsDelegateToTheStaticRule() {
        Instant start = NOW.minus(Duration.ofHours(1));
        Instant beat = NOW.minus(Duration.ofMinutes(20));
        ActiveJob job = new ActiveJob("j", "ws", start, beat, "desc", null);
        assertEquals(ActiveJob.sinceHeartbeat(start, beat, NOW), job.sinceHeartbeat(NOW));
        assertEquals(ActiveJob.isStale(start, beat, SILENT, NOW), job.isStale(SILENT, NOW));
        assertTrue(job.isStale(SILENT, NOW));
        assertEquals(ActiveJob.UNRESPONSIVE, job.derivedState(SILENT, NOW));
    }
}
