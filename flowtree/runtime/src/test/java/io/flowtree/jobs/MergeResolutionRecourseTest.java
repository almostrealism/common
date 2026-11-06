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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks, against a real repository and the production
 * {@link GitCommitHandler}, how a merge of the base branch is committed when
 * the agent's resolution of a path both sides changed was rejected or could
 * not be staged.
 *
 * <p>The merge leaves three conflicts for the agent: a source file the target
 * branch modified and the base branch deleted, a CI workflow, and a protected
 * test resource, each changed on both sides.</p>
 */
public class MergeResolutionRecourseTest extends TestSuiteBase {

    /** A source file the target branch modifies and the base branch deletes. */
    private static final String DELETED = "src/main/java/AssignmentSupport.java";

    /** A CI workflow both branches change. */
    private static final String CI_FILE = ".github/workflows/ci.yaml";

    /** A protected test resource both branches change. */
    private static final String TEST_RESOURCE = "src/test/resources/data.txt";

    /** A {@link GitManagedJob} whose merge parent and operator grants the test supplies. */
    private static final class MergeJob extends GitManagedJob {
        /** The merge parent this job reports. */
        private final String mergeParent;

        /** The operator grants this job carries. */
        private final OperatorGrants grants;

        /**
         * Creates a job reporting {@code mergeParent} and carrying {@code grants}.
         *
         * @param mergeParent the recorded merge parent, or {@code null}
         * @param grants      the job's operator grants
         */
        MergeJob(String mergeParent, OperatorGrants grants) {
            super("merge-recourse-test");
            this.mergeParent = mergeParent;
            this.grants = grants;
        }

        @Override
        String getHarnessMergeParent() {
            return mergeParent;
        }

        @Override
        OperatorGrants operatorGrants() {
            return grants;
        }

        @Override
        protected void doWork() {
            // The test prepares the working tree directly.
        }

        @Override
        public String getTaskString() {
            return getTaskId();
        }
    }

    /** The repository, recreated for each test. */
    private GitTestRepository repo;

    /** The base-branch commit merged into {@code feature/test}. */
    private String mergeParent;

    /** Builds the three-conflict merge described in the class documentation and leaves it in progress. */
    @Before
    public void setUp() throws Exception {
        repo = GitTestRepository.create("merge-recourse", false);
        repo.write(DELETED, "class AssignmentSupport { }\n");
        repo.write(CI_FILE, "name: v1\n");
        repo.write(TEST_RESOURCE, "data v1\n");
        repo.commitAll("initial");

        repo.git("checkout", "--quiet", "-b", "feature/test");
        repo.write(DELETED, "class AssignmentSupport { int branch; }\n");
        repo.write(CI_FILE, "name: branch\n");
        repo.write(TEST_RESOURCE, "data branch\n");
        repo.commitAll("branch changes");

        repo.git("checkout", "--quiet", "master");
        repo.git("rm", "--quiet", DELETED);
        repo.write(CI_FILE, "name: v2\n");
        repo.write(TEST_RESOURCE, "data v2\n");
        mergeParent = repo.commitAll("master changes");
        repo.git("update-ref", "refs/remotes/origin/master", mergeParent);

        repo.git("checkout", "--quiet", "feature/test");
        repo.run("merge", "--no-edit", "master");
        assertTrue("the merge must be left in progress", repo.hasRef("MERGE_HEAD"));
    }

    /** Deletes the repository. */
    @After
    public void tearDown() throws IOException {
        if (repo != null) repo.close();
    }

    /**
     * The incident: the agent resolves the modify/delete conflict with
     * {@code git rm}, taking the base branch's deletion, and resolves the
     * others to the base side. Every resolution equals the base branch's
     * version, so the merge commits cleanly and nothing is reported skipped.
     */
    @Test(timeout = 60000)
    public void modifyDeleteResolvedToBaseDeletionCommitsCleanly() throws Exception {
        repo.git("rm", "--quiet", DELETED);
        takeBase(CI_FILE, "name: v2\n");
        takeBase(TEST_RESOURCE, "data v2\n");

        GitCommitHandler handler = commit(newJob(mergeParent, new OperatorGrants()));

        assertEquals("the merge must be committed", 2, repo.parents("HEAD").size());
        assertEquals(mergeParent, repo.parents("HEAD").get(1));
        assertNull("the deletion must be committed", repo.show("HEAD", DELETED));
        assertEquals("name: v2\n", repo.show("HEAD", CI_FILE));
        assertTrue("nothing was rejected: " + handler.getSkippedFiles(), handler.getSkippedFiles().isEmpty());
        assertTrue("no merge may be left in progress", !repo.hasRef("MERGE_HEAD"));
    }

