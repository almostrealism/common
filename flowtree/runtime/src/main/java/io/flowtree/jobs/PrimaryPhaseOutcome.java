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

/**
 * How a {@link CodingAgentJob}'s primary phase ended, captured before any
 * later phase can absorb a new result into the shared
 * {@link JobSessionAccumulator} and overwrite it.
 *
 * <p>The job used to carry a single {@code primaryPhaseHardFailed} boolean for
 * this. That was too coarse to tell a session that <em>crashed instantly</em>
 * (no work performed) from one that <em>ran for a long time and was killed by a
 * signal</em> (left verified, committed work) — both of which a self-reported
 * duration of {@code 0} would conflate. This holds the primary phase's
 * {@link AgentRunResult.EndState} classification, its measured duration, and
 * the signal-kill cause, so the job can be graded by what the primary actually
 * delivered rather than by a self-report a killed session never wrote.</p>
 *
 * <p>Captured once at the end of the primary phase (see
 * {@link CodingAgentJob#capturePrimaryOutcome()}) and never reset, so it
 * survives any subsequent {@code enforce_changes} retry that overwrites the
 * accumulator's exit code with a successful result.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJob
 * @see CodingAgentJobEvent
 */
class PrimaryPhaseOutcome {

    /** How the primary phase ended; {@link AgentRunResult.EndState#CLEAN} until captured. */
    private AgentRunResult.EndState endState = AgentRunResult.EndState.CLEAN;

    /** Measured wall-clock duration of the primary phase's final attempt, in milliseconds. */
    private long durationMs;

    /** Signal-kill cause of the primary phase, or {@code ""} when it was not a signal kill. */
    private String killCause = "";

    /**
     * {@code true} when the primary produced no usable work: a launch failure
     * or an instant crash ({@link AgentRunResult.EndState#COULD_NOT_START}). Tracked separately from
     * {@link #endState} so a test can pin it directly.
     */
    private boolean hardFailure;

    /**
     * Captures the primary phase's outcome from the accumulator's most recent
     * run. The hard-failure flag follows the
     * {@link AgentRunResult.EndState#COULD_NOT_START} classification: only a
     * session that performed no measurable work is a hard failure, so a signal
     * kill after real runtime is not one.
     *
     * @param accumulator the job's session accumulator, holding the just-run
     *                    primary phase's latest-value state
     */
    void capture(JobSessionAccumulator accumulator) {
        this.endState = accumulator.getLastEndState();
        this.durationMs = accumulator.getLastDurationMs();
        this.killCause = accumulator.getLastKillCause();
        this.hardFailure = endState == AgentRunResult.EndState.COULD_NOT_START;
    }

    /**
     * Returns whether the primary produced no usable work — the genuine
     * nothing-delivered case the rollup treats as a terminal failure.
     *
     * @return {@code true} when the primary was a hard failure
     */
    boolean isHardFailure() {
        return hardFailure;
    }

    /**
     * Sets the hard-failure flag directly, for tests that drive
     * {@link CodingAgentJob#createEvent(Exception)} without a captured outcome.
     *
     * @param hardFailure whether the primary produced no usable work
     */
    void setHardFailure(boolean hardFailure) {
        this.hardFailure = hardFailure;
    }

    /**
     * Returns whether the primary ran and was then killed by a signal — the
     * killed-but-productive case that is graded by what it delivered rather
     * than as a failure.
     *
     * @return {@code true} when the primary ended {@link AgentRunResult.EndState#KILLED_SIGNAL}
     */
    boolean killedBySignal() {
        return endState == AgentRunResult.EndState.KILLED_SIGNAL;
    }

    /**
     * Returns the measured wall-clock duration of the primary phase, in
     * milliseconds.
     *
     * @return the primary phase duration
     */
    long durationMs() {
        return durationMs;
    }

    /**
     * Builds the completion reason for a killed-but-productive primary, naming
     * the signal cause and the measured duration so the record states what
     * happened rather than a self-reported {@code 0s}.
     *
     * @return the human-readable degraded-completion reason
     */
    String describeSignalKill() {
        return signalKillSummary()
                + ", but its work was verified and committed by the later phase(s)";
    }

    /**
     * Builds the completion reason for a signal-killed primary that delivered
     * nothing a later phase could commit, naming the signal cause and the
     * measured duration so a {@code FAILED} record states what happened rather
     * than a bare {@code exited with code 143}. Distinct from
     * {@link #describeSignalKill()}, which claims committed work.
     *
     * @return the human-readable failed-completion reason
     */
    String describeSignalKillWithoutDelivery() {
        return signalKillSummary() + ", and nothing was committed";
    }

    /**
     * The shared prefix naming the signal cause and the measured duration of a
     * killed primary, without any claim about what was delivered.
     *
     * @return the "killed by X after running Ys" summary
     */
    private String signalKillSummary() {
        String cause = killCause.isEmpty() ? "a signal" : killCause;
        return "Primary session was killed by " + cause + " after running "
                + HarnessStatusReporter.formatDuration(durationMs);
    }
}
