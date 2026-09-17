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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs git and other subprocess commands on behalf of a {@link GitManagedJob},
 * in its {@link GitManagedJob#getWorkingDirectory() working directory} and
 * under its configured git identity.
 *
 * <p>Extracted from {@link GitManagedJob} so that class stays focused on job
 * lifecycle and configuration rather than process plumbing; {@code GitManagedJob}
 * exposes package-private {@code executeGit}/{@code executeGitWithOutput}/
 * {@code executeCommandWithOutput} wrappers that delegate here, so every
 * existing caller in this package is unaffected by the extraction.</p>
 *
 * @author Michael Murray
 * @see GitManagedJob
 */
class GitCommandExecutor implements ConsoleFeatures {

    /** The job whose working directory and git identity this executor uses. */
    private final GitManagedJob job;

    /**
     * Creates a new executor bound to the given job.
     *
     * @param job the job that this executor runs commands on behalf of
     */
    GitCommandExecutor(GitManagedJob job) {
        this.job = job;
    }

    /**
     * Applies git identity environment variables to a {@link ProcessBuilder}.
     *
     * <p>Uses {@code GIT_AUTHOR_NAME}, {@code GIT_AUTHOR_EMAIL},
     * {@code GIT_COMMITTER_NAME}, and {@code GIT_COMMITTER_EMAIL} so the
     * identity is scoped to the process and never persisted in the repo's
     * local config.</p>
     *
     * @param pb the process builder to configure
     */
    private void applyGitIdentity(ProcessBuilder pb) {
        String gitUserName = job.getGitUserName();
        String gitUserEmail = job.getGitUserEmail();
        if (gitUserName != null && !gitUserName.isEmpty()) {
            pb.environment().put("GIT_AUTHOR_NAME", gitUserName);
            pb.environment().put("GIT_COMMITTER_NAME", gitUserName);
        }
        if (gitUserEmail != null && !gitUserEmail.isEmpty()) {
            pb.environment().put("GIT_AUTHOR_EMAIL", gitUserEmail);
            pb.environment().put("GIT_COMMITTER_EMAIL", gitUserEmail);
        }
    }

    /**
     * Executes a git sub-command in the job's working directory and returns
     * its exit code.
     *
     * <p>Standard error is merged into standard output so the full output is
     * captured. If the exit code is non-zero, the output is logged as a
     * warning. SSH host-key prompts are suppressed via
     * {@code GIT_SSH_COMMAND}. Git identity environment variables are
     * injected via {@link #applyGitIdentity(ProcessBuilder)}.</p>
     *
     * @param args git sub-command and its arguments (e.g. {@code "commit", "-m", "msg"})
     * @return the process exit code (0 on success)
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    int executeGit(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(GitOperations.resolveGitCommand());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        String workingDirectory = job.getWorkingDirectory();
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);

        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes");
        applyGitIdentity(pb);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            warn("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output.toString().trim());
        }

        return exitCode;
    }

    /**
     * Executes a git sub-command in the job's working directory and returns
     * its combined standard-output and standard-error as a string.
     *
     * <p>Unlike {@link #executeGit(String...)}, the exit code is not checked;
     * callers that need to detect failure should inspect the returned string
     * or use {@link #executeGit(String...)} instead.</p>
     *
     * @param args git sub-command and its arguments
     * @return the full output of the command
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    String executeGitWithOutput(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(GitOperations.resolveGitCommand());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        String workingDirectory = job.getWorkingDirectory();
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);

        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes");
        applyGitIdentity(pb);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();
        return output.toString();
    }

    /**
     * Executes an arbitrary command and returns its output.
     * Used for non-git commands like {@code gh}.
     *
     * @param command the command and its arguments
     * @return the combined stdout and stderr of the command
     * @throws IOException if the process cannot be started
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    String executeCommandWithOutput(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        String workingDirectory = job.getWorkingDirectory();
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        GitOperations.augmentPath(pb);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();
        return output.toString();
    }

    /**
     * Formats a log message with this class's simple name prefix and,
     * when available, the task ID from the associated job.
     *
     * @param msg the raw message text
     * @return the formatted log string
     */
    @Override
    public String formatMessage(String msg) {
        String taskId = job.getTaskId();
        if (taskId != null && !taskId.isEmpty()) {
            return "GitCommandExecutor [" + taskId + "]: " + msg;
        }
        return "GitCommandExecutor: " + msg;
    }
}
