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
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins how a {@link GitManagedJob} runs git and other subprocesses against
 * real repositories: git sub-commands carry the job's identity, non-git
 * commands do not, both run in the job's working directory, and every
 * repository the job touches — the primary one and each dependent one — is
 * driven under that same identity.
 */
public class GitManagedJobGitOperationsTest extends TestSuiteBase {

    /** Identity configured in each test repository's local git config. */
    private static final String REPO_NAME = "Repo Local";

    /** Email configured in each test repository's local git config. */
    private static final String REPO_EMAIL = "repo-local@example.com";

    /** Identity the job is configured to author its git work under. */
    private static final String JOB_NAME = "Job Identity";

    /** Email the job is configured to author its git work under. */
    private static final String JOB_EMAIL = "job-identity@example.com";

    /** Temporary parent directory holding every repository for one test. */
    private Path root;

    /** The primary repository, used as the job's working directory. */
    private Path repo;

    /** Creates a primary repository with one seed commit on {@code feature/x}. */
    @Before
    public void setUp() throws IOException, InterruptedException {
        root = Files.createTempDirectory("git-managed-job-ops");
        repo = initRepository("primary");
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

    /** A git sub-command runs under the job's identity, overriding the repository's own. */
    @Test(timeout = 60000)
    public void gitCommandsCarryTheJobIdentity() throws IOException, InterruptedException {
        CodingAgentJob job = job();

        assertEquals(0, job.executeGit("commit", "-q", "--allow-empty", "-m", "by job"));
        assertEquals(JOB_NAME + "|" + JOB_EMAIL + "|" + JOB_NAME + "|" + JOB_EMAIL,
                job.executeGitWithOutput("log", "-1", "--format=%an|%ae|%cn|%ce").trim());
    }

    /** Without a configured job identity, git falls back to the repository's own. */
    @Test(timeout = 60000)
    public void gitCommandsWithoutJobIdentityUseTheRepository() throws IOException, InterruptedException {
        CodingAgentJob job = new CodingAgentJob("ops", "pin git execution");
        job.setWorkingDirectory(repo.toString());

        assertEquals(0, job.executeGit("commit", "-q", "--allow-empty", "-m", "no identity"));
        assertEquals(REPO_NAME + "|" + REPO_EMAIL,
                job.executeGitWithOutput("log", "-1", "--format=%an|%ae").trim());
    }

    /** A failing git sub-command reports its exit code, and its error text is captured as output. */
    @Test(timeout = 60000)
    public void failingGitCommandsReportExitCodeAndErrorOutput() throws IOException, InterruptedException {
        CodingAgentJob job = job();

        assertNotEquals(0, job.executeGit("rev-parse", "--verify", "--quiet", "refs/heads/missing"));
        String output = job.executeGitWithOutput("rev-parse", "--verify", "refs/heads/missing");
        assertTrue(output, output.contains("fatal"));
    }

    /** The current branch is read from the job's working directory. */
    @Test(timeout = 60000)
    public void getCurrentBranchReadsTheWorkingDirectory() throws IOException, InterruptedException {
        CodingAgentJob job = job();

        assertEquals("feature/x", job.getCurrentBranch());
    }

    /** A branch is reported as existing only when it is present in the working directory. */
    @Test(timeout = 60000)
    public void branchExistsReflectsLocalBranches() throws IOException, InterruptedException {
        CodingAgentJob job = job();

        assertTrue(job.branchExists("feature/x"));
        assertFalse(job.branchExists("no/such/branch"));
    }

    /**
     * A non-git command runs in the job's working directory but without the
     * job's identity: a commit made through it is authored by the repository's
     * own configuration.
     */
    @Test(timeout = 60000)
    public void nonGitCommandsRunInTheWorkingDirectoryWithoutJobIdentity()
            throws IOException, InterruptedException {
        CodingAgentJob job = job();
        String git = GitOperations.resolveGitCommand();

        assertEquals(repo.toRealPath().toString(),
                Path.of(job.executeCommandWithOutput(git, "rev-parse", "--show-toplevel").trim())
                        .toRealPath().toString());

        job.executeCommandWithOutput(git, "commit", "-q", "--allow-empty", "-m", "by command");
        assertEquals(REPO_NAME + "|" + REPO_EMAIL,
                job.executeGitWithOutput("log", "-1", "--format=%an|%ae").trim());
    }

    /**
     * Preparing a dependent repository's branch stashes its uncommitted work
     * under the job's identity, exactly as the primary repository is stashed.
     * The dependent repository used to be driven without the job's identity,
     * so its stash was authored by whatever git configuration the host
     * happened to carry — and on a host with none, the stash failed and the
     * fallback discarded the work instead.
     */
    @Test(timeout = 60000)
    public void dependentRepositoryIsStashedUnderTheJobIdentity() throws IOException, InterruptedException {
        Path dependent = initRepository("dependent");
        Files.writeString(dependent.resolve("README.md"), "uncommitted\n", StandardCharsets.UTF_8);

        CodingAgentJob job = job();
        job.setDependentRepos(Collections.singletonList("https://example.com/dependent.git"));

        GitRepositorySetup setup = new GitRepositorySetup(job);
        setup.prepareDependentRepos();
        assertEquals(Collections.singletonList(dependent.toString()), setup.getDependentRepoPaths());
        setup.prepareDependentReposBranches();

        assertEquals(JOB_NAME + "|" + JOB_EMAIL,
                git(dependent, "log", "-1", "--format=%an|%ae", "refs/stash").trim());
    }

    /**
     * Creates a job working in {@link #repo} on {@code feature/x} with the
     * {@link #JOB_NAME} identity.
     *
     * @return the configured job
     */
    private CodingAgentJob job() {
        CodingAgentJob job = new CodingAgentJob("ops", "pin git execution");
        job.setWorkingDirectory(repo.toString());
        job.setTargetBranch("feature/x");
        job.setGitUserName(JOB_NAME);
        job.setGitUserEmail(JOB_EMAIL);
        return job;
    }

    /**
     * Initializes a repository under {@link #root} with the {@link #REPO_NAME}
     * identity in its local config, a committed {@code README.md}, and
     * {@code feature/x} checked out.
     *
     * @param name the directory name of the repository
     * @return the repository path
     */
    private Path initRepository(String name) throws IOException, InterruptedException {
        Path dir = Files.createDirectory(root.resolve(name));
        git(dir, "init", "-q");
        git(dir, "config", "user.name", REPO_NAME);
        git(dir, "config", "user.email", REPO_EMAIL);
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
        git(dir, "add", "README.md");
        git(dir, "commit", "-q", "-m", "seed");
        git(dir, "checkout", "-q", "-b", "feature/x");
        return dir;
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
