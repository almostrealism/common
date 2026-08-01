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

import java.util.function.IntFunction;

/**
 * Single authority over <em>every</em> decision to launch, re-launch, or stop
 * an agent session within one {@link CodingAgentJob}.
 *
 * <p>Coding-agent jobs restart the agent from several independent paths —
 * the inactivity watchdog, the {@link EnforcementRunner} correction loop
 * (e.g. {@code enforce-changes} re-runs), the git-tampering and binary-file
 * guardrails, and push-conflict resolution. Historically each path carried its
 * own ceiling and the per-session {@code maxTurns}/{@code maxBudgetUsd} limits
 * were handed straight to the agent subprocess, so a job whose agent never
 * produced a usable change could launch dozens of fresh full-budget sessions
 * before any limit tripped. A single job could therefore spend many multiples
 * of its configured dollar budget while making no progress.</p>
 *
 * <p>This collaborator closes that gap by becoming the one chokepoint through
 * which all sessions pass. Before each launch {@link #beginSession()} is
 * consulted; once any stop condition holds it refuses every further launch,
 * so no restart path — present or future — can run away. Three independent,
 * overlapping ceilings are enforced (the first to trip wins):</p>
 *
 * <ol>
 *   <li><b>Global session cap</b> — at most {@link #getMaxTotalSessions()}
 *       agent sessions per job (default {@link #DEFAULT_MAX_TOTAL_SESSIONS}).
 *       This is the "global total number of restarts" backstop.</li>
 *   <li><b>Dollar budget</b> — cumulative session cost may not reach
 *       {@link CodingAgentJob#getMaxBudgetUsd()}. Unlike the per-session
 *       limit handed to the subprocess, this bounds the <em>whole job</em>.
 *       A negative budget disables the check (matching
 *       {@link CodingAgentJob#setMaxBudgetUsd(double)} semantics).</li>
 *   <li><b>Turn budget</b> — cumulative agentic turns may not reach
 *       {@link #getMaxTotalTurns()} (default
 *       {@link #DEFAULT_MAX_TOTAL_TURNS}); {@code 0} disables the check.</li>
 * </ol>
 *
 * <p>The very first session of a job is always permitted regardless of the
 * ceilings, so a job always performs its primary work; the stop conditions
 * gate only the restarts that follow.</p>
 *
 * <p>The governor also owns the inactivity-restart relaunch loop
 * ({@link #runWithInactivityRetries(String, IntFunction)}) so that all
 * restart accounting lives in one place. Cumulative cost and turn totals are
 * read from the job's {@link JobSessionAccumulator}, which the job populates
 * after every session via {@link CodingAgentJob#absorbResult}.</p>
 *
 * <p>Lives in {@code io.flowtree.jobs} so it can reach the job's package-private
 * run state without widening the public API. Not thread-safe; owned by a single
 * job.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob#executeSingleRun()
 * @see EnforcementRunner
 */
class RestartGovernor {

    /**
     * Default hard ceiling on the total number of agent sessions a single job
     * may launch across every restart path. Generous enough that a healthy
     * multi-phase job (primary, review, dedup, falsification, retrospective,
     * plus a few enforcement corrections) never trips it, but finite so a
     * chronically unproductive agent cannot spin forever.
     */
    static final int DEFAULT_MAX_TOTAL_SESSIONS = 30;

    /**
     * Default job-wide ceiling on cumulative agentic turns. With a typical
     * per-session limit of 50 turns this allows roughly twenty full sessions
     * of work before the turn budget alone halts further restarts. Acts as a
     * backstop independent of the session-count and dollar ceilings; {@code 0}
     * disables it.
     */
    static final int DEFAULT_MAX_TOTAL_TURNS = 1000;

    /**
     * Default maximum number of times the agent subprocess is relaunched within
     * a single logical session after the inactivity watchdog kills it. A value
     * of {@code 3} permits up to four total launches per session.
     */
    static final int DEFAULT_MAX_INACTIVITY_RESTARTS = 3;

    /** The job whose session launches this governor authorizes and counts. */
    private final CodingAgentJob job;

