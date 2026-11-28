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

import io.flowtree.jobs.agent.AgentRunResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the session-level result state across all phase runs within a single
 * {@link CodingAgentJob}: primary phase, correction sessions, and retrospective.
 *
 * <p>Fields fall into two categories:</p>
 * <ul>
 *   <li><b>Latest</b> — overwritten on each {@link #absorb(AgentRunResult)} call:
 *       exit code, session ID, stop reason, and error flag.</li>
 *   <li><b>Cumulative</b> — summed across all runs: wall-clock and API duration,
 *       turn count, USD cost, permission-denial count, and denied tool list.</li>
 * </ul>
 *
 * <p>The accumulator is owned by a single {@link CodingAgentJob} and is not
 * thread-safe. It is populated by
 * {@link CodingAgentJob#absorbResult(AgentRunResult)} after each session run
 * and read by {@link CodingAgentJobEvent#forJob} at job completion.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob
 * @see CodingAgentJobEvent
 */
class JobSessionAccumulator {

    /** Raw text output produced by the most recent session run (latest only). */
    private String output = "";

    /** Exit code returned by the most recent session run. */
    private int exitCode;

    /** Claude Code session identifier assigned during the most recent run. */
    private String sessionId;

    /** Stop reason / subtype reported by the most recent run (e.g. {@code "success"}). */
    private String subtype;

    /** Whether the most recent run was flagged as a session error by Claude Code. */
    private boolean isError;

    /** Total wall-clock duration accumulated across all phase runs, in milliseconds. */
    private long durationMs;

    /** Wall-clock duration of the most recent session run alone, in milliseconds (latest only). */
    private long lastDurationMs;

    /** How the most recent session run ended; see {@link AgentRunResult#endState()}. */
    private AgentRunResult.EndState lastEndState = AgentRunResult.EndState.CLEAN;

    /** Signal-kill cause of the most recent run, or {@code ""} when it was not a signal kill. */
    private String lastKillCause = "";

    /** Total API-call duration accumulated across all phase runs, in milliseconds. */
    private long durationApiMs;

    /** Total number of agentic turns accumulated across all phase runs. */
    private int numTurns;

    /** Total USD cost accumulated across all phase runs. */
    private double costUsd;

    /** Total input tokens accumulated across all phase runs. */
    private long inputTokens;

    /** Total output tokens accumulated across all phase runs. */
    private long outputTokens;

    /** Total cache-read tokens accumulated across all phase runs. */
    private long cacheReadTokens;

    /** Total cache-write tokens accumulated across all phase runs. */
    private long cacheWriteTokens;

    /** Total number of tool-use permission denials accumulated across all phase runs. */
    private int permissionDenials;

    /** Names of tools denied across all phase runs; {@code null} until the first denial. */
    private List<String> deniedToolNames;

    /**
     * The block the agent reported for this job, or {@code null} when it did not
     * report one. Set by the worker after it reads the controller's record (see
     * {@link BlockReport#fetch(String, String, boolean)}); read by {@link EnforcementRunner}
     * to skip retries and by {@link CodingAgentJobEvent} to report the terminal
     * {@link JobCompletionEvent.Status#BLOCKED} status.
     */
    private BlockReport blockReport;

    /**
     * Human-readable records of enforcement rules retired during this job, each
     * naming the rule and why it was retired. Appended by {@link EnforcementRunner}
     * when it stops driving a rule — because the violation was environment-determined
     * (never retried), because correction attempts stopped making progress, or
     * because a retry cap was exhausted — and read by {@link CodingAgentJobEvent#forJob}
     * so the reasons travel on the completion event. Empty when no rule was retired.
     */
    private final List<String> enforcementRetirements = new ArrayList<>();

    /**
     * Absorbs the result of one agent session run into the accumulated state.
     *
     * <p>Latest-value fields ({@link #exitCode}, {@link #sessionId}, {@link #subtype},
     * {@link #isError}) are overwritten; cumulative fields ({@link #durationMs},
     * {@link #durationApiMs}, {@link #numTurns}, {@link #costUsd},
     * {@link #permissionDenials}, {@link #deniedToolNames}) are summed or appended.</p>
     *
     * @param result result returned by {@link io.flowtree.jobs.agent.AgentRunner#run}
     */
    void absorb(AgentRunResult result) {
        exitCode = result.exitCode();
        sessionId = result.sessionId();
        subtype = result.stopReason();
        isError = result.sessionIsError();
        durationMs += result.durationMs();
        lastDurationMs = result.durationMs();
        lastEndState = result.endState();
        lastKillCause = result.killCauseDescription();
        durationApiMs += result.durationApiMs();
        numTurns += result.numTurns();
        costUsd += result.costUsd();
        inputTokens += result.inputTokens();
        outputTokens += result.outputTokens();
        cacheReadTokens += result.cacheReadTokens();
        cacheWriteTokens += result.cacheWriteTokens();
        if (!result.deniedToolNames().isEmpty()) {
            if (deniedToolNames == null) {
                deniedToolNames = new ArrayList<>();
            }
            deniedToolNames.addAll(result.deniedToolNames());
            permissionDenials += result.deniedToolNames().size();
        }
    }

    /** Returns the raw output from the most recent session run; never {@code null}. */
    String getOutput() { return output; }

    /** Sets the raw output of the most recent session run (overwritten each attempt). */
    void setOutput(String output) { this.output = output; }

    /** Returns the exit code from the most recent session run. */
    int getExitCode() { return exitCode; }

    /** Returns the Claude Code session ID from the most recent run. */
    String getSessionId() { return sessionId; }

    /** Returns the stop reason / subtype from the most recent run. */
    String getSubtype() { return subtype; }

    /** Returns whether the most recent run was flagged as a session error. */
    boolean isError() { return isError; }

    /** Returns the cumulative wall-clock duration in milliseconds. */
    long getDurationMs() { return durationMs; }

    /** Returns the wall-clock duration of the most recent session run alone, in milliseconds. */
    long getLastDurationMs() { return lastDurationMs; }

    /** Returns how the most recent session run ended. */
    AgentRunResult.EndState getLastEndState() { return lastEndState; }

    /** Returns the signal-kill cause of the most recent run, or {@code ""} when it was not a signal kill. */
    String getLastKillCause() { return lastKillCause; }

    /** Returns the cumulative API-call duration in milliseconds. */
    long getDurationApiMs() { return durationApiMs; }

    /** Returns the cumulative number of agentic turns. */
    int getNumTurns() { return numTurns; }

    /** Returns the cumulative USD cost. */
    double getCostUsd() { return costUsd; }

    /** Returns the cumulative input tokens. */
    long getInputTokens() { return inputTokens; }

    /** Returns the cumulative output tokens. */
    long getOutputTokens() { return outputTokens; }

    /** Returns the cumulative cache-read tokens. */
    long getCacheReadTokens() { return cacheReadTokens; }

    /** Returns the cumulative cache-write tokens. */
    long getCacheWriteTokens() { return cacheWriteTokens; }

    /** Returns the cumulative number of tool-use permission denials. */
    int getPermissionDenials() { return permissionDenials; }

    /** Returns the list of denied tool names, or {@code null} if no denials occurred. */
    List<String> getDeniedToolNames() { return deniedToolNames; }

    /** Returns the block the agent reported, or {@code null} when none was reported. */
    BlockReport blockReport() { return blockReport; }

    /**
     * Records the block the agent reported for this job, or clears it when
     * {@code blockReport} is {@code null} (the job was not reported blocked).
     *
     * @param blockReport the reported block, or {@code null}
     */
    void setBlockReport(BlockReport blockReport) { this.blockReport = blockReport; }

    /**
     * Records that an enforcement rule was retired, with a message naming the
     * rule and why. Called by {@link EnforcementRunner}; the recorded reasons
     * are folded into the completion event by {@link CodingAgentJobEvent#forJob}.
     *
     * @param retirement the retirement message; ignored when {@code null} or blank
     */
    void recordEnforcementRetirement(String retirement) {
        if (retirement != null && !retirement.isBlank()
                && !enforcementRetirements.contains(retirement)) {
            enforcementRetirements.add(retirement);
        }
    }

    /**
     * Returns the enforcement-rule retirement records accumulated during this
     * job, in the order they occurred.
     *
     * @return the retirement messages; empty when no rule was retired
     */
    List<String> enforcementRetirements() { return enforcementRetirements; }
}
