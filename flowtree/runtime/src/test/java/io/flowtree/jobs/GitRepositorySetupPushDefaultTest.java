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
import java.util.Collections;
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

        Path[] origin = seededOrigin("working");
        bare = origin[0];
        working = origin[1];
    }

    /**
     * Builds a bare origin seeded with one commit on {@code master} and a
     * working clone of it, both children of {@link #root}.
     *
     * @param name the basename of the pair; the bare origin is
     *             {@code <name>.git} and the clone is {@code <name>}, so a
     *             dependent repo's clone matches the name
     *             {@link WorkspaceResolver#extractRepoName(String)} derives
     *             from its URL
     * @return the pair {@code {bare, clone}}
     */
    private Path[] seededOrigin(String name) throws IOException, InterruptedException {
        Path bareDir = root.resolve(name + ".git");
        git(root, "init", "-q", "--bare", "--initial-branch=master", bareDir.toString());

        Path seed = Files.createDirectory(root.resolve(name + "-seed"));
        git(seed, "init", "-q", "--initial-branch=master");
        configureIdentity(seed);
        Files.writeString(seed.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
        git(seed, "add", "README.md");
        git(seed, "commit", "-q", "-m", "seed");
        git(seed, "remote", "add", "origin", bareDir.toString());
        git(seed, "push", "-q", "origin", "master");

        Path clone = root.resolve(name);
        git(root, "clone", "-q", bareDir.toString(), clone.toString());
        configureIdentity(clone);
        return new Path[] {bareDir, clone};
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
     * upstream untouched. This is the {@code branch.autoSetupMerge=false} path:
     * the new branch never acquires an upstream in the first place.
     */
    @Test(timeout = 60000)
    public void preparedCheckoutPushesToCurrentBranchNotMaster()
            throws IOException, InterruptedException {
        new GitRepositorySetup(primaryJob()).prepare();

        assertEquals("current", git(working, "config", "--local", "push.default").trim());
        assertEquals("false", git(working, "config", "--local", "branch.autoSetupMerge").trim());

        git(working, "checkout", "-q", "-b", "x", "origin/master");

        GitOperations.Result upstream = run(working, "rev-parse", "--abbrev-ref", "x@{upstream}");
        assertFalse("branch x must not track any upstream: " + upstream.output(),
                upstream.succeeded());

        assertBarePushTargetsOwnBranch(working, bare, "x");
    }

    /**
     * A branch that <em>already</em> tracks {@code origin/master} — the failure
     * mode this configuration exists to close — still pushes to a remote branch
     * of its own name. Here {@code branch.autoSetupMerge=false} cannot help,
     * because the tracking link is established explicitly after the branch
     * exists; {@code push.default=current} is the sole protection, and a bare
     * {@code git push} must create {@code origin/y} rather than updating
     * {@code origin/master}.
     */
    @Test(timeout = 60000)
    public void preparedCheckoutTrackingMasterStillPushesToOwnBranch()
            throws IOException, InterruptedException {
        new GitRepositorySetup(primaryJob()).prepare();

        git(working, "checkout", "-q", "-b", "y", "origin/master");
        git(working, "branch", "--set-upstream-to=origin/master", "y");

        Assert.assertEquals("branch y must track origin/master for this scenario",
                "origin/master",
                git(working, "rev-parse", "--abbrev-ref", "y@{upstream}").trim());

        assertBarePushTargetsOwnBranch(working, bare, "y");
    }

    /**
     * A dependent repo prepared by {@link GitRepositorySetup} receives the same
     * push-target safety as the primary working tree. After
     * {@link GitRepositorySetup#prepareDependentReposBranches()}, the dependent
     * clone carries {@code push.default=current} and
     * {@code branch.autoSetupMerge=false}, and a branch that already tracks
     * {@code origin/master} there still bare-pushes to its own name — a
     * regression at the dependent-repo configuration call would otherwise leave
     * dependent-repo pushes unprotected.
     */
    @Test(timeout = 60000)
    public void preparedDependentRepoPushesToCurrentBranchNotMaster()
            throws IOException, InterruptedException {
        Path[] dep = seededOrigin("dep");
        Path depBare = dep[0];
        Path depClone = dep[1];

        CodingAgentJob job = primaryJob();
        job.setDependentRepos(Collections.singletonList("https://example.com/dep.git"));

        GitRepositorySetup setup = new GitRepositorySetup(job);
        setup.prepareDependentRepos();
        assertEquals(Collections.singletonList(depClone.toString()), setup.getDependentRepoPaths());
        setup.prepareDependentReposBranches();

        assertEquals("current", git(depClone, "config", "--local", "push.default").trim());
        assertEquals("false", git(depClone, "config", "--local", "branch.autoSetupMerge").trim());

        git(depClone, "checkout", "-q", "-b", "y", "origin/master");
        git(depClone, "branch", "--set-upstream-to=origin/master", "y");
        Assert.assertEquals("branch y must track origin/master for this scenario",
                "origin/master",
                git(depClone, "rev-parse", "--abbrev-ref", "y@{upstream}").trim());

        assertBarePushTargetsOwnBranch(depClone, depBare, "y");
    }

    /**
     * Asserts that a bare {@code git push} from {@code clone}, whose current
     * branch is {@code branch}, creates {@code origin/<branch>} on
     * {@code bareOrigin} while leaving {@code master}'s commit untouched.
     *
     * @param clone      the working clone whose current branch is pushed
     * @param bareOrigin the bare origin the clone pushes to
     * @param branch     the name of the current branch and expected remote branch
     */
    private void assertBarePushTargetsOwnBranch(Path clone, Path bareOrigin, String branch)
            throws IOException, InterruptedException {
        String masterBefore = git(bareOrigin, "rev-parse", "refs/heads/master").trim();

        git(clone, "push", "-q");

        assertTrue("the push must create origin/" + branch,
                run(bareOrigin, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).succeeded());
        Assert.assertEquals("master's upstream must be untouched by the push",
                masterBefore, git(bareOrigin, "rev-parse", "refs/heads/master").trim());
    }

    /** A job preparing the primary working clone on {@code master}. */
    private CodingAgentJob primaryJob() {
        CodingAgentJob job = new CodingAgentJob("ops", "pin push defaults");
        job.setWorkingDirectory(working.toString());
        job.setTargetBranch("master");
        job.setGitUserName(JOB_NAME);
        job.setGitUserEmail(JOB_EMAIL);
        return job;
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
