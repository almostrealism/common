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

import java.util.Collections;
import java.util.Set;

/**
 * Proves that {@link AgentJobEndToEndTest} can fail: each method here runs one
 * of its scenarios against a job carrying a deliberately broken collaborator,
 * and the scenario is <em>expected to fail</em>.
 *
 * <p>A green end-to-end test is not evidence until it has been watched going
 * red for the defect it is named for. During the outage this suite was built
 * after, a test named for the live defect passed throughout, because the job
 * it constructed could not reach the code path in question. These methods are
 * the observation that each scenario still reaches its path.</p>
 *
 * <p>This class is therefore not part of the ordinary test run. Its name does
 * not match Surefire's default test patterns, so a module run skips it; it is
 * selected explicitly by {@code tools/ci/agent-e2e/run-fault-catalogue.sh},
 * which runs every method in one invocation and reads each method's verdict
 * from the Surefire report against {@code tools/ci/agent-e2e/faults/<name>/expect.txt}.
 * Every method but the control must fail; the control must pass.</p>
 *
 * <p>Each defect is a test-side double — a subclass of the production
 * collaborator — installed through a seam the job already has: the MCP
 * configuration builder, the work-outcome classification, the commit-message
 * builder, the restart governor's session cap. Production code carries no
 * defect and no switch for one. When a substituted class changes shape, this
 * file stops compiling, so the change and the update to its fault land in the
 * same pull request, made by the same author with the same permissions.</p>
 *
 * @author Michael Murray
 */
public class AgentJobFaultCatalogue extends TestSuiteBase {

    /** A scenario of {@link AgentJobEndToEndTest}, run against a prepared instance of it. */
    @FunctionalInterface
    interface Scenario {
        /**
         * Runs the scenario.
         *
         * @param e2e an end-to-end test instance whose fixture is set up
         */
        void run(AgentJobEndToEndTest e2e) throws Exception;
    }

    /**
     * Control: the scenario must PASS. Every seam the other faults use is
     * exercised with the production collaborator it normally holds, so a pass
     * shows both that the seams are inert and that the runner reading this
     * report can record a pass. A runner that reported "detected"
     * unconditionally fails here.
     */
    @Test(timeout = 180000)
    public void controlNoDefect() throws Exception {
        checkScenario(job -> {
            job.setMcpConfigBuilder(new McpConfigBuilder());
            job.setWorkOutcome(new JobWorkOutcome(job));
            job.setCommitMessageBuilder(new CommitMessageBuilder());
        }, AgentJobEndToEndTest::agentWorkReachesTheRemote);
    }

    /**
     * ar-manager is required whenever it is configured, ignoring the
     * enforcement switch that is off by default — the policy that stopped the
     * fleet. The work must still publish when ar-manager did not connect.
     */
    @Test(timeout = 180000)
    public void requiredMcpServerKillsJob() throws Exception {
        checkScenario(job -> job.setMcpConfigBuilder(new RequiresArManagerWheneverConfigured()),
                AgentJobEndToEndTest::workPublishesWhenArManagerDidNotConnect);
    }

    /**
     * The work-outcome classification reports neither dropped changes nor
     * unpublished work, so a change set the integrity gate rejected ends as
     * {@code SUCCESS}.
     */
    @Test(timeout = 240000)
    public void droppedWorkReportedAsSuccess() throws Exception {
        checkScenario(job -> job.setWorkOutcome(new ReportsNoLostWork(job)),
                AgentJobEndToEndTest::rejectedChangesAreReportedNotSilentlyDropped);
    }

    /**
     * The classification stops recognising that every change was dropped, so
     * a session that authored a commit message and published nothing is no
     * longer reported for the right reason.
     */
    @Test(timeout = 180000)
    public void allChangesDroppedNotReported() throws Exception {
        checkScenario(job -> job.setWorkOutcome(new MissesDroppedChanges(job)),
                AgentJobEndToEndTest::aCommitMessageWithNothingToPublishIsNotASuccess);
    }

