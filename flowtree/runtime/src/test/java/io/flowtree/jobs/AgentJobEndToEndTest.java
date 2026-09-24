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

import io.flowtree.jobs.agent.AgentRunnerRegistry;
import io.flowtree.jobs.agent.ClaudeCodeRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs a coding agent job the whole way through and asks the only question
 * that matters operationally: did the work reach the remote?
 *
 * <p>Everything above the agent process is real — the runner's command line,
 * its {@code stream-json} parsing, the MCP init-event handling, the job's
 * phase dispatch, the staging guardrails, the commit, and the push to a real
 * bare repository standing in for origin. Only the model is replaced, by
 * {@code tools/ci/agent-e2e/fake-agent.sh}, which emits a real transcript and
 * makes real edits.</p>
 *
 * <p>That seam is chosen deliberately. Unit tests here stub the
 * {@link io.flowtree.jobs.agent.AgentRunner} itself, which means the runner's
 * own behaviour — what it does with an init event reporting a server as not
 * connected — is invisible to them. A production outage came from exactly
 * that gap: every unit test passed while no job could publish anything. These
 * tests assert on the commit in the remote, so a job that fails to publish
 * fails the build no matter which layer dropped it.</p>
 *
 * @author Michael Murray
 */
public class AgentJobEndToEndTest extends TestSuiteBase {

    /** Runner name the fake agent is registered under. */
    private static final String FAKE_RUNNER = "e2e-fake-agent";

    /** Bare repository standing in for origin. */
    private Path origin;

    /** Working clone the job operates in. */
    private Path workdir;

    /** Scenario environment handed to the fake agent process. */
    private Map<String, String> agentEnv;

    /** Creates the origin and a clone, and registers the fake runner. */
    @Before
    public void setUp() throws Exception {
        origin = Files.createTempDirectory("e2e-origin");
        workdir = Files.createTempDirectory("e2e-workdir");
        agentEnv = new HashMap<>();

        git(origin, "init", "--bare", "--quiet", "--initial-branch=master");

        Path seed = Files.createTempDirectory("e2e-seed");
        git(seed, "init", "--quiet", "--initial-branch=master");
        git(seed, "config", "user.email", "e2e@example.com");
        git(seed, "config", "user.name", "E2E");
        git(seed, "config", "commit.gpgsign", "false");
        Files.writeString(seed.resolve("README.md"), "seed\n", StandardCharsets.UTF_8);
        git(seed, "add", "README.md");
        git(seed, "commit", "--quiet", "-m", "seed");
        git(seed, "remote", "add", "origin", origin.toString());
        git(seed, "push", "--quiet", "origin", "master");
        deleteTree(seed);

        git(workdir.getParent(), "clone", "--quiet", origin.toString(), workdir.getFileName().toString());
        git(workdir, "config", "user.email", "e2e@example.com");
        git(workdir, "config", "user.name", "E2E");
        git(workdir, "config", "commit.gpgsign", "false");
        git(workdir, "checkout", "--quiet", "-b", "feature/e2e");

        String fakeAgent = Paths.get("").toAbsolutePath()
                .resolve("../../tools/ci/agent-e2e/fake-agent.sh").normalize().toString();
        AgentRunnerRegistry.register(FAKE_RUNNER, () -> new ClaudeCodeRunner(fakeAgent));
    }

    /** Removes both repositories. */
    @After
    public void tearDown() throws IOException {
        deleteTree(origin);
        deleteTree(workdir);
    }

    /**
     * The baseline every other scenario is measured against: an agent that
     * edits a file and writes a commit message produces a commit on the
     * remote. If this fails, no job in the fleet can publish anything.
     */
    @Test(timeout = 120000)
    public void agentWorkReachesTheRemote() throws Exception {
        agentEnv.put("AR_FAKE_AGENT_WRITE", "src/Added.java=class Added {}");
        agentEnv.put("AR_FAKE_AGENT_COMMIT_MSG", "Add the Added class");

        CodingAgentJob job = newJob();
        job.run();

        assertTrue("the job must report a commit: " + job.getCommitHash(),
                job.getCommitHash() != null && !job.getCommitHash().isEmpty());
        assertTrue("the agent's file must be in the remote",
                remoteContains("feature/e2e", "src/Added.java"));
        assertEquals("Add the Added class", remoteCommitSubject("feature/e2e"));
    }

