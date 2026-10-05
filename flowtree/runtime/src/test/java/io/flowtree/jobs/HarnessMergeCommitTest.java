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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end checks, against a real git repository and the production
 * {@link GitCommitHandler}, of how a merge the harness left in progress is
 * committed.
 *
 * <p>The merge path cannot reset the index the way the normal path does, so it
 * once committed whatever the merge and the agent had staged, including files
 * the guardrails rejected. These tests hold both halves of the fix: content the
 * merge carried in from the base branch is committed even when it is a CI or
 * protected test file, and content the agent wrote or staged itself is judged
 * by the same locks as outside a merge, and never reaches the commit when they
 * reject it.</p>
 *
 * <p>Every assertion reads the committed tree ({@code git show HEAD:<path>}),
 * never the handler's own report of what it did.</p>
 */
public class HarnessMergeCommitTest extends TestSuiteBase {

    /** The CI workflow both branches start from. */
    private static final String CI_FILE = ".github/workflows/ci.yaml";

    /** A protected (non-Java) test resource. */
    private static final String TEST_RESOURCE = "src/test/resources/data.txt";

    /** A {@link GitManagedJob} whose merge parent is supplied by the test. */
    private static final class MergeJob extends GitManagedJob {
        /** The merge parent this job reports, or {@code null}. */
        private final String mergeParent;

        /**
         * Creates a job reporting {@code mergeParent} as the merge it left in progress.
         *
         * @param mergeParent the recorded merge parent, or {@code null}
         */
        MergeJob(String mergeParent) {
            super("harness-merge-test");
            this.mergeParent = mergeParent;
        }

        @Override
        String getHarnessMergeParent() {
            return mergeParent;
        }

        @Override
        protected void doWork() {
            // The test prepares the working tree directly.
        }

        @Override
        public String getTaskString() {
            return getTaskId();
        }
    }

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /** The base-branch commit merged into {@code feature/test}. */
    private String mergeParent;

    /**
     * Builds a repository in which {@code feature/test} has an unrelated
     * conflict with {@code master}, and {@code master} has changed the CI file
     * and a protected test resource that the feature branch never touched.
     */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("harness-merge");
        git("init", "--quiet", "--initial-branch=master");
        git("config", "user.email", "merge-test@example.com");
        git("config", "user.name", "Merge Test");
        git("config", "commit.gpgsign", "false");
        write(CI_FILE, "name: v1\n");
        write(TEST_RESOURCE, "data v1\n");
        write("shared.txt", "base\n");
        commitAll("initial");

        git("checkout", "--quiet", "-b", "feature/test");
        write("shared.txt", "branch\n");
        commitAll("branch change");

        git("checkout", "--quiet", "master");
        write(CI_FILE, "name: v2\n");
        write(TEST_RESOURCE, "data v2\n");
        write("shared.txt", "master\n");
        commitAll("master change");
        mergeParent = git("rev-parse", "HEAD").trim();
        git("update-ref", "refs/remotes/origin/master", mergeParent);

        git("checkout", "--quiet", "feature/test");
        startConflictedMerge();
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
     * The 2026-10-03 case: the merge carries the base branch's CI file in and
     * the agent only resolves an unrelated conflict. The CI file is committed
     * with the base content, and nothing is reported as skipped.
     */
    @Test(timeout = 60000)
    public void mergeCarriedCiFileIsCommittedAndNotReportedSkipped() throws Exception {
        resolveSharedConflict();
        MergeJob job = newJob(mergeParent);
        GitCommitHandler handler = commit(job);

        assertEquals("name: v2\n", committed(CI_FILE));
        assertTrue("the merge commit must have two parents", isMergeCommit());
        assertTrue("nothing was rejected: " + handler.getSkippedFiles(),
                handler.getSkippedFiles().isEmpty());
        List<String> previewed = newJob(mergeParent).previewStaging().getSkippedFiles();
        assertTrue("the preview must agree with the commit: " + previewed, previewed.isEmpty());
    }

