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

import io.flowtree.jobs.agent.AgentRunRequest;
import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Runs a single configured {@link ProcessBuilder} that invokes the Claude
 * subprocess, captures its stdout, and applies an inactivity monitor that
 * destroys the process and its descendants when stdout falls silent for
 * longer than a configured timeout.
 *
 * <p>This is the bulk of one Claude attempt extracted out of
 * {@link CodingAgentJob} so that command construction (which has its own
 * complexity around prompt, MCP, environment, etc.) stays in the job class
 * while process management lives here.</p>
 */
public final class AgentProcessRunner {

    /** Static-only utility; not instantiable. */
    private AgentProcessRunner() {}

    /**
     * Result of one attempt: exit code, captured stdout (possibly partial), and
     * the reason a watchdog killed the process, if any.
     *
     * @param exitCode             process exit code; {@code -1} on I/O failure
     * @param output               captured stdout (possibly partial after a kill)
     * @param killedForInactivity  {@code true} when the inactivity watchdog fired
     * @param killedForLooping      {@code true} when the loop detector fired on repeated actions
     */
    public record Result(int exitCode, String output,
                         boolean killedForInactivity, boolean killedForLooping) {}

    /**
     * Starts the configured {@link ProcessBuilder} and reads its merged stdout
     * to completion, applying an inactivity monitor that destroys the
     * process tree when no output appears within {@code inactivityTimeoutMillis}.
     *
     * <p>Equivalent to calling {@link #runAttempt(ProcessBuilder, boolean, long, String, ConsoleFeatures)}
     * with {@code useTmux=false}.</p>
     *
     * @param pb                       fully configured process builder (command, env, dirs)
     * @param inactivityTimeoutMillis  stdout silence duration that triggers a kill
     * @param taskId                   task identifier used in the monitor thread name
     * @param logger                   target for {@code log}/{@code warn} messages
     * @return the captured result; {@link Result#exitCode} is {@code -1} on I/O failure
     */
    public static Result runAttempt(ProcessBuilder pb,
                             long inactivityTimeoutMillis,
                             String taskId,
                             ConsoleFeatures logger) {
        return runAttempt(pb, false, inactivityTimeoutMillis, taskId, logger);
    }

    /**
     * Starts the configured {@link ProcessBuilder} and reads its merged stdout
     * to completion, applying an inactivity monitor that kills the subprocess
     * (and its descendants) when no output appears within
     * {@code inactivityTimeoutMillis}.
     *
     * <p>When {@code useTmux} is {@code true}, the subprocess is launched
     * inside a {@link TmuxSession} so the child receives a real controlling
     * tty. The {@link ProcessBuilder} is treated as a parameter carrier in
     * that case: its command, working directory, and environment are pulled
     * off it and handed to the tmux session, but {@link ProcessBuilder#start()}
     * is never called. The stdin and stdout/stderr redirections configured
     * on the builder are ignored because tmux's pane provides its own.</p>
     *
     * @param pb                       fully configured process builder (command, env, dirs)
     * @param useTmux                  if true, launch via {@link TmuxSession} for a real tty
     * @param inactivityTimeoutMillis  stdout silence duration that triggers a kill
     * @param taskId                   task identifier used in the monitor thread name
     * @param logger                   target for {@code log}/{@code warn} messages
     * @return the captured result; {@link Result#exitCode} is {@code -1} on I/O failure
     */
    public static Result runAttempt(ProcessBuilder pb,
                             boolean useTmux,
                             long inactivityTimeoutMillis,
                             String taskId,
                             ConsoleFeatures logger) {
        return runAttempt(pb, useTmux, inactivityTimeoutMillis, taskId, null, logger);
    }