    /**
     * The outage this suite exists for. ar-manager reports as not connected
     * at session start; the agent still does its work. Whether that work is
     * kept is a policy decision, but it must be a decision someone made
     * on purpose, not one nobody can see until the fleet stops.
     *
     * <p>Pinned to the current policy: the session is not failed for it, so
     * the work publishes. Flipping that policy must break this test.</p>
     */
    @Test(timeout = 120000)
    public void workPublishesWhenArManagerDidNotConnect() throws Exception {
        agentEnv.put("AR_FAKE_AGENT_MCP", "ar-manager=failed");
        agentEnv.put("AR_FAKE_AGENT_WRITE", "src/Added.java=class Added {}");
        agentEnv.put("AR_FAKE_AGENT_COMMIT_MSG", "Work done without ar-manager");

        CodingAgentJob job = newJob();
        job.run();

        assertTrue("a session that lost ar-manager must still publish its work,"
                        + " or the whole fleet stops the moment ar-manager blips",
                remoteContains("feature/e2e", "src/Added.java"));
    }

    /**
     * A session that deliberately changes nothing publishes nothing and is
     * still a success — the review job that finds no defect.
     */
    @Test(timeout = 120000)
    public void deliberateNoOpPublishesNothingAndSucceeds() throws Exception {
        CodingAgentJob job = newJob();
        job.run();

        assertTrue(job.getCommitHash() == null || job.getCommitHash().isEmpty());
        assertEquals(JobCompletionEvent.Status.SUCCESS, job.createEvent(null).getStatus());
    }

    /**
     * An agent that edits files and writes no commit message still publishes.
     *
     * <p>Not writing {@code commit.txt} is the most ordinary thing an agent
     * can get wrong, and the harness covers for it with a fallback message.
     * If that ever stops working the failure is near-silent: the session does
     * its work, produces no message, and the edits stay in a working tree on
     * an agent host where nobody looks.</p>
     *
     * <p>This asserts the outcome rather than a disjunction of the possible
     * outcomes. An earlier version accepted "published <em>or</em> reported",
     * which is satisfied however the system behaves and would have gone on
     * passing if the behaviour flipped — the same shape as a test that cannot
     * fail. The observed behaviour is that the work publishes and the job
     * succeeds, so that is what is pinned here.</p>
     */
    @Test(timeout = 120000)
    public void editsWithoutACommitMessageStillReachTheRemote() throws Exception {
        agentEnv.put("AR_FAKE_AGENT_WRITE", "src/Added.java=class Added {}");

        CodingAgentJob job = newJob();
        job.run();

        JobCompletionEvent event = job.createEvent(null);
        assertTrue("an agent that edited a file but wrote no commit message must still"
                + " publish — the fallback message exists so that forgetting commit.txt"
                + " costs a worse subject line, not the work; status was "
                + event.getStatus() + ": " + event.getErrorMessage(),
                remoteContains("feature/e2e", "src/Added.java"));
        assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
        assertTrue("the job must report the commit it published",
                job.getCommitHash() != null && !job.getCommitHash().isEmpty());
    }

    /**
     * Work destroyed by the tampering revert is reported as destroyed.
     *
     * <p>An agent that makes its own git commit trips the tampering
     * detector, and the revert that follows discards what that commit held.
     * This is the one path in the job where the harness itself is what
     * destroys the session's output, which makes silence about it worse than
     * anywhere else: there is no working tree left holding the work, no
     * commit, and nothing for a later session to recover.</p>
     *
     * <p>Pinned to {@code FAILED} specifically rather than "not SUCCESS".
     * The difference between {@code FAILED} and {@code DEGRADED} is the
     * difference between "this job lost your work" and "this job finished
     * with a caveat", and a fleet dashboard is read at that granularity.</p>
     */
    @Test(timeout = 180000)
    public void workDestroyedByTheTamperingRevertIsReported() throws Exception {
        agentEnv.put("AR_FAKE_AGENT_WRITE", "src/Added.java=class Added {}");
        agentEnv.put("AR_FAKE_AGENT_COMMIT_MSG", "Add the Added class");
        agentEnv.put("AR_FAKE_AGENT_GIT_COMMIT", "true");

        CodingAgentJob job = newJob();
        job.run();

        JobCompletionEvent event = job.createEvent(null);
        assertFalse("the reverted work must not appear in the remote",
                remoteContains("feature/e2e", "src/Added.java"));
        assertTrue("destroyed work is a failure, not a caveat; got " + event.getStatus(),
                event.getStatus() == JobCompletionEvent.Status.FAILED);
        assertTrue("the reason must say the work was reverted as tampering, so the"
                + " report distinguishes destroyed work from work never done; got: "
                + event.getErrorMessage(),
                event.getErrorMessage() != null
                        && event.getErrorMessage().contains("tampering"));
    }

