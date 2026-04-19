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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Handles repository cloning, branch preparation, and base-branch
 * synchronization for a {@link GitManagedJob} run.
 *
 * <p>This class encapsulates the setup phases that must complete before
 * the coding agent starts work: cloning the repository if absent, stashing
 * residual changes, checking out the target branch, pulling the latest
 * remote state, and optionally merging in changes from the base branch.</p>
 *
 * <p>When a merge attempt produces conflicts, {@link #hasMergeConflicts()}
 * returns {@code true} and {@link #getConflictFiles()} returns the list of
 * conflicted paths.  The merge is intentionally left in progress so that
 * the coding agent can resolve the conflict markers in place and produce a
 * proper two-parent merge commit via {@link GitCommitHandler}.</p>
 */
class GitRepositorySetup implements ConsoleFeatures {

    /** The job whose repository is being prepared. */
    private final GitManagedJob job;

    /** Set to {@code true} when a merge conflict is detected during base-branch sync. */
    private boolean mergeConflictsDetected;

    /** Paths of files that were in a conflicted state during base-branch synchronization. */
    private final List<String> conflictFiles = new ArrayList<>();

    /** Resolved filesystem paths for dependent repos after cloning. */
    private final List<String> dependentRepoPaths = new ArrayList<>();

    /**
     * Creates a new {@code GitRepositorySetup} for the given job.
     *
     * @param job the owning job; used for git command execution and configuration
     */
    GitRepositorySetup(GitManagedJob job) {
        this.job = job;
    }

    // ==================== Public Entry Points ====================

    /**
     * Resolves the working directory, cloning the repository if required.
     *
     * <p>When {@link GitManagedJob#getRepoUrl()} is set but
     * {@link GitManagedJob#getWorkingDirectory()} is either null or absent
     * on disk, the repository is cloned into the resolved workspace path.</p>
     *
     * @return the resolved working directory path
     * @throws IOException          if a git command fails
     * @throws InterruptedException if a command is interrupted
     */
    String resolveAndClone() throws IOException, InterruptedException {
        String workingDirectory = job.getWorkingDirectory();
        String repoUrl = job.getRepoUrl();

        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            File workDir = new File(workingDirectory);
            if (!new File(workDir, ".git").exists() && !workDir.exists()) {
                log("Working directory does not exist, cloning " + repoUrl + " into " + workingDirectory);
                cloneRepository(workingDirectory);
            }
            return workingDirectory;
        }

        String resolvedPath = resolveWorkspacePath();
        log("Resolved workspace path: " + resolvedPath);

        File resolvedDir = new File(resolvedPath);
        if (new File(resolvedDir, ".git").exists()) {
            log("Repository already exists at " + resolvedPath);
            return resolvedPath;
        }

        cloneRepository(resolvedPath);
        return resolvedPath;
    }

    /**
     * Prepares the working directory for agent use.
     *
     * <p>Steps performed (in order):
     * <ol>
     *   <li>Stash any uncommitted changes that would block a branch switch.</li>
     *   <li>Fetch the latest refs from {@code origin}.</li>
     *   <li>Check out the target branch (creating it if configured to do so).</li>
     *   <li>Pull the latest remote state (fast-forward; falls back to hard-reset).</li>
     *   <li>Merge in the base branch, recording conflicts when they occur.</li>
     * </ol>
     *
     * @throws IOException          if a git command fails
     * @throws InterruptedException if a command is interrupted
     */
    void prepare() throws IOException, InterruptedException {
        // 1. Stash any uncommitted changes so they do not block branch switches.
        List<String> allDirtyFiles = getAllDirtyFiles();
        if (!allDirtyFiles.isEmpty()) {
            List<String> nonExcluded = filterExcluded(allDirtyFiles);

            List<String> labelFiles = nonExcluded.isEmpty() ? allDirtyFiles : nonExcluded;
            String fileList = labelFiles.size() <= 5
                    ? String.join(", ", labelFiles)
                    : String.join(", ", labelFiles.subList(0, 5))
                            + " (+" + (labelFiles.size() - 5) + " more)";

            if (!nonExcluded.isEmpty()) {
                warn("Uncommitted changes found: " + fileList + " -- stashing");
            } else {
                log("Only excluded files are dirty (" + fileList + ") -- stashing to allow branch switch");
            }

            String stashMessage = "flowtree: interrupted job residue before "
                    + job.getTaskId() + " [" + fileList + "]";
            if (job.executeGit("stash", "push", "--include-untracked", "-m", stashMessage) != 0) {
                throw new RuntimeException("Failed to stash uncommitted changes: " + fileList);
            }
            log("Working directory cleaned (changes stashed)");
        }

        // 2. Fetch latest from origin.
        log("Fetching latest from origin...");
        if (job.executeGit("fetch", "origin") != 0) {
            throw new RuntimeException("Failed to fetch from origin");
        }

        // 3. Checkout target branch.
        if (!job.ensureOnTargetBranch()) {
            throw new RuntimeException("Failed to switch to target branch: " + job.getTargetBranch());
        }

        // 4. Sync with remote if remote branch exists.
        String targetBranch = job.getTargetBranch();
        boolean remoteBranchExists = job.executeGit(
                "show-ref", "--verify", "--quiet",
                "refs/remotes/origin/" + targetBranch) == 0;
        if (remoteBranchExists) {
            log("Syncing with origin/" + targetBranch + "...");
            int pullResult = job.executeGit("pull", "--ff-only", "origin", targetBranch);
            if (pullResult != 0) {
                log("Fast-forward pull failed; resetting to origin/" + targetBranch);
                int resetResult = job.executeGit("reset", "--hard", "origin/" + targetBranch);
                if (resetResult != 0) {
                    throw new RuntimeException(
                            "Failed to sync with origin/" + targetBranch
                                    + " -- both pull and reset failed");
                }
            }
            String headHash = job.executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
            log("Working directory is up to date with origin/" + targetBranch + " at " + headHash);
        } else {
            log("No remote branch origin/" + targetBranch + " -- skipping pull");
        }

        // 5. Synchronize with the base branch.
        synchronize();
    }

    /**
     * Returns whether the most recent {@link #prepare()} call encountered
     * merge conflicts when synchronizing with the base branch.
     *
     * @return true if a merge conflict was detected
     */
    boolean hasMergeConflicts() {
        return mergeConflictsDetected;
    }

    /**
     * Returns the list of files that had merge conflicts during the last
     * {@link #prepare()} call.
     *
     * @return the list of conflicted file paths, or an empty list if none
     */
    List<String> getConflictFiles() {
        return conflictFiles;
    }

    // ==================== Private Helpers ====================

    /**
     * Merges the remote base branch into the current working branch.
     *
     * <p>When conflicts occur, they are recorded and the merge is left in
     * progress so the coding agent can resolve the markers in place.
     * {@link GitCommitHandler} detects the in-progress merge and produces
     * a proper two-parent merge commit.</p>
     */
    private void synchronize() throws IOException, InterruptedException {
        String baseBranch = job.getBaseBranch();
        String targetBranch = job.getTargetBranch();

        if (baseBranch == null || baseBranch.isEmpty() || baseBranch.equals(targetBranch)) {
            return;
        }

        String remoteBase = "origin/" + baseBranch;
        boolean remoteBaseExists = job.executeGit(
                "show-ref", "--verify", "--quiet", "refs/remotes/" + remoteBase) == 0;
        if (!remoteBaseExists) {
            log("Remote base branch " + remoteBase + " does not exist -- skipping sync");
            return;
        }

        String mergeBase = job.executeGitWithOutput("merge-base", "HEAD", remoteBase).trim();
        String baseHead = job.executeGitWithOutput("rev-parse", remoteBase).trim();

        if (mergeBase.equals(baseHead)) {
            log("Already up to date with " + remoteBase);
            return;
        }

        log("Synchronizing with " + remoteBase + " (merge-base: "
                + mergeBase.substring(0, Math.min(7, mergeBase.length()))
                + ", base HEAD: "
                + baseHead.substring(0, Math.min(7, baseHead.length())) + ")...");

        int mergeResult = job.executeGit(
                "merge", remoteBase, "--no-edit", "-m",
                "Merge " + remoteBase + " into " + targetBranch);

        if (mergeResult == 0) {
            String headHash = job.executeGitWithOutput("rev-parse", "--short", "HEAD").trim();
            log("Successfully merged " + remoteBase + " (now at " + headHash + ")");
        } else {
            log("Merge conflict detected while synchronizing with " + remoteBase);
            mergeConflictsDetected = true;

            String statusOutput = job.executeGitWithOutput("status", "--porcelain");
            for (String line : statusOutput.split("\n")) {
                if (line.startsWith("UU ") || line.startsWith("AA ")
                        || line.startsWith("DD ") || line.startsWith("AU ")
                        || line.startsWith("UA ") || line.startsWith("DU ")
                        || line.startsWith("UD ")) {
                    String file = line.substring(3).trim();
                    if (!file.isEmpty()) {
                        conflictFiles.add(file);
                    }
                }
            }

            log("Conflicted files (" + conflictFiles.size() + "): "
                    + (conflictFiles.size() <= 10
                            ? String.join(", ", conflictFiles)
                            : String.join(", ", conflictFiles.subList(0, 10))
                                    + " (+" + (conflictFiles.size() - 10) + " more)"));
            log("Merge left in conflicted state -- agent will resolve conflict markers in place");
        }
    }

    /**
     * Clones {@link GitManagedJob#getRepoUrl()} into {@code targetPath}.
     */
    private void cloneRepository(String targetPath) throws IOException, InterruptedException {
        GitOperations gitOps = new GitOperations(null, job.getTaskId());
        gitOps.setGitUserName(job.getGitUserName());
        gitOps.setGitUserEmail(job.getGitUserEmail());
        gitOps.cloneRepository(job.getRepoUrl(), targetPath);
    }

    /**
     * Resolves the workspace parent directory and appends the repo name.
     */
    private String resolveWorkspacePath() {
        String repoName = WorkspaceResolver.extractRepoName(job.getRepoUrl());
        String defaultWorkspacePath = job.getDefaultWorkspacePath();

        if (defaultWorkspacePath != null && !defaultWorkspacePath.isEmpty()) {
            return defaultWorkspacePath + "/" + repoName;
        }

        File defaultDir = new File("/workspace/project");
        if (defaultDir.exists() && defaultDir.isDirectory()) {
            return "/workspace/project/" + repoName;
        }

        return "/tmp/flowtree-workspaces/" + repoName;
    }

    /**
     * Returns every file reported as dirty by {@code git status --porcelain},
     * with no exclusion filtering applied.
     */
    private List<String> getAllDirtyFiles() throws IOException, InterruptedException {
        String statusOutput = job.executeGitWithOutput("status", "--porcelain");
        List<String> dirtyFiles = new ArrayList<>();

        for (String line : statusOutput.split("\n")) {
            if (line.length() > 3) {
                String file = line.substring(3).trim();
                if (file.contains(" -> ")) {
                    file = file.split(" -> ")[1];
                }
                if (!file.isEmpty()) {
                    dirtyFiles.add(file);
                }
            }
        }

        return dirtyFiles;
    }

    /**
     * Filters out files that match the job's excluded patterns.
     *
     * @param files list of file paths to filter
     * @return files that do NOT match any excluded pattern
     */
    private List<String> filterExcluded(List<String> files) {
        Set<String> allExcluded = job.getAllExcludedPatterns();
        List<String> result = new ArrayList<>();
        for (String file : files) {
            if (!FileStager.matchesAnyPattern(file, allExcluded)) {
                result.add(file);
            }
        }
        return result;
    }

    /**
     * Returns the resolved filesystem paths for dependent repos cloned during
     * {@link #prepareDependentRepos()}.
     *
     * @return list of dependent repo paths
     */
    List<String> getDependentRepoPaths() {
        return new ArrayList<>(dependentRepoPaths);
    }

    /**
     * Clones dependent repositories as siblings of the primary repo and records
     * their paths in {@link #dependentRepoPaths} for later branch preparation
     * and commit handling.
     *
     * @throws IOException if a git command fails
     * @throws InterruptedException if a command is interrupted
     */
    void prepareDependentRepos() throws IOException, InterruptedException {
        List<String> deps = job.getDependentRepos();
        if (deps == null || deps.isEmpty()) {
            return;
        }

        String workDir = job.getWorkingDirectory();
        String defaultWsPath = job.getDefaultWorkspacePath();
        String parentDir = (workDir != null && !workDir.isEmpty())
                ? new File(workDir).getParent() : defaultWsPath;
        if (parentDir == null) {
            warn("Cannot resolve parent directory for dependent repos");
            return;
        }

        for (String depRepoUrl : deps) {
            String repoName = WorkspaceResolver.extractRepoName(depRepoUrl);
            String depPath = parentDir + "/" + repoName;
            File depDir = new File(depPath);
            if (new File(depDir, ".git").exists()) {
                log("Dependent repo already cloned: " + depPath);
            } else {
                log("Cloning dependent repo: " + depRepoUrl + " into " + depPath);
                GitOperations gitOps = new GitOperations(parentDir, job.getTaskId());
                gitOps.cloneRepository(depRepoUrl, depPath);
            }
            dependentRepoPaths.add(depPath);
        }
    }

    /**
     * Prepares branch state for all dependent repos: stash, fetch, checkout the target
     * branch (creating it from the base branch if needed), and pull latest changes.
     *
     * @throws IOException if a git command fails
     * @throws InterruptedException if a command is interrupted
     */
    void prepareDependentReposBranches() throws IOException, InterruptedException {
        String targetBranch = job.getTargetBranch();
        String baseBranch = job.getBaseBranch();
        for (String depPath : dependentRepoPaths) {
            log("Preparing dependent repo branch: " + depPath);
            GitOperations gitOps = new GitOperations(depPath, job.getTaskId());

            String statusOutput = gitOps.executeWithOutput("status", "--porcelain");
            if (!statusOutput.trim().isEmpty()) {
                String stashMsg = "flowtree: dependent repo cleanup before " + job.getTaskId();
                log("Stashing uncommitted changes in dependent repo: " + depPath);
                int stashExit = gitOps.execute("stash", "push", "--include-untracked", "-m", stashMsg);
                if (stashExit != 0) {
                    throw new IOException("git stash push failed (exit " + stashExit + ") in " + depPath);
                }
            }

            if (gitOps.execute("fetch", "origin") != 0) {
                warn("git fetch origin failed in dependent repo: " + depPath);
            }

            String currentBranch = gitOps.executeWithOutput(
                "rev-parse", "--abbrev-ref", "HEAD").trim();
            boolean remoteExists = gitOps.execute("show-ref", "--verify", "--quiet",
                "refs/remotes/origin/" + targetBranch) == 0;

            if (!targetBranch.equals(currentBranch)) {
                boolean localExists = gitOps.execute("show-ref", "--verify", "--quiet",
                    "refs/heads/" + targetBranch) == 0;
                if (localExists || remoteExists) {
                    int checkoutExit = gitOps.execute("checkout", targetBranch);
                    if (checkoutExit != 0) {
                        throw new IOException("git checkout " + targetBranch
                            + " failed (exit " + checkoutExit + ") in " + depPath);
                    }
                } else {
                    String startPoint = "origin/" + baseBranch;
                    log("Creating branch " + targetBranch + " from " + startPoint + " in " + depPath);
                    int createExit = gitOps.execute("checkout", "-b", targetBranch, "--no-track", startPoint);
                    if (createExit != 0) {
                        throw new IOException("git checkout -b " + targetBranch
                            + " failed (exit " + createExit + ") in " + depPath);
                    }
                    continue;
                }
            }

            if (remoteExists) {
                int pullExit = gitOps.execute("pull", "--ff-only", "origin", targetBranch);
                if (pullExit != 0) {
                    log("Fast-forward pull failed; resetting to origin/" + targetBranch + " in " + depPath);
                    int resetExit = gitOps.execute("reset", "--hard", "origin/" + targetBranch);
                    if (resetExit != 0) {
                        throw new IOException("Failed to sync with origin/" + targetBranch + " in " + depPath);
                    }
                }
            }
        }
    }

    @Override
    public String formatMessage(String msg) {
        String id = job.getTaskId();
        if (id != null && !id.isEmpty()) {
            return "GitRepositorySetup [" + id + "]: " + msg;
        }
        return "GitRepositorySetup: " + msg;
    }
}
