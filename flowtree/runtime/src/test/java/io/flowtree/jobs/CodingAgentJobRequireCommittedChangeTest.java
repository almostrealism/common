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
import static org.junit.Assert.assertTrue;

/**
 * Proves, against a real git repository and the production
 * {@link CodingAgentJobEvent#forJob} classifier, that a coding-agent job whose
 * contract is to produce a committed change
 * ({@link CodingAgentJob#isRequireCommittedChange()}) reports
 * {@link JobCompletionEvent.Status#DEGRADED} — not SUCCESS — when it exits
 * cleanly with nothing committed.
 *
 * <p>This is RETRO-20261019 finding F1: the QA retrospective round surveyed,
 * recorded memories, and exited SUCCESS while writing no report to disk, so the
 * round meant to close the loop on every other round left nothing a person
 * could review. The flag has the harness surface that non-delivery.</p>
 */
public class CodingAgentJobRequireCommittedChangeTest extends TestSuiteBase {

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /** Initializes a git repository with one committed file on a feature branch. */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("require-committed-change-test");
        git("init", "--quiet");
        git("config", "user.email", "require-commit-test@example.com");
        git("config", "user.name", "Require Commit Test");
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
     * The contract requires a committed change; a clean exit with nothing
     * committed is DEGRADED, and the message names the branch that got nothing.
     */
    @Test(timeout = 60000)
    public void cleanExitWithNothingCommittedIsDegraded() {
        CodingAgentJob job = newJob();
        job.setRequireCommittedChange(true);

        CodingAgentJobEvent event = job.createEvent(null);
        assertEquals(JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertTrue("message must name the branch: " + event.getErrorMessage(),
                event.getErrorMessage().contains("feature/test"));
    }

    /**
     * Without the flag, the same clean no-commit exit is the legitimate no-op
     * case and stays SUCCESS — the behaviour every other round keeps.
     */
    @Test(timeout = 60000)
    public void cleanExitStaysSuccessWithoutTheFlag() {
        CodingAgentJob job = newJob();

        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }

    /**
     * Unpublished work (edits left in the tree with no commit behind them) is a
     * stronger signal than mere non-delivery: it stays FAILED even with the flag
     * on, because the flag's DEGRADED case is reached only after that check.
     */
    @Test(timeout = 60000)
    public void unpublishedWorkStillFailsEvenWithTheFlag() throws Exception {
        write("src/main/java/Foo.java", "public class Foo { int x; }\n");

        CodingAgentJob job = newJob();
        job.setRequireCommittedChange(true);

        assertEquals(JobCompletionEvent.Status.FAILED, job.createEvent(null).getStatus());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a coding-agent job pointed at the temporary repository and its
     * feature branch.
     *
     * @return the configured job
     */
    private CodingAgentJob newJob() {
        CodingAgentJob job = new CodingAgentJob("require-commit-test", "survey and report");
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
