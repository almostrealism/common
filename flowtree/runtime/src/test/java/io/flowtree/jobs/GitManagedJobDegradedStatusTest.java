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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end proof — against a real git repository and the production
 * {@link GitCommitHandler} / {@link FileStager} / {@link TestMethodProtection}
 * pipeline — that a job whose only change is dropped by a staging guardrail
 * reports {@link JobCompletionEvent.Status#DEGRADED}, not
 * {@link JobCompletionEvent.Status#SUCCESS}.
 *
 * <p>This is the exact regression PR #492 exposed: a job modified an existing
 * test method, guardrail 2 discarded the whole change, and the job still
 * reported success with no commit.</p>
 */
public class GitManagedJobDegradedStatusTest extends TestSuiteBase {

    /** Concrete minimal {@link GitManagedJob} whose work is supplied directly by the test. */
    private static final class TestGitJob extends GitManagedJob {
        /** Creates a job with a fixed task id; working-tree content is supplied by the test. */
        TestGitJob() {
            super("degraded-status-test");
        }

        @Override
        protected void doWork() {
            // The test creates working-tree files directly; no agent work here.
        }

        @Override
        public String getTaskString() {
            return getTaskId();
        }
    }

    /** The protected test file path used across the scenarios below. */
    private static final String TEST_FILE = "src/test/java/FooTest.java";

    /** Fixture: a base-branch test file with one {@code @Test} method. */
    private static final String ONE_METHOD =
            "package com.example;\n\n"
            + "import org.junit.Test;\n\n"
            + "public class FooTest {\n"
            + "    @Test\n"
            + "    public void testFoo() {\n"
            + "        assertEquals(1, 1);\n"
            + "    }\n"
            + "}\n";

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /** Initializes a fresh git repository with a committed test file on master. */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("degraded-status-test");
        git("init", "--quiet");
        git("config", "user.email", "degraded-test@example.com");
        git("config", "user.name", "Degraded Test");
        git("config", "commit.gpgsign", "false");

        installAwkScript();
        write(TEST_FILE, ONE_METHOD);
        git("add", TEST_FILE, "tools/ci/agent-protection/test-method-lines.awk");
        git("commit", "--quiet", "-m", "seed");
        // Publish HEAD as the base-branch remote-tracking ref -- no real
        // remote is needed for TestMethodProtection's merge-base resolution.
        git("update-ref", "refs/remotes/origin/master", "HEAD");
        git("checkout", "--quiet", "-b", "feature/test");
    }

    /** Recursively deletes the temporary repository after each test. */
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
     * The only change on the branch is an edit to a pre-existing test
     * method: guardrail 2 drops it in full, nothing is staged or committed,
     * and the job must report DEGRADED with the file named in the error.
     */
    @Test(timeout = 60000)
    public void allChangesDroppedReportsDegraded() throws Exception {
        write(TEST_FILE, ONE_METHOD.replace("assertEquals(1, 1);", "assertEquals(2, 2);"));

        TestGitJob job = newJob();
        GitCommitHandler handler = new GitCommitHandler(job);
        handler.handle(false);
        job.setCommitHandlerForTesting(handler);

        assertTrue("stagedFiles must be empty", handler.getStagedFiles().isEmpty());
        assertFalse("skippedFiles must be non-empty", handler.getSkippedFiles().isEmpty());
        assertTrue("hasAllChangesDropped() must be true", job.hasAllChangesDropped());

        JobCompletionEvent event = job.createEvent(null);
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertTrue("Error message must name the dropped file: " + event.getErrorMessage(),
                event.getErrorMessage().contains(TEST_FILE));
    }

    /**
     * When one file is blocked but another (branch-new) file still commits,
     * the job is not "all changes dropped" and keeps reporting SUCCESS --
     * but the blocked file is still visible in skippedFiles.
     */
    @Test(timeout = 60000)
    public void partialDropStaysSuccessButListsSkippedFile() throws Exception {
        write(TEST_FILE, ONE_METHOD.replace("assertEquals(1, 1);", "assertEquals(2, 2);"));
        write("src/test/java/NewTest.java", "package com.example;\npublic class NewTest {}\n");

        TestGitJob job = newJob();
        GitCommitHandler handler = new GitCommitHandler(job);
        handler.handle(false);
        job.setCommitHandlerForTesting(handler);

        assertFalse("A branch-new file must still be staged", handler.getStagedFiles().isEmpty());
        assertFalse("The blocked file must be recorded as skipped", handler.getSkippedFiles().isEmpty());
        assertFalse("hasAllChangesDropped() must be false when something staged",
                job.hasAllChangesDropped());

        JobCompletionEvent event = job.createEvent(null);
        assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
    }

    /** A clean run with no changes at all is plain SUCCESS, not DEGRADED. */
    @Test(timeout = 60000)
    public void noChangesAtAllStaysSuccess() throws Exception {
        TestGitJob job = newJob();
        GitCommitHandler handler = new GitCommitHandler(job);
        handler.handle(false);
        job.setCommitHandlerForTesting(handler);

        assertTrue(handler.getStagedFiles().isEmpty());
        assertTrue(handler.getSkippedFiles().isEmpty());
        assertFalse(job.hasAllChangesDropped());
        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Builds a job bound to the temp repo and the {@code feature/test} branch with protection on. */
    private TestGitJob newJob() {
        TestGitJob job = new TestGitJob();
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/test");
        job.setBaseBranch("master");
        job.setCreateBranchIfMissing(false);
        job.setPushToOrigin(false);
        job.setProtectTestFiles(true);
        job.setGitUserName("Degraded Test");
        job.setGitUserEmail("degraded-test@example.com");
        return job;
    }

    /**
     * Copies the repository's real {@code test-method-lines.awk} into the
     * temp repo at its expected repo-relative path, so
     * {@link TestMethodProtection} finds it there.
     */
    private void installAwkScript() throws IOException {
        Path scriptDir = Files.createDirectories(repo.resolve("tools/ci/agent-protection"));
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "tools/ci/agent-protection/test-method-lines.awk");
            if (candidate.isFile()) {
                Files.copy(candidate.toPath(), scriptDir.resolve("test-method-lines.awk"),
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("Could not locate tools/ci/agent-protection/test-method-lines.awk"
                + " above " + new File("").getAbsolutePath());
    }

    /** Writes {@code content} to {@code relativePath} under the repo, creating parents. */
    private void write(String relativePath, String content) throws IOException {
        Path p = repo.resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    /** Runs a git command in the repo, failing the test on a non-zero exit. */
    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exit + "): " + output);
        }
        return output;
    }
}