    /**
     * Starts the configured subprocess as {@link #runAttempt(ProcessBuilder, boolean, long, String, ConsoleFeatures)}
     * does, additionally applying a loop detector that kills the process tree
     * when the agent repeats the same action many times within a short window.
     *
     * <p>The {@code loopSignatureExtractor} maps a single line of subprocess
     * output to a normalized <em>action signature</em> (or {@code null} when the
     * line is not an action), letting each runner supply its own knowledge of
     * its output format. Pass {@code null} to disable loop detection entirely.</p>
     *
     * @param pb                       fully configured process builder (command, env, dirs)
     * @param useTmux                  if true, launch via {@link TmuxSession} for a real tty
     * @param inactivityTimeoutMillis  stdout silence duration that triggers a kill
     * @param taskId                   task identifier used in the monitor thread name
     * @param loopSignatureExtractor   maps an output line to an action signature, or null to disable
     * @param logger                   target for {@code log}/{@code warn} messages
     * @return the captured result; {@link Result#exitCode} is {@code -1} on I/O failure
     */
    public static Result runAttempt(ProcessBuilder pb,
                             boolean useTmux,
                             long inactivityTimeoutMillis,
                             String taskId,
                             Function<String, String> loopSignatureExtractor,
                             ConsoleFeatures logger) {
        if (useTmux) {
            return runInTmux(pb, inactivityTimeoutMillis, taskId, loopSignatureExtractor, logger);
        }
        return runInProcess(pb, inactivityTimeoutMillis, taskId, loopSignatureExtractor, logger);
    }