    /**
     * A CI edit the agent stages itself during the merge never reaches the
     * commit; the file returns to the base branch's version, which is what a
     * clean merge would have produced.
     */
    @Test(timeout = 60000)
    public void agentStagedCiEditDuringMergeIsNotCommitted() throws Exception {
        resolveSharedConflict();
        write(CI_FILE, "name: agent\n");
        git("add", CI_FILE);

        assertTrue("the preview must report the edit as skipped",
                mentions(newJob(mergeParent).previewStaging().getSkippedFiles(), CI_FILE));
        GitCommitHandler handler = commit(newJob(mergeParent));

        assertEquals("name: v2\n", committed(CI_FILE));
        assertTrue(mentions(handler.getSkippedFiles(), CI_FILE));
    }

    /** A branch-new CI file the agent stages during the merge is not committed. */
    @Test(timeout = 60000)
    public void agentStagedNewCiFileDuringMergeIsNotCommitted() throws Exception {
        resolveSharedConflict();
        write("tools/ci/new.sh", "echo new\n");
        git("add", "tools/ci/new.sh");

        commit(newJob(mergeParent));

        assertNull(committed("tools/ci/new.sh"));
    }

    /**
     * Putting the CI file back to the feature branch's version would silently
     * revert the base branch's change inside the merge commit. {@code git
     * status} cannot see it (the file matches {@code HEAD}), so the commit must
     * find and undo it anyway.
     */
    @Test(timeout = 60000)
    public void revertingBaseCiChangeDuringMergeIsNotCommitted() throws Exception {
        resolveSharedConflict();
        write(CI_FILE, "name: v1\n");
        git("add", CI_FILE);

        GitCommitHandler handler = commit(newJob(mergeParent));

        assertEquals("name: v2\n", committed(CI_FILE));
        assertTrue(mentions(handler.getSkippedFiles(), CI_FILE));
    }

    /**
     * Under the test lock, a protected test resource the merge carried in is
     * committed with the base content, and an agent edit to it is not.
     */
    @Test(timeout = 60000)
    public void testLockAcceptsMergeCarriedResourceAndRejectsAgentEdit() throws Exception {
        resolveSharedConflict();
        MergeJob carried = newJob(mergeParent);
        carried.setProtectTestFiles(true);
        List<String> previewed = carried.previewStaging().getSkippedFiles();
        assertTrue("the merge-carried resource must not be skipped: " + previewed, previewed.isEmpty());

        write(TEST_RESOURCE, "data agent\n");
        git("add", TEST_RESOURCE);
        MergeJob edited = newJob(mergeParent);
        edited.setProtectTestFiles(true);
        commit(edited);

        assertEquals("data v2\n", committed(TEST_RESOURCE));
    }

    /**
     * A merge with no recorded parent was not started by the harness, so
     * nothing it brings in is exempt: the CI lock rejects the base branch's CI
     * change and the file stays at the feature branch's version.
     */
    @Test(timeout = 60000)
    public void mergeWithoutRecordedParentGetsNoExemption() throws Exception {
        resolveSharedConflict();

        GitCommitHandler handler = commit(newJob(null));

        assertEquals("name: v1\n", committed(CI_FILE));
        assertTrue(mentions(handler.getSkippedFiles(), CI_FILE));
    }

    /**
     * The recorded parent is trusted only while {@code MERGE_HEAD} still names
     * it, so an agent that rewrites {@code MERGE_HEAD} gains no exemption.
     */
    @Test(timeout = 60000)
    public void rewrittenMergeHeadIsNotTrusted() throws Exception {
        FileStagingConfig config = FileStagingConfig.builder().mergeParent(mergeParent).build();
        assertEquals(mergeParent, FileStager.trustedMergeParent(config, newJob(mergeParent).asGitOperations()));

        String other = git("rev-parse", "feature/test").trim();
        Files.writeString(repo.resolve(".git/MERGE_HEAD"), other + "\n");

        assertNull(FileStager.trustedMergeParent(config, newJob(mergeParent).asGitOperations()));
    }

