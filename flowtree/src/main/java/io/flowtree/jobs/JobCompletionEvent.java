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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /** Unique identifier for the job that produced this event. */
    private final String jobId;
    /** Completion status of the job. */
    private final Status status;
    /** Human-readable description of the job. */
    private final String description;
    /** Instant at which this event was created. */
    private final Instant timestamp;

    /** Name of the git branch targeted by this job. */
    private String targetBranch;
    /** Hash of the commit created by this job, or {@code null} if no commit was made. */
    private String commitHash;
    /** Files staged for commit during this job. */
    private List<String> stagedFiles;
    /** Files skipped (not staged) during this job, with reasons. */
    private List<String> skippedFiles;
    /** Whether the commit was pushed to the remote origin. */
    private boolean pushed;

    /** Human-readable description of the error, if the job failed. */
    private String errorMessage;
    /** Exception that caused the job to fail, or {@code null}. */
    private Throwable exception;

    /** URL of the GitHub pull request opened by this job, or {@code null}. */
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

    /**
     * Returns the unique identifier for the job that produced this event.
     *
     * @return the job ID
     */
    public String getJobId() { return jobId; }

    /**
     * Returns the completion status of the job.
     *
     * @return the job status
     */
    public Status getStatus() { return status; }

    /**
     * Returns a human-readable description of the job.
     *
     * @return the job description
     */
    public String getDescription() { return description; }

    /**
     * Returns the instant at which this event was created.
     *
     * @return the event timestamp
     */
    public Instant getTimestamp() { return timestamp; }

    /**
     * Returns the git branch targeted by this job.
     *
     * @return the target branch name, or {@code null} if not set
     */
    public String getTargetBranch() { return targetBranch; }

    /**
     * Returns the hash of the commit created by this job.
     *
     * @return the commit hash, or {@code null} if no commit was made
     */
    public String getCommitHash() { return commitHash; }

    /**
     * Returns the list of files staged for commit during this job.
     *
     * @return the staged file list, never {@code null}
     */
    public List<String> getStagedFiles() { return stagedFiles; }

    /**
     * Returns the list of files skipped (not staged) during this job.
     *
     * @return the skipped file list, never {@code null}
     */
    public List<String> getSkippedFiles() { return skippedFiles; }

    /**
     * Returns whether the commit produced by this job was pushed to the
     * remote origin.
     *
     * @return {@code true} if the commit was pushed
     */
    public boolean isPushed() { return pushed; }

    /**
     * Returns the URL of the GitHub pull request opened by this job.
     *
     * @return the PR URL, or {@code null} if no PR was created
     */
    public String getPullRequestUrl() { return pullRequestUrl; }

    /**
     * Returns the human-readable error message, if the job failed.
     *
     * @return the error message, or {@code null} if the job succeeded
     */
    public String getErrorMessage() { return errorMessage; }

    /**
     * Returns the exception that caused this job to fail.
     *
     * @return the exception, or {@code null} if the job succeeded
     */
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

    /** Shared Jackson mapper for {@link #toJson()}. */
    private static final ObjectMapper EVENT_MAPPER = new ObjectMapper();

    /**
     * Serializes this event to a JSON string for transmission to the controller.
     *
     * @return JSON string representation of this event
     */
    public String toJson() {
        ObjectNode root = EVENT_MAPPER.createObjectNode();
        root.put("jobId", jobId);
        root.put("status", status.name());
        root.put("description", description);
        root.put("targetBranch", targetBranch);
        root.put("commitHash", commitHash);
        root.put("pushed", pushed);

        ArrayNode stagedArray = root.putArray("stagedFiles");
        for (String f : stagedFiles) stagedArray.add(f);

        ArrayNode skippedArray = root.putArray("skippedFiles");
        for (String f : skippedFiles) skippedArray.add(f);

        root.put("pullRequestUrl", pullRequestUrl);
        root.put("errorMessage", errorMessage);

        root.put("prompt", getPrompt());
        root.put("sessionId", getSessionId());
        root.put("exitCode", getExitCode());

        root.put("durationMs", getDurationMs());
        root.put("durationApiMs", getDurationApiMs());
        root.put("costUsd", getCostUsd());
        root.put("numTurns", getNumTurns());

        root.put("subtype", getSubtype());
        root.put("sessionIsError", isSessionError());
        root.put("permissionDenials", getPermissionDenials());

        ArrayNode deniedArray = root.putArray("deniedToolNames");
        for (String t : getDeniedToolNames()) deniedArray.add(t);

        try {
            return EVENT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Returns a human-readable summary of this event including job ID, status,
     * description, and commit hash.
     *
     * @return a diagnostic string representation of this event
     */
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
