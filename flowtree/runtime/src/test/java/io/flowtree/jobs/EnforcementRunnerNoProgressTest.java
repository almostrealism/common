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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

/**
 * Tests that {@link EnforcementRunner} stops spending correction sessions on a
 * rule the agent is not moving toward resolution.
 *
 * <p>The scenario this guards against: a rule the agent cannot satisfy (a
 * guardrail rejecting content the agent did not write) was retried until the
 * job-wide caps stopped it, more than a dozen sessions on one branch, each of
 * which changed nothing. Every test here runs in a real git working tree,
 * because "changed nothing" is judged from the tree itself.</p>
 */
public class EnforcementRunnerNoProgressTest extends TestSuiteBase {

    /** Name of the rule under test. */
    private static final String RULE = "never-satisfied";

    /** Temporary git working directory, recreated for each test. */
    private Path repo;

    /** Initializes a git repository with one committed file before each test. */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("enforce-no-progress");
        git("init", "--quiet");
        git("config", "user.email", "enforce-test@example.com");
        git("config", "user.name", "Enforce Test");
        git("config", "commit.gpgsign", "false");
        write("work.txt", "0\n");
        git("add", "work.txt");
        git("commit", "--quiet", "-m", "initial");
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
     * Correction sessions that change nothing stop after
     * {@link EnforcementRunner#MAX_NO_PROGRESS_ATTEMPTS}, well short of the
     * rule's own cap, and the rule is not re-entered on later passes.
     */
    @Test(timeout = 60000)
    public void ruleIsRetiredAfterAttemptsThatChangeNothing() throws Exception {
        List<String> sessions = runWith(List.of(alwaysViolated(RULE, 5)), activity -> { });

        assertEquals(EnforcementRunner.MAX_NO_PROGRESS_ATTEMPTS, sessions.size());
    }

    /**
     * Sessions that keep changing the tree are progress, even when the rule is
     * never satisfied, so no-progress detection never cuts them short: the
     * rule re-enters pass after pass until the job-wide attempt cap stops it,
     * exactly as before.
     */
    @Test(timeout = 60000)
    public void attemptsThatChangeTheTreeAreNotCutShort() throws Exception {
        int[] edits = {0};
        List<String> sessions = runWith(List.of(alwaysViolated(RULE, 5)),
                activity -> write("work.txt", (++edits[0]) + "\n"));

        assertEquals(CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS, sessions.size());
    }

    /**
     * Rewriting session output, harness artifacts or {@code commit.txt} is not
     * progress toward a rule that does not read them.
     */
    @Test(timeout = 60000)
    public void scratchSpaceWritesAreNotProgress() throws Exception {
        int[] edits = {0};
        List<String> sessions = runWith(List.of(alwaysViolated(RULE, 5)), activity -> {
            edits[0]++;
            write(".flowtree/claude-output/session-" + edits[0] + ".json", "{}\n");
            write("commit.txt", "Attempt " + edits[0] + "\n");
        });

        assertEquals(EnforcementRunner.MAX_NO_PROGRESS_ATTEMPTS, sessions.size());
    }

    /**
     * Writing a file the rule names as a {@linkplain EnforcementRule#getProgressPaths()
     * progress path} is progress even when that file is scratch space, so a
     * rule resolved by writing {@code commit.txt} is not cut short while the
     * agent keeps rewriting it: like any rule making progress, it runs until
     * the job-wide attempt cap stops it.
     */
    @Test(timeout = 60000)
    public void writingAProgressPathIsProgress() throws Exception {
        int[] edits = {0};
        EnforcementRule rule = new EnforcementRule() {
            @Override public String getName() { return RULE; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
            @Override public int getMaxRetries() { return 5; }
            @Override public Set<String> getProgressPaths() { return Set.of("commit.txt"); }
        };
        List<String> sessions = runWith(List.of(rule),
                activity -> write("commit.txt", "Attempt " + (++edits[0]) + "\n"));

        assertEquals(CodingAgentJob.DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS,
                sessions.stream().filter(RULE::equals).count());
    }

    /** The commit-message rule is resolved by writing {@code commit.txt}, so it names it. */
    @Test(timeout = 60000)
    public void commitMessageRuleCountsCommitTxtAsProgress() {
        assertEquals(Set.of("commit.txt"), new CommitMessageRule().getProgressPaths());
    }

    /**
     * A retired rule comes back once something else changes the tree, since the
     * change may have made it satisfiable, and is retired again when it still
     * makes no progress.
     */
    @Test(timeout = 60000)
    public void retiredRuleReentersAfterAnotherRuleChangesTheTree() throws Exception {
        EnforcementRule editsOnce = EnforcementRule.singleFire("edits-once", "edit the file");
        List<String> sessions = runWith(List.of(alwaysViolated(RULE, 5), editsOnce), activity -> {
            if ("edits-once".equals(activity)) write("work.txt", "edited\n");
        });

        long stalledSessions = sessions.stream().filter(RULE::equals).count();
        assertEquals(2L * EnforcementRunner.MAX_NO_PROGRESS_ATTEMPTS, stalledSessions);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** An action a stub correction session performs on the working tree. */
    private interface SessionAction {
        /**
         * Performs the session's work.
         *
         * @param activity the rule the session is correcting
         * @throws IOException if the working tree cannot be written
         */
        void run(String activity) throws IOException;
    }

    /**
     * Runs the enforcement rules of a job whose correction sessions perform
     * {@code action}, and returns the rule name of every session in order.
     */
    private List<String> runWith(List<EnforcementRule> rules, SessionAction action) {
        List<String> sessions = new ArrayList<>();
        CodingAgentJob job = new CodingAgentJob("no-progress", "Make the rule pass") {
            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                sessions.add(activity);
                try {
                    action.run(activity);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        job.setWorkingDirectory(repo.toString());
        job.setEnforceOrganizationalPlacement(false);
        rules.forEach(job::addEnforcementRule);
        job.runEnforcementRules();
        return sessions;
    }

    /** A rule that is never satisfied and allows {@code maxRetries} attempts per pass. */
    private static EnforcementRule alwaysViolated(String name, int maxRetries) {
        return new EnforcementRule() {
            @Override public String getName() { return name; }
            @Override public boolean isViolated(CodingAgentJob job) { return true; }
            @Override public String buildCorrectionPrompt(CodingAgentJob job) { return "fix it"; }
            @Override public int getMaxRetries() { return maxRetries; }
        };
    }

    /** Writes {@code content} to {@code relativePath} under the repo, creating parents. */
    private void write(String relativePath, String content) throws IOException {
        Path p = repo.resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    /** Runs git in the repo, failing if it exits non-zero. */
    private void git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
