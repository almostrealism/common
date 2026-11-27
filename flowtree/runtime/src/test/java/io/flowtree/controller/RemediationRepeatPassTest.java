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

import io.flowtree.github.PullRequestEscalation;
import io.flowtree.github.ReviewActivity;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Covers the repeat skip of the per-PR remediation gate: a pass re-triggered on
 * the commit the last dispatched pass ran on, of the same kind, with no review
 * thread opened since, is skipped without being counted, while a new commit, a
 * new review thread or a different kind of pass still dispatches.
 */
public class RemediationRepeatPassTest extends TestSuiteBase {

    /** The label CI gives an auto-review pass. */
    private static final String REVIEW = "Review branch changes";

    /** The link of a review thread already open when the first pass ran. */
    private static final String OLD_THREAD = "https://github.com/o/r/pull/1#discussion_r1";

    /** The link of a review thread opened later. */
    private static final String NEW_THREAD = "https://github.com/o/r/pull/1#discussion_r2";

    /** Creates and initialises a store backed by a fresh temp HSQLDB. */
    private static RemediationStateStore newStore(File dir) {
        RemediationStateStore store = new RemediationStateStore(
                new File(dir, "remediation").getAbsolutePath());
        store.initialize();
        return store;
    }

    /** Returns the activity of a pass labelled {@code label} with {@code threads} unresolved. */
    private static ReviewActivity activity(String label, String... threads) {
        return new ReviewActivity(label, Set.of(threads));
    }

    /** Returns an open pull request whose unresolved threads have {@code threads} as links. */
    private static PullRequestEscalation pullRequest(String... threads) {
        List<PullRequestEscalation.Finding> findings = new ArrayList<>();
        for (String thread : threads) {
            findings.add(PullRequestEscalation.Finding.reviewComment("A.java", 3, thread, "fix this"));
        }
        return new PullRequestEscalation("https://github.com/o/r/pull/1", findings, List.of(), true);
    }