    /**
     * A session that tampers once and behaves on the restart publishes.
     *
     * <p>The companion to
     * {@link #workDestroyedByTheTamperingRevertIsReported()}. That one pins
     * what happens when nothing replaces the reverted work; this one pins the
     * recovery the restart exists to provide. Covering only the first would
     * leave the harness free to stop restarting altogether — every assertion
     * would still hold, because a job that never recovers is exactly what
     * that test describes.</p>
     *
     * <p>The agent's invocation count is asserted, not just the outcome. A
     * scenario that silently stopped tampering would publish and succeed
     * without any restart having happened, and would satisfy every other
     * assertion here while testing nothing about the restart path.</p>
     */
    @Test(timeout = 180000)
    public void aRestartAfterTamperingRepublishesTheWork() throws Exception {
        Path state = Files.createTempFile("e2e-invocations", ".txt");
        agentEnv.put("AR_FAKE_AGENT_STATE", state.toString());
        agentEnv.put("AR_FAKE_AGENT_WRITE", "src/Added.java=class Added {}");
        agentEnv.put("AR_FAKE_AGENT_COMMIT_MSG", "Add the Added class");
        agentEnv.put("AR_FAKE_AGENT_GIT_COMMIT", "1");

        CodingAgentJob job = newJob();
        job.run();

        JobCompletionEvent event = job.createEvent(null);
        String invocations = Files.readString(state).trim();
        assertTrue("the agent must have been run twice — once tampering, once"
                        + " recovering; one invocation means the restart never happened"
                        + " and this scenario proved nothing. Invocations: " + invocations,
                "2".equals(invocations));
        assertTrue("the restart's work must reach the remote; status was "
                        + event.getStatus() + ": " + event.getErrorMessage(),
                remoteContains("feature/e2e", "src/Added.java"));
        assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
        assertTrue("the job must report the commit the restart produced",
                job.getCommitHash() != null && !job.getCommitHash().isEmpty());
    }

    /**
     * A session that wrote a commit message and published nothing is not a
     * success.
     *
     * <p>Authoring a commit message is a session stating that it produced
     * something worth committing. If nothing then reaches the remote, the two
     * halves disagree, and the disagreement is the only externally visible
     * trace of work that was made and dropped on the way out.</p>
     *
     * <p>The distinction against {@link #deliberateNoOpPublishesNothingAndSucceeds()}
     * is the whole point: both sessions publish nothing, and the commit
     * message is what separates "found nothing to do" from "did something
     * that vanished". A suite that only checked the no-op case would report
     * both as correct.</p>
     *
     * <p>The reason is asserted, not just the status. A test satisfied by any
     * non-success would pass while the job failed for a reason having nothing
     * to do with the commit message, and would then go on passing after the
     * rule it is named for stopped working.</p>
     *
     * <p>What currently produces that reason is worth knowing, because it is
     * not the code written for it. {@code commit.txt} is an excluded pattern,
     * so writing it puts an entry in the skipped list, which is what makes
     * {@code hasAllChangesDropped()} true — and that branch is reached first.
     * The {@code messageAuthored} term in
     * {@link JobWorkOutcome#describeUnpublishedWork()} is therefore never the
     * deciding factor for a coding-agent job. The rule holds; the explanation
     * it reports points at staging guardrails rather than at the commit
     * message. Asserting on {@code commit.txt} rather than on the wording of
     * either branch keeps this test pinned to the behaviour instead of to
     * whichever branch currently supplies it.</p>
     */
    @Test(timeout = 120000)
    public void aCommitMessageWithNothingToPublishIsNotASuccess() throws Exception {
        agentEnv.put("AR_FAKE_AGENT_COMMIT_MSG", "Fix the thing that was broken");

        CodingAgentJob job = newJob();
        job.run();

        assertFalse("nothing should have been published",
                remoteContains("feature/e2e", "src/Added.java"));

        JobCompletionEvent event = job.createEvent(null);
        assertTrue("a session that authored a commit message and published nothing"
                + " must not report SUCCESS; that combination is how work that was"
                + " produced and then dropped leaves no trace at all",
                event.getStatus() != JobCompletionEvent.Status.SUCCESS);
        assertTrue("the reported reason must name commit.txt, so the result is tied"
                + " to the commit message rather than to any incidental failure; got "
                + event.getStatus() + ": " + event.getErrorMessage(),
                event.getErrorMessage() != null
                        && event.getErrorMessage().contains("commit.txt"));
    }

