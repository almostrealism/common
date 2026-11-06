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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs whole {@link GitManagedJob}s against a real working copy and a local
 * bare {@code origin}, and checks what {@link WorkingTreeRescue} leaves on the
 * remote when a job fails: the job's full working tree on
 * {@code rescue/<jobId>}, named in the failure message, for a job that failed
 * after its work changed the tree, and nothing for one that did not.
 *
 * <p>{@code master} and {@code feature/test} both change a CI workflow, so
 * each job's run starts with that merge left in progress, as the harness
 * leaves it for an agent.</p>
 */
public class WorkingTreeRescueTest extends TestSuiteBase {

    /** A CI workflow both branches change. */
    private static final String CI_FILE = ".github/workflows/ci.yaml";

    /** The branch every job works on. */
    private static final String TARGET = "feature/test";

    /** What a job's work does to the working tree after the harness prepared it. */
    @FunctionalInterface
    private interface Work {
        /**
         * Changes the working tree as an agent would.
         *
         * @param repo the job's working copy
         * @throws Exception to fail the job's work
         */
        void perform(GitTestRepository repo) throws Exception;
    }

    /** A job whose work the test supplies, and which records the status it reports. */
    private static class ScriptedJob extends GitManagedJob {
        /** The job's working copy. */
        private final GitTestRepository repo;

        /** What the job's work does. */
        private final Work work;

        /** Every status event the job posted. */
        private final List<JsonNode> events = new ArrayList<>();

        /**
         * Creates a job that performs {@code work} on {@code repo}.
         *
         * @param taskId the job id
         * @param repo   the working copy
         * @param work   the work
         */
        ScriptedJob(String taskId, GitTestRepository repo, Work work) {
            super(taskId);
            this.repo = repo;
            this.work = work;
        }