    /** Hard ceiling on total session launches for the job. */
    private int maxTotalSessions = DEFAULT_MAX_TOTAL_SESSIONS;

    /** Job-wide cumulative turn ceiling; {@code 0} disables the turn check. */
    private int maxTotalTurns = DEFAULT_MAX_TOTAL_TURNS;

    /** Maximum inactivity-triggered relaunches within one logical session. */
    private int maxInactivityRestarts = DEFAULT_MAX_INACTIVITY_RESTARTS;

    /** Number of logical sessions launched so far for this job. */
    private int sessionsLaunched;

    /** Current inactivity-restart attempt index; {@code 0} outside the relaunch loop. */
    private int inactivityRestartAttempt;

    /** Set by the relaunch loop when the watchdog kills the most recent attempt. */
    private volatile boolean wasKilledForInactivity;

    /** Human-readable reason the most recent {@link #beginSession()} was refused, or {@code null}. */
    private String lastBlockReason;

    /**
     * Creates a governor bound to the given job.
     *
     * @param job the job whose session launches this governor controls
     */
    RestartGovernor(CodingAgentJob job) {
        this.job = job;
    }

    /**
     * Returns whether another agent session may be launched right now without
     * tripping any stop condition. The first session of a job is always
     * permitted. Does not claim a launch slot; {@link #beginSession()} is the
     * method that actually records a launch.
     *
     * @return {@code true} if a session may launch; {@code false} if a ceiling
     *         has been reached (see {@link #blockReason()})
     */
    boolean canLaunchSession() {
        if (sessionsLaunched == 0) {
            return true;
        }
        if (sessionsLaunched >= maxTotalSessions) {
            lastBlockReason = "global session cap (" + maxTotalSessions
                    + ") reached after " + sessionsLaunched + " sessions";
            return false;
        }
        double budget = job.getMaxBudgetUsd();
        double spent = cumulativeCostUsd();
        if (budget >= 0 && spent >= budget) {
            lastBlockReason = "dollar budget ($" + budget + ") reached: spent $" + spent;
            return false;
        }
        int turns = cumulativeTurns();
        if (maxTotalTurns > 0 && turns >= maxTotalTurns) {
            lastBlockReason = "turn budget (" + maxTotalTurns + ") reached: used " + turns + " turns";
            return false;
        }
        return true;
    }

    /**
     * Authorizes and records a new session launch. When a launch is permitted
     * the internal session counter is incremented and {@code true} is returned;
     * when a stop condition holds nothing is mutated and {@code false} is
     * returned, with the reason available via {@link #blockReason()}.
     *
     * <p>Callers that launch agent sessions ({@link CodingAgentJob#executeSingleRun()})
     * must invoke this and skip the launch when it returns {@code false}.</p>
     *
     * @return {@code true} if the caller may launch a session
     */
    boolean beginSession() {
        if (!canLaunchSession()) {
            return false;
        }
        sessionsLaunched++;
        return true;
    }

    /**
     * Returns the reason the most recent {@link #beginSession()} call was
     * refused, or {@code null} if none has been refused.
     *
     * @return the most recent block reason, or {@code null}
     */
    String blockReason() {
        return lastBlockReason;
    }

    /** Returns the number of logical sessions launched so far for this job. */
    int getSessionsLaunched() {
        return sessionsLaunched;
    }

    /** Returns the hard ceiling on total session launches for the job. */
    int getMaxTotalSessions() {
        return maxTotalSessions;
    }

    /**
     * Sets the hard ceiling on total session launches.
     *
     * @param maxTotalSessions the cap; must be at least {@code 1} so the
     *                         primary session can always run
     * @throws IllegalArgumentException if {@code maxTotalSessions < 1}
     */
    void setMaxTotalSessions(int maxTotalSessions) {
        if (maxTotalSessions < 1) {
            throw new IllegalArgumentException("maxTotalSessions must be at least 1, got: " + maxTotalSessions);
        }
        this.maxTotalSessions = maxTotalSessions;
    }

    /** Returns the job-wide cumulative turn ceiling; {@code 0} means unlimited. */
    int getMaxTotalTurns() {
        return maxTotalTurns;
    }

