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

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes a branch to {@code origin}, reconciling against a target branch that
 * advanced while the job was running.
 *
 * <h2>Why this exists</h2>
 * <p>A single FlowTree worker node runs one job at a time, and
 * {@link GitManagedJob}'s workspace lock serializes jobs that share a
 * bind-mounted host path. Neither prevents two <em>separate</em> agent nodes
 * from working the same repository: each node has its own checkout, both branch
 * from the same {@code origin/<targetBranch>} during
 * {@link GitRepositorySetup#prepare()}, and whichever node pushes second is
 * rejected with a non-fast-forward error. This reconciler resolves that race at
 * push time.</p>
 *
 * <h2>How it differs from base-branch sync</h2>
 * <p>{@link GitRepositorySetup} synchronizes with the base branch <em>before</em>
 * {@code doWork()} — the coding agent is still running and resolves any conflict
 * markers in place. Push reconciliation runs <em>after</em> the agent has exited,
 * so there is no in-flight agent to resolve a conflict. Instead, the reconciler
 * calls back into {@link GitManagedJob#onPushConflict(String, List)}, which
 * subclasses with an agent (e.g. {@code CodingAgentJob}) override to run a
 * focused conflict-resolution session.</p>
 *
 * <h2>Algorithm (reactive, bounded)</h2>
 * <ol>
 *   <li>Attempt the push. On success, return.</li>
 *   <li>On rejection, {@code fetch origin} and confirm {@code origin/<target>}
 *       actually advanced (it is no longer an ancestor of {@code HEAD}). If it
 *       did not advance, the push failed for some other reason — fail loudly.</li>
 *   <li>Merge {@code origin/<target>}. A clean merge retries the push directly.</li>
 *   <li>On conflict, delegate to {@link GitManagedJob#onPushConflict(String, List)};
 *       if it resolves the markers, stage them, make the merge commit, and retry
 *       the push.</li>
 *   <li>Give up after {@link #MAX_RECONCILE_ATTEMPTS} attempts, failing loudly.</li>
 * </ol>
 *
 * <p>The reconciler is generic over a git executor so the same logic serves the
 * primary repository (via {@link GitManagedJob#executeGit(String...)}) and
 * dependent repositories (via {@code GitOperations}).</p>
 *
 * @author Michael Murray
 * @see GitCommitHandler
 * @see GitManagedJob#onPushConflict(String, List)
 */
class GitPushReconciler implements ConsoleFeatures {

    /** Maximum number of push attempts before giving up and failing loudly. */
    static final int MAX_RECONCILE_ATTEMPTS = 4;

    /**
     * Runs a git sub-command and returns its exit code.
     * Implemented by {@link GitManagedJob#executeGit(String...)} for the primary
     * repo and {@code GitOperations#execute(String...)} for dependent repos.
     */
    @FunctionalInterface
    interface GitCommand {
        /**
         * Runs a git sub-command.
         *
         * @param args the sub-command and its arguments
         * @return the process exit code (0 on success)
         * @throws IOException          if the process cannot be started
         * @throws InterruptedException if the calling thread is interrupted
         */
        int run(String... args) throws IOException, InterruptedException;
    }

    /**
     * Runs a git sub-command and returns its combined output.
     * Implemented by {@link GitManagedJob#executeGitWithOutput(String...)} for
     * the primary repo and {@code GitOperations#executeWithOutput(String...)}
     * for dependent repos.
     */
    @FunctionalInterface
    interface GitQuery {
        /**
         * Runs a git sub-command and returns its output.
         *
         * @param args the sub-command and its arguments
         * @return the combined standard output and standard error
         * @throws IOException          if the process cannot be started
         * @throws InterruptedException if the calling thread is interrupted
         */
        String run(String... args) throws IOException, InterruptedException;
    }

    /** The job that owns this reconciler; supplies the conflict-resolution hook and identity. */
    private final GitManagedJob job;

    /** Filesystem path of the repository being pushed, passed to the conflict hook. */
    private final String repoPath;

    /** Executes git sub-commands against {@link #repoPath}. */
    private final GitCommand exec;

    /** Queries git sub-commands against {@link #repoPath}. */
    private final GitQuery query;

    /**
     * Creates a reconciler bound to a single repository.
     *
     * @param job      the owning job, used for the conflict hook and commit identity
     * @param repoPath filesystem path of the repository being pushed
     * @param exec     executor for git sub-commands that return an exit code
     * @param query    executor for git sub-commands that return output
     */
    GitPushReconciler(GitManagedJob job, String repoPath, GitCommand exec, GitQuery query) {
        this.job = job;
        this.repoPath = repoPath;
        this.exec = exec;
        this.query = query;
    }

    /**
     * Pushes {@code targetBranch} to {@code origin}, reconciling against an
     * advanced remote branch up to {@link #MAX_RECONCILE_ATTEMPTS} times.
     *
     * @param targetBranch the branch to push (used as both local and remote ref)
     * @throws IOException          if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException     if the push cannot be made to converge
     */
    void push(String targetBranch) throws IOException, InterruptedException {
        String refspec = targetBranch + ":" + targetBranch;

        for (int attempt = 1; attempt <= MAX_RECONCILE_ATTEMPTS; attempt++) {
            log("Pushing to origin/" + targetBranch + " (attempt " + attempt
                    + " of " + MAX_RECONCILE_ATTEMPTS + ")...");

            if (exec.run("push", "-u", "origin", refspec) == 0) {
                log("Pushed to origin/" + targetBranch);
                return;
            }

            warn("Push to origin/" + targetBranch + " rejected -- reconciling against the remote");
            reconcile(targetBranch);
        }

        throw new RuntimeException("Git push to origin/" + targetBranch
                + " failed after " + MAX_RECONCILE_ATTEMPTS + " reconciliation attempts");
    }

    /**
     * Fetches the remote and merges {@code origin/<targetBranch>} into the
     * current branch, delegating conflict resolution to the job when needed.
     *
     * @param targetBranch the branch being reconciled
     * @throws IOException          if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException     if reconciliation cannot proceed
     */
    private void reconcile(String targetBranch) throws IOException, InterruptedException {
        if (exec.run("fetch", "origin") != 0) {
            throw new RuntimeException("Failed to fetch origin while reconciling push to "
                    + targetBranch);
        }

        String remote = "origin/" + targetBranch;

        // If the remote is an ancestor of HEAD, it did not advance -- the push
        // was rejected for some other reason (auth, network, protected branch).
        if (exec.run("merge-base", "--is-ancestor", remote, "HEAD") == 0) {
            throw new RuntimeException("Push to origin/" + targetBranch
                    + " was rejected but " + remote + " did not advance -- "
                    + "push failed for another reason (authentication, network, "
                    + "or a protected branch)");
        }

        int mergeResult = exec.run("merge", remote, "--no-edit", "-m",
                "Merge " + remote + " into " + targetBranch + " (push reconciliation)");

        if (mergeResult == 0) {
            String headHash = query.run("rev-parse", "--short", "HEAD").trim();
            log("Reconciled cleanly with " + remote + " (now at " + headHash + ")");
            return;
        }

        resolveConflict(targetBranch, remote);
    }

    /**
     * Handles a conflicted reconciliation merge: delegates marker resolution to
     * {@link GitManagedJob#onPushConflict(String, List)}, then stages the
     * resolved files and produces the merge commit.
     *
     * @param targetBranch the branch being reconciled
     * @param remote       the remote-tracking ref being merged in
     * @throws IOException          if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException     if the conflict is not resolved
     */
    private void resolveConflict(String targetBranch, String remote)
            throws IOException, InterruptedException {
        List<String> conflicted = conflictedFiles();
        warn("Reconciliation merge with " + remote + " conflicted on "
                + conflicted.size() + " file(s): " + String.join(", ", conflicted));

        if (!job.onPushConflict(repoPath, conflicted)) {
            throw new RuntimeException("Push reconciliation conflict in " + repoPath
                    + " was not resolved (onPushConflict returned false); "
                    + "conflicted files: " + String.join(", ", conflicted));
        }

        String unmerged = query.run("ls-files", "--unmerged").trim();
        if (!unmerged.isEmpty()) {
            throw new RuntimeException("Unresolved conflicts remain after onPushConflict() "
                    + "in " + repoPath + ":\n" + unmerged);
        }

        for (String file : conflicted) {
            if (exec.run("add", file) != 0) {
                throw new RuntimeException("Failed to stage resolved file '" + file
                        + "' in " + repoPath);
            }
        }

        commitMerge(targetBranch, remote);
    }

    /**
     * Creates the reconciliation merge commit, forwarding the job's git identity
     * via {@code -c user.name}/{@code -c user.email} flags (consistent with
     * {@link GitCommitHandler}).
     *
     * @param targetBranch the branch being reconciled
     * @param remote       the remote-tracking ref that was merged in
     * @throws IOException          if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     * @throws RuntimeException     if the commit fails
     */
    private void commitMerge(String targetBranch, String remote)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        String name = job.getGitUserName();
        String email = job.getGitUserEmail();
        if (name != null && !name.isEmpty()) {
            args.add("-c");
            args.add("user.name=" + name);
        }
        if (email != null && !email.isEmpty()) {
            args.add("-c");
            args.add("user.email=" + email);
        }
        args.add("commit");
        args.add("-m");
        args.add("Merge " + remote + " into " + targetBranch + " (push reconciliation)");

        if (exec.run(args.toArray(new String[0])) != 0) {
            throw new RuntimeException("Failed to commit reconciliation merge in " + repoPath);
        }

        String headHash = query.run("rev-parse", "--short", "HEAD").trim();
        log("Committed reconciliation merge in " + repoPath + " (now at " + headHash + ")");
    }

    /**
     * Returns the paths currently in an unmerged (conflicted) state.
     *
     * @return list of conflicted file paths, empty when none
     * @throws IOException          if a git command fails to execute
     * @throws InterruptedException if a git command is interrupted
     */
    private List<String> conflictedFiles() throws IOException, InterruptedException {
        List<String> files = new ArrayList<>();
        String output = query.run("diff", "--name-only", "--diff-filter=U");
        for (String line : output.split("\n")) {
            String file = line.trim();
            if (!file.isEmpty()) {
                files.add(file);
            }
        }
        return files;
    }

    @Override
    public String formatMessage(String msg) {
        String taskId = job.getTaskId();
        if (taskId != null && !taskId.isEmpty()) {
            return "GitPushReconciler [" + taskId + "]: " + msg;
        }
        return "GitPushReconciler: " + msg;
    }
}
