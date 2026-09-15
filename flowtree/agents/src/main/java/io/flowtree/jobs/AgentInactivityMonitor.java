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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Watches a subprocess for stdout silence and terminates it when no output
 * has been observed for a configured duration.
 *
 * <p>The owning code updates an {@link AtomicLong} clock on every output line
 * received from the process; the monitor wakes periodically and runs the
 * configured kill action if the clock has not advanced within the timeout.
 * This protects against subprocesses that spawn shell loops which fail to
 * terminate (a recurring failure mode for autonomous coding agents).</p>
 *
 * <p>Silence is not always a hang. An agent waiting on an MCP-served tool —
 * a test run, a build validation, a download, a peer's message — emits nothing
 * until the tool returns, and that wait is bounded by the serving process
 * rather than by anything the agent wrote. When the owner supplies a way to
 * ask whether such a call is in flight (see {@link AgentActivityTracker}), the
 * monitor extends the window to {@link #IN_FLIGHT_MCP_CALL_MILLIS} before it
 * fires. A shell command the agent wrote itself earns no extension: an
 * unterminated polling loop is exactly the failure this monitor exists to
 * bound.</p>
 *
 * <p>The monitor is decoupled from any particular process abstraction: it
 * is given a {@link BooleanSupplier} that reports whether the subprocess is
 * still alive and a {@link Runnable} that terminates it. This lets the same
 * monitor watch a raw {@link Process} or a {@link TmuxSession}.</p>
 */
final class AgentInactivityMonitor {

    /**
     * Silence tolerated while an MCP-served tool call is in flight. Sized to
     * the longest MCP-managed operation seen in the field (a multi-gigabyte
     * download); the ordinary window applies again as soon as the call
     * returns.
     */
    static final long IN_FLIGHT_MCP_CALL_MILLIS = 60L * 60L * 1000L;

    /** Reports whether the watched subprocess is still alive. */
    private final BooleanSupplier alive;

    /** Action that terminates the watched subprocess and its children. */
    private final Runnable killAction;

    /** Clock updated by the owner on every line of stdout. */
    private final AtomicLong lastOutputAt;

    /** Wall-clock duration of stdout silence after which the kill action runs. */
    private final long inactivityTimeoutMillis;

    /** Knows which tool calls are in flight; {@code null} when the runner cannot tell. */
    private final AgentActivityTracker activityTracker;

    /** Polling interval; never less than 5 seconds, never more than 60 seconds. */
    private final long checkIntervalMillis;

    /** Invoked with the observed idle duration (ms) when the timeout fires. */
    private final LongConsumer onTimeout;

    /** Thread name used for the daemon worker; helpful in thread dumps. */
    private final String threadName;

    /**
     * Creates a monitor with explicit alive-check and kill action.
     *
     * @param alive                    reports whether the subprocess is still running
     * @param killAction               terminates the subprocess (called once when the timeout fires)
     * @param lastOutputAt             clock updated by the read loop on every output line
     * @param inactivityTimeoutMillis  duration of stdout silence that triggers a kill
     * @param onTimeout                callback invoked with the observed idle ms when firing
     * @param threadName               name for the daemon monitor thread
     */
    AgentInactivityMonitor(BooleanSupplier alive,
                           Runnable killAction,
                           AtomicLong lastOutputAt,
                           long inactivityTimeoutMillis,
                           LongConsumer onTimeout,
                           String threadName) {
        this(alive, killAction, lastOutputAt, inactivityTimeoutMillis, null, onTimeout, threadName);
    }

    /**
     * Creates a monitor that also knows whether the agent is waiting on an
     * MCP-served tool, and extends its patience while it is.
     *
     * @param alive                    reports whether the subprocess is still running
     * @param killAction               terminates the subprocess (called once when the timeout fires)
     * @param lastOutputAt             clock updated by the read loop on every output line
     * @param inactivityTimeoutMillis  duration of stdout silence that triggers a kill
     * @param activityTracker          knows which tool calls are in flight, or
     *                                 {@code null} when the runner cannot tell
     * @param onTimeout                callback invoked with the observed idle ms when firing
     * @param threadName               name for the daemon monitor thread
     */
    AgentInactivityMonitor(BooleanSupplier alive,
                           Runnable killAction,
                           AtomicLong lastOutputAt,
                           long inactivityTimeoutMillis,
                           AgentActivityTracker activityTracker,
                           LongConsumer onTimeout,
                           String threadName) {
        this.alive = alive;
        this.killAction = killAction;
        this.lastOutputAt = lastOutputAt;
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.activityTracker = activityTracker;
        this.checkIntervalMillis = Math.min(60_000L,
                Math.max(5_000L, inactivityTimeoutMillis / 4));
        this.onTimeout = onTimeout;
        this.threadName = threadName;
    }

    /**
     * Starts the monitor as a daemon thread and returns its handle.
     *
     * <p>Interrupt the returned thread to stop the monitor without firing.
     * The monitor also exits naturally when the subprocess is no longer alive.</p>
     *
     * @return the monitor thread (already started)
     */
    Thread start() {
        Thread t = new Thread(this::run, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Monitor body invoked on the daemon thread; exits when the process dies or the timeout fires. */
    private void run() {
        while (alive.getAsBoolean()) {
            try {
                Thread.sleep(checkIntervalMillis);
            } catch (InterruptedException ie) {
                return;
            }
            long idle = System.currentTimeMillis() - lastOutputAt.get();
            if (idle >= toleratedSilenceMillis()) {
                onTimeout.accept(idle);
                killAction.run();
                return;
            }
        }
    }

    /**
     * Returns the silence this monitor tolerates right now: the configured
     * window, or {@link #IN_FLIGHT_MCP_CALL_MILLIS} when that is longer and an
     * MCP-served tool call is in flight.
     *
     * @return the tolerated silence in milliseconds
     */
    long toleratedSilenceMillis() {
        if (activityTracker != null && activityTracker.isMcpCallInFlight()) {
            return Math.max(inactivityTimeoutMillis, IN_FLIGHT_MCP_CALL_MILLIS);
        }

        return inactivityTimeoutMillis;
    }
}
