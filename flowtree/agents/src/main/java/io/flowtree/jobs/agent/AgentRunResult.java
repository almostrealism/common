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

package io.flowtree.jobs.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a single {@link AgentRunner#run(AgentRunRequest, org.almostrealism.io.ConsoleFeatures) run}.
 *
 * <p>Carries the captured exit code, the raw stdout text, accumulated metrics,
 * and any runner-specific metadata such as a session identifier. The
 * orchestrator absorbs these values across phases and rolls them up into the
 * job completion event.</p>
 *
 * @param exitCode             process exit code; {@code -1} on launch failure
 * @param killedForInactivity  {@code true} when the inactivity watchdog fired
 * @param rawOutput            captured stdout (may be partial after a kill)
 * @param sessionId            runner's session identifier; may be {@code null}
 * @param durationMs           total wall-clock duration of the session in ms
 * @param durationApiMs        time spent in API calls in ms; 0 when not separated
 * @param numTurns             number of agentic turns reported by the runner; 0 when not reported
 * @param costUsd              session cost in USD; 0 when not reported
 * @param stopReason           termination tag such as {@code "success"} or {@code "error_max_turns"}
 * @param sessionIsError       whether the runner flagged the session as an error
 * @param deniedToolNames      tool names denied during the session; empty when unsupported
 * @param runnerMetadata       free-form runner-specific data
 * @param unavailableRequiredMcpServers
 *                             names of the MCP servers the request declared required
 *                             (see {@link AgentRunRequest#getRequiredMcpServers()}) that
 *                             the session reported as not connected when it started;
 *                             empty when all connected, or when the runner does not
 *                             report connection status
 */
public record AgentRunResult(
        int exitCode,
        boolean killedForInactivity,
        String rawOutput,
        String sessionId,
        long durationMs,
        long durationApiMs,
        int numTurns,
        double costUsd,
        String stopReason,
        boolean sessionIsError,
        List<String> deniedToolNames,
        Map<String, String> runnerMetadata,
        List<String> unavailableRequiredMcpServers) {

    /**
     * {@code stopReason} value a runner sets when the loop detector killed the
     * session for repeated non-progressing actions. Shared across runners so a
     * loop kill has one canonical shape on the result regardless of which CLI
     * produced it; see {@link #wasKilledForLooping()}.
     */
    public static final String STOP_LOOP_DETECTED = "error_loop_detected";

    /**
     * How an agent session ended, classified from the exit code, the watchdog
     * flags, and whether the process did measurable work.
     *
     * <p>This distinguishes a session that <em>crashed instantly</em> from one
     * that <em>ran and was killed</em> — both of which a self-reported duration
     * of {@code 0} would conflate — so a killed-but-productive session can be
     * graded by what it delivered rather than by a self-report it never got to
     * write. The exit code alone is not enough: under the tmux launch path a
     * session killed after real work also reports {@code -1}, so a
     * {@linkplain #isSignalLikeExit(int) signal-like} exit is
     * {@link #COULD_NOT_START} only when it shows neither runtime nor output.</p>
     */
    public enum EndState {
        /** Exited with code {@code 0}. */
        CLEAN,
        /**
         * No work was performed, the genuine nothing-delivered case that a job
         * grades as a hard failure: either a launch failure (a signal-like exit
         * with no runtime and no output), or an instant crash — an ordinary
         * non-zero exit that never reported a duration, which a session that
         * actually worked always does before exiting of its own accord.
         */
        COULD_NOT_START,
        /** The inactivity watchdog killed the process tree. */
        KILLED_INACTIVITY,
        /** The loop detector killed the process tree for repeated actions. */
        KILLED_LOOP,
        /**
         * Killed by an external signal after doing measurable work — a SIGTERM
         * (143) the harness did not initiate, a SIGKILL/OOM (137), or a
         * tmux-path kill of unknown origin ({@code -1}/{@code 129}) — none of
         * them attributed to the inactivity or loop watchdogs.
         */
        KILLED_SIGNAL,
        /**
         * Ran, then exited non-zero of its own accord. A normal failure the
         * recovery path may still rescue, not a signal kill and not a
         * could-not-start.
         */
        FAILED
    }

    /**
     * Canonical compact constructor that defensively normalises the collections
     * so consumers can iterate them without null checks.
     */
    public AgentRunResult {
        rawOutput = rawOutput == null ? "" : rawOutput;
        deniedToolNames = deniedToolNames == null
                ? Collections.emptyList()
                : List.copyOf(deniedToolNames);
        runnerMetadata = runnerMetadata == null
                ? Collections.emptyMap()
                : Map.copyOf(runnerMetadata);
        unavailableRequiredMcpServers = unavailableRequiredMcpServers == null
                ? Collections.emptyList()
                : List.copyOf(unavailableRequiredMcpServers);
    }

    /**
     * The result of a session whose runner does not report MCP connection
     * status (or reported every required server connected).
     */
    public AgentRunResult(int exitCode,
                          boolean killedForInactivity,
                          String rawOutput,
                          String sessionId,
                          long durationMs,
                          long durationApiMs,
                          int numTurns,
                          double costUsd,
                          String stopReason,
                          boolean sessionIsError,
                          List<String> deniedToolNames,
                          Map<String, String> runnerMetadata) {
        this(exitCode, killedForInactivity, rawOutput, sessionId, durationMs, durationApiMs,
                numTurns, costUsd, stopReason, sessionIsError, deniedToolNames, runnerMetadata,
                Collections.emptyList());
    }

    /**
     * Whether a required MCP server was unavailable when the session started.
     *
     * <p>A session that ran without one is not a degraded success but a
     * failed one: every instruction that depends on that server — reporting
     * progress, storing memories, reading a pull request's review — was
     * silently impossible, and the model may have substituted whatever else
     * it could find. The job treats this as a failure and does not keep the
     * session's work.</p>
     *
     * @return {@code true} when {@link #unavailableRequiredMcpServers()} is non-empty
     */
    public boolean hasUnavailableRequiredMcpServer() {
        return !unavailableRequiredMcpServers.isEmpty();
    }

    /**
     * A one-line description of the unavailable required servers, for a
     * job-failure reason or a status message.
     *
     * @return the description, or an empty string when none were unavailable
     */
    public String describeUnavailableRequiredMcpServers() {
        if (unavailableRequiredMcpServers.isEmpty()) return "";
        return "Required MCP server(s) unavailable when the agent session started: "
                + String.join(", ", unavailableRequiredMcpServers)
                + " — the agent ran without them, so its output is not trusted";
    }

    /**
     * Whether the loop detector killed this session for repeated
     * non-progressing actions, carried on the result as the
     * {@link #STOP_LOOP_DETECTED} stop reason.
     *
     * @return {@code true} when the session was killed for looping
     */
    public boolean wasKilledForLooping() {
        return STOP_LOOP_DETECTED.equals(stopReason);
    }

    /**
     * Whether the session was killed by a signal after doing measurable work.
     *
     * @return {@code true} when {@link #endState()} is {@link EndState#KILLED_SIGNAL}
     */
    public boolean isKilledBySignal() {
        return endState() == EndState.KILLED_SIGNAL;
    }

    /**
     * Whether a kill ended the session before it could emit its terminal cost,
     * so the reported cost is a lower bound. True for an inactivity, loop, or
     * signal kill; a job marks its cost incomplete on any of these.
     *
     * @return {@code true} when the kill lost cost data
     */
    public boolean lostCostBeforeReporting() {
        EndState state = endState();
        return state == EndState.KILLED_INACTIVITY
                || state == EndState.KILLED_LOOP
                || state == EndState.KILLED_SIGNAL;
    }

    /**
     * Classifies how this session ended; see {@link EndState}.
     *
     * <p>The watchdog flags are consulted first so a kill the harness itself
     * triggered is never mistaken for an external one. A
     * {@linkplain #isSignalLikeExit(int) signal-like} exit with runtime or
     * output is {@link EndState#KILLED_SIGNAL}, which is what separates a
     * tmux-path kill from an instant launch failure that also reports
     * {@code -1}. Any other non-zero exit that never reported a duration is
     * {@link EndState#COULD_NOT_START}, even when it printed output.</p>
     *
     * @return the end-state classification
     */
    public EndState endState() {
        if (killedForInactivity) return EndState.KILLED_INACTIVITY;
        if (wasKilledForLooping()) return EndState.KILLED_LOOP;
        if (exitCode == 0) return EndState.CLEAN;
        if (isSignalLikeExit(exitCode) && (durationMs > 0 || !rawOutput.isBlank())) {
            return EndState.KILLED_SIGNAL;
        }
        if (durationMs == 0L) return EndState.COULD_NOT_START;
        return EndState.FAILED;
    }

    /**
     * Whether {@code exitCode} has the shape of a signal termination: above
     * {@code 128} (the shell's {@code 128 + signal} convention) or {@code -1}
     * (a tmux-path kill, or a launch failure when nothing ran). Runners use it
     * to decide when a missing self-reported duration means the session was cut
     * short rather than that it crashed before doing anything.
     *
     * @param exitCode the process exit code
     * @return {@code true} for a signal-like exit code
     */
    public static boolean isSignalLikeExit(int exitCode) {
        return exitCode > 128 || exitCode == -1;
    }

    /**
     * A short human description of how a {@link EndState#KILLED_SIGNAL} session
     * ended, naming only what can be proven from inside the worker JVM.
     *
     * <p>On the direct-process launch path the harness's own kills use
     * {@code SIGKILL} and set {@link #killedForInactivity()}/looping, so a plain
     * {@code SIGTERM} (143) here is external and not harness-initiated; a 137
     * that no watchdog flag claims is a force-kill or OOM candidate; a
     * {@code -1} or {@code 129} ({@code SIGHUP}) with runtime is a tmux-path
     * kill of unknown origin. The specific external agent (cgroup OOM vs node
     * restart vs CLI timeout) cannot be told apart from here and is not
     * guessed.</p>
     *
     * @return the description; an empty string when the session was not
     *         killed by a signal
     */
    public String killCauseDescription() {
        if (!isKilledBySignal()) return "";
        switch (exitCode) {
            case 143:
                return "external SIGTERM (not harness-initiated)";
            case 137:
                return "SIGKILL (force-kill or out-of-memory)";
            case -1:
            case 129:
                return "signal kill of unknown origin (tmux launch path)";
            default:
                return "signal " + (exitCode - 128);
        }
    }

    /**
     * Convenience factory for a launch failure where no process was started.
     *
     * @param reason short description recorded as the {@code stopReason}
     * @return a result with {@code exitCode=-1} and empty output
     */
    public static AgentRunResult launchFailed(String reason) {
        return new AgentRunResult(
                -1, false, "", null,
                0L, 0L, 0, 0.0,
                reason, true,
                Collections.emptyList(),
                Collections.emptyMap());
    }
}
