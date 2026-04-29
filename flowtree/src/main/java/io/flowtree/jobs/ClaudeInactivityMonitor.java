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
import java.util.function.LongConsumer;

/**
 * Watches a {@link Process} for stdout silence and destroys it (and its
 * descendants) when no output has been observed for a configured duration.
 *
 * <p>The owning code updates an {@link AtomicLong} clock on every output line
 * received from the process; the monitor wakes periodically and destroys the
 * process if the clock has not advanced within the timeout.  This protects
 * against subprocesses that spawn shell loops which fail to terminate
 * (a recurring failure mode for autonomous coding agents).</p>
 */
final class ClaudeInactivityMonitor {

    /** The process being watched. */
    private final Process process;

    /** Clock updated by the owner on every line of stdout. */
    private final AtomicLong lastOutputAt;

    /** Wall-clock duration of stdout silence after which {@link #process} is killed. */
    private final long inactivityTimeoutMillis;

    /** Polling interval; never less than 5 seconds, never more than 60 seconds. */
    private final long checkIntervalMillis;

    /** Invoked with the observed idle duration (ms) when the timeout fires. */
    private final LongConsumer onTimeout;

    /** Thread name used for the daemon worker; helpful in thread dumps. */
    private final String threadName;

    /**
     * Creates a monitor for {@code process}.
     *
     * @param process                  the subprocess to observe
     * @param lastOutputAt             clock updated by the read loop on every output line
     * @param inactivityTimeoutMillis  duration of stdout silence that triggers a kill
     * @param onTimeout                callback invoked with the observed idle ms when firing
     * @param threadName               name for the daemon monitor thread
     */
    ClaudeInactivityMonitor(Process process,
                             AtomicLong lastOutputAt,
                             long inactivityTimeoutMillis,
                             LongConsumer onTimeout,
                             String threadName) {
        this.process = process;
        this.lastOutputAt = lastOutputAt;
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.checkIntervalMillis = Math.min(60_000L,
                Math.max(5_000L, inactivityTimeoutMillis / 4));
        this.onTimeout = onTimeout;
        this.threadName = threadName;
    }

    /**
     * Starts the monitor as a daemon thread and returns its handle.
     *
     * <p>Interrupt the returned thread to stop the monitor without firing.
     * The monitor also exits naturally when {@link #process} is no longer alive.</p>
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
        while (process.isAlive()) {
            try {
                Thread.sleep(checkIntervalMillis);
            } catch (InterruptedException ie) {
                return;
            }
            long idle = System.currentTimeMillis() - lastOutputAt.get();
            if (idle >= inactivityTimeoutMillis) {
                onTimeout.accept(idle);
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return;
            }
        }
    }
}
