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

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Event object containing details about a job's completion.
 *
 * <p>This event is fired when a job completes (successfully or with failure)
 * and contains all relevant information for status reporting. Generic fields
 * (job ID, status, git info, error info) live here; Claude Code-specific
 * fields (prompt, session, timing) are in {@link ClaudeCodeJobEvent}.</p>
 *
 * <p>The Claude-specific getter methods on this base class return zero/null
 * defaults so that consumers (like {@code SlackNotifier}) can call them
 * uniformly on any event type. Only {@link ClaudeCodeJobEvent} returns real
 * values.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJobEvent
 * @see JobCompletionListener
 */
public class JobCompletionEvent {

    /**
     * Status of the job completion.
     */
    public enum Status {
        /** Job started but not yet complete */
        STARTED,
        /** Job completed successfully */
        SUCCESS,
        /** Job failed with an error */
        FAILED,
        /** Job was cancelled before completion */
        CANCELLED
    }

    private final String jobId;
    private final Status status;
    private final String description;
    private final Instant timestamp;

    // Git-related fields
    private String targetBranch;
    private String commitHash;
    private List<String> stagedFiles;
    private List<String> skippedFiles;
    private boolean pushed;

    // Error information
    private String errorMessage;
    private Throwable exception;

    // Pull request
    private String pullRequestUrl;

    /**
     * Creates a new job completion event.
     *
     * @param jobId       the job identifier
     * @param status      the completion status
     * @param description human-readable description of the job
     */
    public JobCompletionEvent(String jobId, Status status, String description) {
        this.jobId = jobId;
        this.status = status;
        this.description = description;
        this.timestamp = Instant.now();
        this.stagedFiles = Collections.emptyList();
        this.skippedFiles = Collections.emptyList();
    }

    /**
     * Creates a started event for a job.
     */
    public static JobCompletionEvent started(String jobId, String description) {
        return new JobCompletionEvent(jobId, Status.STARTED, description);
    }

    /**
     * Creates a success event for a job.
     */
    public static JobCompletionEvent success(String jobId, String description) {
        return new JobCompletionEvent(jobId, Status.SUCCESS, description);
    }

    /**
     * Creates a failure event for a job.
     */
    public static JobCompletionEvent failed(String jobId, String description,
                                            String errorMessage, Throwable exception) {
        JobCompletionEvent event = new JobCompletionEvent(jobId, Status.FAILED, description);
        event.errorMessage = errorMessage;
        event.exception = exception;
        return event;
    }

    /**
     * Sets the error message for this event.
     * Intended for use by subclass factory methods.
     *
     * @param errorMessage the error message
     */
    protected void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Sets the exception for this event.
     * Intended for use by subclass factory methods.
     *
     * @param exception the exception that caused the failure
     */
    protected void setException(Throwable exception) {
        this.exception = exception;
    }

    // ==================== Getters ====================

    public String getJobId() { return jobId; }
    public Status getStatus() { return status; }
    public String getDescription() { return description; }
    public Instant getTimestamp() { return timestamp; }
    public String getTargetBranch() { return targetBranch; }
    public String getCommitHash() { return commitHash; }
    public List<String> getStagedFiles() { return stagedFiles; }
    public List<String> getSkippedFiles() { return skippedFiles; }
    public boolean isPushed() { return pushed; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public String getErrorMessage() { return errorMessage; }
    public Throwable getException() { return exception; }

    // ---- Claude Code-specific getters (defaults for base class) ----

    /** Returns the prompt sent to Claude Code, or null for non-Claude jobs. */
    public String getPrompt() { return null; }
    /** Returns the Claude Code session ID, or null for non-Claude jobs. */
    public String getSessionId() { return null; }
    /** Returns the Claude Code exit code, or 0 for non-Claude jobs. */
    public int getExitCode() { return 0; }
    /** Returns the wall-clock duration in ms, or 0 for non-Claude jobs. */
    public long getDurationMs() { return 0; }
    /** Returns the API call duration in ms, or 0 for non-Claude jobs. */
    public long getDurationApiMs() { return 0; }
    /** Returns the cost in USD, or 0 for non-Claude jobs. */
    public double getCostUsd() { return 0; }
    /** Returns the number of turns, or 0 for non-Claude jobs. */
    public int getNumTurns() { return 0; }
    /** Returns the session subtype (stop reason), or null for non-Claude jobs. */
    public String getSubtype() { return null; }
    /** Returns whether the session is an error, always false for non-Claude jobs. */
    public boolean isSessionError() { return false; }
    /** Returns the number of permission denials, or 0 for non-Claude jobs. */
    public int getPermissionDenials() { return 0; }
    /** Returns the denied tool names, or empty list for non-Claude jobs. */
    public List<String> getDeniedToolNames() { return Collections.emptyList(); }

    // ==================== Builder-pattern setters ====================

    /**
     * Sets git information on this event.
     *
     * @param branch     the target branch name
     * @param commitHash the commit hash (if committed)
     * @param staged     list of staged files
     * @param skipped    list of skipped files (with reasons)
     * @param pushed     whether changes were pushed to origin
     * @return this event for chaining
     */
    public JobCompletionEvent withGitInfo(String branch, String commitHash, List<String> staged,
                                          List<String> skipped, boolean pushed) {
        this.targetBranch = branch;
        this.commitHash = commitHash;
        this.stagedFiles = staged != null ? staged : Collections.emptyList();
        this.skippedFiles = skipped != null ? skipped : Collections.emptyList();
        this.pushed = pushed;
        return this;
    }

    /**
     * Sets the pull request URL for this event.
     *
     * @param url the GitHub PR URL, or null if no PR was found
     * @return this event for chaining
     */
    public JobCompletionEvent withPullRequestUrl(String url) {
        this.pullRequestUrl = url;
        return this;
    }

    @Override
    public String toString() {
        return "JobCompletionEvent{" +
               "jobId='" + jobId + '\'' +
               ", status=" + status +
               ", description='" + description + '\'' +
               ", commitHash='" + commitHash + '\'' +
               '}';
    }
}
