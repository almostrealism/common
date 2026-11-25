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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the push-target safety that {@link GitRepositorySetup} configures on a
 * prepared checkout. In a checkout it prepared, a branch created from
 * {@code origin/master} and then pushed with a bare {@code git push} reaches a
 * remote branch of its own name — never master's upstream — because the setup
 * writes {@code push.default=current} and {@code branch.autoSetupMerge=false}
 * as repository-local git config.
 */
public class GitRepositorySetupPushDefaultTest extends TestSuiteBase {

    /** Identity the job authors its git work under. */
    private static final String JOB_NAME = "Job Identity";

    /** Email the job authors its git work under. */
    private static final String JOB_EMAIL = "job-identity@example.com";

    /** Temporary parent directory holding every repository for one test. */
    private Path root;

    /** The bare repository standing in for {@code origin}. */
    private Path bare;

    /** The prepared working clone of {@link #bare}. */
    private Path working;

    /**
     * Builds a bare origin with one seed commit on {@code master} and a working
     * clone of it that is a child of {@link #root}.
     */
    @Before
    public void setUp() throws IOException, InterruptedException {
        root = Files.createTempDirectory("git-repo-setup-push");

        bare = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", "--initial-branch=master", bare.toString());

        Path seed = Files.createDirectory(root.resolve("seed"));
        git(seed, "init", "-q", "--initial-branch=master");
        configureIdentity(seed);
        Files.writeString(seed.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
        git(seed, "add", "README.md");
        git(seed, "commit", "-q", "-m", "seed");
        git(seed, "remote", "add", "origin", bare.toString());
        git(seed, "push", "-q", "origin", "master");

        working = root.resolve("working");
        git(root, "clone", "-q", bare.toString(), working.toString());
        configureIdentity(working);
    }

    /** Recursively deletes the temporary repositories. */
    @After
    public void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /**
     * After {@link GitRepositorySetup#prepare()} prepares the clone,
     * {@code git checkout -b x origin/master} creates an untracked branch and a
     * bare {@code git push} creates {@code origin/x}, leaving {@code master}'s
     * upstream untouched.
     */
    // TODO(review): also cover a branch that ALREADY tracks master, where push.default=current is the sole protection.
    @Test(timeout = 60000)
    public void preparedCheckoutPushesToCurrentBranchNotMaster()
            throws IOException, InterruptedException {
        String masterBefore = git(working, "rev-parse", "refs/heads/master").trim();

        CodingAgentJob job = new CodingAgentJob("ops", "pin push defaults");
        job.setWorkingDirectory(working.toString());
        job.setTargetBranch("master");
        job.setGitUserName(JOB_NAME);
        job.setGitUserEmail(JOB_EMAIL);

        new GitRepositorySetup(job).prepare();

        assertEquals("current", git(working, "config", "--local", "push.default").trim());
        assertEquals("false", git(working, "config", "--local", "branch.autoSetupMerge").trim());

        git(working, "checkout", "-q", "-b", "x", "origin/master");

        GitOperations.Result upstream = run(working, "rev-parse", "--abbrev-ref", "x@{upstream}");
        assertFalse("branch x must not track any upstream: " + upstream.output(),
                upstream.succeeded());

        git(working, "push", "-q");

        assertTrue("the push must create origin/x",
                run(bare, "rev-parse", "--verify", "--quiet", "refs/heads/x").succeeded());
        Assert.assertEquals("master's upstream must be untouched by the push",
                masterBefore, git(bare, "rev-parse", "refs/heads/master").trim());
    }

    /**
     * Configures a committable identity in {@code dir}'s local git config.
     *
     * @param dir the repository directory
     */
    private static void configureIdentity(Path dir) throws IOException, InterruptedException {
        git(dir, "config", "user.name", JOB_NAME);
        git(dir, "config", "user.email", JOB_EMAIL);
        git(dir, "config", "commit.gpgsign", "false");
    }

    /**
     * Runs a git sub-command in {@code dir} independently of the code under
     * test, failing the test when it exits non-zero.
     *
     * @param dir  the directory to run in
     * @param args the git sub-command and its arguments
     * @return the command's standard output
     */
    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        GitOperations.Result result = run(dir, args);
        Assert.assertEquals("git " + String.join(" ", args) + ": " + result.output(),
                0, result.exitCode());
        return result.output();
    }

    /**
     * Runs a git sub-command in {@code dir} and returns its result whatever the
     * exit code.
     *
     * @param dir  the directory to run in
     * @param args the git sub-command and its arguments
     * @return the result
     */
    private static GitOperations.Result run(Path dir, String... args)
            throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = GitOperations.resolveGitCommand();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitOperations.Result(List.of(args), process.waitFor(), output);
    }
}
