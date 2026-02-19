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
 * and contains all relevant information for status reporting.</p>
 *
 * @author Michael Murray
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

    // Claude Code specific
    private String prompt;
    private String sessionId;
    private int exitCode;

    // Timing information from Claude Code output
    private long durationMs;
    private long durationApiMs;
    private double costUsd;
    private int numTurns;

    // Session details from Claude Code output
    private String subtype;
    private boolean sessionIsError;
    private int permissionDenials;

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

    // Getters

    public String getJobId() {
        return jobId;
    }

    public Status getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public List<String> getStagedFiles() {
        return stagedFiles;
    }

    public List<String> getSkippedFiles() {
        return skippedFiles;
    }

    public boolean isPushed() {
        return pushed;
    }

    public String getPullRequestUrl() {
        return pullRequestUrl;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Throwable getException() {
        return exception;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getExitCode() {
        return exitCode;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getDurationApiMs() {
        return durationApiMs;
    }

    public double getCostUsd() {
        return costUsd;
    }

    public int getNumTurns() {
        return numTurns;
    }

    /**
     * Returns the session subtype (stop reason) from Claude Code output.
     * Common values: "success", "error_max_turns".
     */
    public String getSubtype() {
        return subtype;
    }

    /**
     * Returns whether the Claude Code session ended with an error.
     */
    public boolean isSessionError() {
        return sessionIsError;
    }

    /**
     * Returns the number of permission denials during the session.
     */
    public int getPermissionDenials() {
        return permissionDenials;
    }

    // Setters (builder pattern)

    /** Performs the withGitInfo operation. */
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

    /** Performs the withClaudeCodeInfo operation. */
    public JobCompletionEvent withClaudeCodeInfo(String prompt, String sessionId, int exitCode) {
        this.prompt = prompt;
        this.sessionId = sessionId;
        this.exitCode = exitCode;
        return this;
    }

    /**
     * Sets timing information extracted from Claude Code output.
     *
     * @param durationMs    total wall-clock duration reported by Claude Code
     * @param durationApiMs time spent in API calls
     * @param costUsd       total cost in USD
     * @param numTurns      number of agentic turns
     * @return this event for chaining
     */
    public JobCompletionEvent withTimingInfo(long durationMs, long durationApiMs,
                                              double costUsd, int numTurns) {
        this.durationMs = durationMs;
        this.durationApiMs = durationApiMs;
        this.costUsd = costUsd;
        this.numTurns = numTurns;
        return this;
    }

    /**
     * Sets session detail fields extracted from Claude Code output.
     *
     * @param subtype           the session subtype / stop reason (e.g. "success", "error_max_turns")
     * @param sessionIsError    whether Claude Code flagged the session as an error
     * @param permissionDenials number of tool permission denials during the session
     * @return this event for chaining
     */
    public JobCompletionEvent withSessionDetails(String subtype, boolean sessionIsError,
                                                  int permissionDenials) {
        this.subtype = subtype;
        this.sessionIsError = sessionIsError;
        this.permissionDenials = permissionDenials;
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