    /**
     * Both of the harness's fallbacks for a missing {@code commit.txt}
     * produce an empty message, so an agent that forgot to write one loses
     * its work.
     */
    @Test(timeout = 180000)
    public void missingCommitMessageDropsWork() throws Exception {
        checkScenario(job -> job.setCommitMessageBuilder(new NoFallbackMessage()),
                AgentJobEndToEndTest::editsWithoutACommitMessageStillReachTheRemote);
    }

    /**
     * The classification loses its report of work the tampering revert
     * destroyed, so the loss looks like a session that did nothing.
     */
    @Test(timeout = 240000)
    public void revertedWorkNotReported() throws Exception {
        checkScenario(job -> job.setWorkOutcome(new ForgetsTheTamperingRevert(job)),
                AgentJobEndToEndTest::workDestroyedByTheTamperingRevertIsReported);
    }

    /**
     * The restart governor refuses every launch after the first, so the
     * restart that replaces work reverted as tampering never runs.
     */
    @Test(timeout = 240000)
    public void tamperingRestartSkipped() throws Exception {
        checkScenario(job -> job.restartGovernor().setMaxTotalSessions(1),
                AgentJobEndToEndTest::aRestartAfterTamperingRepublishesTheWork);
    }

    /**
     * Runs the checks of {@code scenario} on a fresh end-to-end fixture whose
     * jobs carry {@code fault}, tearing the fixture down whatever the outcome.
     * The scenario's own assertions are the verdict; nothing here weakens or
     * inverts them.
     *
     * @param fault    the defect every job in the scenario carries
     * @param scenario the scenario that must notice it
     */
    private void checkScenario(AgentJobEndToEndTest.Fault fault, Scenario scenario) throws Exception {
        AgentJobEndToEndTest e2e = new AgentJobEndToEndTest();
        e2e.installFault(fault);
        e2e.setUp();
        try {
            scenario.run(e2e);
        } finally {
            e2e.tearDown();
        }
    }

    /**
     * Requires ar-manager whenever it is configured, as though the
     * {@code AR_REQUIRE_MCP_SERVERS} enforcement switch had been dropped and
     * the policy were always on.
     */
    static class RequiresArManagerWheneverConfigured extends McpConfigBuilder {
        @Override
        public Set<String> requiredServerNames() {
            return arManagerEnabled() ? Set.of("ar-manager") : Collections.emptySet();
        }
    }

    /** Never recognises that every change the agent made was dropped by a staging guardrail. */
    static class MissesDroppedChanges extends JobWorkOutcome {
        /**
         * Creates the classification over {@code job}.
         *
         * @param job the job whose work it misreports
         */
        MissesDroppedChanges(GitManagedJob job) {
            super(job);
        }

        @Override
        boolean allChangesDropped() {
            return false;
        }
    }

    /** Reports no dropped changes and no unpublished work, whatever the job left behind. */
    static class ReportsNoLostWork extends MissesDroppedChanges {
        /**
         * Creates the classification over {@code job}.
         *
         * @param job the job whose work it misreports
         */
        ReportsNoLostWork(GitManagedJob job) {
            super(job);
        }

        @Override
        String describeUnpublishedWork() {
            return null;
        }
    }

    /** Never reports work the tampering revert destroyed; every other check runs as normal. */
    static class ForgetsTheTamperingRevert extends JobWorkOutcome {
        /**
         * Creates the classification over {@code job}.
         *
         * @param job the job whose work it misreports
         */
        ForgetsTheTamperingRevert(GitManagedJob job) {
            super(job);
        }

        @Override
        String describeRevertedWork() {
            return null;
        }
    }

    /** Produces an empty message from both fallbacks, as though neither had been written. */
    static class NoFallbackMessage extends CommitMessageBuilder {
        @Override
        String buildFallbackMessage(CodingAgentJob job) {
            return "";
        }

        @Override
        String buildExhaustionFallback(CodingAgentJob job) {
            return "";
        }
    }
}
