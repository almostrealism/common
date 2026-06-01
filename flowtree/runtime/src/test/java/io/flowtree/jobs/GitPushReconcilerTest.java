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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link GitPushReconciler}, exercising the reactive
 * push-and-reconcile loop with a scripted in-memory git executor rather than a
 * real repository. The scenarios cover: a clean first push, a rejection
 * followed by a clean reconciliation merge, a conflicted reconciliation that the
 * agent resolves, and the three loud-failure paths (remote did not advance,
 * conflict left unresolved, retries exhausted).
 */
public class GitPushReconcilerTest extends TestSuiteBase {

    /**
     * Scriptable in-memory stand-in for git command execution. Records every
     * invocation and returns exit codes / output driven by a few configurable
     * fields, so each test can describe exactly the remote behavior it wants.
     */
    static final class FakeGit {
        /** Ordered log of every command run; queries are prefixed with {@code "Q:"}. */
        final List<String> commands = new ArrayList<>();
        /** Number of {@code push} invocations so far. */
        int pushAttempts = 0;
        /** The push attempt number (1-based) on which the push first succeeds. */
        int pushSucceedsOnAttempt = 1;
        /** When true, {@code merge-base --is-ancestor} reports the remote advanced. */
        boolean remoteAdvanced = true;
        /** Exit code returned by {@code merge}; non-zero simulates a conflict. */
        int mergeExit = 0;
        /** Output of {@code ls-files --unmerged}; non-empty means conflicts remain. */
        String unmergedOutput = "";
        /** Paths reported by {@code diff --name-only --diff-filter=U}. */
        List<String> conflicted = new ArrayList<>();

        /** Executes a git command described by {@code args} and returns the simulated exit code. */
        int exec(String... args) {
            commands.add(String.join(" ", args));
            switch (args[0]) {
                case "push":
                    pushAttempts++;
                    return pushAttempts >= pushSucceedsOnAttempt ? 0 : 1;
                case "merge-base":
                    // `merge-base --is-ancestor remote HEAD` returns 0 when the
                    // remote IS an ancestor (did NOT advance), 1 otherwise.
                    return remoteAdvanced ? 1 : 0;
                case "merge":
                    return mergeExit;
                default:
                    return 0;
            }
        }

        /** Executes a git query command and returns the simulated output string. */
        String query(String... args) {
            commands.add("Q:" + String.join(" ", args));
            if ("ls-files".equals(args[0])) return unmergedOutput;
            if ("diff".equals(args[0])) return String.join("\n", conflicted);
            if ("rev-parse".equals(args[0])) return "abc1234\n";
            return "";
        }

        /** Returns the number of recorded commands that start with the given prefix. */
        long count(String prefix) {
            return commands.stream().filter(c -> c.startsWith(prefix)).count();
        }
    }

    /** Minimal concrete {@link GitManagedJob} whose conflict hook is scriptable. */
    static final class StubJob extends GitManagedJob {
        /** Whether the conflict hook should report that conflicts were resolved. */
        boolean resolveConflict = true;
        /** Tracks how many times the {@link #onPushConflict} hook has been invoked. */
        int conflictHookCalls = 0;
        /** The list of conflicted files passed to the most recent conflict hook call. */
        List<String> lastConflictedFiles;

        /** Constructs a {@link StubJob} targeting the {@code feature/x} branch with task id {@code t1}. */
        StubJob() {
            setTargetBranch("feature/x");
            setTaskId("t1");
        }

        /** No-op implementation of the work body; all behavior is driven by the reconciler under test. */
        @Override
        protected void doWork() {
        }

        /** Returns a fixed stub task string for identification purposes. */
        @Override
        public String getTaskString() {
            return "stub-task";
        }

        /** Records the call and returns the configured {@link #resolveConflict} flag. */
        @Override
        protected boolean onPushConflict(String repoPath, List<String> conflictedFiles) {
            conflictHookCalls++;
            lastConflictedFiles = conflictedFiles;
            return resolveConflict;
        }
    }

    /** Creates a {@link GitPushReconciler} wired to the given stub job and fake git executor. */
    private static GitPushReconciler reconciler(StubJob job, FakeGit git) {
        return new GitPushReconciler(job, "/tmp/repo", git::exec, git::query);
    }

