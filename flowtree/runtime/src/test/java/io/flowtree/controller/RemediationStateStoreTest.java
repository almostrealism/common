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

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the one per-PR remediation-state record (RETRO-20261026 P2+P3): the
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
            implements BiConsumer<String, String> {
        /** The pings the guard sent, as "workstreamId: message". */
        private final List<String> pings = new ArrayList<>();
        @Override public void accept(String workstreamId, String message) {
            pings.add(workstreamId + ": " + message);
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
}
