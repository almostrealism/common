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

import io.flowtree.git.GitOperations;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Drives the real primary-repository commit path of {@link GitCommitHandler}
 * end to end — {@code requireChangedFiles} -> {@code findChangedFiles} ->
 * {@link FileStager#evaluateFiles} -> {@code git add} -> commit — against a
 * temporary git repository, and pins that a changed tracked file whose path
 * {@code git status --porcelain} would C-quote is committed rather than
 * silently dropped.
 *
 * <p>The historical failure: the non-{@code -z} porcelain wraps a path
 * containing spaces (and quotes, and non-ASCII bytes) in double quotes, the
 * parser carried the quoted form through, {@link FileStager} saw a
 * non-existent path and treated it as a deletion exempt from the size and
 * binary guardrails, and {@code git rm --cached --ignore-unmatch} "staged" it
 * as a no-op. The file was reported staged, never reached the commit, and the
 * job reported success. These tests fail if that regresses.</p>
 */
public class GitCommitHandlerSpacedPathTest extends TestSuiteBase {

    /** Identity the job authors its git work under. */
    private static final String JOB_NAME = "Job Identity";

    /** Email the job authors its git work under. */
    private static final String JOB_EMAIL = "job-identity@example.com";

    /** The branch the job targets. */
    private static final String BRANCH = "work";

    /** The ordinary file committed alongside each special-path file. */
    private static final String NORMAL_FILE = "normal.txt";

    /** The primary working directory, a fresh repository on {@link #BRANCH}. */
    private Path repo;

    /** Runs git in {@link #repo}, under the job's identity, for test setup. */
    private GitOperations git;

    /** Creates the repository, on {@link #BRANCH} with a baseline commit. */
    @Before
    public void setUp() throws IOException, InterruptedException {
        repo = Files.createTempDirectory("git-commit-handler-spaced");
        git = new GitOperations(repo.toString(), "spaced-path-test");
        git.setGitUserName(JOB_NAME);
        git.setGitUserEmail(JOB_EMAIL);
        requireGit("init", "-q");
        requireGit("config", "commit.gpgsign", "false");
        requireGit("checkout", "-q", "-b", BRANCH);
        Files.writeString(repo.resolve("README.md"), "baseline\n", StandardCharsets.UTF_8);
        requireGit("add", "README.md");
        requireGit("commit", "-q", "-m", "baseline");
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
     * A modified tracked file whose path is in a dot-directory and contains
     * spaces is committed alongside an ordinary modified file: the path git
     * would quote is staged and reaches the commit.
     */
    @Test(timeout = 60000)
    public void spacedPathInDotDirectoryIsCommitted() throws IOException, InterruptedException {
        assertSpecialAndNormalCommitted(".run/My App Config.run.xml");
    }

    /** A modified tracked file whose path contains a double quote is committed. */
    @Test(timeout = 60000)
    public void quoteCharPathIsCommitted() throws IOException, InterruptedException {
        assertSpecialAndNormalCommitted("config/quote\"name.txt");
    }

    /** A modified tracked file whose path contains non-ASCII bytes is committed. */
    @Test(timeout = 60000)
    public void nonAsciiPathIsCommitted() throws IOException, InterruptedException {
        assertSpecialAndNormalCommitted("notes/uniçode.txt");
    }

    // TODO(review): add an end-to-end case that renames a spaced path (git mv)
    // so GitOperations.parseChangedFiles' rename branch (new-path-first in -z)
    // is exercised against real git, not just the hand-constructed unit case.

    /**
     * Seeds {@code specialPath} and {@link #NORMAL_FILE} into a commit, modifies
     * both, runs the handler's primary commit path, and asserts both the special
     * path and the ordinary file are reported staged and present in the commit.
     *
     * @param specialPath the repository-relative path git would quote in
     *                    non-{@code -z} porcelain output
     * @throws IOException          if a git command or file write fails
     * @throws InterruptedException if a git command is interrupted
     */
    private void assertSpecialAndNormalCommitted(String specialPath)
            throws IOException, InterruptedException {
        writeRepoFile(specialPath, "original\n");
        writeRepoFile(NORMAL_FILE, "original\n");
        requireGit("add", "-A");
        requireGit("commit", "-q", "-m", "seed files");

        writeRepoFile(specialPath, "changed by the job\n");
        writeRepoFile(NORMAL_FILE, "changed by the job\n");

        GitCommitHandler handler = new GitCommitHandler(job());
        handler.handle(false);

        List<String> staged = handler.getStagedFiles();
        assertTrue(specialPath + " must be reported staged: " + staged, staged.contains(specialPath));
        assertTrue(NORMAL_FILE + " must be reported staged: " + staged, staged.contains(NORMAL_FILE));

        Set<String> committed = committedFilesAtHead();
        assertTrue(specialPath + " must be in the commit: " + committed, committed.contains(specialPath));
        assertTrue(NORMAL_FILE + " must be in the commit: " + committed, committed.contains(NORMAL_FILE));
    }

    /** Writes {@code content} to {@code relativePath}, creating parent directories. */
    private void writeRepoFile(String relativePath, String content) throws IOException {
        Path target = repo.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** Lists the paths HEAD changed relative to its parent, from {@code -z} output. */
    private Set<String> committedFilesAtHead() throws IOException, InterruptedException {
        String output = requireGit("diff-tree", "--no-commit-id", "--name-only", "-r", "-z", "HEAD");
        Set<String> paths = new LinkedHashSet<>();
        for (String path : output.split("\0", -1)) {
            if (!path.isEmpty() && !"\n".equals(path)) paths.add(path);
        }
        return paths;
    }

    /**
     * Runs a git sub-command in {@link #repo} independently of the code under
     * test, failing the test if it exits non-zero.
     *
     * @param args the git sub-command and its arguments
     * @return the command's combined output
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the process is interrupted
     */
    private String requireGit(String... args) throws IOException, InterruptedException {
        GitOperations.Result result = git.executeForResult(args);
        Assert.assertEquals(result.describe(), 0, result.exitCode());
        return result.output();
    }

    /** A job working in the repository on {@link #BRANCH}, not pushing to origin. */
    private CodingAgentJob job() {
        CodingAgentJob job = new CodingAgentJob("ops", "exercise the primary commit path");
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch(BRANCH);
        job.setGitUserName(JOB_NAME);
        job.setGitUserEmail(JOB_EMAIL);
        job.setPushToOrigin(false);
        return job;
    }
}
