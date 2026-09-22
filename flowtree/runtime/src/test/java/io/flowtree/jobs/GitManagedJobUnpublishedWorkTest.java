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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves, against a real git repository, that a job which produced work but
 * published none of it reports {@link JobCompletionEvent.Status#FAILED}
 * rather than success.
 *
 * <p>This is the regression behind "the job says SUCCESS and the branch has
 * no commit": every path that drops a job's work on the way out —
 * {@link GitManagedJob#validateChanges()} returning false, a phase that threw
 * after the edits were already made, staging guardrails that passed no file —
 * leaves the changes sitting in the working tree with no commit behind them,
 * and the completion event used to call that success.</p>
 */
public class GitManagedJobUnpublishedWorkTest extends TestSuiteBase {

    /** Minimal concrete job; the test supplies the working tree directly. */
    private static final class TestGitJob extends GitManagedJob {
        /** Commit message this job reports as agent-authored, or {@code null} for none. */
        private String authored;

        /** Creates a job with a fixed task id. */
        TestGitJob() {
            super("unpublished-work-test");
        }

        @Override
        protected void doWork() {
            // The test writes working-tree files itself; no agent work here.
        }

        @Override
        public String getTaskString() {
            return getTaskId();
        }

        @Override
        protected String authoredCommitMessage() {
            return authored;
        }

        /**
         * Sets the message this job reports as agent-authored.
         *
         * @param authored the message, or {@code null} for none
         */
        void setAuthoredCommitMessage(String authored) {
            this.authored = authored;
        }
    }

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /** Initializes a git repository with one committed file on a feature branch. */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("unpublished-work-test");
        git("init", "--quiet");
        git("config", "user.email", "unpublished-test@example.com");
        git("config", "user.name", "Unpublished Test");
        git("config", "commit.gpgsign", "false");

        write("src/main/java/Foo.java", "public class Foo { }\n");
        git("add", "src/main/java/Foo.java");
        git("commit", "--quiet", "-m", "seed");
        git("update-ref", "refs/remotes/origin/master", "HEAD");
        git("checkout", "--quiet", "-b", "feature/test");
    }

    /** Recursively deletes the temporary repository. */
    @After
    public void tearDown() throws IOException {
        if (repo != null && Files.exists(repo)) {
            try (Stream<Path> walk = Files.walk(repo)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /**
     * Edits left in the working tree with no commit behind them fail the job
     * and the reason says the work is unpublished.
     */
    @Test(timeout = 60000)
    public void uncommittedChangesWithNoCommitFailTheJob() throws Exception {
        write("src/main/java/Foo.java", "public class Foo { int x; }\n");

        TestGitJob job = newJob();
        String reason = job.describeUnpublishedWork();
        assertNotNull("uncommitted work with no commit must be reported", reason);
        assertTrue("reason must say nothing was committed: " + reason,
                reason.contains("Nothing was committed or pushed"));

        JobCompletionEvent event = job.createEvent(null);
        assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
    }

    /**
     * An agent-authored commit message with no commit behind it fails the job
     * even when the tree itself is clean: the session declared it had changes
     * worth describing, so their absence means they were lost.
     */
    @Test(timeout = 60000)
    public void authoredCommitMessageWithNoChangesFailsTheJob() throws Exception {
        TestGitJob job = newJob();
        job.setAuthoredCommitMessage("Fix the thing\n");

        String reason = job.describeUnpublishedWork();
        assertNotNull("an authored message with no commit must be reported", reason);
        assertTrue("reason must name the commit message: " + reason,
                reason.contains("commit message"));
        assertEquals(JobCompletionEvent.Status.FAILED, job.createEvent(null).getStatus());
    }

    /**
     * A session that deliberately changed nothing and wrote no commit message
     * is the legitimate no-op case and stays successful.
     */
    @Test(timeout = 60000)
    public void noChangesAndNoAuthoredMessageStaysSuccessful() throws Exception {
        TestGitJob job = newJob();

        assertNull(job.describeUnpublishedWork());
        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }

    /**
     * Harness-owned artifacts are not work: a tree whose only change is
     * {@code commit.txt} or something under {@code .flowtree/} must not be
     * read as changes the job failed to publish.
     */
    @Test(timeout = 60000)
    public void harnessArtifactsAloneAreNotUnpublishedWork() throws Exception {
        write("commit.txt", "Fix the thing\n");
        write(".flowtree/results.json", "{}\n");

        TestGitJob job = newJob();
        assertNull("harness artifacts must not count as work", job.describeUnpublishedWork());
        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }

    /**
     * A job with no target branch never commits by design, so it is outside
     * this invariant entirely.
     */
    @Test(timeout = 60000)
    public void jobWithoutTargetBranchIsExempt() throws Exception {
        write("src/main/java/Foo.java", "public class Foo { int x; }\n");

        TestGitJob job = newJob();
        job.setTargetBranch(null);
        assertNull(job.describeUnpublishedWork());
        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }

    /** A dry run makes no commit on purpose and is exempt. */
    @Test(timeout = 60000)
    public void dryRunIsExempt() throws Exception {
        write("src/main/java/Foo.java", "public class Foo { int x; }\n");

        TestGitJob job = newJob();
        job.setDryRun(true);
        assertNull(job.describeUnpublishedWork());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a job pointed at the temporary repository and its feature branch.
     *
     * @return the configured job
     */
    private TestGitJob newJob() {
        TestGitJob job = new TestGitJob();
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/test");
        job.setBaseBranch("master");
        return job;
    }

    /**
     * Writes {@code content} to {@code relativePath} under the repository,
     * creating parent directories as needed.
     *
     * @param relativePath path relative to the repository root
     * @param content      file content
     * @throws IOException if the write fails
     */
    private void write(String relativePath, String content) throws IOException {
        Path target = repo.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /**
     * Runs a git command in the temporary repository, failing the test on a
     * non-zero exit.
     *
     * @param args git arguments
     * @throws Exception if the process cannot be run or exits non-zero
     */
    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(repo.toString()));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
