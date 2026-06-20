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
import java.util.Map;

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
public class CodingAgentJobEvent extends JobCompletionEvent {

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
     * How the final commit message was produced.
     * {@code "agent"} — the agent wrote a valid {@code commit.txt}.
     * {@code "prompt_fallback"} — the harness fell back to using the task prompt.
     * {@code "commit_rule_recovered"} — {@link CommitMessageRule} recovered the message.
     */
    private String commitMessageSource;

    /**
     * Name of the {@link io.flowtree.jobs.agent.AgentRunner} that ran this
     * job's sessions. {@code null} for events emitted before the pluggable
     * agent refactor.
     */
    private String runnerName;

    /**
     * {@code true} when the post-completion command exhausted its per-job pass
     * cap without ever exiting zero. Distinguishes "command succeeded after N
     * passes" from "command failed and the gate was abandoned".
     */
    private boolean postCompletionCapHit;

    /** Number of files modified during the review phase ({@code 0} when the phase did not run). */
    private int reviewFilesModified;
    /** Number of {@code memory_store} calls observed during the review phase. */
    private int reviewMemoriesStored;
    /** {@code true} when the review session ran AND exited with code 0. */
    private boolean reviewExitedCleanly;
    /** {@code true} when at least one review session ran during this job. */
    private boolean reviewRan;

    /** {@code true} when the retrospective phase ran (retrospectiveEnabled was true and doWork ran it). */
    private boolean reflectionRan;
    /** USD cost of the retrospective session, accumulated via absorbResult() into costByRunner/costByModel. */
    private double reflectionCostUsd;
    /** {@code true} when the retrospective agent found and analyzed a primary-phase transcript. */
    private boolean reflectionTranscriptFound;
    /** Number of improvement findings emitted as memories by the retrospective agent. */
    private int reflectionFindingsCount;
    /** Estimated token cost of system prompt + standing instructions + job prompt consumed before the agent acted. */
    private int reflectionContextUpfrontTokenEstimate;
    /** Number of times the primary session had to compact or summarize context. */
    private int reflectionContextPressureEvents;

    /**
     * Creates a new Claude Code job completion event.
     *
     * @param jobId       the job identifier
     * @param status      the completion status
     * @param description human-readable description of the job
     */
    public CodingAgentJobEvent(String jobId, Status status, String description) {
        super(jobId, status, description);
    }