    /**
     * Putting the CI file back to the target branch's version reverts the
     * base branch's change, which the CI lock rejects. The commit is refused,
     * and the failure names the lock and says the resolution reverts the
     * base branch.
     */
    @Test(timeout = 60000)
    public void resolutionRevertingTheBaseChangeIsRefusedWithItsReason() throws Exception {
        repo.git("rm", "--quiet", DELETED);
        takeBase(TEST_RESOURCE, "data v2\n");
        takeBase(CI_FILE, "name: branch\n");
        String before = repo.git("rev-parse", "HEAD").trim();

        String message = refusal(newJob(mergeParent, new OperatorGrants()));

        assertTrue(message, message.contains(CI_FILE + " (protected - CI/workflow file)"));
        assertTrue(message, message.contains("reverts the base branch's change"));
        assertTrue(message, message.contains("acceptedMergeResolutions"));
        assertEquals(before, repo.git("rev-parse", "HEAD").trim());
    }

    /**
     * Under the test lock, a new resolution of a protected test resource is
     * refused, and the failure names the protection that rejected it.
     */
    @Test(timeout = 60000)
    public void resolutionRejectedByTheTestLockIsRefusedNamingTheProtection() throws Exception {
        repo.git("rm", "--quiet", DELETED);
        takeBase(CI_FILE, "name: v2\n");
        takeBase(TEST_RESOURCE, "data agent\n");
        MergeJob job = newJob(mergeParent, new OperatorGrants());
        job.setProtectTestFiles(true);

        String message = refusal(job);

        assertTrue(message, message.contains(TEST_RESOURCE + " (protected - exists on base branch)"));
        assertTrue(message, message.contains("matches neither side"));
        assertTrue(message, !message.contains(DELETED));
    }

    /**
     * Once the owner accepts the agent's resolution of the path, signed, the
     * same merge commits with the agent's content and records the acceptance
     * in the commit message.
     */
    @Test(timeout = 60000)
    public void ownerAcceptedResolutionIsCommittedAndRecorded() throws Exception {
        repo.git("rm", "--quiet", DELETED);
        takeBase(CI_FILE, "name: v2\n");
        takeBase(TEST_RESOURCE, "data agent\n");
        OperatorGrants grants = new OperatorGrants();
        grants.setAcceptedMergeResolutions(List.of(TEST_RESOURCE), "signature");
        MergeJob job = newJob(mergeParent, grants);
        job.setProtectTestFiles(true);

        commit(job);

        assertEquals("data agent\n", repo.show("HEAD", TEST_RESOURCE));
        assertEquals(2, repo.parents("HEAD").size());
        String body = repo.git("log", "-1", "--format=%B");
        assertTrue(body, body.contains(OperatorGrants.RESOLUTION_TRAILER_KEY + ": "
                + job.getTaskId() + "=signature; paths=" + TEST_RESOURCE));
    }

    /** An acceptance nothing signed accepts nothing: the merge is still refused. */
    @Test(timeout = 60000)
    public void unsignedAcceptanceIsRefused() throws Exception {
        repo.git("rm", "--quiet", DELETED);
        takeBase(CI_FILE, "name: v2\n");
        takeBase(TEST_RESOURCE, "data agent\n");
        OperatorGrants grants = new OperatorGrants();
        grants.setAcceptedMergeResolutions(List.of(TEST_RESOURCE), null);
        MergeJob job = newJob(mergeParent, grants);
        job.setProtectTestFiles(true);

        String message = refusal(job);

        assertTrue(message, message.contains(TEST_RESOURCE));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes {@code content} to {@code path} and stages it, as an agent resolving a conflict would. */
    private void takeBase(String path, String content) throws IOException, InterruptedException {
        repo.write(path, content);
        repo.git("add", path);
    }

    /** Builds a job bound to the repository, with pushing disabled. */
    private MergeJob newJob(String parent, OperatorGrants grants) {
        MergeJob job = new MergeJob(parent, grants);
        job.setWorkingDirectory(repo.dir().toString());
        job.setTargetBranch("feature/test");
        job.setBaseBranch("master");
        job.setCreateBranchIfMissing(false);
        job.setPushToOrigin(false);
        job.setGitUserName(GitTestRepository.USER);
        job.setGitUserEmail(GitTestRepository.EMAIL);
        return job;
    }

    /** Runs the production commit handler for {@code job}. */
    private GitCommitHandler commit(MergeJob job) throws IOException, InterruptedException {
        GitCommitHandler handler = new GitCommitHandler(job);
        handler.handle(true);
        return handler;
    }

    /** Runs the commit handler, expecting it to refuse, and returns the refusal. */
    private String refusal(MergeJob job) throws IOException, InterruptedException {
        try {
            commit(job);
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        fail("the merge must not be committed");
        return null;
    }
}
