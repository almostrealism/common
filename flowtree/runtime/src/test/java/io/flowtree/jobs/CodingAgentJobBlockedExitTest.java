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

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the structured "blocked" exit: an agent that reports a {@link BlockReport}
 * through the {@code job_report_blocked} tool ends its job with the terminal
 * {@link JobCompletionEvent.Status#BLOCKED} status and is not retried by
 * {@link EnforcementRunner}.
 */
public class CodingAgentJobBlockedExitTest extends TestSuiteBase {

    /**
     * A job whose correction sessions are recorded rather than launched, so a
     * test can assert whether enforcement tried to retry it. Review is enabled
     * (the default) and the change-dependent rules are off so that, absent a
     * block, {@link EnforcementRunner} would have at least the review rule to
     * run — making the "nothing ran" assertion meaningful.
     */
    private static final class SpyJob extends CodingAgentJob {
        /** Rule names whose correction session the runner invoked. */
        private final List<String> correctionRules = new ArrayList<>();

        /** Creates a spy whose primary left reviewable changes in the tree. */
        SpyJob() {
            super("blk-1", "do the thing");
            setWorkingDirectory("/tmp/blocked-exit-test");
            setEnforceChanges(false);
            setEnforceOrganizationalPlacement(false);
            setObjectOrientedReviewEnabled(false);
        }

        @Override boolean hasUncommittedChanges() { return true; }

        @Override List<String> extractNewFilePaths() { return Collections.emptyList(); }

        @Override List<String> extractChangedFilePaths() { return Collections.emptyList(); }

        @Override
        protected void runCorrectionSession(String prompt, String ruleName) {
            correctionRules.add(ruleName);
        }

        /** Returns the rule names whose correction session ran. */
        List<String> correctionRules() { return correctionRules; }
    }

    /** A reported block produces a terminal BLOCKED event carrying its category and detail. */
    @Test(timeout = 30000)
    public void reportedBlockEndsJobBlocked() {
        CodingAgentJob job = new CodingAgentJob("blk-event", "write the report");
        job.sessionAccumulator().setBlockReport(
                new BlockReport(BlockReport.MISSING_RESOURCE, "model file absent", true));

        CodingAgentJobEvent event = job.createEvent(null);

        assertEquals(JobCompletionEvent.Status.BLOCKED, event.getStatus());
        assertEquals("missing_resource", event.getBlockedCategory());
        assertEquals("model file absent", event.getBlockedDetail());
        assertTrue(event.isBlockedVerified());
        assertTrue("the reason summary travels in the error-message slot",
                event.getErrorMessage().contains("model file absent"));
    }

    /**
     * A reported block is authoritative over a non-zero process exit. An agent
     * that declares it cannot proceed and whose session then exits non-zero
     * still ends {@link JobCompletionEvent.Status#BLOCKED}, not {@code FAILED}:
     * the block is a deliberate, recorded declaration, so the exit code does not
     * override it. Only a genuine worker exception would.
     */
    @Test(timeout = 30000)
    public void reportedBlockOutranksNonZeroExit() {
        CodingAgentJob job = new CodingAgentJob("blk-nonzero-exit", "write the report");
        job.absorbResult(new AgentRunResult(
                3, false, "partial output", "sess-blk",
                5000L, 4000L, 2, 0.02,
                "error", true,
                Collections.emptyList(), Collections.emptyMap()));
        job.sessionAccumulator().setBlockReport(
                new BlockReport(BlockReport.MISSING_RESOURCE, "model file absent", true));

        CodingAgentJobEvent event = job.createEvent(null);

        assertEquals(JobCompletionEvent.Status.BLOCKED, event.getStatus());
        assertEquals("missing_resource", event.getBlockedCategory());
        assertEquals("model file absent", event.getBlockedDetail());
    }

    /**
     * A genuine worker exception still takes precedence over a reported block:
     * when the job machinery itself threw, the terminal status is
     * {@code FAILED}, carrying the exception, rather than {@code BLOCKED}.
     */
    @Test(timeout = 30000)
    public void workerExceptionOutranksReportedBlock() {
        CodingAgentJob job = new CodingAgentJob("blk-worker-exc", "write the report");
        job.sessionAccumulator().setBlockReport(
                new BlockReport(BlockReport.MISSING_RESOURCE, "model file absent", true));

        CodingAgentJobEvent event = job.createEvent(
                new IllegalStateException("worker machinery failed"));

        assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
    }

    /** An unverified guardrail claim still ends BLOCKED but is flagged unverified. */
    @Test(timeout = 30000)
    public void unverifiedGuardrailBlockIsFlagged() {
        CodingAgentJob job = new CodingAgentJob("blk-unverified", "write the report");
        job.sessionAccumulator().setBlockReport(
                new BlockReport(BlockReport.GUARDRAIL, "claimed a hook denial", false));

        CodingAgentJobEvent event = job.createEvent(null);

        assertEquals(JobCompletionEvent.Status.BLOCKED, event.getStatus());
        assertFalse(event.isBlockedVerified());
        assertTrue(event.getErrorMessage().contains("unverified"));
    }

    /** Enforcement runs no correction session for a job that reported blocked. */
    @Test(timeout = 30000)
    public void enforcementDoesNotRetryABlockedJob() {
        SpyJob job = new SpyJob();
        assertTrue("review would otherwise be eligible to run", job.isReviewEnabled());
        job.sessionAccumulator().setBlockReport(
                new BlockReport(BlockReport.OUT_OF_SCOPE, "belongs on another branch", true));

        job.runEnforcementRules();

        assertTrue("no rule may be retried once the agent reported blocked",
                job.correctionRules().isEmpty());
    }

    /** A job that did not report blocked produces no block report and reaches its normal rules. */
    @Test(timeout = 30000)
    public void unblockedJobStillRunsEnforcement() {
        SpyJob job = new SpyJob();

        job.runEnforcementRules();

        assertTrue("the review rule runs when nothing reported a block",
                job.correctionRules().contains("review"));
    }

    /** The block categories are exactly the fixed set the tool and endpoint share. */
    @Test(timeout = 30000)
    public void categorySetIsFixed() {
        assertTrue(BlockReport.isValidCategory("guardrail"));
        assertTrue(BlockReport.isValidCategory("already_done"));
        assertFalse(BlockReport.isValidCategory("something_else"));
        assertFalse(BlockReport.isValidCategory(null));
        assertEquals(5, BlockReport.CATEGORIES.size());
    }

    /**
     * The blocked-report URL is rebuilt around the job id, so it names the job
     * even when the job's workstream URL is the workstream-level URL a
     * completion-listener job carries (no {@code /jobs/{jobId}} segment).
     */
    @Test(timeout = 30000)
    public void blockedUrlIsBuiltFromJobIdForWorkstreamLevelUrl() {
        assertEquals("http://host:7780/api/workstreams/ws-1/jobs/job-9/blocked",
                BlockReport.blockedUrl("http://host:7780/api/workstreams/ws-1", "job-9"));
    }

    /**
     * A workstream URL that already names a different job is still rebuilt
     * around the supplied job id, not appended to.
     */
    @Test(timeout = 30000)
    public void blockedUrlRebuildsAroundSuppliedJobId() {
        assertEquals("http://host:7780/api/workstreams/ws-1/jobs/job-9/blocked",
                BlockReport.blockedUrl("http://host:7780/api/workstreams/ws-1/jobs/other", "job-9"));
    }

    /** A missing workstream URL, job id, or non-workstream URL yields no URL. */
    @Test(timeout = 30000)
    public void blockedUrlRequiresBothInputs() {
        assertNull(BlockReport.blockedUrl(null, "job-9"));
        assertNull(BlockReport.blockedUrl("http://host:7780/api/workstreams/ws-1", null));
        assertNull(BlockReport.blockedUrl("http://host:7780/api/workstreams/ws-1", ""));
        assertNull(BlockReport.blockedUrl("http://host:7780/health", "job-9"));
    }

    /** The registry delivers a reported block once, removing it as it is read. */
    @Test(timeout = 30000)
    public void registryConsumesReportOnRead() {
        BlockReportRegistry registry = new BlockReportRegistry();
        registry.record("job-7", new BlockReport(BlockReport.ALREADY_DONE, "on master", true));

        Optional<BlockReport> first = registry.consume("job-7");
        assertTrue("the first read finds the report", first.isPresent());
        assertEquals("already_done", first.get().getCategory());

        assertTrue("a second read finds nothing — the report was consumed",
                registry.consume("job-7").isEmpty());
    }

    /** A null id or an unknown job consumes nothing rather than failing. */
    @Test(timeout = 30000)
    public void registryConsumeToleratesMissing() {
        BlockReportRegistry registry = new BlockReportRegistry();
        assertTrue(registry.consume(null).isEmpty());
        assertTrue(registry.consume("no-such-job").isEmpty());
    }
}