    /**
     * Creates a success event for a Claude Code job.
     *
     * @param jobId       the job identifier
     * @param description human-readable description of the job
     * @return a new event with {@link Status#SUCCESS} status
     */
    public static CodingAgentJobEvent success(String jobId, String description) {
        return new CodingAgentJobEvent(jobId, Status.SUCCESS, description);
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
    public static CodingAgentJobEvent failed(String jobId, String description,
                                            String errorMessage, Throwable exception) {
        CodingAgentJobEvent event = new CodingAgentJobEvent(jobId, Status.FAILED, description);
        event.setErrorMessage(errorMessage);
        event.setException(exception);
        return event;
    }

    /**
     * Creates a {@link Status#DEGRADED} event for a Claude Code job whose
     * process exited cleanly but which left started work unfinished — most
     * commonly an ar-test-runner run that the agent never polled to a
     * terminal status before ending its turn.
     *
     * @param jobId        the job identifier
     * @param description  human-readable description of the job
     * @param errorMessage diagnostic detail describing what was abandoned
     * @return a new event with {@link Status#DEGRADED} status and detail
     */
    public static CodingAgentJobEvent degraded(String jobId, String description,
                                              String errorMessage) {
        CodingAgentJobEvent event = new CodingAgentJobEvent(jobId, Status.DEGRADED, description);
        event.setErrorMessage(errorMessage);
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
    public CodingAgentJobEvent withClaudeCodeInfo(String prompt, String sessionId, int exitCode) {
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
    public CodingAgentJobEvent withTimingInfo(long durationMs, long durationApiMs,
                                             double costUsd, int numTurns) {
        this.durationMs = durationMs;
        this.durationApiMs = durationApiMs;
        this.costUsd = costUsd;
        setTotalCostUsd(costUsd);
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
    public CodingAgentJobEvent withSessionDetails(String subtype, boolean sessionIsError,
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
    @Override
    public String getPrompt() {
        return prompt;
    }

    /**
     * Returns the Claude Code session identifier.
     *
     * @return the session ID, or null if not set
     */
    @Override
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the process exit code from Claude Code.
     *
     * @return the exit code (0 typically indicates success)
     */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Returns the total wall-clock duration reported by Claude Code.
     *
     * @return duration in milliseconds
     */
    @Override
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Returns the time spent in API calls during the Claude Code session.
     *
     * @return API duration in milliseconds
     */
    @Override
    public long getDurationApiMs() {
        return durationApiMs;
    }

    /**
     * Returns the total cost of the Claude Code session in USD.
     *
     * @return cost in USD
     */
    @Override
    public double getCostUsd() {
        return costUsd;
    }

    /**
     * Returns the number of agentic turns in the Claude Code session.
     *
     * @return the turn count
     */
    @Override
    public int getNumTurns() {
        return numTurns;
    }

    /**
     * Returns the session subtype (stop reason) from Claude Code output.
     * Common values: "success", "error_max_turns".
     *
     * @return the subtype string, or null if not set
     */
    @Override
    public String getSubtype() {
        return subtype;
    }

    /**
     * Returns whether the Claude Code session ended with an error.
     *
     * @return true if the session was flagged as an error
     */
    @Override
    public boolean isSessionError() {
        return sessionIsError;
    }

    /**
     * Returns the number of permission denials during the session.
     *
     * @return the denial count
     */
    @Override
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
    @Override
    public List<String> getDeniedToolNames() {
        return deniedToolNames != null ? deniedToolNames : Collections.emptyList();
    }

    /**
     * Sets the commit message source tag.
     *
     * @param commitMessageSource one of {@code "agent"}, {@code "prompt_fallback"},
     *                            or {@code "commit_rule_recovered"}
     * @return this event for chaining
     */
    public CodingAgentJobEvent withCommitMessageSource(String commitMessageSource) {
        this.commitMessageSource = commitMessageSource;
        return this;
    }

    /**
     * Returns how the final commit message was produced for this job.
     *
     * @return the commit message source tag, or {@code null} if not set
     */
    @Override
    public String getCommitMessageSource() {
        return commitMessageSource;
    }

    /**
     * Sets the name of the runner that produced this event.
     *
     * @param runnerName the runner identifier (e.g.
     *                   {@link io.flowtree.jobs.agent.AgentRunnerRegistry#CLAUDE})
     * @return this event for chaining
     */
    public CodingAgentJobEvent withRunnerName(String runnerName) {
        this.runnerName = runnerName;
        return this;
    }

    /**
     * Returns the name of the runner that produced this event, or
     * {@code null} if not populated.
     *
     * @return the runner identifier
     */
    @Override
    public String getRunnerName() {
        return runnerName;
    }

    /**
     * Sets the per-runner cost breakdown for this job.
     *
     * @param costByRunner map of runner name to cumulative USD cost; a
     *                     {@code null} value is treated as an empty map
     * @return this event for chaining
     */
    public CodingAgentJobEvent withCostByRunner(Map<String, Double> costByRunner) {
        setCostByRunner(costByRunner);
        return this;
    }

    /**
     * Sets the per-model cost breakdown for this job.
     *
     * @param costByModel map of provider/model identifier to cumulative USD cost;
     *                    a {@code null} value is treated as an empty map
     * @return this event for chaining
     */
    public CodingAgentJobEvent withCostByModel(Map<String, Double> costByModel) {
        setCostByModel(costByModel);
        return this;
    }

    /**
     * Marks whether the recorded cost is incomplete (a lower bound) because an
     * agent session was killed for inactivity before reporting its cost.
     *
     * @param costIncomplete {@code true} when the true cost is unknown and
     *                       exceeds the reported total
     * @return this event for chaining
     */
    public CodingAgentJobEvent withCostIncomplete(boolean costIncomplete) {
        setCostIncomplete(costIncomplete);
        return this;
    }

    /**
     * Records whether the post-completion command gate was abandoned because the
     * per-job pass cap was exhausted without a successful exit.
     *
     * @param postCompletionCapHit {@code true} when the cap was hit without success
     * @return this event for chaining
     */
    public CodingAgentJobEvent withPostCompletionCapHit(boolean postCompletionCapHit) {
        this.postCompletionCapHit = postCompletionCapHit;
        return this;
    }

    /**
     * Returns {@code true} when the post-completion command gate was abandoned because
     * the per-job pass cap was exhausted without a successful exit.
     *
     * @return {@code true} if the gate was abandoned
     */
    public boolean isPostCompletionCapHit() {
        return postCompletionCapHit;
    }

    /**
     * Records review-phase telemetry on this event.
     *
     * @param ran           whether the review phase ran at all
     * @param filesModified number of files modified during the review session
     * @param memoriesStored number of {@code memory_store} calls during the review session
     * @param exitedCleanly whether the review session exited with code 0
     * @return this event for chaining
     */
    public CodingAgentJobEvent withReviewInfo(boolean ran, int filesModified,
                                              int memoriesStored, boolean exitedCleanly) {
        this.reviewRan = ran;
        this.reviewFilesModified = filesModified;
        this.reviewMemoriesStored = memoriesStored;
        this.reviewExitedCleanly = exitedCleanly;
        return this;
    }

    /** Returns the number of files modified during the review phase. */
    public int getReviewFilesModified() { return reviewFilesModified; }

    /** Returns the number of {@code memory_store} calls observed during the review phase. */
    public int getReviewMemoriesStored() { return reviewMemoriesStored; }

    /** Returns whether the review session exited cleanly (and ran at all). */
    public boolean isReviewExitedCleanly() { return reviewExitedCleanly; }

    /** Returns whether the review phase ran at any point during this job. */
    public boolean isReviewRan() { return reviewRan; }

    /**
     * Records retrospective-phase telemetry on this event.
     *
     * @param ran              whether the retrospective phase ran
     * @param costUsd          USD cost of the retrospective session
     * @param transcriptFound  whether a primary-phase transcript was found and analyzed
     * @param findingsCount    number of improvement findings stored as memories
     * @return this event for chaining
     */
    public CodingAgentJobEvent withReflectionInfo(boolean ran, double costUsd,
                                                   boolean transcriptFound, int findingsCount) {
        this.reflectionRan = ran;
        this.reflectionCostUsd = costUsd;
        this.reflectionTranscriptFound = transcriptFound;
        this.reflectionFindingsCount = findingsCount;
        return this;
    }

    /**
     * Records the two new context-usage figures on this event. Called
     * alongside {@link #withReflectionInfo}; either call can come first.
     *
     * @param contextUpfrontTokenEstimate  ballpark token cost of the
     *     system prompt + standing instructions + job prompt consumed
     *     before the primary agent acted
     * @param contextPressureEvents        number of times the primary
     *     session had to compact or summarize context mid-session
     * @return this event for chaining
     */
    public CodingAgentJobEvent withReflectionContextUsage(int contextUpfrontTokenEstimate,
                                                           int contextPressureEvents) {
        this.reflectionContextUpfrontTokenEstimate = contextUpfrontTokenEstimate;
        this.reflectionContextPressureEvents = contextPressureEvents;
        return this;
    }

    /** Returns whether the retrospective phase ran during this job. */
    public boolean isReflectionRan() { return reflectionRan; }

    /** Returns the USD cost of the retrospective session. */
    public double getReflectionCostUsd() { return reflectionCostUsd; }

    /** Returns whether the retrospective agent found and analyzed a primary-phase transcript. */
    public boolean isReflectionTranscriptFound() { return reflectionTranscriptFound; }

    /** Returns the number of improvement findings emitted as memories by the retrospective agent. */
    public int getReflectionFindingsCount() { return reflectionFindingsCount; }

    /**
     * Returns the agent's ballpark token estimate of the upfront context
     * cost (system prompt + standing instructions + job prompt) consumed
     * before the primary agent acted. 0 when not reported.
     */
    public int getReflectionContextUpfrontTokenEstimate() { return reflectionContextUpfrontTokenEstimate; }

    /**
     * Returns the number of times the primary session had to compact or
     * summarize context. 0 when not reported.
     */
    public int getReflectionContextPressureEvents() { return reflectionContextPressureEvents; }

    /**
     * Creates the appropriate completion event for the given job outcome.
     *
     * <p>Encapsulates the terminal-state logic previously in
     * {@code CodingAgentJob.createEvent}, now expressed in terms of the public
     * job API and the {@link JobSessionAccumulator} so that
     * {@link CodingAgentJob} does not need to hold a copy of this decision tree.</p>
     *
     * @param job   the job being completed
     * @param acc   accumulated session state across all runs of the job
     * @param error the exception that terminated the job, or {@code null} for normal exit
     * @return the appropriate {@link JobCompletionEvent} subtype
     */
    static JobCompletionEvent forJob(CodingAgentJob job, JobSessionAccumulator acc, Exception error) {
        if (error != null) {
            return failed(job.getTaskId(), job.getTaskString(), error.getMessage(), error);
        }
        if (job.isPrimaryPhaseHardFailed() && acc.getExitCode() == 0) {
            return failed(job.getTaskId(), job.getTaskString(),
                    "Primary phase hard-failed (non-zero exit, 0s duration, no work performed)", null);
        }
        if (acc.getExitCode() != 0) {
            return failed(job.getTaskId(), job.getTaskString(),
                    "Claude Code exited with code " + acc.getExitCode(), null);
        }
        if (job.isPostCompletionCapHit()) {
            return degraded(job.getTaskId(), job.getTaskString(),
                    "Post-completion command did not exit zero within "
                    + job.getMaxPostCompletionPasses()
                    + " pass(es) — gate abandoned, work may be incomplete");
        }
        List<String> abandoned = AbandonedTestRunDetector.findAbandonedRunsForJob(
                job.getWorkingDirectory(), job.getSessionStartedAt());
        if (abandoned.isEmpty()) {
            return success(job.getTaskId(), job.getTaskString());
        }
        return degraded(job.getTaskId(), job.getTaskString(),
                "Agent abandoned " + abandoned.size() + " test-runner run(s): "
                + String.join(", ", abandoned));
    }

    /**
     * Populates this event with the final execution state of the given job.
     *
     * <p>Called from {@link CodingAgentJob#populateEventDetails(JobCompletionEvent)}.
     * The cost maps are snapshotted at call time so the event is an immutable
     * record of the job's final state.</p>
     *
     * @param job job that produced this event
     * @param acc accumulated session state across all runs of the job
     */
    void populateFrom(CodingAgentJob job, JobSessionAccumulator acc) {
        withClaudeCodeInfo(job.getPrompt(), acc.getSessionId(), acc.getExitCode());
        withTimingInfo(acc.getDurationMs(), acc.getDurationApiMs(), acc.getCostUsd(), acc.getNumTurns());
        withSessionDetails(acc.getSubtype(), acc.isError(), acc.getPermissionDenials(), acc.getDeniedToolNames());
        if (job.getCommitMessageSource() != null) {
            withCommitMessageSource(job.getCommitMessageSource());
        }
        withRunnerName(job.getRunnerName());
        JobCostTracker costs = job.getCostTracker();
        withCostByRunner(costs.snapshotByRunner());
        withCostByModel(costs.snapshotByModel());
        withCostIncomplete(costs.isIncomplete());
        if (job.isPostCompletionCapHit()) {
            withPostCompletionCapHit(true);
        }
        if (job.isReviewRan()) {
            withReviewInfo(true, job.getReviewFilesModified(),
                    job.getReviewMemoriesStored(), job.isReviewExitedCleanly());
        }
        if (job.isRetrospectiveRan()) {
            withReflectionInfo(true, job.getRetrospectiveCostUsd(),
                    job.isRetrospectiveTranscriptFound(), job.getRetrospectiveFindingsCount());
            withReflectionContextUsage(
                    job.getRetrospectiveContextUpfrontTokenEstimate(),
                    job.getRetrospectiveContextPressureEvents());
        }
    }
}
