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
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Covers the one per-PR remediation-state record: the
 * {@link RemediationState} predicates and transitions, the
 * {@link RemediationStateStore} counting of dispatched passes, and the
 * {@link RemediationBudgetGuard} submit-time decision — the per-PR cap on
 * dispatched passes and the foreign-commit reset.
 *
 * <p>Passes are counted when they are <em>dispatched</em>
 * ({@link RemediationStateStore#evaluateSubmission}), not when they complete. A
 * pass triggered by the loop's own last-produced commit continues counting; a
 * commit the loop did not produce (a person's push, or {@code null}) resets the
 * count. A completed pass records only the commit it produced
 * ({@link RemediationStateStore#completeJob}), so the next pass that commit
 * triggers is recognised as the loop's own.</p>
 */
public class RemediationStateStoreTest extends TestSuiteBase {

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private RemediationStateStore newStore(File dir) {
        RemediationStateStore store = new RemediationStateStore(
                new File(dir, "remediation").getAbsolutePath());
        store.initialize();
        return store;
    }

    /**
     * Drives the store through {@code passes} dispatched remediation passes using
     * the self-sustaining own-commit loop: each pass dispatches (counted), then
     * records the commit it produced so the next pass is triggered by the loop's
     * own commit and continues counting rather than resetting. Builds under an
     * effectively unlimited budget so the cap never fires during the build-up.
     *
     * @param store  the store to drive
     * @param ws     the workstream key
     * @param passes the number of dispatched passes to accumulate
     * @return the last commit a pass produced, for use as the next trigger (any
     *         other trigger is a foreign commit and resets the accumulated count)
     */
    private String driveToPassCount(RemediationStateStore store, String ws, int passes) {
        String commit = null;
        for (int i = 0; i < passes; i++) {
            store.evaluateSubmission(ws, Integer.MAX_VALUE, commit);
            commit = ws + "-sha-" + i;
            store.registerJob(ws + "-job-" + i, ws);
            store.completeJob(ws + "-job-" + i, ws, "b", commit, true);
        }
        return commit;
    }

    /** The cap is reached at the threshold, not before. */
    @Test(timeout = 30000)
    public void budgetExhaustedAtTheThresholdNotBefore() {
        assertFalse(new RemediationState("ws", 4, null, false).budgetExhausted(5));
        assertTrue(new RemediationState("ws", 5, null, false).budgetExhausted(5));
        assertTrue(new RemediationState("ws", 6, null, false).budgetExhausted(5));
    }

    /** A non-positive budget disables the cap entirely. */
    @Test(timeout = 30000)
    public void aNonPositiveBudgetNeverExhausts() {
        assertFalse(new RemediationState("ws", 99, null, false).budgetExhausted(0));
    }

    /** Only the exact, non-empty last-produced commit is the loop's own. */
    @Test(timeout = 30000)
    public void onlyTheExactLastProducedCommitIsTheLoopsOwn() {
        RemediationState state = new RemediationState("ws", 0, "abc123", false);
        assertTrue(state.wasProducedByRemediationPass("abc123"));
        assertFalse(state.wasProducedByRemediationPass("def456"));
        assertFalse(state.wasProducedByRemediationPass(null));
        assertFalse(state.wasProducedByRemediationPass(""));
        assertFalse(new RemediationState("ws", 0, null, false)
                .wasProducedByRemediationPass("abc123"));
    }

    /** Dispatching a pass increments the count and carries the commit and flag. */
    @Test(timeout = 30000)
    public void afterDispatchIncrementsAndCarriesTheCommitAndFlag() {
        RemediationState base = new RemediationState("ws", 3, "old", true);
        RemediationState dispatched = base.afterDispatch();
        assertEquals(4, dispatched.getPassCount());
        assertEquals("old", dispatched.getLastProducedCommit());
        assertTrue("dispatch does not clear the notified flag", dispatched.isNotified());
    }

    /** A foreign commit resets the count and clears the notified flag, keeping the commit. */
    @Test(timeout = 30000)
    public void afterForeignCommitResetsCountAndNotified() {
        RemediationState base = new RemediationState("ws", 3, "old", true);
        RemediationState reset = base.afterForeignCommitReset();
        assertEquals(0, reset.getPassCount());
        assertFalse("a foreign commit clears the notified flag", reset.isNotified());
        assertEquals("old", reset.getLastProducedCommit());
    }

    /** Recording a produced commit keeps the count and the notified flag. */
    @Test(timeout = 30000)
    public void withProducedCommitKeepsTheCountAndFlag() {
        RemediationState base = new RemediationState("ws", 3, "old", true);
        RemediationState produced = base.withProducedCommit("new-sha");
        assertEquals("a pass's own commit does not reset the count", 3, produced.getPassCount());
        assertEquals("new-sha", produced.getLastProducedCommit());
        assertTrue(produced.isNotified());
    }

    /** Marking notified flips only the flag; undelivered clears it, both leaving count and commit. */
    @Test(timeout = 30000)
    public void notifiedTransitionsTouchOnlyTheFlag() {
        RemediationState notified = new RemediationState("ws", 5, "sha", false).afterNotified();
        assertTrue(notified.isNotified());
        assertEquals(5, notified.getPassCount());
        assertEquals("sha", notified.getLastProducedCommit());

        RemediationState released = notified.afterNotificationUndelivered();
        assertFalse(released.isNotified());
        assertEquals(5, released.getPassCount());
        assertEquals("sha", released.getLastProducedCommit());
    }

    /** Own-commit dispatches increment the count; a foreign commit resets it. */
    @Test(timeout = 30000)
    public void ownCommitDispatchesIncrementAndAForeignCommitResets() throws Exception {
        File dir = Files.createTempDirectory("rem-count").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertEquals(0, store.get("ws").getPassCount());

            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 5, null));
            assertEquals("the first pass with no history dispatches and counts as one",
                    1, store.get("ws").getPassCount());

            store.registerJob("j1", "ws");
            store.completeJob("j1", "ws", "b", "c1", true);
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 5, "c1"));
            assertEquals("the loop's own commit continues counting", 2, store.get("ws").getPassCount());

            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 5, "someone-elses-sha"));
            assertEquals("a foreign commit resets the count", 1, store.get("ws").getPassCount());
        } finally {
            store.close();
        }
    }

    /** The count and last-produced commit survive closing and reopening the database. */
    @Test(timeout = 30000)
    public void statePersistsAcrossReopen() throws Exception {
        File dir = Files.createTempDirectory("rem-persist").toFile();
        dir.deleteOnExit();
        String own;
        RemediationStateStore store = newStore(dir);
        try {
            own = driveToPassCount(store, "ws", 2);
            assertEquals(2, store.get("ws").getPassCount());
        } finally {
            store.close();
        }
        RemediationStateStore reopened = newStore(dir);
        try {
            assertEquals(2, reopened.get("ws").getPassCount());
            assertEquals(own, reopened.get("ws").getLastProducedCommit());
        } finally {
            reopened.close();
        }
    }

    /** The notified flag survives own-commit dispatches and clears on a foreign commit. */
    @Test(timeout = 30000)
    public void markNotifiedSticksUntilAForeignCommitResetsIt() throws Exception {
        File dir = Files.createTempDirectory("rem-notify").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 5, null);
            assertFalse(store.get("ws").isNotified());
            store.markNotified("ws");
            assertTrue(store.get("ws").isNotified());

            store.registerJob("j1", "ws");
            store.completeJob("j1", "ws", "b", "c1", true);
            store.evaluateSubmission("ws", 5, "c1");
            assertTrue("an own-commit dispatch keeps the notified flag", store.get("ws").isNotified());

            store.evaluateSubmission("ws", 5, "foreign-sha");
            assertFalse("a foreign commit clears the notified flag", store.get("ws").isNotified());
        } finally {
            store.close();
        }
    }

    /** Records the (workstreamId, message) pairs the guard pings. */
    private static final class RecordingNotifier
            implements BiPredicate<String, String> {
        /** The pings the guard attempted, as "workstreamId: message". */
        private final List<String> pings = new ArrayList<>();
        /** Whether an attempted ping reports itself delivered. */
        private boolean delivers = true;
        @Override public boolean test(String workstreamId, String message) {
            pings.add(workstreamId + ": " + message);
            return delivers;
        }
    }

    /**
     * A ping that cannot be delivered (no channel or notifier configured) does
     * not consume the one-time escalation: the flag stays unset and the next
     * refused submission pings again, until one is delivered.
     */
    @Test(timeout = 30000)
    public void anUndeliveredPingIsRetriedUntilDelivered() throws Exception {
        File dir = Files.createTempDirectory("rem-undelivered").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.delivers = false;
        try {
            String own = driveToPassCount(store, "ws", 3);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 3, notifier);

            String reason = guard.skipReason("ws", true, own);
            assertTrue("still skipped", reason != null && reason.contains("cap"));
            assertEquals(1, notifier.pings.size());
            assertFalse("an undelivered ping is not recorded as sent",
                    store.get("ws").isNotified());
            assertEquals("the count is untouched", 3, store.get("ws").getPassCount());

            assertTrue(guard.skipReason("ws", true, own) != null);
            assertEquals("the next refusal tries again", 2, notifier.pings.size());

            notifier.delivers = true;
            assertTrue(guard.skipReason("ws", true, own) != null);
            assertEquals(3, notifier.pings.size());
            assertTrue("a delivered ping is recorded", store.get("ws").isNotified());

            assertTrue(guard.skipReason("ws", true, own) != null);
            assertEquals("no ping after delivery", 3, notifier.pings.size());
        } finally {
            store.close();
        }
    }

    /** A guard with no notifier treats every ping as undelivered. */
    @Test(timeout = 30000)
    public void aMissingNotifierLeavesThePingUnclaimed() throws Exception {
        File dir = Files.createTempDirectory("rem-no-notifier").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            String own = driveToPassCount(store, "ws", 2);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 2, null);
            assertTrue(guard.skipReason("ws", true, own) != null);
            assertFalse(store.get("ws").isNotified());
        } finally {
            store.close();
        }
    }

    /** releaseNotification clears only a set flag and keeps the count. */
    @Test(timeout = 30000)
    public void releaseNotificationClearsOnlyASetFlag() throws Exception {
        File dir = Files.createTempDirectory("rem-release").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertFalse("an empty workstream key releases nothing", store.releaseNotification(""));
            assertFalse("a missing record releases nothing", store.releaseNotification("ws"));
            driveToPassCount(store, "ws", 2);
            assertFalse("an unset flag releases nothing", store.releaseNotification("ws"));
            assertTrue(store.markNotified("ws"));
            assertTrue(store.releaseNotification("ws"));
            RemediationState state = store.get("ws");
            assertFalse(state.isNotified());
            assertEquals(2, state.getPassCount());
            assertTrue("a released flag can be claimed again", store.markNotified("ws"));
        } finally {
            store.close();
        }
    }

    /** A person's (non-remediation) submission is never gated. */
    @Test(timeout = 30000)
    public void aPersonsSubmissionIsNeverGated() throws Exception {
        File dir = Files.createTempDirectory("rem-guard-human").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            driveToPassCount(store, "ws", 10);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            assertNull(guard.skipReason("ws", false, null));
        } finally {
            store.close();
        }
    }

    /** At the cap the gate skips and pings once; a foreign commit resets and resumes. */
    @Test(timeout = 30000)
    public void theCapStopsDispatchAndPingsOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-guard-budget").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        RecordingNotifier notifier = new RecordingNotifier();
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 3, notifier);

            assertNull(guard.skipReason("ws", true, null));
            assertTrue(notifier.pings.isEmpty());
            store.registerJob("j1", "ws");
            store.completeJob("j1", "ws", "b", "c1", true);
            assertNull(guard.skipReason("ws", true, "c1"));
            store.registerJob("j2", "ws");
            store.completeJob("j2", "ws", "b", "c2", true);
            assertNull(guard.skipReason("ws", true, "c2"));
            store.registerJob("j3", "ws");
            store.completeJob("j3", "ws", "b", "c3", true);

            String reason = guard.skipReason("ws", true, "c3");
            assertTrue("expected a cap skip reason", reason != null && reason.contains("cap"));
            assertEquals("the person is pinged once", 1, notifier.pings.size());

            assertTrue(guard.skipReason("ws", true, "c3") != null);
            assertEquals("no second ping while the cap stays reached", 1, notifier.pings.size());

            assertNull(guard.skipReason("ws", true, "someone-elses-sha"));
        } finally {
            store.close();
        }
    }

    /** markNotified claims the flag exactly once until a foreign commit clears it. */
    @Test(timeout = 30000)
    public void markNotifiedClaimsTheFlagOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-claim").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertFalse("an empty workstream key claims nothing", store.markNotified(""));
            assertTrue(store.markNotified("ws"));
            assertFalse(store.markNotified("ws"));
            store.evaluateSubmission("ws", 10, "foreign-sha");
            assertTrue("a foreign commit re-arms the notification", store.markNotified("ws"));
        } finally {
            store.close();
        }
    }

    /** Concurrent refusals at a reached cap ping a person exactly once. */
    @Test(timeout = 30000)
    public void concurrentRefusalsPingOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-concurrent").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        List<String> pings = Collections.synchronizedList(new ArrayList<>());
        try {
            String own = driveToPassCount(store, "ws", 3);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 3,
                    (ws, message) -> pings.add(ws));
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool =
                    Executors.newFixedThreadPool(threads);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return guard.skipReason("ws", true, own);
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertTrue("every concurrent submission is refused", result.get() != null);
            }
            pool.shutdown();
            assertEquals("the person is pinged exactly once", 1, pings.size());
        } finally {
            store.close();
        }
    }

    /** Only a registered (automated) remediation job's produced commit is recorded. */
    @Test(timeout = 30000)
    public void onlyRegisteredJobsCommitsAreRecorded() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertNull("an unregistered job records nothing",
                    store.completeJob("human-1", "ws", "b", "human-sha", true));
            assertNull(store.get("ws").getLastProducedCommit());

            store.registerJob("auto-1", "ws");
            RemediationState afterAuto = store.completeJob("auto-1", "ws", "b", "auto-sha", true);
            assertEquals("auto-sha", afterAuto.getLastProducedCommit());

            assertNull("a job is recorded once; a repeated terminal event is ignored",
                    store.completeJob("auto-1", "ws", "b", "auto-sha-2", true));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());

            store.registerJob("auto-2", "ws");
            assertNull("a registered no-commit job records nothing",
                    store.completeJob("auto-2", "ws", "b", null, true));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /** A registered job's commit is recorded against the workstream it was registered for. */
    @Test(timeout = 30000)
    public void aRegisteredJobsCommitRecordsAgainstItsRegisteredWorkstream() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs-ws").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.registerJob("auto-1", "ws-a");
            store.completeJob("auto-1", "ws-b", "b", "sha", true);
            assertEquals("sha", store.get("ws-a").getLastProducedCommit());
            assertNull(store.get("ws-b").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /** A cancelled registered job is forgotten without recording its commit. */
    @Test(timeout = 30000)
    public void aCancelledRegisteredJobIsForgottenUnrecorded() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs-cancel").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.registerJob("auto-1", "ws");
            assertNull(store.completeJob("auto-1", "ws", "b", "sha", false));
            assertNull(store.get("ws").getLastProducedCommit());
            assertNull("the cancelled job was forgotten",
                    store.completeJob("auto-1", "ws", "b", "sha-2", true));
            assertNull(store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /** The guard registers automated remediation dispatches only. */
    @Test(timeout = 30000)
    public void dispatchedRegistersOnlyRemediationSubmissions() throws Exception {
        File dir = Files.createTempDirectory("rem-dispatched").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            guard.dispatched("auto-1", "ws", true);
            guard.dispatched("human-1", "ws", false);
            assertEquals("auto-sha", store.completeJob("auto-1", "ws", "b", "auto-sha", true)
                    .getLastProducedCommit());
            assertNull(store.completeJob("human-1", "ws", "b", "human-sha", true));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /** Registered jobs survive a reopen, so a job finishing after a restart still records. */
    @Test(timeout = 30000)
    public void registeredJobsPersistAcrossReopen() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs-persist").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.registerJob("auto-1", "ws");
        } finally {
            store.close();
        }
        RemediationStateStore reopened = newStore(dir);
        try {
            assertEquals("auto-sha", reopened.completeJob("auto-1", "ws", "b", "auto-sha", true)
                    .getLastProducedCommit());
        } finally {
            reopened.close();
        }
    }

    /** The lifecycle recorder records only registered jobs' produced commits. */
    @Test(timeout = 30000)
    public void lifecycleRecorderRecordsOnlyRemediationJobsCommits() throws Exception {
        File dir = Files.createTempDirectory("rem-recorder").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            JobLifecycleRecorder recorder = new JobLifecycleRecorder(null, store);
            store.registerJob("auto-1", "ws");
            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-1", "review")
                    .withGitInfo("b", "auto-sha", null, null, true));
            recorder.recordTerminal("ws", JobCompletionEvent.success("human-1", "chat")
                    .withGitInfo("b", "human-sha", null, null, true));
            recorder.recordTerminal("ws", JobCompletionEvent.failed("shell-1", "sh", "x", null));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /**
     * A cancelled remediation job is a superseded run, not a completed pass: the
     * recorder forgets it without recording its commit, even when it carries one,
     * and a later event for that forgotten job records nothing.
     */
    @Test(timeout = 30000)
    public void lifecycleRecorderDoesNotRecordCancelledJobs() throws Exception {
        File dir = Files.createTempDirectory("rem-recorder-cancel").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            JobLifecycleRecorder recorder = new JobLifecycleRecorder(null, store);
            store.registerJob("auto-1", "ws");
            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-1", "review")
                    .withGitInfo("b", "auto-sha", null, null, true));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());

            store.registerJob("auto-2", "ws");
            recorder.recordTerminal("ws", new JobCompletionEvent("auto-2",
                    JobCompletionEvent.Status.CANCELLED, "resolve")
                    .withGitInfo("b", "cancelled-sha", null, null, true));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());

            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-2", "review")
                    .withGitInfo("b", "late-sha", null, null, true));
            assertEquals("auto-sha", store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /**
     * A review triggered by a remediation pass's own commit is not skipped: it is
     * dispatched and counts toward the cap, which is what ends the self-sustaining
     * loop (a pass that finds nothing commits nothing, so nothing triggers another).
     */
    @Test(timeout = 30000)
    public void aRemediationPassOwnCommitIsDispatchedAndCounts() throws Exception {
        File dir = Files.createTempDirectory("rem-ownpass").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            guard.dispatched("review-1", "ws", true);
            store.completeJob("review-1", "ws", "b", "review-sha", true);
            assertEquals("review-sha", store.get("ws").getLastProducedCommit());

            assertNull("re-reviewing the pass's own commit is dispatched under the cap",
                    guard.skipReason("ws", true, "review-sha"));
            assertEquals("the own-commit pass counts toward the cap",
                    1, store.get("ws").getPassCount());
        } finally {
            store.close();
        }
    }

    /**
     * The atomic submit decision: dispatch with no history, dispatch and count the
     * own commit, reset on a foreign commit, and at the cap claim the one-time
     * ping before falling silent, with a foreign commit re-arming the ping.
     */
    @Test(timeout = 30000)
    public void evaluateSubmissionCountsEveryPassAndStopsAtTheCap() throws Exception {
        File dir = Files.createTempDirectory("rem-evaluate").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 3, null));
            assertEquals(1, store.get("ws").getPassCount());

            store.registerJob("j1", "ws");
            store.completeJob("j1", "ws", "b", "c1", true);
            assertSame("the own commit is dispatched and counted, not skipped",
                    RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 3, "c1"));
            assertEquals(2, store.get("ws").getPassCount());

            store.registerJob("j2", "ws");
            store.completeJob("j2", "ws", "b", "c2", true);
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 3, "c2"));
            assertEquals(3, store.get("ws").getPassCount());

            store.registerJob("j3", "ws");
            store.completeJob("j3", "ws", "b", "c3", true);
            assertSame("at the cap the own commit is skipped and claims the ping",
                    RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY,
                    store.evaluateSubmission("ws", 3, "c3"));
            assertSame("the ping is claimed only once while the cap stays reached",
                    RemediationStateStore.Decision.BUDGET_SPENT_SILENT,
                    store.evaluateSubmission("ws", 3, "c3"));

            assertSame("a foreign commit resets the count and re-arms the ping",
                    RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 3, "someone-elses-sha"));
            assertEquals(1, store.get("ws").getPassCount());
            assertFalse(store.get("ws").isNotified());
        } finally {
            store.close();
        }
    }

    /**
     * A read error fails closed: the decision is {@code UNAVAILABLE} and the
     * guard refuses the automated submission rather than dispatching without
     * the cap.
     */
    @Test(timeout = 30000)
    public void evaluateSubmissionFailsClosedWhenTheRecordCannotBeRead() throws Exception {
        File dir = Files.createTempDirectory("rem-unreadable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 5, null);
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("DROP TABLE pr_remediation");
            }
            assertSame("a read error fails closed",
                    RemediationStateStore.Decision.UNAVAILABLE,
                    store.evaluateSubmission("ws", 5, null));

            RemediationBudgetGuard guard =
                    new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            assertTrue("the guard refuses a submission it cannot evaluate",
                    guard.skipReason("ws", true, null) != null);
        } finally {
            store.close();
        }
    }

    /**
     * A counted pass that cannot be persisted fails closed: the decision is
     * {@code UNAVAILABLE} rather than {@code DISPATCH}, and the stored count is
     * left untouched, so a later submission cannot read the old count and dispatch
     * past the cap. The constraint rejects writing a count above one, so the
     * own-commit dispatch that would advance the count to two cannot persist.
     */
    @Test(timeout = 30000)
    public void evaluateSubmissionFailsClosedWhenThePassCannotBePersisted() throws Exception {
        File dir = Files.createTempDirectory("rem-unwritable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 5, null);
            store.registerJob("j1", "ws");
            store.completeJob("j1", "ws", "b", "c1", true);
            assertEquals(1, store.get("ws").getPassCount());
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("ALTER TABLE pr_remediation ADD CONSTRAINT cap "
                        + "CHECK (consecutive_no_progress <= 1)");
            }
            assertSame("a pass that cannot be persisted fails closed",
                    RemediationStateStore.Decision.UNAVAILABLE,
                    store.evaluateSubmission("ws", 5, "c1"));
            assertEquals("the stored count is left untouched", 1, store.get("ws").getPassCount());
        } finally {
            store.close();
        }
    }

    /**
     * A produced commit that cannot be persisted fails closed: {@code completeJob}
     * records nothing and keeps the job registered, so once the store can write
     * again a later terminal event re-records the commit rather than the commit
     * being forgotten and the pass's own next commit resetting the cap.
     */
    @Test(timeout = 30000)
    public void completeJobKeepsTheRegistrationWhenTheCommitCannotBePersisted() throws Exception {
        File dir = Files.createTempDirectory("rem-complete-unwritable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.registerJob("auto-1", "ws");
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("ALTER TABLE pr_remediation ADD CONSTRAINT no_commit "
                        + "CHECK (last_produced_commit IS NULL)");
            }
            assertNull("a commit that cannot be persisted records nothing",
                    store.completeJob("auto-1", "ws", "b", "c1", true));
            assertNull("the produced commit was not recorded",
                    store.get("ws").getLastProducedCommit());
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("ALTER TABLE pr_remediation DROP CONSTRAINT no_commit");
            }
            assertEquals("c1",
                    store.completeJob("auto-1", "ws", "b", "c1", true).getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /**
     * A record that cannot be read claims no notification, rather than claiming
     * it on a substitute empty record whose write would zero the stored count.
     */
    @Test(timeout = 30000)
    public void markNotifiedClaimsNothingWhenTheRecordCannotBeRead() throws Exception {
        File dir = Files.createTempDirectory("rem-notify-unreadable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 5, null);
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("DROP TABLE pr_remediation");
            }
            assertFalse("an unreadable record claims nothing", store.markNotified("ws"));
            assertFalse("an unreadable record still claims nothing on retry",
                    store.markNotified("ws"));
        } finally {
            store.close();
        }
    }

    /**
     * A record that reads fine but whose flag cannot be persisted claims nothing,
     * so the one-time ping is not sent on a write that did not stick: a later
     * submission re-reads the flag still unset and can claim it once the store can
     * write again, rather than every refused submission pinging during the outage.
     */
    @Test(timeout = 30000)
    public void markNotifiedClaimsNothingWhenTheFlagCannotBePersisted() throws Exception {
        File dir = Files.createTempDirectory("rem-notify-unwritable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 5, null);
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("ALTER TABLE pr_remediation ADD CONSTRAINT no_notify CHECK (notified = FALSE)");
            }
            assertFalse("a flag that cannot be persisted is not claimed",
                    store.markNotified("ws"));
            assertFalse("the flag is left unset so a later claim can still ping once",
                    store.get("ws").isNotified());
        } finally {
            store.close();
        }
    }

    /**
     * A non-remediation job (a QA round, a planning or implementation job) is
     * dispatched as not a remediation pass, so its primary-work commit is not
     * recorded as the workstream's last-produced commit; an auto-review
     * triggered by that commit therefore dispatches rather than being treated as
     * the previous pass's own.
     */
    @Test(timeout = 30000)
    public void aNonRemediationJobsCommitLeavesTheNextReviewDispatching() throws Exception {
        File dir = Files.createTempDirectory("rem-nonremediation").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            guard.dispatched("qa-1", "ws", false);
            store.completeJob("qa-1", "ws", "b", "qa-sha", true);
            assertNull("the non-remediation commit is not the last-produced commit",
                    store.get("ws").getLastProducedCommit());
            assertNull("auto-review must run on the primary work's commit",
                    guard.skipReason("ws", true, "qa-sha"));
        } finally {
            store.close();
        }
    }

    /**
     * A person's commit on the same branch resets the count at submit time: it is
     * not the loop's own commit, so the review it triggers dispatches and the
     * per-PR cap starts over.
     */
    @Test(timeout = 30000)
    public void aPersonsCommitResetsTheCountAtSubmitTime() throws Exception {
        File dir = Files.createTempDirectory("rem-reviewthenhuman").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            String own = driveToPassCount(store, "ws", 3);
            store.markNotified("ws");
            assertEquals(3, store.get("ws").getPassCount());
            assertTrue("the loop's own commit was recorded", own != null && !own.isEmpty());

            assertNull("a review of the person's commit dispatches",
                    guard.skipReason("ws", true, "human-sha"));
            RemediationState after = store.get("ws");
            assertEquals("the person's commit reset the count", 1, after.getPassCount());
            assertFalse("and cleared the notified flag", after.isNotified());
        } finally {
            store.close();
        }
    }

    /**
     * Only remediation passes count against the per-PR cap. A non-remediation
     * submission ({@code remediationPass = false}) is never counted — the gate
     * returns before counting it — so however many are submitted the cap is
     * untouched; only remediation passes exhaust it.
     */
    @Test(timeout = 30000)
    public void theCapCountsOnlyRemediationPasses() throws Exception {
        File dir = Files.createTempDirectory("rem-budget-scope").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 2, new RecordingNotifier());
            for (int i = 0; i < 5; i++) {
                assertNull(guard.skipReason("ws", false, null));
            }
            assertEquals("non-remediation submissions never move the count",
                    0, store.get("ws").getPassCount());

            assertNull(guard.skipReason("ws", true, null));
            store.registerJob("review-1", "ws");
            store.completeJob("review-1", "ws", "b", "c1", true);
            assertNull(guard.skipReason("ws", true, "c1"));
            store.registerJob("review-2", "ws");
            store.completeJob("review-2", "ws", "b", "c2", true);
            assertEquals(2, store.get("ws").getPassCount());
            assertTrue("only remediation passes exhaust the cap",
                    guard.skipReason("ws", true, "c2") != null);
        } finally {
            store.close();
        }
    }
}
