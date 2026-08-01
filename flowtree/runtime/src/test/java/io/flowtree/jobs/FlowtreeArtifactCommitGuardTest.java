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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end proof — against a real git repository and the production
 * {@link GitCommitHandler} — that the {@code .flowtree/} commit guard is real:
 *
 * <ol>
 *   <li>A file placed under {@code .flowtree/} during a job is NOT in the
 *       resulting commit (empty whitelist).</li>
 *   <li>The whitelist genuinely gates: a test-injected entry IS committed while
 *       its non-whitelisted sibling is excluded — so the mechanism is not a
 *       no-op.</li>
 *   <li>A normal change OUTSIDE {@code .flowtree/} still commits (no
 *       over-exclusion regression).</li>
 * </ol>
 *
 * <p>Every assertion is made against the actual committed path set
 * ({@code git show --name-only HEAD}), never a boolean proxy.</p>
 */
public class FlowtreeArtifactCommitGuardTest extends TestSuiteBase {

    /** Concrete minimal {@link GitManagedJob} whose work is supplied directly by the test. */
    private static final class TestGitJob extends GitManagedJob {
        /** Creates a job with a fixed task id; all working-tree content is supplied by the test. */
        TestGitJob() {
            super("flowtree-guard-test");
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

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /** Initializes a fresh git repository on a {@code feature/test} branch before each test. */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("flowtree-guard");
        git("init", "--quiet");
        git("config", "user.email", "guard-test@example.com");
        git("config", "user.name", "Guard Test");
        git("config", "commit.gpgsign", "false");
        // Seed an initial commit so HEAD exists, then move onto the target branch.
        write("README.md", "seed\n");
        git("add", "README.md");
        git("commit", "--quiet", "-m", "initial");
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
     * A {@code .flowtree/} artifact written during the job must NOT appear in the
     * commit, while a normal change OUTSIDE {@code .flowtree/} must. This is the
     * core no-leak guarantee with the production (empty) whitelist.
     */
    @Test(timeout = 60000)
    public void flowtreeArtifactExcludedAndNormalChangeCommitted() throws Exception {
        write(".flowtree/falsification-results.json", "{\"claims\":[]}\n");
        write(".flowtree/claude-output/run.json", "{}\n");
        write("realwork.txt", "a genuine code change\n");

        runCommit(newJob(), null);

        List<String> committed = committedFiles();
        assertTrue("The agent's real change must still commit",
                committed.contains("realwork.txt"));
        assertFalse("falsification-results.json under .flowtree/ must NOT commit",
                committed.contains(".flowtree/falsification-results.json"));
        assertFalse("nested .flowtree/ output must NOT commit",
                committed.contains(".flowtree/claude-output/run.json"));
        assertNoFlowtreePaths(committed);
    }

    /**
     * With a test-injected whitelist entry, that specific {@code .flowtree/} path
     * IS committed, while a non-whitelisted sibling is still excluded — proving
     * the whitelist is a genuine gate and not a no-op.
     */
    @Test(timeout = 60000)
    public void whitelistedFlowtreePathIsCommittedSiblingIsNot() throws Exception {
        write(".flowtree/keepme.txt", "promote me\n");
        write(".flowtree/other.txt", "leave me behind\n");
        write("realwork.txt", "a genuine code change\n");

        runCommit(newJob(), Set.of(".flowtree/keepme.txt"));

        List<String> committed = committedFiles();
        assertTrue("A whitelisted .flowtree/ path MUST be committed",
                committed.contains(".flowtree/keepme.txt"));
        assertFalse("A non-whitelisted .flowtree/ sibling must be excluded",
                committed.contains(".flowtree/other.txt"));
        assertTrue("The normal change must still commit alongside",
                committed.contains("realwork.txt"));
    }

    /**
     * The exact same {@code .flowtree/} file is excluded under the empty whitelist
     * but committed once whitelisted — the fail-WITHOUT / pass-WITH contrast on a
     * single path that proves the gate is load-bearing.
     */
    @Test(timeout = 60000)
    public void sameFlowtreePathExcludedThenCommittedDependingOnWhitelist() throws Exception {
        write(".flowtree/keepme.txt", "candidate\n");
        write("realwork.txt", "change\n");

        // Empty whitelist (production default): excluded.
        runCommit(newJob(), null);
        assertFalse("With the empty whitelist the path must be excluded",
                committedFiles().contains(".flowtree/keepme.txt"));

        // Re-create the artifact (the commit did not include it) and whitelist it.
        write(".flowtree/keepme.txt", "candidate\n");
        write("realwork2.txt", "another change\n");
        runCommit(newJob(), Set.of(".flowtree/keepme.txt"));
        assertTrue("With the path whitelisted it must now be committed",
                committedFiles().contains(".flowtree/keepme.txt"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a job bound to the temp repo and the {@code feature/test} branch with pushing disabled. */
    private TestGitJob newJob() {
        TestGitJob job = new TestGitJob();
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/test");
        job.setBaseBranch("master");
        job.setCreateBranchIfMissing(false);
        job.setPushToOrigin(false);
        job.setGitUserName("Guard Test");
        job.setGitUserEmail("guard-test@example.com");
        return job;
    }

    /** Runs the production commit handler against {@code job}, optionally injecting a whitelist. */
    private void runCommit(TestGitJob job, Set<String> whitelist) throws IOException, InterruptedException {
        GitCommitHandler handler = new GitCommitHandler(job);
        if (whitelist != null) {
            handler.setFlowtreeCommitWhitelist(whitelist);
        }
        handler.handle(false);
    }

    /** Returns the set of paths in the most recent commit on HEAD. */
    private List<String> committedFiles() throws IOException, InterruptedException {
        String out = git("show", "--pretty=format:", "--name-only", "HEAD");
        List<String> files = new ArrayList<>();
        for (String line : out.split("\n")) {
            String f = line.trim();
            if (!f.isEmpty()) files.add(f);
        }
        return files;
    }

    /** Asserts that no committed path lies under {@code .flowtree/}. */
    private void assertNoFlowtreePaths(List<String> committed) {
        for (String f : committed) {
            assertFalse("No committed path may be under .flowtree/: " + f,
                    FlowtreeArtifacts.isUnderDirectory(f));
        }
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
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(repo.toString()));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + code + "): " + out);
        }
        return out;
    }
}