    /** Verifies that a push that succeeds on the first attempt performs no fetch or conflict resolution. */
    @Test(timeout = 30000)
    public void cleanPushFirstTrySkipsReconciliation() throws Exception {
        StubJob job = new StubJob();
        FakeGit git = new FakeGit();

        reconciler(job, git).push("feature/x");

        assertEquals(1, git.pushAttempts);
        assertEquals("no fetch should happen on a clean push", 0, git.count("fetch"));
        assertEquals(0, job.conflictHookCalls);
    }

    /** Verifies that a rejected push triggers a fetch-and-merge reconciliation and then retries the push. */
    @Test(timeout = 30000)
    public void rejectionThenCleanMergeRetriesPush() throws Exception {
        StubJob job = new StubJob();
        FakeGit git = new FakeGit();
        git.pushSucceedsOnAttempt = 2;
        git.mergeExit = 0;

        reconciler(job, git).push("feature/x");

        assertEquals(2, git.pushAttempts);
        assertEquals(1, git.count("fetch origin"));
        assertEquals("clean merge needs no conflict hook", 0, job.conflictHookCalls);
    }

    /** Verifies that when the merge produces conflicts the agent hook is called and the resolved files are staged and committed before retrying. */
    @Test(timeout = 30000)
    public void conflictResolvedByAgentThenCommitsAndRetries() throws Exception {
        StubJob job = new StubJob();
        job.resolveConflict = true;
        FakeGit git = new FakeGit();
        git.pushSucceedsOnAttempt = 2;
        git.mergeExit = 1;
        git.conflicted = Arrays.asList("scratch/notes.md");
        git.unmergedOutput = "";

        reconciler(job, git).push("feature/x");

        assertEquals(2, git.pushAttempts);
        assertEquals(1, job.conflictHookCalls);
        assertEquals(Arrays.asList("scratch/notes.md"), job.lastConflictedFiles);
        assertTrue("resolved file should be staged",
                git.commands.contains("add scratch/notes.md"));
        assertTrue("merge commit should be made",
                git.commands.stream().anyMatch(c -> c.contains("commit")));
    }

    /** Verifies that a push failure where the remote did not advance throws an exception containing "did not advance". */
    @Test(timeout = 30000)
    public void remoteDidNotAdvanceFailsLoudly() {
        StubJob job = new StubJob();
        FakeGit git = new FakeGit();
        git.pushSucceedsOnAttempt = 99;
        git.remoteAdvanced = false;

        try {
            reconciler(job, git).push("feature/x");
            fail("expected push to fail when the remote did not advance");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not advance"));
        }
        assertEquals(0, job.conflictHookCalls);
    }

    /** Verifies that when the conflict hook declines to resolve conflicts an exception containing "not resolved" is thrown. */
    @Test(timeout = 30000)
    public void unresolvedConflictFailsLoudly() {
        StubJob job = new StubJob();
        job.resolveConflict = false;
        FakeGit git = new FakeGit();
        git.pushSucceedsOnAttempt = 99;
        git.mergeExit = 1;
        git.conflicted = Arrays.asList("scratch/notes.md");

        try {
            reconciler(job, git).push("feature/x");
            fail("expected push to fail when the conflict hook declines to resolve");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not resolved"));
        }
        assertEquals(1, job.conflictHookCalls);
    }

    /** Verifies that exhausting all reconciliation attempts throws an exception mentioning the maximum attempt count. */
    @Test(timeout = 30000)
    public void exhaustsRetriesFailsLoudly() {
        StubJob job = new StubJob();
        FakeGit git = new FakeGit();
        git.pushSucceedsOnAttempt = 99;
        git.mergeExit = 0;

        try {
            reconciler(job, git).push("feature/x");
            fail("expected push to fail after exhausting reconciliation attempts");
        } catch (Exception e) {
            assertTrue(e.getMessage(),
                    e.getMessage().contains("after " + GitPushReconciler.MAX_RECONCILE_ATTEMPTS));
        }
        assertEquals(GitPushReconciler.MAX_RECONCILE_ATTEMPTS, git.pushAttempts);
        assertFalse(git.commands.isEmpty());
    }
}