    /**
     * A change the test-integrity gate rejects must not reach the remote, and
     * the job must not report a clean success for it.
     *
     * <p>This is the second shape of the silent-drop failure and the reason
     * the gate exists at all: an agent weakens a test that exists on the base
     * branch, and the run has to end in a way somebody can see. Publishing it
     * is the security failure; publishing nothing while reporting
     * {@code SUCCESS} is the reporting failure, and the two are only
     * distinguishable from outside if both are checked.</p>
     *
     * <p>The real {@code tools/ci/agent-protection} scripts are copied into
     * the fixture repository rather than being simulated, because the gate
     * under test is the script, not a stand-in for it.</p>
     *
     * <p>What counts as "published" here is the weakened form specifically —
     * the file present but missing its second assertion. Checking only that
     * the path exists on the remote would be satisfied by the base-branch
     * version that was already there, and would pass whether or not the gate
     * did anything.</p>
     */
    @Test(timeout = 180000)
    public void rejectedChangesAreReportedNotSilentlyDropped() throws Exception {
        seedTestProtectionFixture();

        Path weakened = Files.createTempFile("e2e-weakened", ".java");
        Files.writeString(weakened, EXAMPLE_TEST_WEAKENED, StandardCharsets.UTF_8);

        agentEnv.put("AR_FAKE_AGENT_COPY", EXAMPLE_TEST_PATH + "=" + weakened);
        agentEnv.put("AR_FAKE_AGENT_COMMIT_MSG", "Tidy up the example test");

        CodingAgentJob job = newJob();
        job.setProtectTestFiles(true);
        job.run();

        boolean weakenedPublished = remoteBranchExists("feature/e2e")
                && remoteContains("feature/e2e", EXAMPLE_TEST_PATH)
                && !remoteFileContains("feature/e2e", EXAMPLE_TEST_PATH, "2 + 2");
        assertFalse("a test weakened on the base branch must never reach the remote;"
                + " if this fails the integrity gate is not gating anything",
                weakenedPublished);

        assertTrue("a rejected change set must be reported, not dropped in silence;"
                + " a SUCCESS with nothing published is indistinguishable from work"
                + " that was never done",
                job.createEvent(null).getStatus() != JobCompletionEvent.Status.SUCCESS);
    }

    // ── Harness ───────────────────────────────────────────────────────────

    /** Repository-relative path of the fixture test guarded by the gate. */
    private static final String EXAMPLE_TEST_PATH = "src/test/java/ExampleTest.java";

    /** The fixture test as it exists on the base branch: two assertions. */
    private static final String EXAMPLE_TEST_BASE =
            "public class ExampleTest {\n"
            + "    @Test\n"
            + "    public void valuesMatch() {\n"
            + "        assertEquals(2, 1 + 1);\n"
            + "        assertEquals(4, 2 + 2);\n"
            + "    }\n"
            + "}\n";

    /** The same test with an assertion removed — a net assertion loss. */
    private static final String EXAMPLE_TEST_WEAKENED =
            "public class ExampleTest {\n"
            + "    @Test\n"
            + "    public void valuesMatch() {\n"
            + "        assertEquals(2, 1 + 1);\n"
            + "    }\n"
            + "}\n";

