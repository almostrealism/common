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
import java.sql.ResultSet;
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
 * {@link RemediationState} predicates, the {@link RemediationStateStore}
 * counting of passes, and the {@link RemediationBudgetGuard} submit-time
 * decision — the budget cap and the own-commit skip.
 */
public class RemediationStateStoreTest extends TestSuiteBase {

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private RemediationStateStore newStore(File dir) {
        RemediationStateStore store = new RemediationStateStore(
                new File(dir, "remediation").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** The budget is spent at the threshold, not before. */
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

    /** Only the exact last-produced commit matches the own-commit predicate. */
    @Test(timeout = 30000)
    public void onlyTheExactLastProducedCommitMatches() {
        RemediationState state = new RemediationState("ws", 0, "abc123", false);
        assertTrue(state.isLastProducedCommit("abc123"));
        assertFalse(state.isLastProducedCommit("def456"));
        assertFalse(state.isLastProducedCommit(null));
        assertFalse(new RemediationState("ws", 0, null, false).isLastProducedCommit("abc123"));
    }

    /** The pass-transition rule on the record: a commit resets, no commit increments. */
    @Test(timeout = 30000)
    public void afterPassResetsOnACommitAndIncrementsOtherwise() {
        RemediationState base = new RemediationState("ws", 3, "old", true);

        RemediationState committed = base.afterPass("new-sha");
        assertEquals(0, committed.getConsecutiveNoProgress());
        assertEquals("new-sha", committed.getLastProducedCommit());
        assertFalse("a commit clears the notified flag", committed.isNotified());

        RemediationState noCommit = base.afterPass(null);
        assertEquals(4, noCommit.getConsecutiveNoProgress());
        assertEquals("old", noCommit.getLastProducedCommit());
        assertTrue("no commit carries the notified flag", noCommit.isNotified());

        assertEquals("an empty SHA is not progress", 4,
                base.afterPass("").getConsecutiveNoProgress());
    }

    /** Marking notified flips only the flag, leaving the count and commit alone. */
    @Test(timeout = 30000)
    public void afterNotifiedSetsOnlyTheFlag() {
        RemediationState state = new RemediationState("ws", 5, "sha", false).afterNotified();
        assertTrue(state.isNotified());
        assertEquals(5, state.getConsecutiveNoProgress());
        assertEquals("sha", state.getLastProducedCommit());
    }

    /** No-commit passes increment the count and a commit resets it. */
    @Test(timeout = 30000)
    public void noCommitPassesIncrementAndACommitResets() throws Exception {
        File dir = Files.createTempDirectory("rem-count").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertEquals(0, store.get("ws").getConsecutiveNoProgress());

            assertEquals(1, store.recordPass("ws", "repo", "branch", null)
                    .getConsecutiveNoProgress());
            assertEquals(2, store.recordPass("ws", "repo", "branch", null)
                    .getConsecutiveNoProgress());

            RemediationState afterCommit = store.recordPass("ws", "repo", "branch", "sha-1");
            assertEquals("a commit resets the count", 0, afterCommit.getConsecutiveNoProgress());
            assertEquals("sha-1", afterCommit.getLastProducedCommit());

            assertEquals(1, store.recordPass("ws", "repo", "branch", null)
                    .getConsecutiveNoProgress());
            assertEquals("sha-1", store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /** The count survives closing and reopening the database. */
    @Test(timeout = 30000)
    public void statePersistsAcrossReopen() throws Exception {
        File dir = Files.createTempDirectory("rem-persist").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.recordPass("ws", "repo", "branch", null);
            store.recordPass("ws", "repo", "branch", null);
        } finally {
            store.close();
        }
        RemediationStateStore reopened = newStore(dir);
        try {
            assertEquals(2, reopened.get("ws").getConsecutiveNoProgress());
        } finally {
            reopened.close();
        }
    }

    /** The notified flag persists across no-op passes and clears on a commit. */
    @Test(timeout = 30000)
    public void markNotifiedSticksUntilACommitResetsIt() throws Exception {
        File dir = Files.createTempDirectory("rem-notify").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.recordPass("ws", "repo", "branch", null);
            assertFalse(store.get("ws").isNotified());
            store.markNotified("ws");
            assertTrue(store.get("ws").isNotified());
            store.recordPass("ws", "repo", "branch", null);
            assertTrue(store.get("ws").isNotified());
            store.recordPass("ws", "repo", "branch", "sha-9");
            assertFalse(store.get("ws").isNotified());
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
            for (int i = 0; i < 3; i++) store.recordPass("ws", "r", "b", null);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 3, notifier);

            String reason = guard.skipReason("ws", true, null);
            assertTrue("still skipped", reason != null && reason.contains("budget"));
            assertEquals(1, notifier.pings.size());
            assertFalse("an undelivered ping is not recorded as sent",
                    store.get("ws").isNotified());
            assertEquals("the count is untouched", 3, store.get("ws").getConsecutiveNoProgress());

            assertTrue(guard.skipReason("ws", true, null) != null);
            assertEquals("the next refusal tries again", 2, notifier.pings.size());

            notifier.delivers = true;
            assertTrue(guard.skipReason("ws", true, null) != null);
            assertEquals(3, notifier.pings.size());
            assertTrue("a delivered ping is recorded", store.get("ws").isNotified());

            assertTrue(guard.skipReason("ws", true, null) != null);
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
            for (int i = 0; i < 2; i++) store.recordPass("ws", "r", "b", null);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 2, null);
            assertTrue(guard.skipReason("ws", true, null) != null);
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
            store.recordPass("ws", "r", "b", null);
            store.recordPass("ws", "r", "b", null);
            assertFalse("an unset flag releases nothing", store.releaseNotification("ws"));
            assertTrue(store.markNotified("ws"));
            assertTrue(store.releaseNotification("ws"));
            RemediationState state = store.get("ws");
            assertFalse(state.isNotified());
            assertEquals(2, state.getConsecutiveNoProgress());
            assertTrue("a released flag can be claimed again", store.markNotified("ws"));
        } finally {
            store.close();
        }
    }

