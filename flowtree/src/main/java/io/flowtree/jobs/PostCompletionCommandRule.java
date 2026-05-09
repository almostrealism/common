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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Enforcement rule that runs a user-specified shell command after the agent
 * completes its primary work. If the command exits non-zero (or times out),
 * a correction session is triggered that shows the agent the command output
 * and asks it to fix the underlying problem.
 *
 * <p>The command is treated as trusted input from the job submitter — it runs
 * with the same privileges as the agent process and inherits its environment.
 * This is NOT a sandbox boundary; it is equivalent to any other instruction
 * the submitter provides.</p>
 *
 * <p>Active only when {@link ClaudeCodeJob#getPostCompletionCommand()} is
 * non-empty. Disabled by default.</p>
 *
 * <h2>Examples</h2>
 * <pre>
 * // Run a single test class after the agent's work:
 * job.setPostCompletionCommand("mvn -pl flowtree test -Dtest=NotifierRegistryTest");
 *
 * // Run a pytest module:
 * job.setPostCompletionCommand("cd tools/mcp/manager && pytest tests/test_secrets.py");
 *
 * // Run a custom verification script:
 * job.setPostCompletionCommand("bash scripts/verify-foo.sh");
 * </pre>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob#getPostCompletionCommand()
 * @see ClaudeCodeJob#setPostCompletionCommand(String)
 * @see EnforcementRule
 */
class PostCompletionCommandRule implements EnforcementRule {

    /** Default timeout for the post-completion command, in seconds (30 minutes). */
    static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    /** Maximum characters of combined command output injected into the correction prompt. */
    static final int MAX_OUTPUT_CHARS = 8000;

    /** The shell command passed to {@code sh -c}. */
    private final String command;
    /** Working directory for the command; {@code null} = use the job's working directory. */
    private final String workingDir;
    /** Maximum wall-clock seconds before the command is killed. */
    private final int timeoutSeconds;

    /** Cached exit code from the most recent command run; {@code -1} before first run. */
    private int lastExitCode = -1;
    /** Cached combined stdout+stderr from the most recent command run. */
    private String lastOutput = "";
    /** Whether the most recent command run timed out. */
    private boolean lastTimedOut;
    /** Whether the cache is valid; set to {@code false} by {@link #onCorrectionAttempted}. */
    private boolean cacheValid;

    /**
     * Creates a rule that runs the given command with the default timeout.
     *
     * @param command    the shell command to run (passed to {@code sh -c})
     * @param workingDir the working directory for the command, or {@code null}
     *                   to use the job's working directory
     */
    PostCompletionCommandRule(String command, String workingDir) {
        this(command, workingDir, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Creates a rule that runs the given command with a configurable timeout.
     *
     * @param command        the shell command to run (passed to {@code sh -c})
     * @param workingDir     the working directory, or {@code null} to use the job's
     *                       working directory
     * @param timeoutSeconds maximum wall-clock seconds before the command is killed
     *                       and treated as a failure
     */
    PostCompletionCommandRule(String command, String workingDir, int timeoutSeconds) {
        this.command = command;
        this.workingDir = workingDir;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String getName() { return "post-completion-command"; }

    /**
     * Runs the post-completion command (if not already cached) and returns
     * {@code true} when the exit code is non-zero or the command timed out.
     *
     * <p>The result is cached until {@link #onCorrectionAttempted} is called,
     * so back-to-back calls within the same enforcement pass do not re-run
     * the command.</p>
     *
     * @param job the job being inspected
     * @return {@code true} if the command failed or timed out
     */
    @Override
    public boolean isViolated(ClaudeCodeJob job) {
        if (!cacheValid) {
            runCommand(job);
        }
        return lastExitCode != 0;
    }

    /**
     * Invalidates the cached command result so the next {@link #isViolated}
     * call re-runs the command to check whether the correction resolved the issue.
     *
     * @param job the job after the correction session completed
     */
    @Override
    public void onCorrectionAttempted(ClaudeCodeJob job) {
        cacheValid = false;
    }

    /**
     * Builds a correction prompt that shows the agent the command that was run,
     * the exit code, and the (possibly truncated) command output.
     *
     * <p>Output is capped at {@link #MAX_OUTPUT_CHARS} total, taking the tail
     * of the combined stdout+stderr so that failure messages (which typically
     * appear at the end) are preserved.</p>
     *
     * @param job the job being corrected
     * @return the correction prompt
     */
    @Override
    public String buildCorrectionPrompt(ClaudeCodeJob job) {
        StringBuilder sb = new StringBuilder();

        if (lastTimedOut) {
            sb.append("POST-COMPLETION COMMAND TIMED OUT\n\n");
            sb.append("The following command was run as a post-completion check and timed ");
            sb.append("out after ").append(timeoutSeconds).append(" seconds:\n\n");
            sb.append("  ").append(command).append("\n\n");
            sb.append("The work is NOT complete. A timeout usually means the command is hung, ");
            sb.append("an infinite loop has been introduced, or a long-running operation is ");
            sb.append("blocking. Please investigate and fix the underlying issue so the ");
            sb.append("command completes within the allowed time.");
        } else {
            sb.append("POST-COMPLETION COMMAND FAILED\n\n");
            sb.append("The following command was run as a post-completion check and exited ");
            sb.append("with code ").append(lastExitCode).append(":\n\n");
            sb.append("  ").append(command).append("\n\n");
            sb.append("The work is NOT complete until this command exits zero. Please fix ");
            sb.append("the underlying problem — do NOT modify the command or its timeout.\n\n");

            String truncated = truncateOutput(lastOutput);
            if (truncated != null && !truncated.isEmpty()) {
                sb.append("--- Command output ---\n");
                sb.append(truncated);
                if (!truncated.endsWith("\n")) {
                    sb.append("\n");
                }
                sb.append("--- End of output ---");
            } else {
                sb.append("(no output captured)");
            }
        }

        return sb.toString();
    }

    /**
     * Returns the exit code from the last command run.
     * Zero means success; non-zero means failure.
     *
     * @return exit code, or {@code -1} if the command has not been run yet
     */
    int getLastExitCode() { return lastExitCode; }

    /**
     * Returns the combined stdout+stderr from the last command run.
     *
     * @return command output, or empty string if the command has not been run yet
     */
    String getLastOutput() { return lastOutput; }

    /**
     * Returns whether the last command run timed out.
     *
     * @return {@code true} if the last run was killed due to timeout
     */
    boolean wasLastRunTimedOut() { return lastTimedOut; }

    /**
     * Runs the command via {@code sh -c}, captures combined stdout+stderr, and
     * stores the result in the cached fields for use by {@link #isViolated} and
     * {@link #buildCorrectionPrompt}.
     *
     * <p>Output is redirected to a temporary file rather than read from the process
     * pipe after exit. Reading the pipe only after {@code waitFor} returns can
     * deadlock when the command emits more output than fits in the OS pipe buffer:
     * the child blocks on write, never exits, and the wall-clock timeout fires
     * even though the work was effectively complete.</p>
     *
     * @param job the job providing the fallback working directory
     */
    private void runCommand(ClaudeCodeJob job) {
        String effectiveWorkingDir = workingDir != null ? workingDir : job.getWorkingDirectory();
        File outputFile = null;
        try {
            outputFile = File.createTempFile("post-completion-", ".log");
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            if (effectiveWorkingDir != null) {
                pb.directory(new File(effectiveWorkingDir));
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile);
            GitOperations.augmentPath(pb);

            Process process = pb.start();
            lastTimedOut = false;

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                lastTimedOut = true;
                lastExitCode = 1;
                lastOutput = readOutputFile(outputFile);
            } else {
                lastExitCode = process.exitValue();
                lastOutput = readOutputFile(outputFile);
            }
        } catch (IOException e) {
            lastTimedOut = false;
            lastExitCode = 1;
            lastOutput = "Failed to launch command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastTimedOut = false;
            lastExitCode = 1;
            lastOutput = "Command interrupted: " + e.getMessage();
        } finally {
            cacheValid = true;
            if (outputFile != null && outputFile.exists()) {
                // Best-effort cleanup; failure to delete is non-fatal because the
                // temp directory is purged by the OS on a regular schedule.
                outputFile.delete();
            }
        }
    }

    /**
     * Reads the contents of the redirected output file, capping the read at
     * {@link #MAX_OUTPUT_CHARS} bytes from the end so that a runaway command
     * that emits megabytes of output cannot exhaust the agent's heap. The
     * char-based truncation applied by {@link #truncateOutput} runs afterwards;
     * this byte-level cap exists purely to bound the read.
     *
     * @param file the redirected output file
     * @return the (possibly tail-only) file contents, or an error message if
     *         the file cannot be read
     */
    private static String readOutputFile(File file) {
        try {
            long size = file.length();
            if (size <= MAX_OUTPUT_CHARS) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long start = size - MAX_OUTPUT_CHARS;
                raf.seek(start);
                byte[] buf = new byte[MAX_OUTPUT_CHARS];
                raf.readFully(buf);
                return new String(buf, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "Failed to read command output: " + e.getMessage();
        }
    }

    /**
     * Truncates command output to at most {@link #MAX_OUTPUT_CHARS} characters,
     * taking the tail so that failure messages (which typically appear at the end)
     * are preserved.
     *
     * @param output the raw command output
     * @return the (possibly truncated) output string
     */
    static String truncateOutput(String output) {
        if (output == null) return "";
        if (output.length() <= MAX_OUTPUT_CHARS) return output;
        String tail = output.substring(output.length() - MAX_OUTPUT_CHARS);
        return "...[output truncated, showing last " + MAX_OUTPUT_CHARS + " chars]\n" + tail;
    }
}
