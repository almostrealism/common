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
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the resource limits a {@link CodingAgentJobFactory} gives its jobs,
 * and for the per-job silence budget the job's {@link RestartGovernor} applies.
 */
public class JobResourceLimitsTest extends TestSuiteBase {

    /** A runner's default silence budget, in milliseconds, for the clamping tests. */
    private static final long RUNNER_DEFAULT_MILLIS = Duration.ofMinutes(35).toMillis();

    /** Without an override, a session runs under its runner's budget. */
    @Test(timeout = 30000)
    public void noOverrideUsesTheRunnerBudget() {
        RestartGovernor gov = new CodingAgentJob("t", "p").restartGovernor();
        assertNull(gov.getInactivityTimeout());
        assertEquals(RUNNER_DEFAULT_MILLIS, gov.inactivityTimeoutMillis(RUNNER_DEFAULT_MILLIS));
    }

    /** A shorter override is honoured. */
    @Test(timeout = 30000)
    public void shorterOverrideIsHonoured() {
        RestartGovernor gov = new CodingAgentJob("t", "p").restartGovernor();
        gov.setInactivityTimeout(Duration.ofMinutes(10));
        assertEquals(Duration.ofMinutes(10).toMillis(), gov.inactivityTimeoutMillis(RUNNER_DEFAULT_MILLIS));
    }

    /** A longer override is clamped to the runner's budget. */
    @Test(timeout = 30000)
    public void longerOverrideIsClampedToTheRunnerBudget() {
        RestartGovernor gov = new CodingAgentJob("t", "p").restartGovernor();
        gov.setInactivityTimeout(Duration.ofHours(2));
        assertEquals(RUNNER_DEFAULT_MILLIS, gov.inactivityTimeoutMillis(RUNNER_DEFAULT_MILLIS));
    }

    /** A budget that is not positive is rejected, on the governor and on the factory. */
    @Test(timeout = 30000)
    public void nonPositiveBudgetIsRejected() {
        RestartGovernor gov = new CodingAgentJob("t", "p").restartGovernor();
        try {
            gov.setInactivityTimeout(Duration.ZERO);
            fail("zero must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNull(gov.getInactivityTimeout());
        }

        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("p");
        try {
            factory.setInactivityTimeoutSeconds(-5);
            fail("a negative budget must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNull(factory.getInactivityTimeoutSeconds());
        }
    }

    /**
     * The silence budget set on a factory survives the factory's round trip,
     * reaches the job it creates, and survives the job's own round trip, which
     * is how it travels to the node that runs the job.
     */
    @Test(timeout = 30000)
    public void silenceBudgetTravelsFromFactoryToJobToNode() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("p");
        factory.setInactivityTimeoutSeconds(600);

        CodingAgentJob.Factory restoredFactory = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(Integer.valueOf(600), restoredFactory.getInactivityTimeoutSeconds());

        CodingAgentJob job = (CodingAgentJob) restoredFactory.nextJob();
        assertEquals(Duration.ofSeconds(600), job.restartGovernor().getInactivityTimeout());

        CodingAgentJob onNode = GitManagedJobSerializationTest.roundTrip(job);
        assertEquals(Duration.ofSeconds(600), onNode.restartGovernor().getInactivityTimeout());
        assertEquals(Duration.ofMinutes(10).toMillis(),
                onNode.restartGovernor().inactivityTimeoutMillis(RUNNER_DEFAULT_MILLIS));
    }

    /** A factory with no silence budget publishes none, and its jobs use the runner's. */
    @Test(timeout = 30000)
    public void unsetSilenceBudgetIsAbsentFromTheWire() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("p");
        assertFalse(factory.encode().contains("inactivityTimeoutSeconds:="));

        CodingAgentJob job = (CodingAgentJob) GitManagedJobSerializationTest.roundTripFactory(factory).nextJob();
        assertNull(job.restartGovernor().getInactivityTimeout());
        assertFalse(job.encode().contains("inactivityTimeoutMs:="));
    }

    /**
     * The turn and spend caps and the wall-clock ceiling, now held by
     * {@link JobResourceLimits}, keep their wire keys, survive the factory's
     * round trip and reach the job as before.
     */
    @Test(timeout = 30000)
    public void capsAndCeilingStillTravelToTheJob() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("p");
        factory.setMaxTurns(120);
        factory.setMaxBudgetUsd(12.5);
        factory.setMaxWallClockHours(9);
        String encoded = factory.encode();
        assertTrue(encoded.contains("maxTurns:=120"));
        assertTrue(encoded.contains("maxBudget:=12.5"));
        assertTrue(encoded.contains("maxWallClockHours:=9"));

        CodingAgentJob.Factory restored = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(120, restored.getMaxTurns());
        assertEquals(12.5, restored.getMaxBudgetUsd(), 0.0);
        assertEquals(Integer.valueOf(9), restored.getMaxWallClockHours());

        CodingAgentJob job = (CodingAgentJob) restored.nextJob();
        assertEquals(120, job.getMaxTurns());
        assertEquals(12.5, job.getMaxBudgetUsd(), 0.0);
        assertEquals(Duration.ofHours(9), job.restartGovernor().getMaxWallClock());
    }

    /** A fresh factory starts from the documented defaults, which reach its jobs. */
    @Test(timeout = 30000)
    public void defaultsReachTheJob() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("p");
        assertEquals(JobResourceLimits.DEFAULT_MAX_TURNS, factory.getMaxTurns());
        assertEquals(JobResourceLimits.DEFAULT_MAX_BUDGET_USD, factory.getMaxBudgetUsd(), 0.0);
        assertNull(factory.getMaxWallClockHours());

        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertEquals(JobResourceLimits.DEFAULT_MAX_TURNS, job.getMaxTurns());
        assertEquals(RestartGovernor.DEFAULT_MAX_WALL_CLOCK, job.restartGovernor().getMaxWallClock());
    }
}