    /** Runs the command directly as a child {@link Process}. */
    private static Result runInProcess(ProcessBuilder pb,
                                       long inactivityTimeoutMillis,
                                       String taskId,
                                       Function<String, String> loopSignatureExtractor,
                                       ConsoleFeatures logger) {
        StringBuilder out = new StringBuilder();
        AtomicBoolean killed = new AtomicBoolean(false);
        AtomicBoolean loopKilled = new AtomicBoolean(false);
        int exitCode = -1;
        Thread monitor = null;
        try {
            Process process = pb.start();
            long pid = process.pid();
            logger.log("Process started (PID: " + pid + ")");

            Runnable killAction = () -> {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            };

            AtomicLong lastOutputAt = new AtomicLong(System.currentTimeMillis());
            monitor = new AgentInactivityMonitor(
                    process::isAlive, killAction, lastOutputAt, inactivityTimeoutMillis,
                    idleMillis -> {
                        killed.set(true);
                        logger.warn("No Claude output for " + (idleMillis / 1000)
                                + "s -- killing process tree (PID " + pid + ")");
                    },
                    "claude-inactivity-monitor-" + taskId).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                pumpOutput(reader, out, lastOutputAt, loopSignatureExtractor,
                        killAction, loopKilled, logger);
            }

            logger.log("Process output stream closed, waiting for exit...");
            exitCode = process.waitFor();
            logger.log("Completed with exit code: " + exitCode);
        } catch (IOException e) {
            logIoOrKill(e, killed.get() || loopKilled.get(), logger);
        } catch (InterruptedException e) {
            // Preserve interrupt status so upstream callers can react.
            Thread.currentThread().interrupt();
            logIoOrKill(e, killed.get() || loopKilled.get(), logger);
        } finally {
            if (monitor != null) {
                monitor.interrupt();
            }
        }
        return new Result(exitCode, out.toString(), killed.get(), loopKilled.get());
    }

    /** Runs the command inside a {@link TmuxSession} so the child has a real tty. */
    private static Result runInTmux(ProcessBuilder pb,
                                    long inactivityTimeoutMillis,
                                    String taskId,
                                    Function<String, String> loopSignatureExtractor,
                                    ConsoleFeatures logger) {
        StringBuilder out = new StringBuilder();
        AtomicBoolean killed = new AtomicBoolean(false);
        AtomicBoolean loopKilled = new AtomicBoolean(false);
        int exitCode = -1;
        Thread monitor = null;
        String sessionName = "agent-" + sanitizeSessionName(taskId);
        try (TmuxSession session = TmuxSession.create(sessionName)) {
            Map<String, String> env = pb.environment();
            // Defensive defaults: the child sees a real tty under tmux. Many CLIs
            // respond by emitting ANSI escapes, spinners, and color codes that
            // would corrupt structured output. Set these only when the caller
            // hasn't already specified a value.
            env.putIfAbsent("TERM", "dumb");
            env.putIfAbsent("NO_COLOR", "1");
            session.workingDirectory(pb.directory()).environment(env);
            session.start(pb.command());
            long pid = session.getPid();
            logger.log("Process started (PID: " + pid + ") in tmux session " + session.getName());

            Runnable killAction = session::close;

            AtomicLong lastOutputAt = new AtomicLong(System.currentTimeMillis());
            monitor = new AgentInactivityMonitor(
                    session::isAlive,
                    killAction,
                    lastOutputAt,
                    inactivityTimeoutMillis,
                    idleMillis -> {
                        killed.set(true);
                        logger.warn("No Claude output for " + (idleMillis / 1000)
                                + "s -- killing tmux session " + session.getName()
                                + " (PID " + pid + ")");
                    },
                    "claude-inactivity-monitor-" + taskId).start();

            try (BufferedReader reader = session.captureOutput()) {
                pumpOutput(reader, out, lastOutputAt, loopSignatureExtractor,
                        killAction, loopKilled, logger);
            }

            logger.log("Process output stream closed, waiting for exit...");
            exitCode = session.waitFor(-1);
            logger.log("Completed with exit code: " + exitCode);
        } catch (IOException e) {
            logIoOrKill(e, killed.get() || loopKilled.get(), logger);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logIoOrKill(e, killed.get() || loopKilled.get(), logger);
        } finally {
            if (monitor != null) {
                monitor.interrupt();
            }
        }
        return new Result(exitCode, out.toString(), killed.get(), loopKilled.get());
    }

    /**
     * Reads the merged output stream to completion, updating the inactivity
     * clock on every line, appending to {@code out}, mirroring each line to the
     * logger, and (when {@code loopSignatureExtractor} is non-null) feeding
     * action signatures to an {@link AgentProgressMonitor}. When the monitor
     * reports a loop, {@code killAction} is run, {@code loopKilled} is set, and
     * the method returns so the caller can collect the now-terminated process.
     *
     * @param reader                  the subprocess output reader
     * @param out                     accumulates the captured output
     * @param lastOutputAt            inactivity clock updated on every line
     * @param loopSignatureExtractor  maps a line to an action signature, or null to disable detection
     * @param killAction              terminates the subprocess tree when a loop is detected
     * @param loopKilled              set to {@code true} when a loop kill is triggered
     * @param logger                  target for {@code log}/{@code warn} messages
     * @throws IOException if reading the stream fails
     */
    private static void pumpOutput(BufferedReader reader,
                                   StringBuilder out,
                                   AtomicLong lastOutputAt,
                                   Function<String, String> loopSignatureExtractor,
                                   Runnable killAction,
                                   AtomicBoolean loopKilled,
                                   ConsoleFeatures logger) throws IOException {
        AgentProgressMonitor progress =
                loopSignatureExtractor != null ? new AgentProgressMonitor() : null;
        String line;
        while ((line = reader.readLine()) != null) {
            lastOutputAt.set(System.currentTimeMillis());
            out.append(line).append("\n");
            logger.log(line);
            if (progress != null && progress.observe(loopSignatureExtractor.apply(line))) {
                loopKilled.set(true);
                logger.warn("repeatedActionSignature=" + progress.getOffendingSignature()
                        + " -- killing process tree after repeated non-progressing agent actions");
                killAction.run();
                return;
            }
        }
    }

    /**
     * Tmux session names cannot contain whitespace, periods, or colons.
     * Task ids may contain such characters; replace them with hyphens.
     * A null or empty task id falls back to a short random suffix so the
     * resulting session name is always unique and valid.
     */
    private static String sanitizeSessionName(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        String sanitized = taskId.replaceAll("[\\s.:]+", "-");
        if (sanitized.isEmpty()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        return sanitized;
    }

    /**
     * Applies the runner-agnostic portions of {@code request} to {@code pb}:
     * working directory, supplied environment overrides, the
     * {@code AR_AGENT_ACTIVITY} env var, the augmented {@code PATH}, merged
     * stderr-into-stdout, and {@code /dev/null} stdin.
     *
     * <p>This boilerplate is identical for every subprocess-backed runner;
     * factoring it here prevents drift between {@code ClaudeCodeRunner} and
     * {@code OpencodeRunner} without inventing a CLI-agent base class.</p>
     *
     * @param pb      the configured process builder (already has its command)
     * @param request the run request
     */
    public static void applyRequestToProcessBuilder(ProcessBuilder pb, AgentRunRequest request) {
        Path workDir = request.getWorkingDirectory();
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }

        Map<String, String> env = request.getEnvironment();
        if (env != null && !env.isEmpty()) {
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getValue() == null) {
                    pb.environment().remove(entry.getKey());
                } else {
                    pb.environment().put(entry.getKey(), entry.getValue());
                }
            }
        }

        String activity = request.getActivityTag();
        if (activity != null && !activity.isEmpty()) {
            pb.environment().put("AR_AGENT_ACTIVITY", activity);
        } else {
            pb.environment().remove("AR_AGENT_ACTIVITY");
        }
        GitOperations.augmentPath(pb);

        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
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
