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

import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a single configured {@link ProcessBuilder} that invokes the Claude
 * subprocess, captures its stdout, and applies an inactivity monitor that
 * destroys the process and its descendants when stdout falls silent for
 * longer than a configured timeout.
 *
 * <p>This is the bulk of one Claude attempt extracted out of
 * {@link ClaudeCodeJob} so that command construction (which has its own
 * complexity around prompt, MCP, environment, etc.) stays in the job class
 * while process management lives here.</p>
 */
final class ClaudeAttemptRunner {

    /** Static-only utility; not instantiable. */
    private ClaudeAttemptRunner() {}

    /**
     * Result of one attempt: exit code, captured stdout (possibly partial),
     * and whether the monitor killed the process for inactivity.
     */
    record Result(int exitCode, String output, boolean killedForInactivity) {}

    /**
     * Starts the configured {@link ProcessBuilder} and reads its merged stdout
     * to completion, applying an inactivity monitor that destroys the
     * process tree when no output appears within {@code inactivityTimeoutMillis}.
     *
     * @param pb                       fully configured process builder (command, env, dirs)
     * @param inactivityTimeoutMillis  stdout silence duration that triggers a kill
     * @param taskId                   task identifier used in the monitor thread name
     * @param logger                   target for {@code log}/{@code warn} messages
     * @return the captured result; {@link Result#exitCode} is {@code -1} on I/O failure
     */
    static Result runAttempt(ProcessBuilder pb,
                             long inactivityTimeoutMillis,
                             String taskId,
                             ConsoleFeatures logger) {
        StringBuilder out = new StringBuilder();
        AtomicBoolean killed = new AtomicBoolean(false);
        int exitCode = -1;
        Thread monitor = null;
        try {
            Process process = pb.start();
            long pid = process.pid();
            logger.log("Process started (PID: " + pid + ")");

            AtomicLong lastOutputAt = new AtomicLong(System.currentTimeMillis());
            monitor = new ClaudeInactivityMonitor(
                    process, lastOutputAt, inactivityTimeoutMillis,
                    idleMillis -> {
                        killed.set(true);
                        logger.warn("No Claude output for " + (idleMillis / 1000)
                                + "s -- killing process tree (PID " + pid + ")");
                    },
                    "claude-inactivity-monitor-" + taskId).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastOutputAt.set(System.currentTimeMillis());
                    out.append(line).append("\n");
                    logger.log(line);
                }
            }

            logger.log("Process output stream closed, waiting for exit...");
            exitCode = process.waitFor();
            logger.log("Completed with exit code: " + exitCode);
        } catch (IOException e) {
            logIoOrKill(e, killed.get(), logger);
        } catch (InterruptedException e) {
            // Preserve interrupt status so upstream callers can react.
            Thread.currentThread().interrupt();
            logIoOrKill(e, killed.get(), logger);
        } finally {
            if (monitor != null) {
                monitor.interrupt();
            }
        }
        return new Result(exitCode, out.toString(), killed.get());
    }

    /** Logs the read-loop exception either as a benign post-kill notice or a real error, per {@code killed}. */
    private static void logIoOrKill(Exception e, boolean killed, ConsoleFeatures logger) {
        if (killed) {
            logger.log("Read loop ended after inactivity kill: " + e.getMessage());
        } else {
            logger.warn("Error: " + e.getMessage(), e);
        }
    }
}