    /**
     * Adds the real agent-protection scripts and a guarded test to the base
     * branch, then rebases the working clone onto it.
     *
     * <p>Done here rather than in {@link #setUp()} so the other scenarios keep
     * a minimal repository: the gate is skipped outright when the script is
     * absent, and a fixture that silently enabled it everywhere would change
     * what those tests mean.</p>
     */
    private void seedTestProtectionFixture() throws Exception {
        Path seed = Files.createTempDirectory("e2e-protect-seed");
        git(seed.getParent(), "clone", "--quiet", origin.toString(), seed.getFileName().toString());
        git(seed, "config", "user.email", "e2e@example.com");
        git(seed, "config", "user.name", "E2E");
        git(seed, "config", "commit.gpgsign", "false");

        Path protection = Paths.get("").toAbsolutePath()
                .resolve("../../tools/ci/agent-protection").normalize();
        Path destination = seed.resolve("tools/ci/agent-protection");
        Files.createDirectories(destination);
        try (Stream<Path> entries = Files.list(protection)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry)) {
                    Path target = destination.resolve(entry.getFileName().toString());
                    Files.copy(entry, target);
                    target.toFile().setExecutable(true);
                }
            }
        }

        Path example = seed.resolve(EXAMPLE_TEST_PATH);
        Files.createDirectories(example.getParent());
        Files.writeString(example, EXAMPLE_TEST_BASE, StandardCharsets.UTF_8);

        git(seed, "add", "-A");
        git(seed, "commit", "--quiet", "-m", "Add the integrity gate and a guarded test");
        git(seed, "push", "--quiet", "origin", "master");
        deleteTree(seed);

        git(workdir, "fetch", "--quiet", "origin");
        git(workdir, "checkout", "--quiet", "-B", "feature/e2e", "origin/master");
    }

    /**
     * Returns whether {@code branch} exists in the origin.
     *
     * @param branch the branch to look for
     * @return {@code true} when the origin carries the branch
     */
    private boolean remoteBranchExists(String branch) throws Exception {
        return runGit(origin, "rev-parse", "--verify", "--quiet", branch) == 0;
    }

    /**
     * Returns whether {@code path} on {@code branch} in the origin contains
     * {@code needle}.
     *
     * @param branch the branch to inspect
     * @param path   the repository-relative path
     * @param needle the text to look for
     * @return {@code true} when the remote file carries the text
     */
    private boolean remoteFileContains(String branch, String path, String needle) throws Exception {
        return gitOutput(origin, "show", branch + ":" + path).contains(needle);
    }

    /**
     * Builds a job wired to the fake agent and the temporary origin.
     *
     * <p>ar-manager is configured with an unreachable URL on purpose.
     * {@code McpConfigBuilder.requiredServerNames()} names it only when a URL
     * and token are both set, so a job without them cannot reach the
     * init-event policy at all and the MCP scenario below would pass for the
     * wrong reason. Nothing connects to the URL — the fake agent decides what
     * the init event reports.</p>
     *
     * @return the configured job
     */
    private CodingAgentJob newJob() {
        CodingAgentJob job = new CodingAgentJob("e2e-task", "do the work");
        job.setWorkingDirectory(workdir.toString());
        job.setTargetBranch("feature/e2e");
        job.setBaseBranch("master");
        job.setPushToOrigin(true);
        job.setRunnerName(FAKE_RUNNER);
        job.setAgentEnv(agentEnv);
        job.setArManagerUrl("http://ar-manager.invalid:8010");
        job.setArManagerToken("armt_tmp_e2etoken");
        job.setGitUserName("E2E");
        job.setGitUserEmail("e2e@example.com");
        // Correction phases would each launch the fake agent again; this suite
        // is about the publish path, and the phases have their own coverage.
        job.setReviewEnabled(false);
        job.setRetrospectiveEnabled(false);
        job.setFalsificationEnabled(false);
        job.setEnforceOrganizationalPlacement(false);
        return job;
    }

    /**
     * Returns whether {@code path} exists on {@code branch} in the origin.
     *
     * @param branch the branch to inspect
     * @param path   the repository-relative path
     * @return {@code true} when the remote branch carries the file
     */
    private boolean remoteContains(String branch, String path) throws Exception {
        String listing = gitOutput(origin, "ls-tree", "-r", "--name-only", branch);
        for (String line : listing.split("\n")) {
            if (line.trim().equals(path)) return true;
        }
        return false;
    }

    /**
     * Returns the subject line of {@code branch}'s tip commit in the origin.
     *
     * @param branch the branch to inspect
     * @return the commit subject
     */
    private String remoteCommitSubject(String branch) throws Exception {
        return gitOutput(origin, "log", "-1", "--format=%s", branch).trim();
    }

    /**
     * Runs git in {@code dir}, failing the test on a non-zero exit.
     *
     * @param dir  the working directory
     * @param args the git arguments
     */
    private void git(Path dir, String... args) throws Exception {
        if (runGit(dir, args) != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed in " + dir);
        }
    }

    /**
     * Runs git in {@code dir} and returns its combined output.
     *
     * @param dir  the working directory
     * @param args the git arguments
     * @return the captured output
     */
    private String gitOutput(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(dir.toString()));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }

    /**
     * Runs git in {@code dir}, returning the exit code.
     *
     * @param dir  the working directory
     * @param args the git arguments
     * @return the process exit code
     */
    private int runGit(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(dir.toString()));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    /**
     * Recursively deletes {@code root} if it exists.
     *
     * @param root the directory to remove
     */
    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
        }
    }
}
