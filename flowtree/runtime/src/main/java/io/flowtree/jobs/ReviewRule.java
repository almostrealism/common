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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Enforcement rule that runs a one-shot "second-pass" review session after
 * the primary phase has produced changes. The reviewer is a separate agent
 * runner (typically a cheaper local model than the one used for primary
 * work) whose job is to catch obvious problems before the rest of the
 * pipeline runs.
 *
 * <p>The reviewer's mandate is intentionally narrow: make small, surgical
 * fixes when they are unambiguous, and defer anything substantial to a
 * {@code review-followup} memory plus an inline {@code TODO(review):}
 * comment. The prompt (built by {@link ReviewPromptBuilder}) makes this
 * bias explicit.</p>
 *
 * <p>Single-pass exit condition. Unlike {@link DeduplicationRule} /
 * {@link OrganizationalPlacementRule} (which extend {@link SetComparisonRule}
 * and loop until the item set stabilises), this rule is one-shot.
 * {@link #onCorrectionAttempted(CodingAgentJob)} sets an internal flag and
 * the next {@link #isViolated(CodingAgentJob)} call short-circuits to
 * {@code false}. The outer enforcement loop still re-runs dedup and
 * placement against anything the review pass changed.</p>
 *
 * <p>The rule also owns the per-job review telemetry (files modified,
 * memory_store calls, clean exit) so the orchestrator does not need a
 * parallel set of fields just for the review phase. The
 * {@link CodingAgentJob} keeps a reference to the active rule and reads
 * its state when populating {@link CodingAgentJobEvent}.</p>
 *
 * @author Michael Murray
 * @see ReviewPromptBuilder
 * @see CodingAgentJob#isReviewEnabled()
 */
class ReviewRule implements EnforcementRule {

    /** Default per-job cap; overridable via {@link CodingAgentJob#setMaxReviewPasses(int)}. */
    static final int DEFAULT_MAX_PASSES = 1;

    /** Maximum number of correction sessions allowed per job. */
    private final int maxPasses;

    /** Flips to {@code true} after the first correction attempt so subsequent checks exit. */
    private boolean alreadyRan;

    /** "Before" snapshot of changed files captured immediately before the review session. */
    private Set<String> beforeSnapshot;

    /** {@code true} when a review session has completed during this job. */
    private boolean ran;

    /** Symmetric-difference count of changed-file sets across the review session. */
    private int filesModified;

    /** Count of {@code memory_store} tool-use events in the review session's output. */
    private int memoriesStored;

    /** {@code true} when the review session's exit code was 0. */
    private boolean exitedCleanly;

    /** Creates a rule with the default pass cap ({@link #DEFAULT_MAX_PASSES}). */
    ReviewRule() {
        this(DEFAULT_MAX_PASSES);
    }

    /**
     * Creates a rule with an explicit pass cap.
     *
     * @param maxPasses maximum review sessions before the enforcement
     *                  framework moves on; must be positive
     * @throws IllegalArgumentException if {@code maxPasses} is not positive
     */
    ReviewRule(int maxPasses) {
        if (maxPasses <= 0) {
            throw new IllegalArgumentException(
                    "maxPasses must be positive, got: " + maxPasses);
        }
        this.maxPasses = maxPasses;
    }

    @Override
    public String getName() { return "review"; }

    @Override
    public int getMaxRetries() { return maxPasses; }

    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (alreadyRan) return false;
        if (!job.extractNewFilePaths().isEmpty()) return true;
        return job.hasUncommittedChanges();
    }

    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        return ReviewPromptBuilder.build(job);
    }

    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        alreadyRan = true;
    }

    /**
     * Captures a snapshot of changed files immediately before the review
     * session runs; the corresponding "after" snapshot is taken in
     * {@link #recordOutcome(CodingAgentJob)}.
     *
     * @param job the job whose working tree is being reviewed
     */
    void captureBefore(CodingAgentJob job) {
        beforeSnapshot = snapshotChangedFiles(job);
    }

    /**
     * Diffs the file set against the {@link #captureBefore(CodingAgentJob)}
     * snapshot, counts {@code memory_store} calls in the agent's output,
     * and marks the rule as having run.
     *
     * @param job the job whose review session just completed
     */
    void recordOutcome(CodingAgentJob job) {
        Set<String> after = snapshotChangedFiles(job);
        Set<String> symDiff = new LinkedHashSet<>(beforeSnapshot != null
                ? beforeSnapshot : new LinkedHashSet<>());
        symDiff.addAll(after);
        Set<String> intersection = new LinkedHashSet<>(beforeSnapshot != null
                ? beforeSnapshot : new LinkedHashSet<>());
        intersection.retainAll(after);
        symDiff.removeAll(intersection);
        filesModified = symDiff.size();
        memoriesStored = countMemoryStoreCalls(job.getOutput());
        exitedCleanly = job.getExitCode() == 0;
        ran = true;
    }

    /** Returns whether a review session has run during this job. */
    boolean hasRun() { return ran; }

    /** Returns the number of files modified during the review session. */
    int getFilesModified() { return filesModified; }

    /** Returns the number of {@code memory_store} calls observed during the review session. */
    int getMemoriesStored() { return memoriesStored; }

    /** Returns whether the review session exited with code 0. */
    boolean isExitedCleanly() { return exitedCleanly; }

    /**
     * Returns the set of paths that differ between the working tree
     * (including uncommitted changes) and the base branch.
     *
     * @param job the job providing working directory and base branch
     * @return the changed-file set; never {@code null}
     */
    static Set<String> snapshotChangedFiles(CodingAgentJob job) {
        Set<String> set = new LinkedHashSet<>();
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        set.addAll(GitOperations.extractNewFilePaths(job.getWorkingDirectory(), base, job::warn));
        try {
            ProcessBuilder pb = new ProcessBuilder(GitOperations.resolveGitCommand(),
                    "diff", "--name-only", "origin/" + base);
            pb.directory(new File(job.getWorkingDirectory() != null
                    ? job.getWorkingDirectory() : "."));
            pb.redirectErrorStream(true);
            GitOperations.augmentPath(pb);
            Process p = pb.start();
            String listing = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            for (String line : listing.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !GitOperations.isExcludedPath(trimmed)) set.add(trimmed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.warn("Review snapshot: failed to list changed files: " + e.getMessage());
        } catch (IOException e) {
            job.warn("Review snapshot: failed to list changed files: " + e.getMessage());
        }
        return set;
    }

    /**
     * Counts {@code memory_store} tool-use events in {@code rawOutput}.
     * Best-effort scan for the agent's tool-invocation marker
     * ({@code "name":"mcp__ar-manager__memory_store"}) which the streamed
     * NDJSON output emits once per invocation.
     *
     * @param rawOutput the agent's raw stdout, or {@code null}
     * @return the number of {@code memory_store} calls observed
     */
    static int countMemoryStoreCalls(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        String marker = "\"name\":\"mcp__ar-manager__memory_store\"";
        while ((idx = rawOutput.indexOf(marker, idx)) >= 0) {
            count++;
            idx += marker.length();
        }
        return count;
    }
}
