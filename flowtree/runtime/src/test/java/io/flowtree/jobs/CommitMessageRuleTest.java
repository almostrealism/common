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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link CommitMessageRule} checks every repository the job
 * changed — the primary working directory and each dependent checkout — and is
 * violated when any of them holds uncommitted changes without a valid
 * {@code commit.txt} at its own root.
 */
public class CommitMessageRuleTest extends TestSuiteBase {

    /** The primary working directory for the job under test. */
    private Path primaryDir;

    /** A dependent repository checked out beside the primary one. */
    private Path depDir;

    /** Creates the primary and dependent repository directories. */
    @Before
    public void setUp() throws IOException {
        primaryDir = Files.createTempDirectory("commit-rule-primary");
        depDir = Files.createTempDirectory("commit-rule-dep");
    }

    /** Deletes the temporary directories. */
    @After
    public void tearDown() throws IOException {
        deleteRecursively(primaryDir);
        deleteRecursively(depDir);
    }

    /** A dependent repo that changed but has no commit.txt is a violation. */
    @Test(timeout = 30000)
    public void dependentChangedWithoutMessageIsViolated() {
        CodingAgentJob job = job(Set.of(depDir.toString()));
        CommitMessageRule rule = new CommitMessageRule();
        assertTrue("a changed dependent repo without a message is a violation", rule.isViolated(job));
        assertTrue("the correction prompt must name the dependent repo",
                rule.buildCorrectionPrompt(job).contains(depDir.toString()));
    }

    /** A dependent repo that changed and has its own commit.txt is not a violation. */
    @Test(timeout = 30000)
    public void dependentChangedWithMessageIsNotViolated() throws IOException {
        Files.write(depDir.resolve("commit.txt"),
                "Move the helper into lib".getBytes(StandardCharsets.UTF_8));
        CodingAgentJob job = job(Set.of(depDir.toString()));
        assertFalse(new CommitMessageRule().isViolated(job));
    }

    /** The primary repo changing without a message is a violation naming the primary. */
    @Test(timeout = 30000)
    public void primaryChangedWithoutMessageIsViolated() {
        CodingAgentJob job = job(Set.of(primaryDir.toString()));
        CommitMessageRule rule = new CommitMessageRule();
        assertTrue(rule.isViolated(job));
        assertTrue("the correction prompt must mark the primary repository",
                rule.buildCorrectionPrompt(job).contains("the primary repository"));
    }

    /** A repository the agent left untouched owes no message, even without a commit.txt. */
    @Test(timeout = 30000)
    public void untouchedReposAreNotViolated() {
        CodingAgentJob job = job(Collections.emptySet());
        assertFalse(new CommitMessageRule().isViolated(job));
    }

    /** A commit.txt that is a verbatim copy of the task prompt is a violation. */
    @Test(timeout = 30000)
    public void primaryCommitTxtCopyingThePromptIsViolated() throws IOException {
        Files.write(primaryDir.resolve("commit.txt"),
                "hunt for a defect".getBytes(StandardCharsets.UTF_8));
        CodingAgentJob job = job(Set.of(primaryDir.toString()));
        assertTrue(new CommitMessageRule().isViolated(job));
    }

    /**
     * Recovering the primary repository's missing message tags the job's commit
     * message source {@link CommitMessageRule#SOURCE_RECOVERED}.
     */
    @Test(timeout = 30000)
    public void recoveringThePrimaryMessageTagsRecovered() throws IOException {
        CodingAgentJob job = job(Set.of(primaryDir.toString()));
        CommitMessageRule rule = new CommitMessageRule();
        rule.buildCorrectionPrompt(job);
        Files.write(primaryDir.resolve("commit.txt"),
                "Fix the inverted guard".getBytes(StandardCharsets.UTF_8));
        rule.onCorrectionAttempted(job);
        Assert.assertEquals(CommitMessageRule.SOURCE_RECOVERED, job.getCommitMessageSource());
    }

    /**
     * Recovering only a dependent checkout's message, while the primary already
     * carries its own, must NOT tag the primary source as recovered: the source
     * describes the primary message, which the agent authored in its own session.
     */
    @Test(timeout = 30000)
    public void recoveringOnlyADependentMessageDoesNotTagThePrimaryRecovered() throws IOException {
        Files.write(primaryDir.resolve("commit.txt"),
                "Primary work the agent authored".getBytes(StandardCharsets.UTF_8));
        CodingAgentJob job = job(Set.of(primaryDir.toString(), depDir.toString()));
        CommitMessageRule rule = new CommitMessageRule();
        assertTrue("the dependent repo still owes a message", rule.isViolated(job));
        rule.buildCorrectionPrompt(job);
        Files.write(depDir.resolve("commit.txt"),
                "Move the helper into lib".getBytes(StandardCharsets.UTF_8));
        rule.onCorrectionAttempted(job);
        assertFalse("the job is fully satisfied after the dependent recovers", rule.isViolated(job));
        Assert.assertNull("a dependent-only recovery must not be tagged as a primary recovery",
                job.getCommitMessageSource());
    }

    /**
     * Builds a job rooted at {@code primaryDir} with {@code depDir} as a
     * dependent repo, reporting the given roots as holding uncommitted changes.
     *
     * @param changedRoots the repository roots to report as changed
     * @return the configured job
     */
    private CodingAgentJob job(Set<String> changedRoots) {
        CodingAgentJob job = new CodingAgentJob("t1", "hunt for a defect") {
            @Override
            public List<String> getDependentRepoPaths() {
                return List.of(depDir.toString());
            }
        };
        job.setWorkingDirectory(primaryDir.toString());
        job.setWorkOutcome(new StubOutcome(job, changedRoots));
        return job;
    }

    /** A work outcome that reports a fixed set of repository roots as changed. */
    private static final class StubOutcome extends JobWorkOutcome {
        /** The repository roots reported as holding uncommitted changes. */
        private final Set<String> changedRoots;

        /**
         * Creates a stub outcome.
         *
         * @param job          the job the outcome reports on
         * @param changedRoots the roots to report as changed
         */
        private StubOutcome(GitManagedJob job, Set<String> changedRoots) {
            super(job);
            this.changedRoots = changedRoots;
        }

        @Override
        boolean hasUncommittedChanges(String repoPath) {
            return changedRoots.contains(repoPath);
        }
    }

    /** Recursively deletes {@code dir} and all its contents. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
        }
    }
}
