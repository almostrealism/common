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
 * Mutable accumulator for the result of a {@link CodingAgentJob}'s agent
 * sessions.
 *
 * <p>A single job dispatches several agent sessions across its lifecycle
 * (primary work plus correction, review, deduplication, and retrospective
 * phases). This object summarises them: the duration, cost, turn, and
 * permission-denial figures are <em>summed</em> across every session, while
 * the identification fields ({@link #sessionId()}, {@link #subtype()},
 * {@link #exitCode()}, {@link #isError()}) track only the most recent session.
 * The {@link #output()} likewise reflects the last session's raw output.</p>
 *
 * <p>Extracted from {@link CodingAgentJob} so the orchestrator does not carry
 * a dozen loose result fields and their accumulation logic inline.</p>
 */
final class AgentSessionMetrics {

    /** Claude Code session identifier from the most recent session. */
    private String sessionId;
    /** Raw text output produced by the most recent session. */
    private String output = "";
    /** Exit code returned by the most recent session. */
    private int exitCode;
    /** Total wall-clock duration summed across sessions, in milliseconds. */
    private long durationMs;
    /** Time spent in API calls summed across sessions, in milliseconds. */
    private long durationApiMs;
    /** Total cost summed across sessions, in US dollars. */
    private double costUsd;
    /** Number of agentic turns summed across sessions. */
    private int numTurns;
    /** Session subtype / stop reason from the most recent session. */
    private String subtype;
    /** Whether the most recent session flagged its result as an error. */
    private boolean isError;
    /** Number of tool-use permission denials summed across sessions. */
    private int permissionDenials;
    /** Names of tools denied across sessions; {@code null} until the first denial. */
    private List<String> deniedToolNames;

    /** Returns the most recent session identifier, or {@code null} before any run. */
    String sessionId() { return sessionId; }

    /** Returns the most recent session's raw output; never {@code null}. */
    String output() { return output; }

    /** Sets the most recent session's raw output (overwritten each attempt). */
    void setOutput(String output) { this.output = output; }

    /** Returns the most recent session's exit code. */
    int exitCode() { return exitCode; }

    /** Returns the wall-clock duration summed across sessions, in milliseconds. */
    long durationMs() { return durationMs; }

    /** Returns the API-call duration summed across sessions, in milliseconds. */
    long durationApiMs() { return durationApiMs; }

    /** Returns the cost summed across sessions, in US dollars. */
    double costUsd() { return costUsd; }

    /** Returns the agentic-turn count summed across sessions. */
    int numTurns() { return numTurns; }

    /** Returns the most recent session's subtype / stop reason. */
    String subtype() { return subtype; }

    /** Returns whether the most recent session flagged its result as an error. */
    boolean isError() { return isError; }

    /** Returns the number of permission denials summed across sessions. */
    int permissionDenials() { return permissionDenials; }

    /** Returns the denied tool names accumulated across sessions; may be {@code null}. */
    List<String> deniedToolNames() { return deniedToolNames; }

    /**
     * Absorbs {@code result} into the accumulated figures. Identification
     * fields (exit code, session id, stop reason, error flag) are replaced with
     * the latest session's values; duration, cost, turn, and permission-denial
     * figures are summed.
     *
     * @param result the result returned by {@link io.flowtree.jobs.agent.AgentRunner#run}
     */
    void absorb(AgentRunResult result) {
        exitCode = result.exitCode();
        sessionId = result.sessionId();
        subtype = result.stopReason();
        isError = result.sessionIsError();
        durationMs += result.durationMs();
        durationApiMs += result.durationApiMs();
        numTurns += result.numTurns();
        costUsd += result.costUsd();
        if (!result.deniedToolNames().isEmpty()) {
            if (deniedToolNames == null) {
                deniedToolNames = new ArrayList<>();
            }
            deniedToolNames.addAll(result.deniedToolNames());
            permissionDenials += result.deniedToolNames().size();
        }
    }
}
