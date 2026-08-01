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

import io.flowtree.jobs.agent.AgentRunResult;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link RestartGovernor}, the single authority that decides
 * whether a {@link CodingAgentJob} may launch another agent session.
 *
 * <p>These tests pin down the three universal stop conditions — the global
 * session cap, the job-wide dollar budget, and the job-wide turn budget — plus
 * the always-allow-the-first-session guarantee, the configuration validation,
 * and the inactivity-restart relaunch loop. Together with
 * {@link CodingAgentJobRestartGovernorTest} they form the regression net that
 * makes a runaway restart loop (the {@code enforce-changes}-with-no-progress
 * failure that has cost thousands of dollars) impossible to reintroduce
 * unnoticed.</p>
 */
public class RestartGovernorTest extends TestSuiteBase {

    /** Builds a job with the default budget ($10) for governor tests. */
    private CodingAgentJob newJob() {
        return new CodingAgentJob("task", "do the work");
    }

    /** Builds an {@link AgentRunResult} carrying the given cost, turn count, and inactivity-kill flag. */
    private static AgentRunResult result(double costUsd, int numTurns, boolean killed) {
        return new AgentRunResult(0, killed, "out", "sid", 1000L, 0L, numTurns, costUsd,
                "success", false, Collections.emptyList(), Collections.emptyMap());
    }

    // ── Defaults ──────────────────────────────────────────────────────────────

    /** The default global session cap is 30. */
    @Test(timeout = 30000)
    public void defaultMaxTotalSessionsIsThirty() {
        assertEquals(30, newJob().restartGovernor().getMaxTotalSessions());
        assertEquals(30, RestartGovernor.DEFAULT_MAX_TOTAL_SESSIONS);
    }

    /** The default job-wide turn budget is 1000. */
    @Test(timeout = 30000)
    public void defaultMaxTotalTurnsIsOneThousand() {
        assertEquals(1000, newJob().restartGovernor().getMaxTotalTurns());
        assertEquals(1000, RestartGovernor.DEFAULT_MAX_TOTAL_TURNS);
    }

    /** The default inactivity-restart cap is 3. */
    @Test(timeout = 30000)
    public void defaultMaxInactivityRestartsIsThree() {
        assertEquals(3, newJob().restartGovernor().getMaxInactivityRestarts());
        assertEquals(3, RestartGovernor.DEFAULT_MAX_INACTIVITY_RESTARTS);
    }

    /** A fresh governor reports zero sessions launched. */
    @Test(timeout = 30000)
    public void freshGovernorHasZeroSessionsLaunched() {
        assertEquals(0, newJob().restartGovernor().getSessionsLaunched());
    }

    /** A job always exposes a non-null governor. */
    @Test(timeout = 30000)
    public void jobExposesGovernor() {
        assertNotNull(newJob().restartGovernor());
    }

    // ── First session always allowed ───────────────────────────────────────────