    /** A person's (non-automated) submission is never gated. */
    @Test(timeout = 30000)
    public void aPersonsSubmissionIsNeverGated() throws Exception {
        File dir = Files.createTempDirectory("rem-guard-human").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            for (int i = 0; i < 10; i++) store.recordPass("ws", "r", "b", null);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            assertNull(guard.skipReason("ws", false, null));
        } finally {
            store.close();
        }
    }

    /** At budget the gate skips and pings once; a commit resets and resumes. */
    @Test(timeout = 30000)
    public void budgetStopsDispatchAndPingsOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-guard-budget").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        RecordingNotifier notifier = new RecordingNotifier();
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 3, notifier);
            store.recordPass("ws", "r", "b", null);
            store.recordPass("ws", "r", "b", null);
            assertNull(guard.skipReason("ws", true, null));
            assertTrue(notifier.pings.isEmpty());

            store.recordPass("ws", "r", "b", null);
            String reason = guard.skipReason("ws", true, null);
            assertTrue("expected a budget skip reason", reason != null && reason.contains("budget"));
            assertEquals("the person is pinged once", 1, notifier.pings.size());

            assertTrue(guard.skipReason("ws", true, null) != null);
            assertEquals("no second ping while the budget stays spent", 1, notifier.pings.size());

            store.recordPass("ws", "r", "b", "sha-x");
            assertNull(guard.skipReason("ws", true, null));
        } finally {
            store.close();
        }
    }

    /** markNotified claims the flag exactly once until a commit clears it. */
    @Test(timeout = 30000)
    public void markNotifiedClaimsTheFlagOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-claim").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertFalse("an empty workstream key claims nothing", store.markNotified(""));
            assertTrue(store.markNotified("ws"));
            assertFalse(store.markNotified("ws"));
            store.recordPass("ws", "r", "b", "sha-1");
            assertTrue("a commit re-arms the notification", store.markNotified("ws"));
        } finally {
            store.close();
        }
    }

    /** Concurrent refusals at an exhausted budget ping a person exactly once. */
    @Test(timeout = 30000)
    public void concurrentRefusalsPingOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-concurrent").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        List<String> pings = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 3; i++) store.recordPass("ws", "r", "b", null);
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
                    return guard.skipReason("ws", true, null);
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

    /** Only a registered (automated) job's no-commit completion counts as a pass. */
    @Test(timeout = 30000)
    public void onlyRegisteredJobsCountAsPasses() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertNull("an unregistered no-commit job records nothing",
                    store.completeJob("human-1", "ws", "b", null, true));
            assertEquals(0, store.get("ws").getConsecutiveNoProgress());

            store.registerJob("auto-1", "ws");
            RemediationState afterAuto = store.completeJob("auto-1", "ws", "b", null, true);
            assertEquals(1, afterAuto.getConsecutiveNoProgress());

            assertNull("a job is counted once; a repeated terminal event is ignored",
                    store.completeJob("auto-1", "ws", "b", null, true));
            assertEquals(1, store.get("ws").getConsecutiveNoProgress());
        } finally {
            store.close();
        }
    }

    /** A registered job is counted against the workstream it was registered for. */
    @Test(timeout = 30000)
    public void aRegisteredJobCountsAgainstItsRegisteredWorkstream() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs-ws").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.registerJob("auto-1", "ws-a");
            store.completeJob("auto-1", "ws-b", "b", null, true);
            assertEquals(1, store.get("ws-a").getConsecutiveNoProgress());
            assertEquals(0, store.get("ws-b").getConsecutiveNoProgress());
        } finally {
            store.close();
        }
    }

    /** A cancelled registered job is forgotten without changing the count. */
    @Test(timeout = 30000)
    public void aCancelledRegisteredJobIsForgottenUncounted() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs-cancel").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.registerJob("auto-1", "ws");
            assertNull(store.completeJob("auto-1", "ws", "b", "sha", false));
            assertEquals(0, store.get("ws").getConsecutiveNoProgress());
            assertNull(store.get("ws").getLastProducedCommit());
            assertNull("the cancelled job was forgotten",
                    store.completeJob("auto-1", "ws", "b", null, true));
            assertEquals(0, store.get("ws").getConsecutiveNoProgress());
        } finally {
            store.close();
        }
    }

    /** Another job's commit resets the count but is not taken as a pass's own commit. */
    @Test(timeout = 30000)
    public void anUnregisteredJobsCommitResetsButIsNotTheOwnCommit() throws Exception {
        File dir = Files.createTempDirectory("rem-jobs-human").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.recordPass("ws", "r", "b", "review-sha");
            store.recordPass("ws", "r", "b", null);
            store.recordPass("ws", "r", "b", null);
            store.markNotified("ws");

            RemediationState next = store.completeJob("human-1", "ws", "b", "human-sha", true);
            assertEquals(0, next.getConsecutiveNoProgress());
            assertFalse(next.isNotified());
            assertEquals("review-sha", next.getLastProducedCommit());

            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            assertNull("a review of the person's commit still runs",
                    guard.skipReason("ws", true, "human-sha"));
        } finally {
            store.close();
        }
    }

    /** The guard registers automated dispatches only. */
    @Test(timeout = 30000)
    public void dispatchedRegistersOnlyAutomatedSubmissions() throws Exception {
        File dir = Files.createTempDirectory("rem-dispatched").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            guard.dispatched("auto-1", "ws", true);
            guard.dispatched("human-1", "ws", false);
            assertEquals(1, store.completeJob("auto-1", "ws", "b", null, true)
                    .getConsecutiveNoProgress());
            assertNull(store.completeJob("human-1", "ws", "b", null, true));
            assertEquals(1, store.get("ws").getConsecutiveNoProgress());
        } finally {
            store.close();
        }
    }

    /** Registered jobs survive a reopen, so a job finishing after a restart still counts. */
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
            assertEquals(1, reopened.completeJob("auto-1", "ws", "b", null, true)
                    .getConsecutiveNoProgress());
        } finally {
            reopened.close();
        }
    }

    /** The lifecycle recorder counts only registered jobs' terminal events. */
    @Test(timeout = 30000)
    public void lifecycleRecorderCountsOnlyRemediationJobs() throws Exception {
        File dir = Files.createTempDirectory("rem-recorder").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            JobLifecycleRecorder recorder = new JobLifecycleRecorder(null, store);
            store.registerJob("auto-1", "ws");
            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-1", "review"));
            recorder.recordTerminal("ws", JobCompletionEvent.success("human-1", "chat"));
            recorder.recordTerminal("ws", JobCompletionEvent.failed("shell-1", "sh", "x", null));
            assertEquals(1, store.get("ws").getConsecutiveNoProgress());

            store.registerJob("auto-2", "ws");
            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-2", "resolve")
                    .withGitInfo("b", "fix-sha", null, null, true));
            assertEquals(0, store.get("ws").getConsecutiveNoProgress());
            assertEquals("fix-sha", store.get("ws").getLastProducedCommit());
        } finally {
            store.close();
        }
    }

    /**
     * A cancelled remediation job is a superseded run, not a completed pass:
     * the recorder forgets it without counting it, even when it carries a
     * commit, so it neither increments nor resets the count.
     */
    @Test(timeout = 30000)
    public void lifecycleRecorderDoesNotCountCancelledJobs() throws Exception {
        File dir = Files.createTempDirectory("rem-recorder-cancel").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            JobLifecycleRecorder recorder = new JobLifecycleRecorder(null, store);
            store.registerJob("auto-1", "ws");
            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-1", "review"));
            assertEquals(1, store.get("ws").getConsecutiveNoProgress());

            store.registerJob("auto-2", "ws");
            recorder.recordTerminal("ws", new JobCompletionEvent("auto-2",
                    JobCompletionEvent.Status.CANCELLED, "review"));
            assertEquals("a cancelled pass does not increment the count",
                    1, store.get("ws").getConsecutiveNoProgress());

            store.registerJob("auto-3", "ws");
            recorder.recordTerminal("ws", new JobCompletionEvent("auto-3",
                    JobCompletionEvent.Status.CANCELLED, "resolve")
                    .withGitInfo("b", "cancelled-sha", null, null, true));
            assertEquals("a cancelled pass does not reset the count",
                    1, store.get("ws").getConsecutiveNoProgress());
            assertNull(store.get("ws").getLastProducedCommit());

            recorder.recordTerminal("ws", JobCompletionEvent.success("auto-2", "review"));
            assertEquals("the cancelled job was forgotten, so a late event does not count",
                    1, store.get("ws").getConsecutiveNoProgress());
        } finally {
            store.close();
        }
    }

    /** The own-commit skip fires only for the pass's own commit. */
    @Test(timeout = 30000)
    public void ownCommitIsSkippedButAnyOtherCommitDispatches() throws Exception {
        File dir = Files.createTempDirectory("rem-guard-own").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 5, new RecordingNotifier());
            store.recordPass("ws", "r", "b", "review-sha");

            assertTrue(guard.skipReason("ws", true, "review-sha") != null);
            assertNull(guard.skipReason("ws", true, "someone-elses-sha"));
            assertNull(guard.skipReason("ws", true, null));
        } finally {
            store.close();
        }
    }

    /**
     * The atomic submit decision: dispatch with no history, skip the own
     * commit, and at budget claim the one-time ping before falling silent, with
     * a commit re-arming the ping for the next exhaustion.
     */
    @Test(timeout = 30000)
    public void evaluateSubmissionDecidesTheSkipAndClaimsThePingOnce() throws Exception {
        File dir = Files.createTempDirectory("rem-evaluate").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 3, null));

            store.recordPass("ws", "r", "b", "review-sha");
            assertSame(RemediationStateStore.Decision.OWN_COMMIT,
                    store.evaluateSubmission("ws", 3, "review-sha"));
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 3, "someone-elses-sha"));

            for (int i = 0; i < 3; i++) store.recordPass("ws", "r", "b", null);
            assertSame(RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY,
                    store.evaluateSubmission("ws", 3, null));
            assertSame("the ping is claimed only once while the budget stays spent",
                    RemediationStateStore.Decision.BUDGET_SPENT_SILENT,
                    store.evaluateSubmission("ws", 3, null));

            store.recordPass("ws", "r", "b", "fix-sha");
            for (int i = 0; i < 3; i++) store.recordPass("ws", "r", "b", null);
            assertSame("a commit reset re-arms the one-time ping",
                    RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY,
                    store.evaluateSubmission("ws", 3, null));
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
            store.recordPass("ws", "r", "b", null);
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
     * A record that cannot be read claims no notification, rather than claiming
     * it on a substitute empty record whose write would zero the stored count.
     */
    @Test(timeout = 30000)
    public void markNotifiedClaimsNothingWhenTheRecordCannotBeRead() throws Exception {
        File dir = Files.createTempDirectory("rem-notify-unreadable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            store.recordPass("ws", "r", "b", null);
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
            store.recordPass("ws", "r", "b", null);
            // The row still reads (notified=false), but a write that sets the
            // flag now violates this constraint, so claimNotification's write fails.
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
     * A no-progress pass on a record that cannot be read is not recorded, rather
     * than incrementing a substitute empty record whose write would overwrite a
     * spent count with one and refund the budget. A pass that produced a commit
     * is still recorded, since its reset does not depend on the stored count.
     */
    @Test(timeout = 30000)
    public void recordPassDoesNotRefundTheBudgetWhenTheRecordCannotBeRead() throws Exception {
        File dir = Files.createTempDirectory("rem-pass-unreadable").toFile();
        dir.deleteOnExit();
        RemediationStateStore store = newStore(dir);
        try {
            for (int i = 0; i < 5; i++) {
                store.recordPass("ws", "r", "b", null);
            }
            assertTrue(store.get("ws").budgetExhausted(5));
            // The row is still writable, but its count no longer reads as an integer.
            try (Statement stmt = store.connection().createStatement()) {
                stmt.execute("ALTER TABLE pr_remediation ALTER COLUMN consecutive_no_progress "
                        + "SET DATA TYPE VARCHAR(16)");
                stmt.execute("UPDATE pr_remediation SET consecutive_no_progress = 'unreadable'");
            }

            assertNull("an unreadable no-progress pass is not recorded",
                    store.recordPass("ws", "r", "b", null));
            // The stored count is left untouched, not reset to one.
            assertEquals("unreadable", rawCount(store));

            RemediationState committed = store.recordPass("ws", "r", "b", "new-sha");
            assertEquals(0, committed.getConsecutiveNoProgress());
            assertEquals("new-sha", committed.getLastProducedCommit());
            // A commit still resets an unreadable record.
            assertEquals("0", rawCount(store));
        } finally {
            store.close();
        }
    }

    /** Reads the stored count for {@code ws} as text, bypassing the store's parsing. */
    private String rawCount(RemediationStateStore store) throws Exception {
        try (Statement stmt = store.connection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT consecutive_no_progress FROM pr_remediation WHERE workstream_id = 'ws'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
