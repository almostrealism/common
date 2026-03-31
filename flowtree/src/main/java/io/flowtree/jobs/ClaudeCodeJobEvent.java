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

import java.util.Collections;
import java.util.List;

/**
 * Extension of {@link JobCompletionEvent} with Claude Code-specific fields.
 *
 * <p>Generic job events use the base class; only Claude Code jobs produce
 * this subclass, which adds prompt, session, timing, and permission denial
 * information specific to the Claude Code execution model.</p>
 *
 * @author Michael Murray
 * @see JobCompletionEvent
 */
public class ClaudeCodeJobEvent extends JobCompletionEvent {

    /** The prompt that was submitted to Claude Code for this job. */
    private String prompt;
    /** The session identifier assigned by Claude Code for this execution. */
    private String sessionId;
    /** The process exit code returned by the Claude Code process. */
    private int exitCode;

    /** Total wall-clock duration of the Claude Code session in milliseconds. */
    private long durationMs;
    /** Time spent in API calls during the Claude Code session, in milliseconds. */
    private long durationApiMs;
    /** Total cost of the Claude Code session in US dollars. */
    private double costUsd;
    /** Number of agentic turns taken during the Claude Code session. */
    private int numTurns;

    /** Session subtype / stop reason reported by Claude Code (e.g. "success", "error_max_turns"). */
    private String subtype;
    /** Whether Claude Code flagged the session as an error. */
    private boolean sessionIsError;
    /** Number of tool-use permission denials recorded during the session. */
    private int permissionDenials;
    /** Names of the tools that were denied during the session. */
    private List<String> deniedToolNames;

    /**
     * Creates a new Claude Code job completion event.
     *
     * @param jobId       the job identifier
     * @param status      the completion status
     * @param description human-readable description of the job
     */
    public ClaudeCodeJobEvent(String jobId, Status status, String description) {
        super(jobId, status, description);
    }

    /**
     * Creates a success event for a Claude Code job.
     *
     * @param jobId       the job identifier
     * @param description human-readable description of the job
     * @return a new event with {@link Status#SUCCESS} status
     */
    public static ClaudeCodeJobEvent success(String jobId, String description) {
        return new ClaudeCodeJobEvent(jobId, Status.SUCCESS, description);
    }

    /**
     * Creates a failure event for a Claude Code job.
     *
     * @param jobId        the job identifier
     * @param description  human-readable description of the job
     * @param errorMessage the error message describing the failure
     * @param exception    the exception that caused the failure, or null
     * @return a new event with {@link Status#FAILED} status and error details
     */
    public static ClaudeCodeJobEvent failed(String jobId, String description,
                                            String errorMessage, Throwable exception) {
        ClaudeCodeJobEvent event = new ClaudeCodeJobEvent(jobId, Status.FAILED, description);
        event.setErrorMessage(errorMessage);
        event.setException(exception);
        return event;
    }

    // ==================== Builder-pattern setters ====================

    /**
     * Sets Claude Code identification fields.
     *
     * @param prompt    the prompt that was sent to Claude Code
     * @param sessionId the Claude Code session identifier
     * @param exitCode  the process exit code from Claude Code
     * @return this event for chaining
     */
    public ClaudeCodeJobEvent withClaudeCodeInfo(String prompt, String sessionId, int exitCode) {
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
    public ClaudeCodeJobEvent withTimingInfo(long durationMs, long durationApiMs,
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
     * @param deniedToolNames   names of tools that were denied, or null
     * @return this event for chaining
     */
    public ClaudeCodeJobEvent withSessionDetails(String subtype, boolean sessionIsError,
                                                 int permissionDenials,
                                                 List<String> deniedToolNames) {
        this.subtype = subtype;
        this.sessionIsError = sessionIsError;
        this.permissionDenials = permissionDenials;
        this.deniedToolNames = deniedToolNames;
        return this;
    }

    // ==================== Getters ====================

    /**
     * Returns the prompt that was sent to Claude Code.
     *
     * @return the prompt string, or null if not set
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * Returns the Claude Code session identifier.
     *
     * @return the session ID, or null if not set
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the process exit code from Claude Code.
     *
     * @return the exit code (0 typically indicates success)
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Returns the total wall-clock duration reported by Claude Code.
     *
     * @return duration in milliseconds
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Returns the time spent in API calls during the Claude Code session.
     *
     * @return API duration in milliseconds
     */
    public long getDurationApiMs() {
        return durationApiMs;
    }

    /**
     * Returns the total cost of the Claude Code session in USD.
     *
     * @return cost in USD
     */
    public double getCostUsd() {
        return costUsd;
    }

    /**
     * Returns the number of agentic turns in the Claude Code session.
     *
     * @return the turn count
     */
    public int getNumTurns() {
        return numTurns;
    }

    /**
     * Returns the session subtype (stop reason) from Claude Code output.
     * Common values: "success", "error_max_turns".
     *
     * @return the subtype string, or null if not set
     */
    public String getSubtype() {
        return subtype;
    }

    /**
     * Returns whether the Claude Code session ended with an error.
     *
     * @return true if the session was flagged as an error
     */
    public boolean isSessionError() {
        return sessionIsError;
    }

    /**
     * Returns the number of permission denials during the session.
     *
     * @return the denial count
     */
    public int getPermissionDenials() {
        return permissionDenials;
    }

    /**
     * Returns the tool names that were denied during the session.
     * Each entry corresponds to a single denial event; the same tool
     * may appear multiple times if it was denied more than once.
     *
     * @return list of denied tool names, or empty list if none
     */
    public List<String> getDeniedToolNames() {
        return deniedToolNames != null ? deniedToolNames : Collections.emptyList();
    }
}