    /** The same pass on the same commit, with the same threads open, is skipped and not counted. */
    @Test(timeout = 30000)
    public void samePassOnTheSameCommitIsSkipped() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 10, null, activity(REVIEW, OLD_THREAD));
            store.registerJob("job-1", "ws");
            store.completeJob("job-1", "ws", "b", "c1", true);

            assertSame("the loop's own commit dispatches", RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, "c1", activity(REVIEW, OLD_THREAD)));
            assertEquals(2, store.get("ws").getPassCount());
            assertSame(RemediationStateStore.Decision.ALREADY_REVIEWED,
                    store.evaluateSubmission("ws", 10, "c1", activity(REVIEW, OLD_THREAD)));
            assertEquals("a skipped repeat is not counted", 2, store.get("ws").getPassCount());
        } finally {
            store.close();
        }
    }

    /** A new commit, a new review thread, or a different kind of pass each dispatch. */
    @Test(timeout = 30000)
    public void somethingNewDispatches() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 10, "c1", activity(REVIEW, OLD_THREAD));
            assertSame("a new review thread", RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, "c1", activity(REVIEW, OLD_THREAD, NEW_THREAD)));
            assertSame("a different kind of pass", RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, "c1", activity("Resolve build failure",
                            OLD_THREAD, NEW_THREAD)));
            assertSame("a new commit", RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, "c2", activity("Resolve build failure",
                            OLD_THREAD, NEW_THREAD)));
        } finally {
            store.close();
        }
    }

    /** A thread resolved since the last pass is not new activity, so the repeat is still skipped. */
    @Test(timeout = 30000)
    public void aResolvedThreadIsNotNewActivity() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 10, "c1", activity(REVIEW, OLD_THREAD, NEW_THREAD));
            assertSame(RemediationStateStore.Decision.ALREADY_REVIEWED,
                    store.evaluateSubmission("ws", 10, "c1", activity(REVIEW, NEW_THREAD)));
        } finally {
            store.close();
        }
    }

    /** Without known review activity, or without a commit, a pass is never taken for a repeat. */
    @Test(timeout = 30000)
    public void unknownActivityNeverSkips() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 10, "c1", activity(REVIEW));
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, "c1", (ReviewActivity) null));
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, "c1"));
            store.evaluateSubmission("ws", 10, null, activity(REVIEW));
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 10, null, activity(REVIEW)));
        } finally {
            store.close();
        }
    }

    /** The last pass's commit and activity survive a controller restart, including an empty thread set. */
    @Test(timeout = 30000)
    public void theLastPassSurvivesARestart() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        store.evaluateSubmission("ws", 10, "c1", activity(REVIEW));
        store.close();

        RemediationStateStore reopened = newStore(dir);
        try {
            RemediationState state = reopened.get("ws");
            assertEquals("c1", state.getLastDispatchedCommit());
            assertEquals(activity(REVIEW), state.getLastReviewed());
            assertSame(RemediationStateStore.Decision.ALREADY_REVIEWED,
                    reopened.evaluateSubmission("ws", 10, "c1", activity(REVIEW)));
        } finally {
            reopened.close();
        }
    }

    /** A database created before the columns existed gains them, and keeps its count. */
    @Test(timeout = 30000)
    public void anEarlierDatabaseIsMigrated() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        String path = new File(dir, "remediation").getAbsolutePath();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + path + ";shutdown=true", "SA", "");
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE pr_remediation (workstream_id VARCHAR(255) PRIMARY KEY, "
                    + "repo_url VARCHAR(1000), branch VARCHAR(255), "
                    + "consecutive_no_progress INTEGER DEFAULT 0 NOT NULL, "
                    + "last_produced_commit VARCHAR(64), notified BOOLEAN DEFAULT FALSE NOT NULL, "
                    + "updated_at TIMESTAMP)");
            stmt.execute("INSERT INTO pr_remediation (workstream_id, consecutive_no_progress) "
                    + "VALUES ('ws', 3)");
            stmt.execute("SHUTDOWN");
        }

        RemediationStateStore store = newStore(dir);
        try {
            assertTrue(store.isOpen());
            assertEquals(3, store.get("ws").getPassCount());
            assertNull(store.get("ws").getLastReviewed());
            store.evaluateSubmission("ws", 10, "c1", activity(REVIEW));
            assertSame(RemediationStateStore.Decision.ALREADY_REVIEWED,
                    store.evaluateSubmission("ws", 10, "c1", activity(REVIEW)));
        } finally {
            store.close();
        }
    }

    /** The guard skips a repeat it finds through the pull request lookup, and says why. */
    @Test(timeout = 30000)
    public void theGuardSkipsARepeatOfThePullRequest() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            List<PullRequestEscalation> current = new ArrayList<>(List.of(pullRequest(OLD_THREAD)));
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 10, (ws, msg) -> true,
                    ws -> null, ws -> current.get(0));

            assertNull(guard.skipReason("ws", true, "c1", REVIEW));
            String reason = guard.skipReason("ws", true, "c1", REVIEW);
            assertTrue(String.valueOf(reason), reason != null && reason.contains("c1"));

            current.set(0, pullRequest(OLD_THREAD, NEW_THREAD));
            assertNull("a new review thread dispatches", guard.skipReason("ws", true, "c1", REVIEW));
            assertNull("a new commit dispatches", guard.skipReason("ws", true, "c2", REVIEW));
        } finally {
            store.close();
        }
    }

    /** When no pull request can be found the guard never skips a pass as a repeat. */
    @Test(timeout = 30000)
    public void noPullRequestNeverSkips() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 10, (ws, msg) -> true,
                    ws -> null, ws -> PullRequestEscalation.NONE);
            assertNull(guard.skipReason("ws", true, "c1", REVIEW));
            assertNull(guard.skipReason("ws", true, "c1", REVIEW));
            assertNull(store.get("ws").getLastReviewed());
        } finally {
            store.close();
        }
    }

    /** At the cap a repeat still surfaces the one-time ping rather than being silently skipped. */
    @Test(timeout = 30000)
    public void atTheCapARepeatStillPingsAPerson() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            store.evaluateSubmission("ws", 2, null, activity(REVIEW, OLD_THREAD));
            store.registerJob("job-1", "ws");
            store.completeJob("job-1", "ws", "b", "c1", true);
            assertSame(RemediationStateStore.Decision.DISPATCH,
                    store.evaluateSubmission("ws", 2, "c1", activity(REVIEW, OLD_THREAD)));

            assertSame("the exhausted cap outranks the repeat skip, so a person is pinged once",
                    RemediationStateStore.Decision.BUDGET_SPENT_NOTIFY,
                    store.evaluateSubmission("ws", 2, "c1", activity(REVIEW, OLD_THREAD)));
            assertSame("after the one ping the repeating cap stays silent",
                    RemediationStateStore.Decision.BUDGET_SPENT_SILENT,
                    store.evaluateSubmission("ws", 2, "c1", activity(REVIEW, OLD_THREAD)));
        } finally {
            store.close();
        }
    }

    /** A thread listing that was truncated or could not be read is unknown activity, never a repeat. */
    @Test(timeout = 30000)
    public void anIncompleteThreadListingNeverSkips() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            PullRequestEscalation incomplete = new PullRequestEscalation(
                    "https://github.com/o/r/pull/1",
                    List.of(PullRequestEscalation.Finding.reviewComment("A.java", 3, OLD_THREAD, "x")),
                    List.of(), false);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 10, (ws, msg) -> true,
                    ws -> null, ws -> incomplete);
            assertNull(guard.skipReason("ws", true, "c1", REVIEW));
            assertNull("an incomplete listing cannot establish a repeat",
                    guard.skipReason("ws", true, "c1", REVIEW));
            assertNull(store.get("ws").getLastReviewed());
        } finally {
            store.close();
        }
    }

    /** A thread without a stable link is unknown activity, so it never establishes a repeat. */
    @Test(timeout = 30000)
    public void aThreadWithoutALinkNeverSkips() throws Exception {
        File dir = Files.createTempDirectory("remediation-repeat").toFile();
        RemediationStateStore store = newStore(dir);
        try {
            PullRequestEscalation noLink = new PullRequestEscalation(
                    "https://github.com/o/r/pull/1",
                    List.of(PullRequestEscalation.Finding.reviewComment("A.java", 3, null, "x")),
                    List.of(), true);
            RemediationBudgetGuard guard = new RemediationBudgetGuard(store, 10, (ws, msg) -> true,
                    ws -> null, ws -> noLink);
            assertNull(guard.skipReason("ws", true, "c1", REVIEW));
            assertNull("a thread with no link cannot establish a repeat",
                    guard.skipReason("ws", true, "c1", REVIEW));
        } finally {
            store.close();
        }
    }

    /** The stored form of the activity reads back as the same activity. */
    @Test(timeout = 30000)
    public void activityRoundTrips() {
        ReviewActivity original = activity(REVIEW, OLD_THREAD, NEW_THREAD);
        assertEquals(original, ReviewActivity.decode(original.encode()));
        assertEquals(activity(""), ReviewActivity.decode(activity(null).encode()));
        assertNull(ReviewActivity.decode(null));
        assertNull("no pull request, no activity", PullRequestEscalation.NONE.reviewActivity(REVIEW));
        assertNull("an incomplete thread listing is unknown activity",
                new PullRequestEscalation("https://github.com/o/r/pull/1", List.of(), List.of(), false)
                        .reviewActivity(REVIEW));
        assertNull("a thread with no link is unknown activity",
                new PullRequestEscalation("https://github.com/o/r/pull/1",
                        List.of(PullRequestEscalation.Finding.reviewComment("A.java", 3, null, "x")),
                        List.of(), true).reviewActivity(REVIEW));
        assertFalse(activity(REVIEW).hasNothingNewSince(null));
    }
}
