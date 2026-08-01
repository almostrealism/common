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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests that wire {@link RestartGovernor} into the live
 * {@link CodingAgentJob} restart machinery: the {@link EnforcementRunner}
 * correction loop, the {@link CodingAgentJob#executeSingleRun()} chokepoint,
 * and the {@link CodingAgentJobCodec} wire round-trip.
 *
 * <p>The headline failure these guard against is an {@code enforce-changes}
 * job whose agent never produces a usable change: historically the loop would
 * relaunch a fresh full-budget session up to twenty-five times, spending many
 * multiples of the configured dollar budget. These tests prove the loop now
 * halts the moment the global session cap, the dollar budget, or the turn
 * budget is exhausted.</p>
 */
public class CodingAgentJobRestartGovernorTest extends TestSuiteBase {

    /** Temporary working directory used as each job's sandbox. */
    private Path tempDir;

    /** Creates a fresh temporary directory before each test. */
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("restart-governor-test");
    }

    /** Recursively deletes the temporary directory after each test. */
    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /** Builds an {@link AgentRunResult} carrying the given cost and turn count. */
    private static AgentRunResult result(double costUsd, int numTurns) {
        return new AgentRunResult(0, false, "out", "sid", 1000L, 0L, numTurns, costUsd,
                "success", false, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Builds an {@code enforce-changes} job whose every session spends the given
     * cost and turns and which never produces a change, so the rule stays
     * violated and the loop relies entirely on the governor to stop.
     */
    private CodingAgentJob loopingJob(double costPerSession, int turnsPerSession) {
        CodingAgentJob job = new CodingAgentJob("task", "do the work") {
            @Override
            void executeSingleRun() {
                // Mirror production: claim a session slot, then spend its cost.
                if (restartGovernor().beginSession()) {
                    absorbResult(result(costPerSession, turnsPerSession));
                }
            }
        };
        job.setWorkingDirectory(tempDir.toString());
        job.setEnforceChanges(true);
        job.setReviewEnabled(false);
        job.setEnforceOrganizationalPlacement(false);
        // No target branch => commit-message rule is not added, isolating enforce-changes.
        return job;
    }

    /** The enforcement loop halts once cumulative cost reaches the dollar budget. */
    @Test(timeout = 30000)
    public void enforcementStopsAtDollarBudget() {
        CodingAgentJob job = loopingJob(5.0, 1);
        job.setMaxBudgetUsd(10.0);

        job.runEnforcementRules();

        assertEquals("budget should bind after 2 sessions ($10 / $5)",
                2, job.restartGovernor().getSessionsLaunched());
        assertTrue(job.restartGovernor().blockReason().contains("dollar budget"));
    }

    /** The enforcement loop halts once the global session cap is reached. */
    @Test(timeout = 30000)
    public void enforcementStopsAtGlobalSessionCap() {
        CodingAgentJob job = loopingJob(0.0, 1);
        job.setMaxBudgetUsd(1000.0);
        job.restartGovernor().setMaxTotalSessions(3);

        job.runEnforcementRules();

        assertEquals(3, job.restartGovernor().getSessionsLaunched());
        assertTrue(job.restartGovernor().blockReason().contains("global session cap"));
    }

    /** The enforcement loop halts once cumulative turns reach the turn budget. */
    @Test(timeout = 30000)
    public void enforcementStopsAtTurnBudget() {
        CodingAgentJob job = loopingJob(0.0, 10);
        job.setMaxBudgetUsd(1000.0);
        job.restartGovernor().setMaxTotalTurns(20);

        job.runEnforcementRules();

        assertEquals(2, job.restartGovernor().getSessionsLaunched());
        assertTrue(job.restartGovernor().blockReason().contains("turn budget"));
    }

    /**
     * The dollar budget binds well before the per-rule retry cap (5) and the
     * total enforcement cap (25), proving the budget — not the legacy caps — is
     * what bounds an unproductive job's spend.
     */
    @Test(timeout = 30000)
    public void dollarBudgetBindsBeforeLegacyCaps() {
        CodingAgentJob job = loopingJob(5.0, 1);
        job.setMaxBudgetUsd(10.0);

        job.runEnforcementRules();

        assertTrue("must stop far below the 25-attempt enforcement cap",
                job.restartGovernor().getSessionsLaunched() < 5);
    }

    /**
     * The real {@link CodingAgentJob#executeSingleRun()} chokepoint refuses to
     * launch — running nothing — once the governor reports a stop condition.
     */
    @Test(timeout = 30000)
    public void realExecuteSingleRunNoOpsWhenGovernorBlocked() {
        CodingAgentJob job = new CodingAgentJob("task", "do the work");
        job.setWorkingDirectory(tempDir.toString());
        RestartGovernor gov = job.restartGovernor();
        gov.setMaxTotalSessions(1);
        gov.beginSession(); // simulate the primary session already consumed

        // Calling the real method is safe: the guard returns before any runner
        // is resolved or launched, so no subprocess is spawned.
        job.executeSingleRun();

        assertEquals("no further session may launch", 1, gov.getSessionsLaunched());
        assertTrue("nothing ran, so no output was captured", job.getOutput().isEmpty());
    }

    /** Custom restart ceilings survive a wire encode/decode round-trip. */
    @Test(timeout = 30000)
    public void customRestartCeilingsRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("task", "do the work");
        job.restartGovernor().setMaxTotalSessions(7);
        job.restartGovernor().setMaxTotalTurns(42);

        CodingAgentJob decoded = GitManagedJobSerializationTest.roundTrip(job);

        assertEquals(7, decoded.restartGovernor().getMaxTotalSessions());
        assertEquals(42, decoded.restartGovernor().getMaxTotalTurns());
    }

    /** Default restart ceilings survive a wire encode/decode round-trip. */
    @Test(timeout = 30000)
    public void defaultRestartCeilingsRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("task", "do the work");

        CodingAgentJob decoded = GitManagedJobSerializationTest.roundTrip(job);

        assertEquals(RestartGovernor.DEFAULT_MAX_TOTAL_SESSIONS,
                decoded.restartGovernor().getMaxTotalSessions());
        assertEquals(RestartGovernor.DEFAULT_MAX_TOTAL_TURNS,
                decoded.restartGovernor().getMaxTotalTurns());
    }

    /** A decoded job still exposes a usable, non-null governor. */
    @Test(timeout = 30000)
    public void decodedJobHasGovernor() {
        CodingAgentJob job = new CodingAgentJob("task", "do the work");
        CodingAgentJob decoded = GitManagedJobSerializationTest.roundTrip(job);
        assertNotNull(decoded.restartGovernor());
        assertTrue(decoded.restartGovernor().canLaunchSession());
    }
}
