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

import io.flowtree.JsonFieldExtractor;
import io.flowtree.git.TransientGitException;

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

    /** Block category for a {@link Status#BLOCKED} event, or {@code null}; see {@link BlockReport}. */
    private String blockedCategory;
    /** Agent's free-text block detail for a {@link Status#BLOCKED} event, or {@code null}. */
    private String blockedDetail;
    /** Whether a {@link Status#BLOCKED} claim was corroborated by the harness. */
    private boolean blockedVerified;

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

    /** {@code true} when the falsification phase ran (falsificationEnabled was true and doWork ran it). */
    private boolean falsificationRan;
    /** Number of load-bearing behavioural claims the falsification phase extracted and assessed. */
    private int falsificationClaimsExtracted;
    /** Number of claims the falsification phase assessed as refuted. */
    private int falsificationRefutedCount;
    /** Number of times the falsification phase bounced the job back to primary. */
    private int falsificationBounceCount;
    /** USD cost of the falsification analysis sessions. */
    private double falsificationCostUsd;

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

    /**
     * Creates a {@link Status#BLOCKED} event for a Claude Code job whose agent
     * declared, through the {@code job_report_blocked} tool, that it could not
     * proceed. The block's category, detail, and verification state are carried
     * on the event and summarised into the error-message slot so a reader sees
     * the reason without decoding fields.
     *
     * @param jobId       the job identifier
     * @param description human-readable description of the job
     * @param report      the block the agent reported
     * @return a new event with {@link Status#BLOCKED} status and the block detail
     */
    public static CodingAgentJobEvent blocked(String jobId, String description,
                                              BlockReport report) {
        CodingAgentJobEvent event = new CodingAgentJobEvent(jobId, Status.BLOCKED, description);
        event.blockedCategory = report.getCategory();
        event.blockedDetail = report.getDetail();
        event.blockedVerified = report.isVerified();
        event.setErrorMessage(report.describe());
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

    @Override
    public String getBlockedCategory() {
        return blockedCategory;
    }

    @Override
    public String getBlockedDetail() {
        return blockedDetail;
    }

    @Override
    public boolean isBlockedVerified() {
        return blockedVerified;
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
     * agent session was killed — for inactivity, looping, or by a signal —
     * before reporting its cost.
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
     * Records falsification-phase telemetry on this event.
     *
     * @param ran             whether the falsification phase ran
     * @param claimsExtracted number of load-bearing claims extracted and assessed
     * @param refutedCount    number of claims assessed as refuted
     * @param bounceCount     number of times the job was bounced back to primary
     * @param costUsd         USD cost of the falsification analysis sessions
     * @return this event for chaining
     */
    public CodingAgentJobEvent withFalsificationInfo(boolean ran, int claimsExtracted,
                                                     int refutedCount, int bounceCount, double costUsd) {
        this.falsificationRan = ran;
        this.falsificationClaimsExtracted = claimsExtracted;
        this.falsificationRefutedCount = refutedCount;
        this.falsificationBounceCount = bounceCount;
        this.falsificationCostUsd = costUsd;
        return this;
    }

    /** Returns whether the falsification phase ran during this job. */
    public boolean isFalsificationRan() { return falsificationRan; }

    /** Returns the number of load-bearing claims the falsification phase extracted and assessed. */
    public int getFalsificationClaimsExtracted() { return falsificationClaimsExtracted; }

    /** Returns the number of claims the falsification phase assessed as refuted. */
    public int getFalsificationRefutedCount() { return falsificationRefutedCount; }

    /** Returns the number of times the falsification phase bounced the job back to primary. */
    public int getFalsificationBounceCount() { return falsificationBounceCount; }

    /** Returns the USD cost of the falsification analysis sessions. */
    public double getFalsificationCostUsd() { return falsificationCostUsd; }

    /**
     * Creates the appropriate completion event for the given job outcome.
     *
     * <p>Decides the terminal status from the job and the
     * {@link JobSessionAccumulator} it owns, then populates the event with the
     * job's final execution state via {@link #populateFrom(CodingAgentJob)}.</p>
     *
     * @param job   the job being completed
     * @param error the exception that terminated the job, or {@code null} for normal exit
     * @return the populated event
     */
    static CodingAgentJobEvent forJob(CodingAgentJob job, Exception error) {
        CodingAgentJobEvent event = statusFor(job, error);
        event.populateFrom(job);
        event.foldEnforcementRetirements(job.sessionAccumulator().enforcementRetirements());
        return event;
    }

    /**
     * Folds the enforcement-rule retirement reasons into this event's error
     * message so they travel to the controller and surface in
     * {@code workstream_get_job} and {@code workstream_context}.
     *
     * <p>The reasons are appended, not substituted: when the event already
     * carries a terminal reason (a degraded or failed status), the retirements
     * extend it rather than replace it. When there is nothing to append the
     * message is left exactly as it was, so a clean success keeps its empty
     * message.</p>
     *
     * @param retirements the retirement records accumulated during the job;
     *                    {@code null} or empty leaves the message unchanged
     */
    private void foldEnforcementRetirements(List<String> retirements) {
        if (retirements == null || retirements.isEmpty()) return;
        String summary = String.join("\n", retirements);
        String existing = getErrorMessage();
        setErrorMessage(existing == null || existing.isEmpty()
                ? summary : existing + "\n\n" + summary);
    }

    /**
     * Populates the common fields and then the Claude-specific fields from the
     * JSON {@code body} of a status post, the inverse of what {@link #toJson()}
     * wrote for them. Overrides {@link JobCompletionEvent#populateFromBody(String)}
     * so {@link JobCompletionEvent#fromStatusBody(String, Status, String, String)}
     * reconstructs a coding-agent event polymorphically, without inspecting its
     * runtime type.
     *
     * @param body the raw JSON body of the status post
     */
    @Override
    protected void populateFromBody(String body) {
        super.populateFromBody(body);
        withClaudeCodeInfo(
                JsonFieldExtractor.extractString(body, "prompt"),
                JsonFieldExtractor.extractString(body, "sessionId"),
                JsonFieldExtractor.extractInt(body, "exitCode"));
        withTimingInfo(
                JsonFieldExtractor.extractLong(body, "durationMs"),
                JsonFieldExtractor.extractLong(body, "durationApiMs"),
                JsonFieldExtractor.extractDouble(body, "costUsd"),
                JsonFieldExtractor.extractInt(body, "numTurns"));
        withSessionDetails(
                JsonFieldExtractor.extractString(body, "subtype"),
                JsonFieldExtractor.extractBoolean(body, "sessionIsError"),
                JsonFieldExtractor.extractInt(body, "permissionDenials"),
                JsonFieldExtractor.extractStringArray(body, "deniedToolNames"));
        String commitMessageSource = JsonFieldExtractor.extractString(body, "commitMessageSource");
        if (commitMessageSource != null) {
            withCommitMessageSource(commitMessageSource);
        }
        withCostByRunner(JsonFieldExtractor.extractDoubleObject(body, "costByRunner"));
        withCostByModel(JsonFieldExtractor.extractDoubleObject(body, "costByModel"));
        withCostIncomplete(JsonFieldExtractor.extractBoolean(body, "costIncomplete"));
        this.blockedCategory = JsonFieldExtractor.extractString(body, "blockedCategory");
        this.blockedDetail = JsonFieldExtractor.extractString(body, "blockedDetail");
        this.blockedVerified = JsonFieldExtractor.extractBoolean(body, "blockedVerified");
    }

    /**
     * Creates the event carrying the terminal status of {@code job}, before
     * its execution state is populated.
     *
     * <p>A job that terminated with a {@link TransientGitException} — a sync
     * race, a lock held by a concurrent process, or a commit that could not be
     * written despite staged changes, each after its bounded retries were spent
     * — is reported {@link Status#DEGRADED} with an infrastructure-labelled
     * message rather than {@link Status#FAILED}, so a harness git hiccup is not
     * conflated with an agent failure.</p>
     *
     * <p>A job whose new-type audit could not run ({@link NewTypeAudit#getFailure()})
     * <em>and therefore withheld changed Java files</em> ({@link CodingAgentJob#getNewTypeRefusals()}
     * non-empty) is reported {@link Status#DEGRADED} with the reason: a backstop
     * that did not run must be noticed rather than read as a clean result. A
     * failure that refused nothing — the working tree held no changed Java file
     * for the audit to examine — withheld no work and does not degrade the
     * outcome.</p>
     *
     * <p>A job whose primary session ran and was then killed by a signal —
     * {@link PrimaryPhaseOutcome#killedBySignal()} — but whose work the later
     * phases verified and committed is reported {@link Status#DEGRADED} rather
     * than {@link Status#FAILED}. The kill was external (the harness's own kills
     * are SIGKILL or set a watchdog flag), the tree holds real reviewed work,
     * and this branch is reached before the blanket non-zero-exit and
     * hard-failure branches so that outcome is graded by what it delivered. The
     * reason names the signal cause and the primary's measured duration so the
     * record states what happened rather than a self-reported {@code 0s}, and
     * the event is marked {@link #withAutoPublishWhenDegraded(boolean) auto-publishable}
     * so the controller opens its pull request. When a signal kill committed
     * nothing the outcome stays {@link Status#FAILED}, but the reason still
     * names the signal cause and measured duration rather than the generic
     * {@code exited with code N}, so a killed launch is not mistaken for an
     * ordinary non-zero exit.</p>
     *
     * <p>A job with {@link CodingAgentJob#isEnforceChanges()} on that exited
     * cleanly with nothing committed is reported {@link Status#DEGRADED}. It
     * had to produce a change, and {@link EnforceChangesRule} has spent its
     * re-run budget ({@link CodingAgentJob#getMaxEnforceChangesRetries()},
     * possibly 0) without getting one. It delivered nothing a person can review,
     * and that is made visible rather than reported as success.</p>
     *
     * <p>A job whose agent reported a {@link BlockReport} is {@link Status#BLOCKED},
     * and that outcome is authoritative over the process exit code: the block is
     * a deliberate, controller-recorded declaration that the agent could not
     * proceed, so a non-zero exit, a hard-failure signature, or a signal kill
     * that follows it does not downgrade it to {@link Status#FAILED} or
     * {@link Status#DEGRADED}. Only a genuine worker exception ({@code error}
     * non-{@code null}) takes precedence, because that is the job machinery
     * itself failing rather than the agent reporting its own state.</p>
     *
     * @param job   the job being completed
     * @param error the exception that terminated the job, or {@code null} for normal exit
     * @return the unpopulated event
     */
    private static CodingAgentJobEvent statusFor(CodingAgentJob job, Exception error) {
        JobSessionAccumulator acc = job.sessionAccumulator();
        if (error != null) {
            if (TransientGitException.isTransient(error)) {
                return degraded(job.getTaskId(), job.getTaskString(),
                        TransientGitException.describe(error));
            }
            return failed(job.getTaskId(), job.getTaskString(), error.getMessage(), error);
        }
        if (acc.blockReport() != null) {
            return blocked(job.getTaskId(), job.getTaskString(), acc.blockReport());
        }
        PrimaryPhaseOutcome primary = job.primaryOutcome();
        if (primary.killedBySignal()) {
            if (job.hasPublishedCommit() || job.hasAgentCommitted()) {
                CodingAgentJobEvent event = degraded(job.getTaskId(), job.getTaskString(),
                        primary.describeSignalKill());
                event.withAutoPublishWhenDegraded(true);
                return event;
            }
            return failed(job.getTaskId(), job.getTaskString(),
                    primary.describeSignalKillWithoutDelivery(), null);
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
        String typeAuditFailure = job.getNewTypeAudit().getFailure();
        if (typeAuditFailure != null && !job.getNewTypeRefusals().isEmpty()) {
            return degraded(job.getTaskId(), job.getTaskString(),
                    "The new-type audit could not run (" + typeAuditFailure
                    + "), so no changed Java file was staged; skipped: "
                    + String.join("; ", job.getSkippedFiles()));
        }
        if (job.workOutcome().allChangesDropped()) {
            return degraded(job.getTaskId(), job.getTaskString(),
                    "All changes were dropped by staging guardrails: "
                    + String.join("; ", job.getSkippedFiles()));
        }
        String unpublished = job.workOutcome().describeUnpublishedWork();
        if (unpublished != null) {
            return failed(job.getTaskId(), job.getTaskString(), unpublished, null);
        }
        if (job.isEnforceChanges() && job.workOutcome().publishedNothing()) {
            return degraded(job.getTaskId(), job.getTaskString(),
                    "enforceChanges required a change, but the result was empty: nothing was committed on "
                    + job.getTargetBranch() + " after " + job.getEnforcementAttempt() + " of "
                    + job.getMaxEnforceChangesRetries() + " permitted re-run(s) of the agent.");
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
     * <p>Called from {@link #forJob(CodingAgentJob, Exception)}. The cost maps
     * are snapshotted at call time so the event is an immutable record of the
     * job's final state.</p>
     *
     * @param job job that produced this event
     */
    private void populateFrom(CodingAgentJob job) {
        JobSessionAccumulator acc = job.sessionAccumulator();
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
        RetrospectivePhase retrospective = job.retrospectivePhase();
        if (retrospective.ran()) {
            withReflectionInfo(true, retrospective.costUsd(),
                    retrospective.transcriptFound(), retrospective.findingsCount());
            withReflectionContextUsage(
                    retrospective.contextUpfrontTokenEstimate(),
                    retrospective.contextPressureEvents());
        }
        FalsificationPhase falsification = job.falsificationPhase();
        if (falsification.ran()) {
            withFalsificationInfo(true, falsification.claimsExtracted(),
                    falsification.refutedCount(), falsification.bounceCount(),
                    falsification.costUsd());
        }
    }
}
