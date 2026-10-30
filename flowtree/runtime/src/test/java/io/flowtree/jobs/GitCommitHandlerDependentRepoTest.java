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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins how {@link GitCommitHandler#handleDependentRepos(GitRepositorySetup)}
 * reports what it pushed. A job that changes a file in a dependent repo reports
 * exactly one {@link DependentRepoPush} carrying that repo's clone URL, the
 * pushed branch, and the pushed HEAD; a job that leaves the dependent repo
 * untouched reports none. These are what the controller needs to open (or skip)
 * a companion pull request.
 */
public class GitCommitHandlerDependentRepoTest extends TestSuiteBase {

    /** Identity the job authors its git work under. */
    private static final String JOB_NAME = "Job Identity";

    /** Email the job authors its git work under. */
    private static final String JOB_EMAIL = "job-identity@example.com";

    /** The URL label the dependent repo is declared with. */
    private static final String DEP_URL = "https://example.com/dependent.git";

    /** Temporary parent directory holding every repository for one test. */
    private Path root;

    /** The dependent repo's bare origin, which the working clone pushes to. */
    private Path depBare;

    /** The dependent working clone, a sibling of the primary working dir. */
    private Path dependent;

    /** The primary working directory, set in {@link #setUp()}. */
    private Path primaryWorkingDir;

    /** Creates the primary working dir, the dependent bare origin, and its clone. */
    @Before
    public void setUp() throws IOException, InterruptedException {
        root = Files.createTempDirectory("git-commit-handler-dep");

        // Primary working directory: only needs to exist so its parent resolves
        // to root, where the dependent clone is a sibling.
        Path primary = Files.createDirectory(root.resolve("primary"));
        git(primary, "init", "-q");

        // Dependent bare origin, seeded with master and feature/x.
        depBare = Files.createDirectory(root.resolve("dep.git"));
        git(depBare, "init", "-q", "--bare");

        Path seed = Files.createDirectory(root.resolve("seed"));
        git(seed, "init", "-q");
        configure(seed);
        Files.writeString(seed.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
        git(seed, "add", "README.md");
        git(seed, "commit", "-q", "-m", "seed");
        git(seed, "branch", "-M", "master");
        git(seed, "remote", "add", "origin", depBare.toString());
        git(seed, "push", "-q", "origin", "master");
        git(seed, "checkout", "-q", "-b", "feature/x");
        git(seed, "push", "-q", "origin", "feature/x");

        // The working clone the job drives, at root/dependent (the name
        // extractRepoName derives from DEP_URL), on feature/x.
        git(root, "clone", "-q", depBare.toString(), "dependent");
        dependent = root.resolve("dependent");
        configure(dependent);
        git(dependent, "checkout", "-q", "feature/x");

        this.primaryWorkingDir = primary;
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
     * A file changed in the dependent repo is committed, pushed, and reported as
     * one {@link DependentRepoPush} whose hash is the pushed HEAD and whose
     * branch and URL match what the job targeted.
     */
    @Test(timeout = 60000)
    public void changedDependentRepoReportsItsPush() throws IOException, InterruptedException {
        Files.writeString(dependent.resolve("README.md"), "changed by the job\n", StandardCharsets.UTF_8);

        GitRepositorySetup setup = preparedSetup();
        GitCommitHandler handler = new GitCommitHandler(job());
        handler.handleDependentRepos(setup);

        List<DependentRepoPush> pushes = handler.getDependentRepoPushes();
        assertEquals(1, pushes.size());
        DependentRepoPush push = pushes.get(0);
        assertEquals(DEP_URL, push.repoUrl());
        assertEquals("feature/x", push.branch());

        String localHead = git(dependent, "rev-parse", "HEAD").trim();
        assertEquals(localHead, push.commitHash());
        Assert.assertEquals("the push reached the origin's feature/x",
                localHead, git(depBare, "rev-parse", "feature/x").trim());
    }

    /** A dependent repo left untouched is committed to nothing and reports no push. */
    @Test(timeout = 60000)
    public void untouchedDependentRepoReportsNoPush() throws IOException, InterruptedException {
        GitRepositorySetup setup = preparedSetup();
        GitCommitHandler handler = new GitCommitHandler(job());
        handler.handleDependentRepos(setup);

        assertTrue(handler.getDependentRepoPushes().isEmpty());
    }

    /** A setup whose dependent-repo map is populated for the configured job. */
    private GitRepositorySetup preparedSetup() throws IOException, InterruptedException {
        GitRepositorySetup setup = new GitRepositorySetup(job());
        setup.prepareDependentRepos();
        assertEquals(Collections.singletonList(dependent.toString()), setup.getDependentRepoPaths());
        return setup;
    }

    /** A job working in the primary dir on feature/x, pushing to origin. */
    private CodingAgentJob job() {
        CodingAgentJob job = new CodingAgentJob("ops", "exercise dependent-repo push reporting");
        job.setWorkingDirectory(primaryWorkingDir.toString());
        job.setTargetBranch("feature/x");
        job.setGitUserName(JOB_NAME);
        job.setGitUserEmail(JOB_EMAIL);
        job.setPushToOrigin(true);
        job.setDependentRepos(Collections.singletonList(DEP_URL));
        return job;
    }

    /** Configures a repo's identity and disables commit signing. */
    private static void configure(Path dir) throws IOException, InterruptedException {
        git(dir, "config", "user.name", JOB_NAME);
        git(dir, "config", "user.email", JOB_EMAIL);
        git(dir, "config", "commit.gpgsign", "false");
    }

    /**
     * Runs a git sub-command in {@code dir} independently of the code under
     * test, failing the test when it fails.
     *
     * @param dir  the directory to run in
     * @param args the git sub-command and its arguments
     * @return the command's standard output
     */
    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = GitOperations.resolveGitCommand();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assert.assertEquals("git " + String.join(" ", args) + ": " + output, 0, process.waitFor());
        return output;
    }
}