    /**
     * Resolving every conflict in the target branch's favour is a valid
     * outcome, and the merge is still committed. Left uncommitted, the base
     * branch would stay unmerged and the next job would meet the same conflict.
     */
    @Test(timeout = 60000)
    public void mergeResolvedToTheBranchTreeIsStillCommitted() throws Exception {
        git("merge", "--abort");
        String root = git("rev-list", "--max-parents=0", "HEAD").trim();
        git("checkout", "--quiet", "-b", "shared-only", root);
        write("shared.txt", "other\n");
        commitAll("shared-only change");
        String otherParent = git("rev-parse", "HEAD").trim();
        git("checkout", "--quiet", "feature/test");
        runGit("merge", "--no-edit", "shared-only");
        write("shared.txt", "branch\n");
        git("add", "shared.txt");
        String before = git("rev-parse", "HEAD").trim();

        commit(newJob(otherParent));

        assertTrue("the merge must be committed", isMergeCommit());
        assertEquals(before, git("rev-parse", "HEAD^1").trim());
        assertEquals(otherParent, git("rev-parse", "HEAD^2").trim());
        assertTrue("no merge may be left in progress",
                runGit("rev-parse", "--verify", "--quiet", "MERGE_HEAD").exit != 0);
    }

    /**
     * A protected file both branches changed has no version the harness can
     * choose for the agent. When its resolution is rejected the commit fails
     * and names it, rather than commit the rejected content.
     */
    @Test(timeout = 60000)
    public void rejectedResolutionOfFileBothSidesChangedFailsTheCommit() throws Exception {
        git("merge", "--abort");
        write(CI_FILE, "name: branch\n");
        commitAll("branch CI change");
        startConflictedMerge();
        resolveSharedConflict();
        write(CI_FILE, "name: resolved\n");
        git("add", CI_FILE);

        try {
            commit(newJob(mergeParent));
            fail("the commit must fail rather than commit a rejected resolution");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(CI_FILE));
        }
        assertEquals("name: branch\n", committed(CI_FILE));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Merges {@code master} into the current branch, leaving its conflict in progress. */
    private void startConflictedMerge() throws IOException, InterruptedException {
        runGit("merge", "--no-edit", "master");
        assertFalse("the merge must be left in progress",
                git("rev-parse", "--verify", "--quiet", "MERGE_HEAD").isBlank());
    }

    /** Resolves the {@code shared.txt} conflict the way an agent would. */
    private void resolveSharedConflict() throws IOException, InterruptedException {
        write("shared.txt", "branch and master\n");
        git("add", "shared.txt");
    }

    /** Builds a job bound to the temp repo, with pushing disabled. */
    private MergeJob newJob(String parent) {
        MergeJob job = new MergeJob(parent);
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/test");
        job.setBaseBranch("master");
        job.setCreateBranchIfMissing(false);
        job.setPushToOrigin(false);
        job.setGitUserName("Merge Test");
        job.setGitUserEmail("merge-test@example.com");
        return job;
    }

    /** Runs the production commit handler for {@code job}. */
    private GitCommitHandler commit(MergeJob job) throws IOException, InterruptedException {
        GitCommitHandler handler = new GitCommitHandler(job);
        handler.handle(true);
        return handler;
    }

    /** Returns {@code path}'s content in {@code HEAD}, or {@code null} if absent. */
    private String committed(String path) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "show", "HEAD:" + path)
                .directory(repo.toFile()).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return p.waitFor() == 0 ? out : null;
    }

    /** Returns whether {@code HEAD} has two parents. */
    private boolean isMergeCommit() throws IOException, InterruptedException {
        return git("rev-list", "--parents", "-n", "1", "HEAD").trim().split(" ").length == 3;
    }

    /** Returns whether any skip-list entry names {@code path}. */
    private boolean mentions(List<String> skipped, String path) {
        return skipped.stream().anyMatch(entry -> entry.startsWith(path + " "));
    }

    /** Stages everything and commits it. */
    private void commitAll(String message) throws IOException, InterruptedException {
        git("add", "-A");
        git("commit", "--quiet", "-m", message);
    }

    /** Writes {@code content} to {@code relativePath} under the repo, creating parents. */
    private void write(String relativePath, String content) throws IOException {
        Path p = repo.resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    /** Runs git in the repo, failing the test if it exits non-zero, and returns its output. */
    private String git(String... args) throws IOException, InterruptedException {
        ProcessResult result = runGit(args);
        if (result.exit != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + result.output);
        }
        return result.output;
    }

    /** Runs git in the repo and returns its exit code and combined output. */
    private ProcessResult runGit(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(p.waitFor(), out);
    }

    /** The exit code and output of one git invocation. */
    private record ProcessResult(int exit, String output) { }
}
