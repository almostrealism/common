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
import java.util.Map;

/**
 * Event object containing details about a job's completion.
 *
 * <p>This event is fired when a job completes (successfully or with failure)
 * and contains all relevant information for status reporting. Generic fields
 * (job ID, status, git info, error info) live here; Claude Code-specific
 * fields (prompt, session, timing) are in {@link CodingAgentJobEvent}.</p>
 *
 * <p>The Claude-specific getter methods on this base class return zero/null
 * defaults so that consumers (like {@code SlackNotifier}) can call them
 * uniformly on any event type. Only {@link CodingAgentJobEvent} returns real
 * values.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJobEvent
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
        CANCELLED,
        /**
         * Job exited cleanly but the agent abandoned work it had started —
         * for example, by invoking the test runner and ending its turn while
         * the run was still in progress. Distinguished from SUCCESS so the
         * developer can tell that the agent's session is incomplete and from
         * FAILED so the agent's clean exit is not conflated with a crash.
         */
        DEGRADED
    }

    /** Unique identifier for the job that produced this event. */
    private final String jobId;
    /** Completion status of the job. */
    private final Status status;
    /** Human-readable description of the job. */
    private final String description;
    /**
     * Instant at which this event was created in memory.
     *
     * <p>Stamped at construction by the live code path (an agent constructing
     * the event before posting it to the controller). For events reconstructed
     * from the {@code job_timing} table, this field is overwritten after
     * construction so the value reflects the persisted {@code completed_at}
     * (or {@code started_at} for {@code STARTED} rows) rather than the
     * read-time instant — see {@link JobStatsStore#rowToEvent}.</p>
     */
    private Instant timestamp;

    /**
     * Explicit read-side timestamp applied to events reconstructed from the
     * {@code job_timing} table. Mirrors {@link #timestamp} once
     * {@link #setTimestamp(Instant)} has been called by
     * {@link JobStatsStore#rowToEvent}; before that, the value is whatever
     * the constructor stamp produced. Tests that assert the in-memory-event
     * constructor stamp continue to use {@link #getTimestamp()} directly.
     */
    private Instant eventTime;
    /** Persisted {@code started_at} from the {@code job_timing} row, when reconstructed by the store. */
    private Instant startedAt;
    /** Persisted {@code completed_at} from the {@code job_timing} row, when reconstructed by the store. */
    private Instant finishedAt;

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
     * Total USD cost for this job, populated from per-phase cost accumulation
     * or from the {@code job_timing} table during retrieval.
     */
    protected double totalCostUsd;

    /**
     * Per-runner USD cost breakdown, populated from per-phase accumulation or
     * from the {@code job_runner_cost} table during retrieval.
     */
    protected Map<String, Double> costByRunner = Collections.emptyMap();

    /**
     * Per-model USD cost breakdown, populated from per-phase accumulation or
     * from the {@code job_model_cost} table during retrieval.
     */
    protected Map<String, Double> costByModel = Collections.emptyMap();

    /**
     * Whether the recorded cost is a lower bound rather than the true total.
     * Set when at least one agent session was killed by the inactivity watchdog
     * before it emitted its terminal cost JSON, so the cost lost from that
     * session cannot be accounted for. When {@code true}, {@link #totalCostUsd}
     * and the per-runner/per-model breakdowns should be reported as a minimum
     * ("at least") and flagged as incomplete rather than presented as exact.
     */
    protected boolean costIncomplete;

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
        this.eventTime = this.timestamp;
        this.startedAt = null;
        this.finishedAt = null;
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
     * Creates a {@link Status#DEGRADED} event for a job whose process
     * exited cleanly but which left work in an incomplete state — most
     * commonly, a test run started via the ar-test-runner MCP that the
     * agent never polled to completion.
     *
     * @param jobId        the job identifier
     * @param description  human-readable description of the job
     * @param errorMessage diagnostic detail describing what was abandoned
     * @return a new event with {@link Status#DEGRADED} status
     */
    public static JobCompletionEvent degraded(String jobId, String description,
                                              String errorMessage) {
        JobCompletionEvent event = new JobCompletionEvent(jobId, Status.DEGRADED, description);
        event.errorMessage = errorMessage;
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
     * Returns the job description shortened to fit the given length, with an
     * ellipsis marking the elision. A description that already fits is
     * returned unchanged, and a missing description yields an empty string
     * rather than {@code null}, so callers can append the result directly.
     *
     * <p>Every channel that reports a job — chat post, SMS alert, listing
     * row — shows the description under a length budget of its own, so the
     * shortening belongs to the event rather than to any one reporter.</p>
     *
     * @param maxLength maximum number of characters to retain, including the
     *                  ellipsis
     * @return the (possibly shortened) description, never {@code null}
     */
    public String shortDescription(int maxLength) {
        if (description == null) return "";
        if (description.length() <= maxLength) return description;
        return description.substring(0, maxLength - 3) + "...";
    }

    /**
     * Returns the instant at which this event was created.
     *
     * @return the event timestamp
     */
    public Instant getTimestamp() { return timestamp; }

    /**
     * Returns the persisted event timestamp recorded for this event. Mirrors
     * {@link #getTimestamp()} once the event has been reconstructed from
     * {@code job_timing}; equals the constructor stamp before that.
     *
     * <p>Distinct from {@link #getTimestamp()} because the constructor stamp
     * captures the moment an event was instantiated (e.g. an agent's
     * {@code Instant.now()} on emit), while this field is the authoritative
     * event time as recorded in the database. The two are separate so the
     * existing in-memory-event flow keeps working without modification while
     * readers can consult the persisted time explicitly.</p>
     *
     * @return the persisted event timestamp
     */
    public Instant getEventTime() { return eventTime != null ? eventTime : timestamp; }

    /**
     * Returns the persisted start time for this event, or {@code null} when
     * the event was never persisted through the {@code job_timing} row
     * reconstruction path. Set by {@link JobStatsStore#rowToEvent} from the
     * row's {@code started_at} column.
     *
     * @return the persisted start time, or {@code null}
     */
    public Instant getStartedAt() { return startedAt; }

    /**
     * Sets the persisted start time. Called by
     * {@link JobStatsStore#rowToEvent}; ignored when {@code null}.
     *
     * @param startedAt the persisted start time
     */
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    /**
     * Returns the persisted finish time for this event, or {@code null} when
     * the row had no {@code completed_at} (e.g. a {@code STARTED} status that
     * never completed). Set by {@link JobStatsStore#rowToEvent}.
     *
     * @return the persisted finish time, or {@code null}
     */
    public Instant getFinishedAt() { return finishedAt; }

    /**
     * Sets the persisted finish time. Called by
     * {@link JobStatsStore#rowToEvent}; ignored when {@code null}.
     *
     * @param finishedAt the persisted finish time
     */
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    /**
     * Overwrites both {@link #timestamp} and {@link #eventTime} on the event.
     * Called from {@link JobStatsStore#rowToEvent} so a row reconstructed from
     * the {@code job_timing} table reports the persisted event time instead of
     * the read-time instant. {@code null} is silently ignored so a partially
     * populated row does not erase an existing stamp.
     *
     * @param time the authoritative event time from the persisted row
     */
    public void setTimestamp(Instant time) {
        if (time == null) return;
        this.timestamp = time;
        this.eventTime = time;
    }

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
    /**
     * Returns how the final commit message was produced, or {@code null} for non-Claude jobs.
     * Values: {@code "agent"}, {@code "prompt_fallback"}, {@code "commit_rule_recovered"}.
     */
    public String getCommitMessageSource() { return null; }
    /**
     * Returns the name of the runner that produced this event, or
     * {@code null} for non-coding-agent jobs.
     */
    public String getRunnerName() { return null; }
    /**
     * Returns the per-runner USD cost breakdown for this job, or an empty map
     * for non-coding-agent jobs and events that predate per-runner tracking.
     */
    public Map<String, Double> getCostByRunner() { return costByRunner; }
    /**
     * Returns the per-model USD cost breakdown for this job, or an empty map
     * for non-coding-agent jobs and events that predate per-model tracking.
     */
    public Map<String, Double> getCostByModel() { return costByModel; }

    /**
     * Returns whether the recorded cost is incomplete (a lower bound) because
     * an agent session was killed for inactivity before reporting its cost.
     *
     * @return {@code true} when the true cost is unknown and exceeds the
     *         reported total; {@code false} when the cost is fully accounted for
     */
    public boolean isCostIncomplete() { return costIncomplete; }

    // ---- Public setters for use by JobStatsStore row reconstruction ----

    /**
     * Sets whether the recorded cost is incomplete (a lower bound).
     *
     * @param costIncomplete {@code true} when an inactivity kill lost cost data
     */
    public void setCostIncomplete(boolean costIncomplete) { this.costIncomplete = costIncomplete; }

    /**
     * Sets the total USD cost for this job.
     *
     * @param totalCostUsd the total cost
     */
    public void setTotalCostUsd(double totalCostUsd) { this.totalCostUsd = totalCostUsd; }

    /**
     * Returns the total USD cost for this job.
     *
     * @return the total cost
     */
    public double getTotalCostUsd() { return totalCostUsd; }

    /**
     * Sets the per-runner cost breakdown for this job.
     *
     * @param costByRunner map of runner name to USD cost
     */
    public void setCostByRunner(Map<String, Double> costByRunner) {
        this.costByRunner = costByRunner != null ? Map.copyOf(costByRunner) : Collections.emptyMap();
    }

    /**
     * Sets the per-model cost breakdown for this job.
     *
     * @param costByModel map of provider/model identifier to USD cost
     */
    public void setCostByModel(Map<String, Double> costByModel) {
        this.costByModel = costByModel != null ? Map.copyOf(costByModel) : Collections.emptyMap();
    }

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
        root.put("commitMessageSource", getCommitMessageSource());
        root.put("runnerName", getRunnerName());

        putDoubleMap(root, "costByRunner", getCostByRunner());
        putDoubleMap(root, "costByModel", getCostByModel());
        root.put("costIncomplete", isCostIncomplete());

        try {
            return EVENT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Serialises a {@code Map<String, Double>} as a JSON object child of {@code root}.
     *
     * @param root the parent object node
     * @param key  the field name under which the child object is created
     * @param map  the entries to serialise; {@code null} is treated as empty
     */
    private static void putDoubleMap(ObjectNode root, String key, Map<String, Double> map) {
        ObjectNode node = root.putObject(key);
        if (map == null) return;
        for (Map.Entry<String, Double> e : map.entrySet()) {
            node.put(e.getKey(), e.getValue());
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