    /**
     * Sets the job-wide cumulative turn ceiling.
     *
     * @param maxTotalTurns the cap, or {@code 0} to disable the turn check
     * @throws IllegalArgumentException if {@code maxTotalTurns < 0}
     */
    void setMaxTotalTurns(int maxTotalTurns) {
        if (maxTotalTurns < 0) {
            throw new IllegalArgumentException("maxTotalTurns must be non-negative, got: " + maxTotalTurns);
        }
        this.maxTotalTurns = maxTotalTurns;
    }

    /** Returns the maximum inactivity-triggered relaunches within one logical session. */
    int getMaxInactivityRestarts() {
        return maxInactivityRestarts;
    }

    /**
     * Sets the maximum inactivity-triggered relaunches within one logical session.
     *
     * @param maxInactivityRestarts the relaunch cap; must be non-negative
     * @throws IllegalArgumentException if {@code maxInactivityRestarts < 0}
     */
    void setMaxInactivityRestarts(int maxInactivityRestarts) {
        if (maxInactivityRestarts < 0) {
            throw new IllegalArgumentException("maxInactivityRestarts must be non-negative, got: " + maxInactivityRestarts);
        }
        this.maxInactivityRestarts = maxInactivityRestarts;
    }

    /** Returns the current inactivity-restart attempt index ({@code 0} outside the relaunch loop). */
    int getInactivityRestartAttempt() {
        return inactivityRestartAttempt;
    }

    /** Returns whether the most recent session attempt was killed by the inactivity watchdog. */
    boolean wasKilledForInactivity() {
        return wasKilledForInactivity;
    }

    /**
     * Records the inactivity-kill flag for the most recent attempt. Normally set
     * by {@link #runWithInactivityRetries(String, IntFunction)}; exposed so test
     * spies that drive session execution directly can mirror production wiring.
     *
     * @param killed {@code true} if the watchdog killed the attempt
     */
    void setWasKilledForInactivity(boolean killed) {
        this.wasKilledForInactivity = killed;
    }

    /**
     * Runs one logical session as a sequence of attempts, relaunching after an
     * inactivity kill up to {@link #getMaxInactivityRestarts()} times.
     *
     * <p>The supplied function performs a single agent attempt for the given
     * attempt index and returns its {@link AgentRunResult}; this method owns the
     * attempt counter, the inactivity-kill detection, and the suspend/relaunch
     * status reporting. The returned result is the last attempt's result (the
     * first non-killed one, or the final killed one when all relaunches are
     * exhausted), or {@code null} if no attempt ran.</p>
     *
     * @param runnerName name of the agent runner, used in status messages
     * @param attempt    runs a single agent attempt for the given index
     * @return the final attempt's result, or {@code null} if none ran
     */
    AgentRunResult runWithInactivityRetries(String runnerName, IntFunction<AgentRunResult> attempt) {
        AgentRunResult finalResult = null;
        for (int i = 0; i <= maxInactivityRestarts; i++) {
            inactivityRestartAttempt = i;
            wasKilledForInactivity = false;
            AgentRunResult result = attempt.apply(i);
            wasKilledForInactivity = result.killedForInactivity();
            finalResult = result;
            if (!wasKilledForInactivity) {
                break;
            }
            job.harnessStatus().inactivitySuspended(runnerName, i, maxInactivityRestarts);
            if (i == maxInactivityRestarts) {
                job.warn("Inactivity-restart limit (" + maxInactivityRestarts
                        + ") reached -- abandoning agent session");
            } else {
                job.log("Relaunching agent after inactivity timeout (attempt "
                        + (i + 2) + " of " + (maxInactivityRestarts + 1) + ")");
            }
        }
        inactivityRestartAttempt = 0;
        return finalResult;
    }

    /** Cumulative USD cost across all sessions absorbed so far. */
    private double cumulativeCostUsd() {
        return job.sessionAccumulator().getCostUsd();
    }

    /** Cumulative agentic turns across all sessions absorbed so far. */
    private int cumulativeTurns() {
        return job.sessionAccumulator().getNumTurns();
    }
}
