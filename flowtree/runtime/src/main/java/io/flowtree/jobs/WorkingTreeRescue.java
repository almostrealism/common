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
import io.flowtree.git.MergeSides;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The last copy of a failed job's work: when a {@link GitManagedJob} ends in
 * failure after its work changed the working tree, the full tree is committed
 * and pushed to a {@code rescue/<jobId>} branch, so the work survives the job
 * and whatever the next job does to the checkout.
 *
 * <h2>What is preserved</h2>
 * <p>The rescue commit is the working tree as the job left it, built in a
 * temporary index so the job's own index and working tree are untouched. Its
 * first parent is {@code HEAD}; while a merge is in progress its second parent
 * is {@code MERGE_HEAD}, so a rescued merge resolution is itself a merge
 * commit, and accepting it is one fast-forward push of the rescue commit to
 * the target branch. The commit message is the agent's own (from
 * {@link GitManagedJob#getCommitMessage()}) followed by a note of the failure
 * and of every finding below.</p>
 *
 * <h2>What is withheld, and why rescue does not skip the protections</h2>
 * <p>A rescue branch is for a person to retrieve, not for merging unseen, so
 * a finding that would block a normal commit is attached to the rescue commit
 * as a note instead of blocking it: a test file the job's test lock rejects is
 * included and named. Content whose publication is itself the harm is never
 * pushed, whatever the job's configuration, and is left at the version a
 * clean merge would give it (see {@link MergeSides#cleanSource}), or at
 * {@code HEAD}:</p>
 * <ul>
 *   <li>CI configuration under the CI-file lock: a pushed branch runs the
 *       workflows its own tree defines, so a workflow the agent wrote would
 *       execute with the repository's secrets the moment the branch
 *       arrived;</li>
 *   <li>files matching the job's excluded patterns (credentials, keys, build
 *       output) and harness artifacts under {@link FlowtreeArtifacts#DIRECTORY};</li>
 *   <li>oversized and binary files.</li>
 * </ul>
 *
 * <h2>When it runs</h2>
 * <p>Only for a job whose work changed something: the working-tree fingerprint
 * after the job differs from the one {@link #recordPreWorkState()} took
 * before {@link GitManagedJob#doWork()}, or {@code HEAD} moved because the job
 * committed locally and then failed to publish that commit. A job that fails
 * before its work starts, or whose work changed nothing, creates nothing. The one excluded
 * failure class is a tampering revert ({@link GitManagedJob#hasRevertedAgentWork()}):
 * the harness destroyed that work deliberately, and rescuing it would undo the
 * remedy. A job that does not push ({@link GitManagedJob#isPushToOrigin()}
 * off) or whose push fails gets a patch file beside its working directory
 * instead; in every case the commit is also kept locally under
 * {@code refs/rescue/<jobId>}.</p>
 *
 * @author Michael Murray
 * @see JobWorkOutcome
 */
class WorkingTreeRescue implements ConsoleFeatures {

    /** Prefix of the branch a rescued job's work is pushed to. */
    static final String BRANCH_PREFIX = "rescue/";

    /** Directory, beside the working directory, that holds rescue patches. */
    static final String PATCH_DIRECTORY = "flowtree-rescue";

    /** The job whose work this preserves. */
    private final GitManagedJob job;

    /** Whether {@link #recordPreWorkState()} ran, meaning the job's work started. */
    private boolean workStarted;

    /** The working-tree fingerprint before the work, or {@code null} if it could not be taken. */
    private String preWorkFingerprint;

    /** The commit {@code HEAD} named before the work, or {@code null} if it could not be read. */
    private String preWorkHead;

    /** Where the work was preserved, or {@code null} when nothing was rescued. */
    private String location;

    /** How a person retrieves and accepts the rescued work, or {@code null}. */
    private String instructions;

    /**
     * Creates the rescue for {@code job}.
     *
     * @param job the job whose work this preserves
     */
    WorkingTreeRescue(GitManagedJob job) {
        this.job = job;
    }

    /**
     * Records the working tree's state before the job's work starts, so that
     * {@link #preserve(String)} can tell work the job produced from state the
     * harness set up (an in-progress merge, for instance).
     */
    void recordPreWorkState() {
        workStarted = true;
        try {
            preWorkFingerprint = GitOperations.fingerprintUncommittedState(job.getWorkingDirectory());
        } catch (IOException e) {
            warn("Could not fingerprint the working tree before the job's work: " + e.getMessage());
            preWorkFingerprint = null;
        }
        preWorkHead = currentHead();
    }

    /**
     * Returns the commit {@code HEAD} names in the job's working tree, or
     * {@code null} when it cannot be read (an unborn branch, an unreadable
     * repository, an interrupted query).
     *
     * @return the {@code HEAD} commit hash, or {@code null}
     */
    private String currentHead() {
        try {
            GitOperations.Result head = job.gitOperations(job.getWorkingDirectory())
                    .executeForResult("rev-parse", "--verify", "HEAD");
            return head.succeeded() ? head.output().trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Returns where the work was preserved.
     *
     * @return the rescue branch or patch path, or {@code null} when nothing was rescued
     */
    String getLocation() {
        return location;
    }

    /**
     * Adds the rescue location and instructions to a failure event; does
     * nothing when nothing was rescued.
     *
     * @param event the job's completion event
     */
    void describe(JobCompletionEvent event) {
        if (location != null) {
            event.withRescue(location, instructions);
        }
    }

    /**
     * Preserves the job's work, if it produced any; see the class
     * documentation for when that is. Never throws: a rescue that cannot be
     * made is logged, and the job's own failure is what gets reported.
     *
     * @param failure a description of how the job failed, for the commit message
     */
    void preserve(String failure) {
        try {
            if (shouldRescue()) {
                rescue(failure);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warn("Rescue of the working tree was interrupted");
        } catch (IOException | RuntimeException e) {
            warn("Could not rescue the working tree: " + e.getMessage(), e);
        }
    }

    /**
     * Returns whether this job's work is eligible for rescue: it publishes to
     * a branch, its work started and changed the working tree, and the
     * harness did not revert that work as tampering.
     *
     * @return {@code true} when the working tree should be rescued
     * @throws IOException if the working tree cannot be read
     */
    private boolean shouldRescue() throws IOException {
        if (!workStarted || job.getTargetBranch() == null || job.getTargetBranch().isEmpty()
                || job.getWorkingDirectory() == null || job.getTaskId() == null
                || !job.performsGitOperations() || job.isDryRun()) {
            return false;
        }
        if (job.hasRevertedAgentWork()) {
            log("Not rescuing: the job's changes were reverted as git tampering");
            return false;
        }
        String now = GitOperations.fingerprintUncommittedState(job.getWorkingDirectory());
        if (preWorkFingerprint == null || !preWorkFingerprint.equals(now)) {
            return true;
        }
        // A moved HEAD is an unpublished local commit; see the class javadoc.
        String head = currentHead();
        return preWorkHead != null && head != null && !preWorkHead.equals(head);
    }

    /**
     * Builds the rescue commit, keeps it under {@code refs/rescue/<jobId>},
     * and publishes it: pushed when the job pushes, otherwise (or when the
     * push fails) as a patch file.
     *
     * @param failure how the job failed
     * @throws IOException if git cannot be run or a file cannot be written
     * @throws InterruptedException if a git command is interrupted
     */
    private void rescue(String failure) throws IOException, InterruptedException {
        String workDir = job.getWorkingDirectory();
        GitOperations git = job.gitOperations(workDir);
        String head = git.executeForResult("rev-parse", "--verify", "HEAD").requireSuccess().output().trim();
        GitOperations.Result merging = git.executeForResult("rev-parse", "--verify", "--quiet", "MERGE_HEAD");
        String mergeHead = merging.succeeded() ? merging.output().trim() : null;

        Path index = Files.createTempFile("flowtree-rescue", ".index");
        Path message = Files.createTempFile("flowtree-rescue", ".msg");
        try {
            Files.delete(index);
            GitOperations staging = job.gitOperations(workDir);
            staging.setEnvironmentVariable("GIT_INDEX_FILE", index.toAbsolutePath().toString());
            staging.executeForResult("read-tree", "HEAD").requireSuccess();

            List<String> notes = new ArrayList<>();
            stageWorkingTree(staging, mergeHead, notes);

            String tree = staging.executeForResult("write-tree").requireSuccess().output().trim();
            Files.writeString(message, commitMessage(failure, mergeHead, notes), StandardCharsets.UTF_8);
            List<String> commitArgs = new ArrayList<>(List.of("commit-tree", tree, "-p", head));
            if (mergeHead != null) {
                commitArgs.addAll(List.of("-p", mergeHead));
            }
            commitArgs.addAll(List.of("-F", message.toAbsolutePath().toString()));
            String commit = git.executeForResult(commitArgs.toArray(new String[0]))
                    .requireSuccess().output().trim();
            git.executeForResult("update-ref", "refs/rescue/" + job.getTaskId(), commit);
            publish(git, commit, mergeHead != null);
        } finally {
            Files.deleteIfExists(index);
            Files.deleteIfExists(message);
        }
    }

    /**
     * Stages, in the rescue index, every working-tree change that may be
     * published, and leaves every withheld path at the version a clean merge
     * would give it (or at {@code HEAD}), recording a note for each withheld
     * path and for each path the job's own guardrails would reject.
     *
     * @param staging   git operations bound to the rescue index
     * @param mergeHead the commit being merged, or {@code null} outside a merge
     * @param notes     receives one note per finding
     * @throws IOException if git cannot be run
     * @throws InterruptedException if a git command is interrupted
     */
    private void stageWorkingTree(GitOperations staging, String mergeHead, List<String> notes)
            throws IOException, InterruptedException {
        List<String> changed = new ArrayList<>();
        for (String path : GitOperations.requireChangedFiles(job.getWorkingDirectory())) {
            if (!GitOperations.isExcludedPath(path)) changed.add(path);
        }
        File workDir = new File(job.getWorkingDirectory());
        FileStager stager = new FileStager();
        StagingResult publishable = stager.evaluateFiles(changed, publicationConfig(), workDir,
                job.asGitOperations());
        StagingResult guarded = stager.evaluateFiles(changed,
                FileStagingConfig.forJob(job), workDir, job.asGitOperations());

        Set<String> included = new LinkedHashSet<>();
        for (String path : publishable.getStagedFiles()) {
            if (FlowtreeArtifacts.isExcludedFromCommit(path, FlowtreeArtifacts.COMMIT_WHITELIST)) {
                notes.add("withheld " + path + " (flowtree artifact)");
            } else {
                included.add(path);
            }
        }
        for (String entry : publishable.getSkippedFiles()) {
            notes.add("withheld " + entry);
        }
        for (String entry : guarded.getSkippedFiles()) {
            if (included.stream().anyMatch(path -> entry.startsWith(path + " ("))) {
                notes.add("included, though the job's guardrails reject it: " + entry);
            }
        }

        Set<String> withheld = new LinkedHashSet<>(changed);
        withheld.removeAll(included);
        for (String path : included) {
            staging.stage(path).requireSuccess();
        }
        if (mergeHead != null && !withheld.isEmpty()) {
            restoreWithheld(staging, mergeHead, withheld, notes);
        }
    }

    /**
     * Puts each withheld path, in a merge rescue, at the version a clean merge
     * would give it: the merged commit's version for a path only it changed.
     * The rescue index already holds {@code HEAD}'s version of everything
     * else; a path both sides changed keeps it, with a note, since the agent's
     * resolution of it is what was withheld.
     *
     * @param staging   git operations bound to the rescue index
     * @param mergeHead the commit being merged
     * @param withheld  the withheld paths
     * @param notes     receives a note for each path left at {@code HEAD}
     * @throws IOException if git cannot be run
     * @throws InterruptedException if a git command is interrupted
     */
    private void restoreWithheld(GitOperations staging, String mergeHead, Set<String> withheld,
                                 List<String> notes) throws IOException, InterruptedException {
        boolean trusted = FileStager.trustedMergeParent(
                FileStagingConfig.forJob(job), job.asGitOperations()) != null;
        MergeSides sides = MergeSides.of(job.asGitOperations(), mergeHead, trusted);
        for (String path : withheld) {
            String source = sides.cleanSource(path);
            if (mergeHead.equals(source)) {
                staging.executeForResult("reset", "-q", mergeHead, "--", path).requireSuccess();
            } else if (source == null) {
                notes.add(path + " is at " + job.getTargetBranch() + "'s version: both sides changed"
                        + " it and the agent's resolution was withheld, so review it before accepting");
            }
        }
    }

    /**
     * Returns the guardrails that decide what a rescue may publish: the job's
     * excluded patterns, size limit and merge parent, the CI-file lock always
     * on, and the test lock off (a test-lock finding is a note, not a reason
     * to withhold).
     *
     * @return the publication guardrails
     */
    private FileStagingConfig publicationConfig() {
        return FileStagingConfig.builder()
                .excludedPatterns(job.getAllExcludedPatterns())
                .protectCiFiles(true)
                .protectTestFiles(false)
                .baseBranch(job.getBaseBranch())
                .maxFileSizeBytes(job.getMaxFileSizeBytes())
                .mergeParent(job.getHarnessMergeParent())
                .build();
    }

    /**
     * Builds the rescue commit's message: the agent's own message, when it
     * can be read, followed by the failure and the findings.
     *
     * @param failure   how the job failed
     * @param mergeHead the commit being merged, or {@code null}
     * @param notes     the findings
     * @return the commit message
     */
    private String commitMessage(String failure, String mergeHead, List<String> notes) {
        String agentMessage;
        try {
            agentMessage = OperatorGrants.stripGrantTrailers(job.getCommitMessage()).trim();
        } catch (RuntimeException e) {
            agentMessage = "";
        }
        StringBuilder text = new StringBuilder(agentMessage.isEmpty()
                ? "Work rescued from failed job " + job.getTaskId() : agentMessage);
        text.append("\n\nRescued by the harness after job ").append(job.getTaskId())
                .append(" failed").append(failure == null ? "." : ": " + failure.lines().findFirst().orElse(""))
                .append("\nThis is the job's whole working tree");
        if (mergeHead != null) {
            text.append(", including its unfinished merge of ")
                    .append(TestMethodProtection.shortSha(mergeHead));
        }
        text.append(". Nothing reviewed or committed it.");
        for (String note : notes) {
            text.append("\n- ").append(note);
        }
        return text.append('\n').toString();
    }

    /**
     * Publishes the rescue commit: pushes it to {@code rescue/<jobId>} when
     * the job pushes, and otherwise, or when the push fails, writes it as a
     * patch beside the working directory.
     *
     * <p>The patch is written by git itself, with {@code git diff --output},
     * so its exact bytes survive: reading the diff back through the process
     * output would normalise line endings (the reader joins lines with
     * {@code \n}) and could fold a git warning on stderr into the patch,
     * either of which can leave a patch that does not apply.</p>
     *
     * <p>The patch is a diff from the pre-work {@code HEAD} (see
     * {@link #recordPreWorkState()}), not the current one. When the job
     * committed its work locally and only the push failed, {@code HEAD} is
     * already that unpublished commit and the rescue commit shares its tree, so
     * a diff against the current {@code HEAD} would be empty and recover
     * nothing; the pre-work {@code HEAD} is the last published commit a person
     * can apply the patch onto. It falls back to {@code HEAD} only when the
     * pre-work head could not be read.</p>
     *
     * @param git     git operations for the working tree
     * @param commit  the rescue commit
     * @param isMerge whether the commit records a merge
     * @throws IOException if the patch cannot be written
     * @throws InterruptedException if a git command is interrupted
     */
    private void publish(GitOperations git, String commit, boolean isMerge)
            throws IOException, InterruptedException {
        String branch = BRANCH_PREFIX + job.getTaskId();
        String sha = TestMethodProtection.shortSha(commit);
        if (job.isPushToOrigin()) {
            GitOperations.Result push = git.executeForResult(
                    "push", "--force", "origin", commit + ":refs/heads/" + branch);
            if (push.succeeded()) {
                location = branch;
                instructions = "The job's work" + (isMerge ? ", merge resolution included," : "")
                        + " was pushed to " + branch + " (commit " + sha + "). Inspect it with"
                        + " `git fetch origin " + branch + "`; accept it with `git push origin "
                        + branch + ":" + job.getTargetBranch() + "`, a fast-forward while "
                        + job.getTargetBranch() + " has not moved. Its commit message lists what"
                        + " was withheld and what the job's guardrails would reject.";
                log("Rescued the working tree to " + branch + " at " + sha);
                return;
            }
            warn("Could not push the rescue commit: " + push.describe());
        }
        File patch = new File(new File(job.getWorkingDirectory()).getAbsoluteFile().getParentFile(),
                PATCH_DIRECTORY + File.separator + job.getTaskId() + ".patch");
        Files.createDirectories(patch.getParentFile().toPath());
        // git writes the file itself so its exact bytes survive; see the javadoc.
        String patchBase = preWorkHead != null ? preWorkHead : "HEAD";
        git.executeForResult("diff", "--binary", "--output=" + patch.getAbsolutePath(),
                patchBase, commit).requireSuccess();
        location = patch.getAbsolutePath();
        instructions = "The job's work could not be pushed, so it was saved on the agent host as "
                + location + " (a patch against " + job.getTargetBranch() + ") and as the local ref"
                + " refs/rescue/" + job.getTaskId() + " (commit " + sha + ") in "
                + job.getWorkingDirectory() + ".";
        log("Rescued the working tree to " + location);
    }

    @Override
    public String formatMessage(String msg) {
        return job.formatMessage(msg);
    }
}