        @Override
        protected void doWork() {
            try {
                work.perform(repo);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String getTaskString() {
            return getTaskId();
        }

        @Override
        protected void postJson(String url, String json) {
            try {
                JsonNode node = new ObjectMapper().readTree(json);
                if (node.has("status")) events.add(node);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Returns the job's completion event.
         *
         * @return the last status event posted
         */
        JsonNode completion() {
            if (events.isEmpty()) throw new AssertionError("the job must report its completion");
            return events.get(events.size() - 1);
        }
    }

    /** The stand-in for the remote. */
    private GitTestRepository origin;

    /** The job's working copy. */
    private GitTestRepository work;

    /** The base-branch commit each job's run merges. */
    private String masterHead;

    /** Builds the remote and a working copy on {@code feature/test} behind a conflicting {@code master}. */
    @Before
    public void setUp() throws Exception {
        origin = GitTestRepository.create("rescue-origin", true);
        work = GitTestRepository.create("rescue-work", false);
        work.git("remote", "add", "origin", origin.dir().toString());
        work.write(CI_FILE, "name: v1\n");
        work.write("shared.txt", "base\n");
        work.commitAll("initial");
        work.git("push", "--quiet", "origin", "master");

        work.git("checkout", "--quiet", "-b", TARGET);
        work.write(CI_FILE, "name: branch\n");
        work.commitAll("branch CI change");
        work.git("push", "--quiet", "origin", TARGET);

        work.git("checkout", "--quiet", "master");
        work.write(CI_FILE, "name: v2\n");
        masterHead = work.commitAll("master CI change");
        work.git("push", "--quiet", "origin", "master");
        work.git("checkout", "--quiet", TARGET);
    }

    /** Deletes both repositories. */
    @After
    public void tearDown() throws IOException {
        if (work != null) work.close();
        if (origin != null) origin.close();
    }

    /**
     * A merge whose resolution the commit refuses fails the job, and the
     * agent's whole resolution, including its other work, reaches
     * {@code rescue/<jobId>} as a merge commit that accepting is one push
     * away from. The failure message names the branch.
     */
    @Test(timeout = 120000)
    public void failedMergeJobPushesItsResolutionToARescueBranch() throws Exception {
        ScriptedJob job = run("job-failed-merge", repo -> {
            repo.write(CI_FILE, "name: branch\n");
            repo.git("add", CI_FILE);
            repo.write("notes.txt", "the agent's other work\n");
        });

        JsonNode event = job.completion();
        assertEquals("FAILED", event.get("status").asText());
        String rescue = WorkingTreeRescue.BRANCH_PREFIX + "job-failed-merge";
        assertEquals(rescue, event.get("rescueRef").asText());
        String message = event.get("errorMessage").asText();
        assertTrue(message, message.contains(CI_FILE + " (protected - CI/workflow file)"));
        assertTrue(message, message.contains(rescue));

        assertTrue("the rescue branch must be pushed", origin.hasRef("refs/heads/" + rescue));
        List<String> parents = origin.parents(rescue);
        assertEquals("a rescued merge is a merge commit", 2, parents.size());
        assertEquals(origin.git("rev-parse", TARGET).trim(), parents.get(0));
        assertEquals(masterHead, parents.get(1));
        assertEquals("the agent's other work\n", origin.show(rescue, "notes.txt"));
        assertTrue("the job must not have published to its branch",
                parents.get(0).equals(origin.git("rev-parse", TARGET).trim()));
    }

    /**
     * A job that throws after its work changed the tree has the tree pushed
     * to {@code rescue/<jobId>}, without the content no pushed branch may
     * carry: an edited workflow stays at the target branch's version, and an
     * excluded file is left out, each named in the commit message.
     */
    @Test(timeout = 120000)
    public void jobThatFailsAfterChangingTheTreePushesARescueBranch() throws Exception {
        ScriptedJob job = run("job-throws", repo -> {
            repo.write(CI_FILE, "name: v2\n");
            repo.git("add", CI_FILE);
            repo.write("work.txt", "finished work\n");
            repo.write("tools/ci/new.sh", "echo agent\n");
            repo.write(".env", "TOKEN=secret\n");
            throw new IllegalStateException("harness failure after the work was done");
        });

        JsonNode event = job.completion();
        assertEquals("FAILED", event.get("status").asText());
        String rescue = WorkingTreeRescue.BRANCH_PREFIX + "job-throws";
        assertTrue(event.get("errorMessage").asText(), event.get("errorMessage").asText().contains(rescue));

        assertEquals("finished work\n", origin.show(rescue, "work.txt"));
        assertNull("an excluded file must never be pushed", origin.show(rescue, ".env"));
        assertNull("an agent-written CI script must never be pushed", origin.show(rescue, "tools/ci/new.sh"));
        String body = origin.git("log", "-1", "--format=%B", rescue);
        assertTrue(body, body.contains("harness failure after the work was done"));
        assertTrue(body, body.contains("withheld tools/ci/new.sh"));
        assertTrue(body, body.contains("withheld .env"));
    }

    /**
     * A job that fails before its work starts leaves no rescue branch and
     * mentions none, even though the harness had already left a merge in
     * progress.
     */
    @Test(timeout = 120000)
    public void failureBeforeAnyChangeCreatesNothing() throws Exception {
        ScriptedJob job = new ScriptedJob("job-early", work, repo -> { }) {
            @Override
            protected void prepareEnvironment() {
                throw new IllegalStateException("environment unavailable");
            }
        };
        configure(job).run();

        assertNoRescue(job, "job-early");
    }

    /** A job whose work changed nothing leaves no rescue branch when it fails. */
    @Test(timeout = 120000)
    public void failedJobWhoseWorkChangedNothingCreatesNothing() throws Exception {
        ScriptedJob job = run("job-no-change", repo -> {
            throw new IllegalStateException("failed without touching the tree");
        });

        assertNoRescue(job, "job-no-change");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Runs a job performing {@code steps} and returns it. */
    private ScriptedJob run(String taskId, Work steps) {
        ScriptedJob job = configure(new ScriptedJob(taskId, work, steps));
        job.run();
        return job;
    }

    /** Points {@code job} at the working copy and a status URL whose posts it records. */
    private ScriptedJob configure(ScriptedJob job) {
        job.setWorkingDirectory(work.dir().toString());
        job.setTargetBranch(TARGET);
        job.setBaseBranch("master");
        job.setPushToOrigin(true);
        job.setGitUserName(GitTestRepository.USER);
        job.setGitUserEmail(GitTestRepository.EMAIL);
        job.setWorkstreamUrl("http://127.0.0.1:9/api/workstreams/ws-rescue/jobs/" + job.getTaskId());
        return job;
    }

    /** Asserts the job failed, named no rescue, and pushed no rescue branch. */
    private void assertNoRescue(ScriptedJob job, String taskId) throws IOException, InterruptedException {
        JsonNode event = job.completion();
        assertEquals("FAILED", event.get("status").asText());
        assertTrue("no rescue may be named: " + event, event.get("rescueRef").isNull());
        assertNotNull(event.get("errorMessage"));
        assertFalse(event.get("errorMessage").asText(), event.get("errorMessage").asText().contains("rescue/"));
        assertFalse(origin.hasRef("refs/heads/" + WorkingTreeRescue.BRANCH_PREFIX + taskId));
        assertFalse(work.hasRef("refs/rescue/" + taskId));
    }
}