    /** The very first session is permitted even when the dollar budget is zero. */
    @Test(timeout = 30000)
    public void firstSessionAllowedWithZeroBudget() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(0.0);
        assertTrue(job.restartGovernor().canLaunchSession());
        assertTrue(job.restartGovernor().beginSession());
    }

    /** The first session is permitted even when the global cap is one. */
    @Test(timeout = 30000)
    public void firstSessionAllowedWithGlobalCapOfOne() {
        CodingAgentJob job = newJob();
        job.restartGovernor().setMaxTotalSessions(1);
        assertTrue(job.restartGovernor().beginSession());
    }

    /** {@code canLaunchSession} is true on a brand-new governor. */
    @Test(timeout = 30000)
    public void canLaunchSessionTrueInitially() {
        assertTrue(newJob().restartGovernor().canLaunchSession());
    }

    // ── Global session cap ─────────────────────────────────────────────────────

    /** Sessions are permitted up to the cap, then refused. */
    @Test(timeout = 30000)
    public void globalCapBlocksAfterMaxSessions() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(3);
        assertTrue(gov.beginSession());
        assertTrue(gov.beginSession());
        assertTrue(gov.beginSession());
        assertFalse(gov.beginSession());
        assertEquals(3, gov.getSessionsLaunched());
    }

    /** A cap of one allows only the primary session. */
    @Test(timeout = 30000)
    public void globalCapOfOneAllowsOnlyPrimary() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(1);
        assertTrue(gov.beginSession());
        assertFalse(gov.beginSession());
    }

    /** The block reason names the global session cap. */
    @Test(timeout = 30000)
    public void blockReasonNamesGlobalSessionCap() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(1);
        gov.beginSession();
        assertFalse(gov.canLaunchSession());
        assertTrue(gov.blockReason().contains("global session cap"));
    }

    /** {@code beginSession} increments the launch counter on success. */
    @Test(timeout = 30000)
    public void beginSessionIncrementsCounter() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        gov.beginSession();
        assertEquals(2, gov.getSessionsLaunched());
    }

    /** A refused {@code beginSession} does not increment the launch counter. */
    @Test(timeout = 30000)
    public void blockedBeginSessionDoesNotIncrementCounter() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(1);
        gov.beginSession();
        int before = gov.getSessionsLaunched();
        assertFalse(gov.beginSession());
        assertEquals(before, gov.getSessionsLaunched());
    }

    /** The global cap is the stop even when budget headroom remains. */
    @Test(timeout = 30000)
    public void globalCapTakesPriorityOverBudget() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(1000.0);
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(2);
        gov.beginSession();
        gov.beginSession();
        assertFalse(gov.canLaunchSession());
        assertTrue(gov.blockReason().contains("global session cap"));
    }

    // ── Dollar budget ──────────────────────────────────────────────────────────

    /** Reaching the dollar budget blocks further sessions. */
    @Test(timeout = 30000)
    public void dollarBudgetBlocksWhenSpendReachesBudget() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(10.0);
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        job.absorbResult(result(10.0, 5, false));
        assertFalse(gov.canLaunchSession());
    }

    /** Staying under the dollar budget permits further sessions. */
    @Test(timeout = 30000)
    public void dollarBudgetAllowsWhenUnderBudget() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(10.0);
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        job.absorbResult(result(4.0, 5, false));
        assertTrue(gov.canLaunchSession());
    }

    /** Spend exactly equal to the budget blocks (the comparison is {@code >=}). */
    @Test(timeout = 30000)
    public void dollarBudgetExactlyAtBudgetBlocks() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(7.5);
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        job.absorbResult(result(7.5, 1, false));
        assertFalse(gov.canLaunchSession());
    }

    /** A negative budget disables the dollar check entirely. */
    @Test(timeout = 30000)
    public void negativeBudgetDisablesDollarCheck() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(-1.0);
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        job.absorbResult(result(1000.0, 5, false));
        assertTrue(gov.canLaunchSession());
    }

    /** The block reason names the dollar budget. */
    @Test(timeout = 30000)
    public void blockReasonNamesDollarBudget() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(5.0);
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        job.absorbResult(result(5.0, 1, false));
        assertFalse(gov.canLaunchSession());
        assertTrue(gov.blockReason().contains("dollar budget"));
    }

    /** Cost accumulates across sessions and eventually trips the budget. */
    @Test(timeout = 30000)
    public void dollarBudgetAccumulatesAcrossSessions() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(10.0);
        RestartGovernor gov = job.restartGovernor();
        gov.beginSession();
        job.absorbResult(result(4.0, 1, false));
        assertTrue(gov.canLaunchSession());
        gov.beginSession();
        job.absorbResult(result(4.0, 1, false));
        assertTrue(gov.canLaunchSession());
        gov.beginSession();
        job.absorbResult(result(4.0, 1, false));
        assertFalse(gov.canLaunchSession());
    }

    /** A zero budget allows only the first session. */
    @Test(timeout = 30000)
    public void zeroBudgetBlocksSecondSession() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(0.0);
        RestartGovernor gov = job.restartGovernor();
        assertTrue(gov.beginSession());
        assertFalse(gov.canLaunchSession());
    }

    /** The dollar budget is the stop named when both dollar and turn budgets are exceeded. */
    @Test(timeout = 30000)
    public void dollarBudgetCheckedBeforeTurnBudget() {
        CodingAgentJob job = newJob();
        job.setMaxBudgetUsd(5.0);
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(10);
        gov.beginSession();
        job.absorbResult(result(5.0, 10, false));
        assertFalse(gov.canLaunchSession());
        assertTrue(gov.blockReason().contains("dollar budget"));
    }

    // ── Turn budget ────────────────────────────────────────────────────────────

    /** Reaching the turn budget blocks further sessions. */
    @Test(timeout = 30000)
    public void turnBudgetBlocksWhenTurnsReachCap() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(20);
        gov.beginSession();
        job.absorbResult(result(0.0, 20, false));
        assertFalse(gov.canLaunchSession());
    }

    /** Staying under the turn budget permits further sessions. */
    @Test(timeout = 30000)
    public void turnBudgetAllowsUnderCap() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(20);
        gov.beginSession();
        job.absorbResult(result(0.0, 10, false));
        assertTrue(gov.canLaunchSession());
    }

    /** Turns exactly equal to the budget block (the comparison is {@code >=}). */
    @Test(timeout = 30000)
    public void turnBudgetExactlyAtCapBlocks() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(15);
        gov.beginSession();
        job.absorbResult(result(0.0, 15, false));
        assertFalse(gov.canLaunchSession());
    }

    /** A zero turn budget disables the turn check. */
    @Test(timeout = 30000)
    public void zeroMaxTotalTurnsDisablesTurnCheck() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(0);
        gov.beginSession();
        job.absorbResult(result(0.0, 9999, false));
        assertTrue(gov.canLaunchSession());
    }

    /** The block reason names the turn budget. */
    @Test(timeout = 30000)
    public void blockReasonNamesTurnBudget() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(10);
        gov.beginSession();
        job.absorbResult(result(0.0, 10, false));
        assertFalse(gov.canLaunchSession());
        assertTrue(gov.blockReason().contains("turn budget"));
    }

    /** Turns accumulate across sessions and eventually trip the budget. */
    @Test(timeout = 30000)
    public void turnBudgetAccumulatesAcrossSessions() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalTurns(30);
        gov.beginSession();
        job.absorbResult(result(0.0, 20, false));
        assertTrue(gov.canLaunchSession());
        gov.beginSession();
        job.absorbResult(result(0.0, 20, false));
        assertFalse(gov.canLaunchSession());
    }

    // ── Configuration validation ───────────────────────────────────────────────

    /** {@code setMaxTotalSessions(0)} is rejected. */
    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setMaxTotalSessionsRejectsZero() {
        newJob().restartGovernor().setMaxTotalSessions(0);
    }

    /** {@code setMaxTotalSessions(-5)} is rejected. */
    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setMaxTotalSessionsRejectsNegative() {
        newJob().restartGovernor().setMaxTotalSessions(-5);
    }

    /** {@code setMaxTotalSessions(1)} is accepted. */
    @Test(timeout = 30000)
    public void setMaxTotalSessionsAcceptsOne() {
        RestartGovernor gov = newJob().restartGovernor();
        gov.setMaxTotalSessions(1);
        assertEquals(1, gov.getMaxTotalSessions());
    }

    /** {@code setMaxTotalTurns(-1)} is rejected. */
    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setMaxTotalTurnsRejectsNegative() {
        newJob().restartGovernor().setMaxTotalTurns(-1);
    }

    /** {@code setMaxTotalTurns(0)} is accepted (disables the check). */
    @Test(timeout = 30000)
    public void setMaxTotalTurnsAcceptsZero() {
        RestartGovernor gov = newJob().restartGovernor();
        gov.setMaxTotalTurns(0);
        assertEquals(0, gov.getMaxTotalTurns());
    }

    /** {@code setMaxInactivityRestarts(-1)} is rejected. */
    @Test(timeout = 30000, expected = IllegalArgumentException.class)
    public void setMaxInactivityRestartsRejectsNegative() {
        newJob().restartGovernor().setMaxInactivityRestarts(-1);
    }

    /** {@code setMaxInactivityRestarts(0)} is accepted (single attempt, no relaunch). */
    @Test(timeout = 30000)
    public void setMaxInactivityRestartsAcceptsZero() {
        RestartGovernor gov = newJob().restartGovernor();
        gov.setMaxInactivityRestarts(0);
        assertEquals(0, gov.getMaxInactivityRestarts());
    }

    // ── Inactivity-restart relaunch loop ───────────────────────────────────────

    /** A non-killed attempt runs exactly once and is not flagged as killed. */
    @Test(timeout = 30000)
    public void runWithInactivityRetriesRunsOnceWhenNotKilled() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        AtomicInteger calls = new AtomicInteger();
        AgentRunResult r = gov.runWithInactivityRetries("claude", a -> {
            calls.incrementAndGet();
            return result(1.0, 1, false);
        });
        assertEquals(1, calls.get());
        assertFalse(gov.wasKilledForInactivity());
        assertNotNull(r);
    }

    /** An inactivity kill is retried; the loop stops on the first non-killed attempt. */
    @Test(timeout = 30000)
    public void runWithInactivityRetriesRetriesThenSucceeds() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxInactivityRestarts(3);
        AtomicInteger calls = new AtomicInteger();
        gov.runWithInactivityRetries("claude", a -> {
            int n = calls.incrementAndGet();
            return result(0.0, 0, n == 1);
        });
        assertEquals(2, calls.get());
        assertFalse(gov.wasKilledForInactivity());
    }

    /** When every attempt is killed, the loop runs {@code maxInactivityRestarts + 1} times. */
    @Test(timeout = 30000)
    public void runWithInactivityRetriesStopsAtCap() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxInactivityRestarts(2);
        AtomicInteger calls = new AtomicInteger();
        gov.runWithInactivityRetries("claude", a -> {
            calls.incrementAndGet();
            return result(0.0, 0, true);
        });
        assertEquals(3, calls.get());
        assertTrue(gov.wasKilledForInactivity());
    }

    /** A cap of zero runs exactly one attempt even when it is killed. */
    @Test(timeout = 30000)
    public void runWithInactivityRetriesZeroCapRunsOnce() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxInactivityRestarts(0);
        AtomicInteger calls = new AtomicInteger();
        gov.runWithInactivityRetries("claude", a -> {
            calls.incrementAndGet();
            return result(0.0, 0, true);
        });
        assertEquals(1, calls.get());
    }

    /** The attempt index is set before each attempt and reset to zero afterward. */
    @Test(timeout = 30000)
    public void runWithInactivityRetriesTracksAttemptIndex() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxInactivityRestarts(3);
        List<Integer> seen = new ArrayList<>();
        gov.runWithInactivityRetries("claude", a -> {
            seen.add(gov.getInactivityRestartAttempt());
            return result(0.0, 0, a < 2);
        });
        assertEquals(List.of(0, 1, 2), seen);
        assertEquals(0, gov.getInactivityRestartAttempt());
    }

    /** {@code setWasKilledForInactivity} mirrors the flag for test spies. */
    @Test(timeout = 30000)
    public void setWasKilledForInactivityMirrorsFlag() {
        RestartGovernor gov = newJob().restartGovernor();
        gov.setWasKilledForInactivity(true);
        assertTrue(gov.wasKilledForInactivity());
        gov.setWasKilledForInactivity(false);
        assertFalse(gov.wasKilledForInactivity());
    }

    /** {@code canLaunchSession} does not mutate state — repeated calls are stable. */
    @Test(timeout = 30000)
    public void canLaunchSessionIsSideEffectFree() {
        CodingAgentJob job = newJob();
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(2);
        gov.beginSession();
        assertTrue(gov.canLaunchSession());
        assertTrue(gov.canLaunchSession());
        assertEquals(1, gov.getSessionsLaunched());
    }
}
